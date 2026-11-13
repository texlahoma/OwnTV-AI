package tv.own.owntv.core.subtitles

import tv.own.owntv.core.storage.MediaTarget
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * The OpenSubtitles "moviehash": file size + the first and last 64 KiB summed as
 * little-endian unsigned 64-bit words. Only computable when the COMPLETE media file is local —
 * OwnTV uses it solely for downloaded movies/episodes, as an optional search-quality enhancer.
 * Reads 128 KiB total, so it's fast; callers still guard it with a timeout and never let a
 * failure block the metadata search.
 *
 * It reads through a **stream** rather than a `RandomAccessFile` so that a download saved into a
 * folder the user picked — a SAF document, which has no path to open randomly — is fingerprinted
 * exactly like one saved to the app's own folder. Skipping to the last chunk is a seek rather than a
 * read in both cases, because both are a `FileInputStream` underneath.
 */
object MovieHash {

    private const val CHUNK = 64 * 1024

    /** Hex hash of [target], or null when it is too small or unreadable (the caller omits it). */
    fun compute(target: MediaTarget?): String? {
        val t = target ?: return null
        return compute(t.length()) { t.openInput() }
    }

    /**
     * Hex hash of a resource of [length] bytes read from [open], or null when it is too small or
     * unreadable. Split out from [compute] so it can be tested with no file and no Android.
     */
    fun compute(length: Long, open: () -> InputStream): String? = runCatching {
        if (length < CHUNK) return null
        open().use { input ->
            var hash = length
            hash += sumChunk(input)
            // Straight to the final chunk. `skip` is not required to move the whole way in one call,
            // so it is asked until it has — a stream that stops short would otherwise hash the wrong
            // bytes and quietly return a wrong answer rather than no answer.
            var remaining = length - CHUNK - CHUNK
            while (remaining > 0) {
                val moved = input.skip(remaining)
                if (moved <= 0) return null
                remaining -= moved
            }
            hash += sumChunk(input)
            String.format(Locale.ROOT, "%016x", hash)
        }
    }.getOrNull()

    /** Read exactly one chunk and sum it as little-endian 64-bit words. */
    private fun sumChunk(input: InputStream): Long {
        val bytes = ByteArray(CHUNK)
        var read = 0
        while (read < CHUNK) {
            val n = input.read(bytes, read, CHUNK - read)
            if (n < 0) throw java.io.EOFException()
            read += n
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0L
        repeat(CHUNK / 8) { sum += buf.long }
        return sum
    }
}
