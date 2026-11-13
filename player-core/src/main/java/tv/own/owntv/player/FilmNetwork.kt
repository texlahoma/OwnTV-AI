package tv.own.owntv.player

import tv.own.owntv.core.player.PlayerBudget

/**
 * A film's buffer, network timeout and mid-play reconnects, resolved against the device.
 *
 * Films only: live has its own latency, pre-buffer and "Give up after" settings and its own reconnect
 * rules. Every "Auto" (0) is exactly what the engines did before these settings existed.
 *
 * **The buffer is seconds first, memory second.** Auto keeps each engine's byte cap exactly the device
 * tier's ([PlayerBudget]). A chosen buffer used to stop at that same cap, so on a 2 GB television (48 MiB
 * on mpv, 16 MiB on ExoPlayer) a 4K film filled it after a few seconds whatever was picked. Now the cap
 * grows with the chosen seconds ([SmartBuffer]) — from the stream's bitrate where it is known — but never
 * past the device's film ceiling ([PlayerBudget.filmCeilingBytes]). The setting still reads "up to":
 * a very high-bitrate film on a small TV reaches that ceiling before the seconds run out.
 *
 * The low-memory trim ([OwnTVPlayer.onTrimMemory]) still wins over all of this until the next load.
 */
internal object FilmNetwork {

    /** mpv's own wait on a silent server, which Auto keeps. ExoPlayer's is its HTTP client's. */
    const val MPV_AUTO_TIMEOUT_SECS = 60

    /** Playback since the last reopen that gives the reconnect budget back — live's rule too. */
    const val HEALTHY_MS = 60_000L

    /** Seconds of film to buffer ahead: the user's choice, or the tier's. */
    fun bufferSecs(userSecs: Int, budget: PlayerBudget): Int =
        if (userSecs > 0) userSecs else budget.cacheSecs.toIntOrNull() ?: 30

    /** mpv's `demuxer-readahead-secs`: the user's choice, or the tier's own (which differs from its cache). */
    fun readaheadSecs(userSecs: Int, budget: PlayerBudget): String =
        if (userSecs > 0) userSecs.toString() else budget.readaheadSecs

    fun mpvTimeoutSecs(userSecs: Int): Int = if (userSecs > 0) userSecs else MPV_AUTO_TIMEOUT_SECS

    /** mpv's resume wait (`cache-pause-wait`) on Auto: the 1 s films always had. */
    const val MPV_AUTO_RESUME_WAIT_SECS = 1

    /**
     * How many seconds mpv refills after a film underruns before playing on. A chosen buffer waits
     * [SmartBuffer.FILM_RESUME_WAIT_SECS], so a slow line plays in longer runs instead of stop-start
     * stutter; Auto keeps the old 1 s.
     */
    fun mpvResumeWaitSecs(userSecs: Int): Int =
        if (userSecs > 0) SmartBuffer.FILM_RESUME_WAIT_SECS else MPV_AUTO_RESUME_WAIT_SECS

    /**
     * Whether a film that dropped at [posMs] may be reopened, as the new count of attempts used — or
     * null when [budget] is spent. A minute of playback since the last reopen (at [lastAtPosMs]) starts
     * the count again, so a two-hour film on a line that drops once an hour never runs out.
     */
    fun nextReconnect(used: Int, lastAtPosMs: Long, posMs: Long, budget: Int): Int? {
        val counted = if (used > 0 && posMs - lastAtPosMs >= HEALTHY_MS) 0 else used
        return if (counted < budget) counted + 1 else null
    }
}
