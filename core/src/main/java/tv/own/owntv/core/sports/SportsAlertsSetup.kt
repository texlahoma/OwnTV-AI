package tv.own.owntv.core.sports

import android.content.Context
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.reminder.ReminderManager
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.model.Match

/**
 * What the apps call from Settings → Sports → Goal alerts and from a match's "Remind me".
 */
class SportsAlertsSetup(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scheduler: MatchAlertScheduler,
    private val reminders: ReminderManager,
    private val notifications: MatchNotifications,
    private val localeStore: LocaleStore,
) {
    /**
     * Goal alerts on or off for [profileId]. Turning them on asks for notification permission once,
     * through the host's hook ([MatchAlertHost.requestNotificationPermission]), if it is not granted.
     */
    suspend fun enable(profileId: Long, on: Boolean) {
        settings.updateSportsConfig(profileId) { it.copy(alerts = it.alerts.copy(enabled = on)) }
        if (on && !notifications.canPost()) MatchAlertHost.requestNotificationPermission?.invoke()
    }

    /**
     * "Remind me" on a match. With a channel showing it, this is a programme reminder on the best one
     * — the guide's own path: the in-app prompt while the app is open, the phone's own notification while
     * it is not. Only without a channel does the phone post a sports notification at the lead time,
     * so one reminder never arrives twice. Returns false when neither applies:
     * a TV with no channel for the match yet ("Guide not out yet").
     */
    suspend fun remind(profileId: Long, match: Match, channels: List<WatchOption>, leadMinutes: Int): Boolean {
        val pair = MatchNotifications.pair(AppLocale.wrap(context, localeStore.currentTag.value), match)
        val best = channels.firstOrNull()
        val programme = best != null && reminders.add(
            profileId = profileId,
            channelId = best.channel.id,
            channelName = best.facets.name,
            epgChannelId = best.channel.epgChannelId,
            title = pair,
            startMs = match.startMs,
            stopMs = match.startMs + MATCH_LENGTH_MS,
            leadMinutes = leadMinutes,
        )
        val notification = !programme && scheduler.remind(match, leadMinutes, channels.map { it.facets.name }.distinct())
        return programme || notification
    }

    private companion object {
        /** A reminder's programme slot when the guide gives none: most matches end inside two and a half hours. */
        const val MATCH_LENGTH_MS = 150 * 60_000L
    }
}
