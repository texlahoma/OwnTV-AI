package tv.own.owntv.features.live

/**
 * How long a CH+/- step waits before it actually opens the stream. Comfortably longer than the ~170 ms
 * D-pad repeat, so a held key resolves to one tune, and short enough that a step in a run still feels
 * immediate.
 */
internal const val ZAP_TUNE_DELAY_MS = 500L

/**
 * A CH+/- press with no other zap key for this long is "isolated" and opens at once instead of waiting
 * [ZAP_TUNE_DELAY_MS], on every panel, single-connection ones included.
 *
 * Why the wait exists at all: tuning on every step of a held key opened thirteen streams in 3.6 s on a
 * real remote, and providers read that as abuse — a one-session Xtream panel locked the account for
 * about two minutes (HTTP 458 on every channel) and a Stalker portal answered 429. That was a burst of
 * opens, not one open: 1.5 s is several times the key repeat, so a held key or a quick run of taps still
 * waits and costs at most two opens — the first step and the channel the user stops on — never one per
 * step.
 *
 * One-connection panels used to wait on every press as well. The wait never protected their single
 * session: the previous channel keeps playing through it, and its socket closes when the new stream
 * starts, either way. So an isolated press opens at once there too (tested on a max_connections=1 Xtream
 * panel without a 458); the burst rule above is what keeps those accounts safe.
 */
internal const val ZAP_ISOLATED_PRESS_MS = 1_500L

/**
 * The wait before a CH+/- step opens its stream: 0 for an isolated press, else [ZAP_TUNE_DELAY_MS].
 * The same on every panel (see [ZAP_ISOLATED_PRESS_MS]). A clock that reads earlier than the last press
 * counts as isolated rather than wedging every step on the wait.
 */
internal fun zapTuneDelayMs(lastPressMs: Long?, nowMs: Long): Long {
    val isolated = lastPressMs == null || nowMs - lastPressMs !in 0 until ZAP_ISOLATED_PRESS_MS
    return if (isolated) 0L else ZAP_TUNE_DELAY_MS
}

/**
 * Why a CH+/- step waited (or didn't), for the diagnostics log next to the [zapTuneDelayMs] decision.
 * [singleConnection]: the playlist reported `max_connections = 1`, or the host was flagged one-session.
 */
internal fun zapWaitReason(lastPressMs: Long?, nowMs: Long, singleConnection: Boolean): String {
    val panel = " (single-connection)".takeIf { singleConnection }.orEmpty()
    return if (zapTuneDelayMs(lastPressMs, nowMs) == 0L) "isolated$panel"
    else "rapid (previous press ${nowMs - (lastPressMs ?: nowMs)}ms ago)$panel"
}
