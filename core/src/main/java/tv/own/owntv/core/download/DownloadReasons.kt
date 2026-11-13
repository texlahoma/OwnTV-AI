package tv.own.owntv.core.download

import android.content.res.Resources
import tv.own.owntv.core.R
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadReason

/**
 * Why a transfer stopped, as the row says it. Both apps show the same list, so the wording lives here
 * once — as [tv.own.owntv.core.recording.RecordingRules.displayTextOf] does for recordings.
 */
object DownloadReasons {

    /** What an HTTP answer that was not the file means for the user. */
    fun ofHttp(code: Int): DownloadReason = when (code) {
        // The usual "max connections reached" answers of IPTV panels.
        429, 458, 509 -> DownloadReason.ACCOUNT_BUSY
        401, 403 -> DownloadReason.REFUSED
        404, 410 -> DownloadReason.NOT_FOUND
        in 500..599 -> DownloadReason.SERVER_ERROR
        else -> DownloadReason.HTTP_ERROR
    }

    /** What a failed read or write means: a full volume, a file too big for it, or otherwise the network. */
    fun ofError(e: Throwable): DownloadReason {
        val text = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return when {
            "ENOSPC" in text || "No space left" in text -> DownloadReason.NO_SPACE
            "EFBIG" in text || "File too large" in text -> DownloadReason.FILE_TOO_LARGE
            else -> DownloadReason.CONNECTION_LOST
        }
    }

    /** The sentence for [d]'s reason, with the HTTP status beside it; null when there is nothing to explain. */
    fun displayTextOf(d: DownloadEntity, res: Resources): String? {
        val text = when (d.reason ?: return null) {
            DownloadReason.ACCOUNT_BUSY -> res.getString(R.string.content_downloads_reason_account_busy)
            DownloadReason.REFUSED -> res.getString(R.string.content_downloads_reason_refused)
            DownloadReason.NOT_FOUND -> res.getString(R.string.content_downloads_reason_not_found)
            DownloadReason.SERVER_ERROR -> res.getString(R.string.content_downloads_reason_server_error)
            DownloadReason.HTTP_ERROR -> res.getString(R.string.content_downloads_reason_http)
            DownloadReason.CONNECTION_LOST -> res.getString(R.string.content_downloads_reason_connection_lost)
            DownloadReason.FOLDER_UNAVAILABLE -> res.getString(R.string.content_downloads_reason_folder)
            DownloadReason.NO_SPACE -> res.getString(R.string.content_downloads_reason_no_space)
            DownloadReason.FILE_TOO_LARGE -> res.getString(R.string.content_downloads_reason_too_large)
            DownloadReason.WAITING_FOR_WATCHING -> res.getString(R.string.content_downloads_reason_waiting_watching)
            DownloadReason.PAUSED_FOR_WATCHING -> res.getString(R.string.content_downloads_reason_paused_watching)
        }
        return d.httpCode?.let { res.getString(R.string.content_downloads_reason_with_code, text, it) } ?: text
    }
}

/** A transfer attempt that failed for a reason the row should show. */
internal class DownloadFailure(val reason: DownloadReason, val httpCode: Int? = null) :
    java.io.IOException("download failed: $reason${httpCode?.let { " (HTTP $it)" } ?: ""}")
