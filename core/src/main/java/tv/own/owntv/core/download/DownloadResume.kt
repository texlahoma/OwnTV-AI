package tv.own.owntv.core.download

import tv.own.owntv.core.storage.MediaTarget

/**
 * The two byte-count decisions a transfer has to get right, pulled out of [DownloadEngine] so they
 * are unit-testable without a network or a database.
 *
 * The rule both of them encode: **the file on disk is the truth.** The in-memory progress counter
 * lags a write, and a killed process loses it entirely, so neither the resume offset nor the paused
 * byte count may ever be taken from it.
 *
 * They speak [MediaTarget] rather than `File` because a download folder the user picked on a phone
 * is a Storage Access Framework document, not a path — but the rule above is unchanged, and for an
 * ordinary file every answer here is byte-identical to what it was before.
 */
internal object DownloadResume {

    /**
     * Where an HTTP `Range` request must start: exactly the bytes already written.
     *
     * Zero when the destination cannot be appended to, which no ordinary file ever refuses but a SAF
     * provider may. Asking for a Range we cannot honour would truncate the partial file on the next
     * write and produce a plausible-looking, corrupt result; starting again from nothing is slower
     * and correct.
     */
    fun resumeOffset(target: MediaTarget): Long =
        if (target.exists() && target.canAppend()) target.length().coerceAtLeast(0L) else 0L

    /**
     * Total size to report while transferring. When the server honoured our Range (206) the body is
     * only the remainder, so the already-written bytes have to be added back.
     */
    fun expectedTotal(append: Boolean, existing: Long, bodyLength: Long): Long =
        (if (append) existing else 0L) + bodyLength.coerceAtLeast(0L)

    /**
     * Byte count to persist when a transfer stops. Prefers what was really written; falls back to
     * the recorded counter only when the destination is gone (removable volume unmounted, SAF grant
     * revoked, user deleted it).
     */
    fun bytesOnDisk(target: MediaTarget?, recorded: Long): Long =
        if (target != null && target.exists()) target.length() else recorded.coerceAtLeast(0L)
}
