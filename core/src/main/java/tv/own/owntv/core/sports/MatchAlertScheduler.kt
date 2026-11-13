package tv.own.owntv.core.sports

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import java.io.File

/**
 * The tiny file behind goal alerts: the matches being watched in the background, the
 * last state seen of each, and the match reminders due. Pure JSON in and out, so it is unit-tested.
 */
data class AlertState(
    val watched: Map<String, Match> = emptyMap(),
    val seen: Map<String, AlertSnapshot> = emptyMap(),
    /** Match key → (match, minutes before kick-off, channel names for the text). */
    val reminders: Map<String, Reminder> = emptyMap(),
) {
    data class Reminder(val match: Match, val leadMinutes: Int, val channels: List<String>) {
        val atMs: Long get() = match.startMs - leadMinutes * 60_000L
    }

    fun toJson(now: Long): String = JSONObject()
        .put("w", JSONObject().apply { watched.forEach { (k, m) -> put(k, SportsCacheJson.matchJson(m)) } })
        .put("s", JSONObject().apply { seen.forEach { (k, s) -> put(k, s.toJson()) } })
        .put("r", JSONObject().apply {
            reminders.forEach { (k, r) ->
                put(k, JSONObject().put("m", SportsCacheJson.matchJson(r.match)).put("lead", r.leadMinutes).put("ch", org.json.JSONArray(r.channels)))
            }
        })
        .put("at", now)
        .toString()

    companion object {
        fun fromJson(text: String?): AlertState {
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return AlertState()
            fun <T> map(key: String, read: (JSONObject) -> T?): Map<String, T> = o.optJSONObject(key)?.let { m ->
                m.keys().asSequence().mapNotNull { k -> m.optJSONObject(k)?.let(read)?.let { k to it } }.toMap()
            }.orEmpty()
            return AlertState(
                watched = map("w", SportsCacheJson::matchFromJson),
                seen = map("s", AlertSnapshot::fromJson),
                reminders = map("r") { r ->
                    val m = r.optJSONObject("m")?.let(SportsCacheJson::matchFromJson) ?: return@map null
                    val ch = r.optJSONArray("ch")
                    Reminder(m, r.optInt("lead"), (0 until (ch?.length() ?: 0)).mapNotNull { ch?.optString(it) })
                },
            )
        }
    }
}

/**
 * Goal alerts with the app closed: one alarm per followed match at kick-off,
 * then a short [MatchAlertWorker] check about every minute until full time. Same clock as
 * [tv.own.owntv.core.reminder.ReminderScheduler]: exact when the user allows it, inexact otherwise.
 *
 * Nothing is scheduled unless the host allows background alerts ([MatchAlertHost.backgroundAllowed],
 * the phone only) **and** the profile has alerts on with "when OwnTV is closed".
 */
class MatchAlertScheduler(
    private val context: Context,
    private val settings: SettingsRepository,
    dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val file = File(dir, FILE)
    private val lock = Mutex()
    private val alarms: AlarmManager? = context.getSystemService(AlarmManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun state(): AlertState = lock.withLock { read() }

    suspend fun update(transform: (AlertState) -> AlertState): AlertState = lock.withLock {
        transform(read()).also { write(it) }
    }

    private fun read(): AlertState = AlertState.fromJson(runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull())

    private fun write(s: AlertState) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeText(s.toJson(clock()))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Log.w(TAG, "alert state write failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Keeps the alarms in step with every board the repository builds (wired in DataModule, so it
     * starts only when a sports screen first creates the repository — never at app launch).
     */
    fun follow(board: Flow<SportsBoard>, config: Flow<SportsConfig>) {
        scope.launch {
            combine(board, config) { b, c -> b to c }.collect { (b, c) -> runCatching { sync(b.matches, c, c.leagues.toSet()) } }
        }
    }

    /** Watch every alert-worthy upcoming or live match; stop watching the rest. Pure rules in [MatchAlertDiff.wants]. */
    suspend fun sync(matches: List<Match>, config: SportsConfig, followedLeagues: Set<String>) {
        var plan = SyncPlan(AlertState(), emptySet(), emptyList())
        update { s -> plan(s, matches, config, followedLeagues, MatchAlertHost.backgroundAllowed).also { plan = it }.state }
        plan.cancel.forEach { cancel(alertUri(it)) }
        val now = clock()
        plan.arm.forEach { arm(alertUri(it.key), maxOf(it.startMs, now)) }
    }

    /** What [sync] does to the state and the alarms — pure, so the rules are unit-tested. */
    data class SyncPlan(val state: AlertState, val cancel: Set<String>, val arm: List<Match>)

    /** After a reboot or an update: alarms do not survive either. */
    suspend fun rearmAll() {
        val s = state()
        if (!MatchAlertHost.backgroundAllowed) { cancelAll(); return }
        val now = clock()
        s.watched.forEach { (key, m) -> arm(alertUri(key), maxOf(m.startMs, now)) }
        s.reminders.forEach { (key, r) -> if (r.atMs > now) arm(reminderUri(key), r.atMs) }
    }

    /** A notification reminder for a match (phone only), at [leadMinutes] before kick-off. */
    suspend fun remind(match: Match, leadMinutes: Int, channels: List<String>): Boolean {
        if (!MatchAlertHost.backgroundAllowed) return false
        val r = AlertState.Reminder(match, leadMinutes, channels)
        if (match.startMs <= clock()) return false
        update { it.copy(reminders = it.reminders + (match.key to r)) }
        arm(reminderUri(match.key), maxOf(r.atMs, clock()))
        return true
    }

    suspend fun cancelReminder(key: String) {
        update { it.copy(reminders = it.reminders - key) }
        cancel(reminderUri(key))
    }

    internal fun armNextCheck(key: String) = arm(alertUri(key), clock() + CHECK_EVERY_MS)

    internal suspend fun stopWatching(key: String) {
        update { it.copy(watched = it.watched - key, seen = it.seen - key) }
        cancel(alertUri(key))
    }

    private suspend fun cancelAll() {
        state().watched.keys.forEach { cancel(alertUri(it)) }
    }

    private fun canBeExact(): Boolean = when {
        alarms == null -> false
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> alarms.canScheduleExactAlarms()
        else -> true
    }

    private fun arm(uri: String, atMs: Long) {
        val manager = alarms ?: return
        val pi = pendingIntent(uri) ?: return
        runCatching {
            if (canBeExact()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }.onFailure { Log.w(TAG, "could not arm $uri: ${it.message}") }
    }

    private fun cancel(uri: String) {
        val manager = alarms ?: return
        pendingIntent(uri)?.let { manager.cancel(it) }
    }

    private fun pendingIntent(uri: String): PendingIntent? {
        val intent = Intent(context, MatchAlertReceiver::class.java).apply {
            action = MatchAlertReceiver.ACTION_MATCH_DUE
            // In the data, not an extra: PendingIntent equality ignores extras.
            data = uri.toUri()
        }
        return runCatching {
            PendingIntent.getBroadcast(context, uri.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }.getOrNull()
    }

    companion object {
        internal const val TAG = "OwnTV-Sports"
        const val CHECK_EVERY_MS = 60_000L
        private const val FILE = "alerts.json"
        private const val ALERT_SCHEME = "owntv://match-alert/"
        private const val REMINDER_SCHEME = MatchAlertReceiver.REMINDER_PREFIX

        /**
         * Watch every alert-worthy upcoming or live match. Off — the host forbids background work (the
         * TV), alerts are off, or "when OwnTV is closed" is off — means watch nothing and cancel all.
         * A match already being checked (it has a last state) keeps its own minute-by-minute chain and
         * its entry until it finishes; what is new, or has not kicked off yet (a kick-off can move), is armed.
         */
        fun plan(s: AlertState, matches: List<Match>, config: SportsConfig, followedLeagues: Set<String>, backgroundAllowed: Boolean): SyncPlan {
            val on = backgroundAllowed && config.alerts.enabled && config.alerts.whenClosed
            val wanted = if (!on) emptyMap() else matches
                .filter { (it.state == MatchState.PRE || it.state == MatchState.IN) && MatchAlertDiff.wants(config, followedLeagues, it) }
                .associateBy { it.key }
            val checking = if (on) s.watched.filterKeys { it in s.seen } else emptyMap()
            val next = checking + wanted.filterKeys { it !in checking }
            val arm = wanted.filterKeys { it !in checking }.values.filter { it.state == MatchState.PRE || it.key !in s.watched }
            return SyncPlan(s.copy(watched = next, seen = s.seen.filterKeys { it in next }), s.watched.keys - next.keys, arm)
        }

        fun alertUri(key: String) = ALERT_SCHEME + android.net.Uri.encode(key)
        fun reminderUri(key: String) = REMINDER_SCHEME + android.net.Uri.encode(key)
    }
}

/**
 * One check of one match: fetch its league's scoreboard (~6 KB), compare with the last state, post
 * what changed, and arm the next check — or stop for good at full time.
 */
class MatchAlertWorker(
    appContext: Context,
    params: WorkerParameters,
    private val source: SportsSource,
    private val scheduler: MatchAlertScheduler,
    private val notifications: MatchNotifications,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_MATCH) ?: return Result.success()
        val reminder = inputData.getBoolean(KEY_REMINDER, false)
        val state = scheduler.state()
        if (reminder) {
            state.reminders[key]?.let { notifications.postReminder(it.match, it.leadMinutes, it.channels) }
            scheduler.cancelReminder(key)
            return Result.success()
        }
        val watched = state.watched[key] ?: return Result.success()
        val config = settings.activeSportsConfig.first()
        if (!MatchAlertHost.backgroundAllowed || !config.alerts.enabled || !config.alerts.whenClosed) {
            scheduler.stopWatching(key)
            return Result.success()
        }
        val league = SportsCatalog.league(watched.feed) ?: run { scheduler.stopWatching(key); return Result.success() }
        val now = try {
            source.scoreboard(league).firstOrNull { it.key == key }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(MatchAlertScheduler.TAG, "alert check failed ${e.javaClass.simpleName}")
            scheduler.armNextCheck(key)
            return Result.success()
        }
        if (now == null) {
            // Gone from today's board: ESPN's day rolled over, or the match moved. Give up after a day.
            if (System.currentTimeMillis() - watched.startMs > GIVE_UP_MS) scheduler.stopWatching(key) else scheduler.armNextCheck(key)
            return Result.success()
        }
        val alerts = MatchAlertDiff.diff(state.seen[key], now, config.alerts.kinds)
        notifications.post(alerts)
        if (MatchAlertDiff.finished(now)) {
            scheduler.stopWatching(key)
        } else {
            scheduler.update { it.copy(seen = it.seen + (key to AlertSnapshot.of(now)), watched = it.watched + (key to now)) }
            scheduler.armNextCheck(key)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_MATCH = "match"
        private const val KEY_REMINDER = "reminder"
        private const val GIVE_UP_MS = 24 * 60 * 60_000L

        fun enqueue(context: Context, key: String, reminder: Boolean) {
            val request = OneTimeWorkRequestBuilder<MatchAlertWorker>()
                .setInputData(workDataOf(KEY_MATCH to key, KEY_REMINDER to reminder))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (reminder) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("owntv-sports-${if (reminder) "reminder" else "alert"}-$key", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** A match check or a match reminder is due: hand it to [MatchAlertWorker] (a receiver may not fetch). */
class MatchAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MATCH_DUE) return
        val uri = intent.data?.toString() ?: return
        val reminder = uri.startsWith(REMINDER_PREFIX)
        MatchAlertWorker.enqueue(context, android.net.Uri.decode(uri.substringAfterLast('/')), reminder)
    }

    companion object {
        const val ACTION_MATCH_DUE = "tv.own.owntv.core.action.MATCH_DUE"
        internal const val REMINDER_PREFIX = "owntv://match-reminder/"
    }
}

/** After a reboot, an update, or an exact-alarm permission change: re-arm (alarms survive none of them). */
class MatchAlertBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val pending = goAsync()
        receiverScope.launch {
            try {
                runCatching { GlobalContext.get().get<MatchAlertScheduler>().rearmAll() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
