package tv.own.owntv.core.sports

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.sports.model.Match
import android.app.NotificationManager

/**
 * The live score card: an ongoing notification for one match, which
 * the user starts from that match. It reads the score through [SportsRepository] as a
 * [Demand.PlayerScore], so the refresh policy polls that one league every 30 s while the match is
 * live, and the card stops itself at full time or on "Stop updates".
 *
 * **Foreground service type `dataSync`**: it keeps a network sync of one match's score
 * running while the app is closed, which is what the type describes. Android 15 caps `dataSync` at six
 * hours a day, and a match fits; if the cap is ever reached, [onTimeout] ends the card cleanly.
 * `specialUse` would need a Play policy declaration, and `mediaPlayback` would be untrue.
 */
class LiveMatchCardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        val key = intent?.getStringExtra(EXTRA_KEY)
        val koin = GlobalContext.getOrNull()
        val repository = koin?.get<SportsRepository>()
        val match = key?.let { repository?.board?.value?.match(it) }
        if (repository == null || match == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notifications = koin.get<MatchNotifications>()
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, LiveMatchCardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notifications.liveCard(match, stopIntent),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        job?.cancel()
        job = scope.launch {
            repository.observe(Demand.PlayerScore(match))
                .mapNotNull { it.match(match.key) }
                .distinctUntilChanged()
                .collect { m ->
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notifications.liveCard(m, stopIntent))
                    if (MatchAlertDiff.finished(m)) {
                        // Leave the final score on screen, no longer ongoing.
                        ServiceCompat.stopForeground(this@LiveMatchCardService, ServiceCompat.STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                }
        }
        return START_NOT_STICKY
    }

    /** Android 15's daily `dataSync` allowance ran out: end the card instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) = stop()

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4311
        private const val EXTRA_KEY = "match"
        private const val ACTION_STOP = "tv.own.owntv.core.action.STOP_LIVE_MATCH"

        /** Start the card for [match] — from a match page, while the app is in front (Android requires it). */
        fun start(context: Context, match: Match) {
            ContextCompat.startForegroundService(context, Intent(context, LiveMatchCardService::class.java).putExtra(EXTRA_KEY, match.key))
        }
    }
}
