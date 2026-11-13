package tv.own.owntv.core.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Where a downloaded film or a live recording is actually written, in the two shapes Android has.
 *
 * [Path] is an ordinary file on a volume this app may write to: its own folder on internal storage
 * or on an SD card, which needs no permission at all, or anywhere on a **television**, which holds
 * `MANAGE_EXTERNAL_STORAGE` and can therefore browse real directories. [Document] is a Storage
 * Access Framework document inside a tree the user picked from the system folder picker, which is
 * the only way a **phone bound for Google Play** can write to a folder of the user's own choosing:
 * Play restricts all-files access to file managers, backup tools and antivirus apps, and a media
 * player asking for it is refused at review.
 *
 * **This cost no database migration, and that is by design.** `filePath` on both `DownloadEntity`
 * and `RecordingEntity` is a `String`, and so is the `downloadRoot` preference; a document is simply
 * a `content://` URI in the same column. [of] is the one place that decides which is which, so no
 * caller anywhere has to ask. Every row written before this existed is a path and keeps working
 * untouched.
 *
 * Callers do not construct these directly — they ask [of] for a stored string, or [MediaRoot.child]
 * for a new one.
 */
sealed interface MediaTarget {

    /** Exactly what goes in the `filePath` column, and what [of] will turn back into this object. */
    val stored: String

    /** The file's own name, for a list row or an export's suggested filename. */
    val displayName: String

    /** Whether anything has been written here yet. */
    fun exists(): Boolean

    /**
     * Bytes written so far. **The file is the truth** — every resume offset and paused byte count
     * comes from here and never from an in-memory counter, which lags a write and is lost outright
     * to a killed process.
     */
    fun length(): Long

    /** Remove it. Best-effort: a file already gone is not a failure. */
    fun delete(): Boolean

    /**
     * Throw away the bytes but keep the destination itself, which is what "start this download
     * again from zero" means.
     *
     * For a file the two are the same thing and this deletes it, exactly as before. For a document
     * they are emphatically not: deleting it would drop the entry the user's folder grant points at,
     * and the retry would then fail with nowhere to write instead of starting over.
     */
    fun truncate()

    /**
     * Whether the destination is there and can be written to — i.e. the volume is actually mounted,
     * or the picked tree is still granted. A download folder on removable storage can simply vanish
     * when the card is pulled, and a SAF grant can be revoked from system settings; both have to
     * fail loudly rather than silently re-home gigabytes onto internal storage.
     */
    fun ensureWritable(): Boolean

    /**
     * Whether a partial file here can be appended to, which is what decides whether an interrupted
     * transfer resumes with an HTTP `Range` or starts again from zero.
     *
     * Always true for a [Path]. For a [Document] it depends on the provider behind the tree: the
     * SAF contract does not require append mode, and one that refuses it would otherwise truncate
     * the partial file on the next attempt and silently produce a corrupt result.
     */
    fun canAppend(): Boolean

    /** Open for writing, continuing after the existing bytes when [append], truncating when not. */
    fun openOutput(append: Boolean): OutputStream


    /**
     * Open for reading. Used to fingerprint a finished download for a subtitle search, which needs
     * the first and last 64 KiB — so the stream this returns has to support skipping forward
     * cheaply. Both shapes do: a file and a document are both a `FileInputStream` underneath, whose
     * `skip` is a seek rather than a read.
     */
    fun openInput(): InputStream

    /**
     * Room left on whatever this is being written to, so a recorder can stop before it fills the
     * card rather than after.
     *
     * Zero when it cannot be worked out, which for a document means a provider with no local volume
     * behind it. Callers treat zero as "no room" and stop, which is the safe direction: refusing to
     * start is recoverable, filling a user's storage is not.
     */
    fun usableSpace(): Long

    /** An ordinary file on a mounted volume. */
    class Path(val file: File) : MediaTarget {
        override val stored: String get() = file.absolutePath
        override val displayName: String get() = file.name
        override fun exists(): Boolean = file.exists()
        override fun length(): Long = if (file.exists()) file.length().coerceAtLeast(0L) else 0L
        override fun delete(): Boolean = runCatching { file.delete() }.getOrDefault(false)

        override fun truncate() {
            delete()
        }

        override fun ensureWritable(): Boolean {
            val parent = file.parentFile ?: return false
            if (!parent.exists()) runCatching { parent.mkdirs() }
            return parent.isDirectory && parent.canWrite()
        }

        override fun canAppend(): Boolean = true
        override fun openOutput(append: Boolean): OutputStream = java.io.FileOutputStream(file, append)
        override fun openInput(): InputStream = file.inputStream()
        override fun usableSpace(): Long = file.parentFile?.usableSpace ?: 0L
    }

    /** A SAF document inside a tree the user granted. */
    class Document(private val context: Context, val uri: Uri) : MediaTarget {
        override val stored: String get() = uri.toString()

        /**
         * Read from the URI rather than queried from the provider: this is wanted on a list row and
         * for an export's suggested name, neither of which should cost a content-resolver round
         * trip. A document id ends in the file's own name on every provider that has a filesystem
         * behind it, which is all of them here.
         */
        override val displayName: String
            get() = Uri.decode(uri.toString()).substringAfterLast('/').substringAfterLast(':')

        private fun doc(): DocumentFile? = DocumentFile.fromSingleUri(context, uri)

        override fun exists(): Boolean = runCatching { doc()?.exists() == true }.getOrDefault(false)

        override fun length(): Long =
            runCatching { doc()?.length() ?: 0L }.getOrDefault(0L).coerceAtLeast(0L)

        override fun delete(): Boolean = runCatching { doc()?.delete() == true }.getOrDefault(false)

        override fun truncate() {
            runCatching { context.contentResolver.openOutputStream(uri, TRUNCATE_MODE)?.use { } }
        }

        override fun ensureWritable(): Boolean =
            runCatching { doc()?.canWrite() == true }.getOrDefault(false)

        /**
         * Probed rather than assumed, once per attempt. Opening in `"wa"` does not truncate, so
         * asking the question is safe — unlike `"w"`, which would destroy the partial file merely by
         * being opened.
         */
        override fun canAppend(): Boolean = runCatching {
            context.contentResolver.openOutputStream(uri, APPEND_MODE)?.use { } != null
        }.getOrDefault(false)

        override fun openOutput(append: Boolean): OutputStream =
            context.contentResolver.openOutputStream(uri, if (append) APPEND_MODE else TRUNCATE_MODE)
                ?: throw java.io.IOException("cannot open $uri for writing")

        override fun openInput(): InputStream =
            context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("cannot open $uri for reading")

        override fun usableSpace(): Long = runCatching {
            val volume = DocumentVolumes.volumeIdOf(DocumentsContract.getDocumentId(uri))
            DocumentVolumes.dirOf(context, volume)?.usableSpace ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        /** Write after what is already there. */
        private const val APPEND_MODE = "wa"

        /** Throw away what is already there. */
        private const val TRUNCATE_MODE = "wt"

        /** The scheme that marks a stored string as a document rather than a path. */
        private const val DOCUMENT_SCHEME = "content://"

        /** Whether [stored] names a SAF document. The single rule, so no caller invents its own. */
        fun isDocument(stored: String?): Boolean =
            stored != null && stored.startsWith(DOCUMENT_SCHEME, ignoreCase = true)

        /**
         * Turn a stored `filePath` back into something that can be written, measured and deleted.
         *
         * Null for a blank or absent value, which is what an unstarted recording has: the row exists
         * before any byte does, and a caller that has nowhere to write yet must be able to tell.
         */
        fun of(context: Context, stored: String?): MediaTarget? {
            val value = stored?.takeIf { it.isNotBlank() } ?: return null
            return if (isDocument(value)) Document(context, value.toUri()) else Path(File(value))
        }

        private fun String.toUri(): Uri = Uri.parse(this)
    }
}
