package tv.own.owntv.features.aisubtitles.streaming

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads and caches the on-demand streaming models from [StreamingModelCatalog].
 *
 * Each model is a `.tar.bz2` archive (50–125 MB) fetched from the official sherpa-onnx release. The
 * archive is streamed to a cache file, extracted under `filesDir/ai/streaming/<archiveName>/…` and
 * then deleted, so the on-disk footprint is exactly the extracted model. Downloads run on the IO
 * dispatcher and report progress through [progress]; [downloaded] tracks which languages are ready.
 *
 * A model counts as present only when all four transducer files exist (`encoder.onnx`,
 * `decoder.onnx`, `joiner.onnx`, `tokens.txt`) — a half-extracted directory from an interrupted
 * download is re-downloaded next time instead of crashing the recognizer.
 *
 * @param readiness optional persist hook: called with the set of ready languages whenever that set
 *   changes for good (download finished / model deleted), so the app can remember the last-good
 *   state and skip the per-launch detection on later starts.
 */
class StreamingModelStore(
    context: Context,
    private val readiness: (suspend (Set<String>) -> Unit)? = null,
) {

    private val tag = "AiSubtitle"

    private val root = File(context.filesDir, "ai/streaming")
    private val cacheDir = File(context.cacheDir, "ai/streaming")
    // Bounded connect (so a blocked/unreachable host surfaces a failure quickly instead of pinning the
    // UI at "Downloading… 0%") but generous read/call windows so multi-hundred-MB archives never abort.
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    // Serialises downloads: the manager auto-downloads on pipeline start and the settings screen can
    // start one manually — two writers racing on the same archive would corrupt it.
    private val mutex = Mutex()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    /** Language codes whose streaming model is present on disk. */
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _progress = MutableStateFlow<Pair<String, Float>?>(null)
    /** Current `(language, fraction 0..1)` download, or null when idle. */
    val progress: StateFlow<Pair<String, Float>?> = _progress.asStateFlow()

    private val _extracting = MutableStateFlow<Pair<String, Long>?>(null)
    /** `(language, uncompressed bytes written so far)` while the archive is being extracted. */
    val extracting: StateFlow<Pair<String, Long>?> = _extracting.asStateFlow()

    init {
        refresh()
    }

    /** Extracted model directory on disk. */
    fun dirFor(model: StreamingModelInfo): File = File(root, model.archiveName)

    /** True when every transducer file of the model is present **and non-trivial in size**. */
    fun isDownloaded(model: StreamingModelInfo): Boolean =
        requiredFiles(model).all { it.isFile && it.length() >= 1024 } &&
            encoderIsPlausible(model)

    /**
     * A half-extracted archive (interrupted between entries) leaves a full-looking `encoder.onnx`
     * that is a few KB long — `isFile` passes, the recognizer loads it and the native decoder
     * crashes mid-stream. The encoder dominates the archive (~40 MB), so any file far below that
     * bound is a truncated extraction and the model must be re-downloaded.
     */
    private fun encoderIsPlausible(model: StreamingModelInfo): Boolean {
        val encoder = File(dirFor(model), "encoder.onnx")
        return encoder.isFile && encoder.length() >= ENCODER_MIN_BYTES
    }

    /** Re-scan the disk (after an external cleanup, or on first construction). */
    fun refresh() {
        _downloaded.value = StreamingModelCatalog.models
            .filter(::isDownloaded)
            .map { it.language }
            .toSet()
    }

    /**
     * Ensure the model is on disk, downloading and extracting it when missing.
     * Returns true when all files are present afterwards.
     */
    suspend fun ensureDownloaded(model: StreamingModelInfo): Boolean {
        if (isDownloaded(model)) return true
        // Hold the mutex across the whole download; the second caller just waits and then sees the
        // model already on disk.
        return mutex.withLock {
            if (isDownloaded(model)) return@withLock true
            try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val archive = File(cacheDir, "${model.archiveName}.tar.bz2")
                        cacheDir.mkdirs()
                        // Report download progress in (language, fraction).
                        download(model.url, archive, model.sizeBytes) { fraction ->
                            _progress.value = model.language to fraction
                        }
                        // The transfer is 100% at this point but the model is not usable until it is
                        // extracted. Surface that phase explicitly so the UI never looks frozen at "100%".
                        _extracting.value = model.language to 0L
                        extract(archive) { written ->
                            _extracting.value = model.language to written
                        }
                        archive.delete()
                        // An interrupted extraction leaves plausible-looking but truncated model files
                        // (they pass isFile, fail the size check). Delete the debris so the next attempt
                        // re-downloads cleanly instead of building a recognizer over garbage.
                        if (!isDownloaded(model)) dirFor(model).deleteRecursively()
                        isDownloaded(model)
                    }.getOrElse { e ->
                        Log.w(tag, "streaming model download failed", e)
                        false
                    }
                }
            } finally {
                // Also on cancellation (the manager cancels downloadJob when the pipeline stops): the
                // blocking download/extract have no suspension points, so cancellation only lands here
                // or on the withContext boundary — either way these must be cleared or the settings UI
                // sticks at "Downloading… x%" forever with no job to finish it.
                _extracting.value = null
                _progress.value = null
            }
            if (isDownloaded(model)) {
                refresh()
                readiness?.invoke(_downloaded.value)
                true
            } else false
        }
    }

    /** Remove the model from disk; the next use re-downloads it. */
    suspend fun delete(model: StreamingModelInfo) {
        dirFor(model).deleteRecursively()
        refresh()
        readiness?.invoke(_downloaded.value)
    }

    private fun requiredFiles(model: StreamingModelInfo): List<File> = listOf(
        File(dirFor(model), "encoder.onnx"),
        File(dirFor(model), "decoder.onnx"),
        File(dirFor(model), "joiner.onnx"),
        File(dirFor(model), "tokens.txt"),
    )

    private fun download(url: String, dest: File, total: Long, onProgress: (Float) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
            // OkHttp 5 exposes Response.body as non-nullable, so no check is required.
            val body = response.body
            // Prefer the server's real Content-Length for progress and short-download detection; the
            // catalog only estimates the size for the settings UI and can drift if the upstream
            // re-publishes a model at a different size. OkHttp returns -1 when unknown.
            val serverLength = body.contentLength()
            val expected = if (serverLength > 0) serverLength else total
            var written = 0L
            dest.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (expected > 0) onProgress((written.toFloat() / expected).coerceIn(0f, 1f))
                    }
                }
            }
            Log.i(tag, "downloaded ${dest.name}: $written bytes (expected $expected)")
            if (written < expected && expected > 0) {
                throw RuntimeException("short download $written/$expected")
            }
        }
    }

    /** Extract a `.tar.bz2` under [root]. Entry names come from the archive and are sanitised. */
    private fun extract(archive: File, onWritten: (Long) -> Unit) {
        root.mkdirs()
        BZip2CompressorInputStream(FileInputStream(archive)).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                var entry: TarArchiveEntry? = tar.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val target = safeTarget(entry.name) ?: continue
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val n = tar.read(buffer)
                                if (n < 0) break
                                if (n > 0) {
                                    out.write(buffer, 0, n)
                                    written += n
                                    onWritten(written)
                                }
                            }
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    /** Resolve an archive entry name inside [root], rejecting anything that escapes it. */
    private fun safeTarget(name: String): File? {
        val target = File(root, name).normalize()
        return if (target.absolutePath.startsWith(root.absolutePath)) target else null
    }

    private companion object {
        // Connection establishment must fail fast so the settings UI isn't stuck at "Downloading…
        // 0%" when the host is unreachable; reads/calls stay generous for the large archives.
        const val CONNECT_TIMEOUT_SECONDS = 12L
        const val READ_TIMEOUT_SECONDS = 300L
        const val CALL_TIMEOUT_SECONDS = 360L
        // Kroko zipformer encoders are ~40 MB; a "downloaded" model whose encoder is far smaller is
        // a truncated extraction and must be re-fetched (loading it makes the native decoder crash).
        const val ENCODER_MIN_BYTES = 10_000_000L
    }
}
