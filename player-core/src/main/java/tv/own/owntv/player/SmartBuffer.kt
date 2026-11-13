package tv.own.owntv.player

import tv.own.owntv.core.player.PlayerBudget

/**
 * Makes the Film buffer setting reach memory, not just the seconds.
 *
 * Before this, a chosen Film buffer only raised the *duration* target. Each engine's byte cap stayed the
 * device tier's (ExoPlayer 16/48/72 MiB, mpv 48/128/256 MiB), so a 4K film on a 2 GB TV filled the cap in
 * a few seconds whatever the user picked. Here the byte cap follows the chosen seconds:
 *
 *  - **Auto (0) is untouched**: every function returns exactly the tier's value, so a user who never opened
 *    the setting gets the same buffer, on the same memory, as before.
 *  - **A chosen buffer** asks for `bitrate × seconds × headroom` when the stream's bitrate is known, or
 *    scales the tier's bytes by `chosen ÷ tier seconds` when it isn't. Either way the answer is never below
 *    the tier (choosing a buffer must never shrink today's) and never above the device's
 *    [PlayerBudget.filmCeilingBytes] (choosing a buffer must never OOM-kill a television).
 *  - **Live is out of scope**: live has its own latency / pre-buffer settings and its own byte rules
 *    (`LiveBuffer`, `MpvLiveBuffer`), so every live answer here is the tier's, unchanged.
 *  - **The low-memory trim wins**: once the OS has asked for memory back, nothing here raises mpv's cache
 *    again until the next load (the rule [OwnTVPlayer.onTrimMemory] already had).
 *
 * Both caps are ceilings, not allocations: the engines only hold what they have downloaded, and stop at
 * whichever of the duration or the byte target comes first, so a low-bitrate film costs no more memory
 * than it did.
 */
internal object SmartBuffer {

    private const val MIB = 1024L * 1024L

    /** On top of `bitrate × seconds`: container overhead, VBR peaks above the average, and audio or
     *  subtitle tracks a video-only bitrate read leaves out. */
    private const val BITRATE_HEADROOM = 1.25

    /** Bitrates outside this range are a bad read (a few header bytes, a burst at open), not a film. */
    const val MIN_PLAUSIBLE_BPS = 100_000L
    const val MAX_PLAUSIBLE_BPS = 200_000_000L

    /** Media that must have downloaded before a measured bitrate is trusted. */
    const val MIN_MEASURED_MEDIA_MS = 10_000L
    const val MIN_MEASURED_BYTES = 1L * MIB

    /** Seconds of playback after which mpv's film cap is re-sized from the bitrate mpv itself measured;
     *  long enough that the read is an average rather than the opening burst. */
    const val REFINE_AFTER_SECS = 20L

    /** How long mpv waits for the cache to refill after a film underruns before it plays again. mpv's
     *  default of 1 s resumed on a near-empty cache and gave stop-start stutter on a slow line. */
    const val FILM_RESUME_WAIT_SECS = 3

    /** [bps] if it can be a real stream's bitrate, else null. */
    fun plausibleBps(bps: Long?): Long? = bps?.takeIf { it in MIN_PLAUSIBLE_BPS..MAX_PLAUSIBLE_BPS }

    /**
     * A film's media bitrate from the bytes downloaded for [mediaMs] of it — the passive count, not
     * [ThroughputTracker.bitsPerSecond], so the stream-info overlay's reading is left alone. Null until
     * enough has downloaded for the number to mean anything.
     */
    fun mediaBitrateBps(bytes: Long, mediaMs: Long): Long? {
        if (bytes < MIN_MEASURED_BYTES || mediaMs < MIN_MEASURED_MEDIA_MS) return null
        return plausibleBps(bytes * 8_000 / mediaMs)
    }

    /**
     * Bytes a film may buffer.
     *
     * @param userSecs the Film buffer choice; 0 = Auto.
     * @param tierBytes the engine's byte cap for this device tier (what Auto uses).
     * @param tierSecs the seconds [tierBytes] was sized for, used to scale it when the bitrate is unknown.
     * @param ceilingBytes the device's film ceiling; a value below [tierBytes] (or 0) means "no raise".
     * @param bitrateBps the stream's bitrate, or null when unknown.
     */
    fun filmBytes(userSecs: Int, tierBytes: Long, tierSecs: Int, ceilingBytes: Long, bitrateBps: Long?): Long {
        if (userSecs <= 0 || tierBytes <= 0) return tierBytes
        val ceiling = maxOf(ceilingBytes, tierBytes)
        val wanted = plausibleBps(bitrateBps)
            ?.let { bps -> (bps / 8.0 * userSecs * BITRATE_HEADROOM).toLong() }
            // Unknown bitrate: assume the one the tier was sized for (its bytes over its seconds).
            ?: (tierBytes.toDouble() * userSecs / tierSecs.coerceAtLeast(1)).toLong()
        return roundUpToMib(wanted).coerceIn(tierBytes, ceiling)
    }

    /**
     * mpv's `demuxer-max-bytes` for a load: the tier's for live and Auto, the trim target after a
     * low-memory signal, otherwise [filmBytes] against the tier's cache.
     */
    fun mpvDemuxerMaxBytes(
        isLive: Boolean,
        userSecs: Int,
        budget: PlayerBudget,
        bitrateBps: Long?,
        trimmed: Boolean,
    ): String {
        if (trimmed) return PlayerBudget.TRIM_DEMUXER_BYTES
        if (isLive || userSecs <= 0) return budget.demuxerMaxBytes
        val tierBytes = parseMpvBytes(budget.demuxerMaxBytes) ?: return budget.demuxerMaxBytes
        val tierSecs = budget.cacheSecs.toIntOrNull() ?: DEFAULT_TIER_SECS
        return formatMpvBytes(filmBytes(userSecs, tierBytes, tierSecs, budget.filmCeilingBytes, bitrateBps))
    }

    /**
     * The most of the app's Java heap ExoPlayer's film buffer may take: a quarter of [heapClassMb]
     * (`ActivityManager.memoryClass`, or `largeMemoryClass` for an app that declares `largeHeap`).
     * ExoPlayer, unlike mpv, buffers on the Java heap, so [PlayerBudget.filmCeilingBytes] (sized for the
     * device's RAM) can be far more than the heap holds. 0 when the heap class is unknown.
     */
    fun exoHeapCapBytes(heapClassMb: Int): Long = heapClassMb.coerceAtLeast(0) * MIB / HEAP_SHARE_DIVISOR

    /**
     * ExoPlayer's `targetBufferBytes` for a film: [tierTargetBytes] (today's value) for Auto, otherwise
     * [filmBytes] within the lower of the film ceiling and [exoHeapCapBytes]. Never below the tier's
     * value (a cap under it means the tier wins). Clamped to Int because that is what Media3 takes.
     */
    fun exoTargetBytes(userSecs: Int, budget: PlayerBudget, tierTargetBytes: Int, tierSecs: Int, bitrateBps: Long?, heapClassMb: Int): Int =
        filmBytes(userSecs, tierTargetBytes.toLong(), tierSecs, minOf(budget.filmCeilingBytes, exoHeapCapBytes(heapClassMb)), bitrateBps)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** "48MiB" / "512KiB" / "1GiB" / "1048576" → bytes; null for anything else. */
    fun parseMpvBytes(spec: String): Long? {
        val m = MPV_BYTES.matchEntire(spec.trim()) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val unit = when (m.groupValues[2].lowercase()) {
            "" -> 1L
            "kib" -> 1024L
            "mib" -> MIB
            "gib" -> 1024L * MIB
            else -> return null
        }
        return n * unit
    }

    /** Bytes as mpv's MiB notation, rounded up so a cap is never smaller than asked. */
    fun formatMpvBytes(bytes: Long): String = "${roundUpToMib(bytes) / MIB}MiB"

    private fun roundUpToMib(bytes: Long): Long = ((bytes.coerceAtLeast(0) + MIB - 1) / MIB) * MIB

    private const val DEFAULT_TIER_SECS = 30

    /** ExoPlayer's film buffer gets at most 1/4 of the heap: decoders, artwork and the UI need the rest. */
    private const val HEAP_SHARE_DIVISOR = 4L
    private val MPV_BYTES = Regex("(\\d+)\\s*([KkMmGg]i[Bb])?")
}
