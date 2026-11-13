package tv.own.owntv.features.aisubtitles.whisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import tv.own.owntv.features.aisubtitles.AiSubtitleLanguages
import tv.own.owntv.features.aisubtitles.streaming.KrokoStreamingRecognizer
import tv.own.owntv.features.aisubtitles.streaming.StreamingModelCatalog
import tv.own.owntv.features.aisubtitles.streaming.StreamingModelStore

/**
 * Speech recognizer over a decoded PCM stream. The AI pipeline depends only on this interface, so
 * the ASR engine can be swapped (or a different model layout used) without touching the subtitle
 * manager.
 */
interface StreamingWhisper {
    /** Push a chunk of 16-bit mono PCM. Returns true once a full utterance is now available. */
    fun feed(samples: ShortArray, sampleRate: Int): CompiledResult
    /** Current (partial) text being built for the open utterance. */
    fun partialText(): String
    /**
     * Clear any in-flight utterance state so the recognizer can be reused for a fresh pipeline
     * session. No-op for stateless recognizers; streaming transducer recognizers drop the buffered
     * stream so reused audio can never bleed into the next session.
     */
    fun reset()
    /**
     * Restart decoder state at an in-session discontinuity (the live-audio queue jumped to the
     * newest position, or the renderer flushed after a seek/live reposition) WITHOUT resetting
     * session health counters. Defaults to [reset] for engines that keep no such counters.
     */
    fun resetStream() = reset()
    /**
     * Point the decoder's contextual bias at [hotwords] (a `/`-separated list), or at nothing when it
     * is empty. The pipeline calls this when the recognised scene changes (see `SportSceneDetector`),
     * so only a channel actually showing a sport is ever biased toward that sport's vocabulary.
     * A no-op default: only engines built for biasing carry a context graph, and the rest ignore it.
     */
    fun setHotwords(hotwords: String) {}
    /** Free the underlying recognizer. */
    fun close()

    /** The output of one [feed] call: whether an utterance finished, and its recognised text. */
    data class CompiledResult(
        val finished: Boolean,
        val text: String,
        /**
         * Age of each spoken word's END relative to the last sample this feed supplied, ms, oldest
         * first — empty when the engine cannot report token times.
         *
         * A word ends where the next one begins, and the last word ends with the audio just fed, so
         * these ages put every caption on the exact moment its last word has been heard. Without
         * them the caller can only anchor a line to the 160 ms audio frame that carried it, which
         * leaves a ±1 frame band. Engines fill this only when their result carries per-token
         * timestamps (see the streaming recognizer); a mismatch with the word count is treated as
         * "unknown" by the caller, which then falls back to frame timing.
         */
        val wordEndAgesMs: FloatArray = EMPTY_WORD_AGES,
        /**
         * Age of the recognizer stream's FIRST sample relative to the audio just fed, ms; 0 when the
         * engine does not report it. Subtracting it from the fed audio's present time gives the
         * present time of the stream's start, which is where a sentence opening at word 0 began.
         */
        val audioAgeMs: Long = 0L,
    ) {
        companion object {
            val EMPTY_WORD_AGES = FloatArray(0)
        }
    }

    /**
     * Live decode-health meters for the on-screen diagnostics chip. Engines that do not instrument
     * themselves (the offline Whisper fallback) report all-zero defaults. Streaming recognizers that
     * loop on `while (isReady) decode` expose how long each [feed] spent in native decode and how
     * often the loop actually advanced — the two numbers that settle "decode is slow" vs "decode is
     * skipped".
     */
    data class RecognizerMeters(
        /** Native wall-clock duration of the most recent [feed], milliseconds. */
        val lastFeedElapsedMs: Long = 0,
        /**
         * Wall-clock of the most recent feed that actually ran model inference, milliseconds.
         * Streaming engines buffer most feeds at ~0 ms (model chunk not yet full), so the generic
         * [lastFeedElapsedMs] always reads 0 on a healthy feed and hides the expensive frames that
         * prove the device is CPU-bound. -1 = no decoding feed observed yet (caller then falls back
         * to [lastFeedElapsedMs], which is the right number for the offline Whisper engine whose
         * every feed is an inference).
         */
        val lastDecodeMs: Long = -1,
        /**
         * Cumulative number of [feed] calls in which the recognizer's `isReady` gate actually admitted
         * at least one decode frame. Divided by the manager's feed-call count this is the `isReady`
         * hit rate: 100% means every chunk was decoded, ~0% means the decoder submitted nothing.
         */
        val isReadyHits: Long = 0,
        /**
         * Total native `decode()` model-chunk calls since the session began and the wall-clock ms
         * spent in them. avg = total/count is the cost of ONE model chunk — the number that decides
         * whether the device can decode in real time (avg must be below the chunk's own audio span,
         * typically ~320 ms). [StreamingWhisper.feed] drains every ready chunk in a loop, so
         * [lastDecodeMs] measures a whole batch (it read 2235 ms on a box that merely drained seven
         * ~300 ms chunks back-to-back) and cannot answer "how slow is one chunk?".
         */
        val decodeChunksTotal: Long = 0,
        val decodeTotalMs: Long = 0,
        /** Slowest single model-chunk decode observed this session, ms. */
        val singleDecodeMaxMs: Long = 0,
        /**
         * Per-chunk decode cost at the 50th/90th/99th percentile, milliseconds, over this session.
         *
         * [decodeTotalMs]/[decodeChunksTotal] is a mean, and a mean cannot show the tail that
         * actually costs the caption: one 2.84 s deschedule against an 823 ms average leaves the
         * average at ~830 ms, so the mean stays healthy while the live queue overran. [singleDecodeMaxMs]
         * is the opposite failure — a lifetime maximum pinned by that one outlier for the rest of the
         * session. The percentiles are the distribution between them, which is what a "did the decode
         * tail get worse?" comparison (and the p99 ≤ 1.5 s target) needs. 0 = not measured.
         */
        val decodeP50Ms: Long = 0,
        val decodeP90Ms: Long = 0,
        val decodeP99Ms: Long = 0,
        /**
         * Model-chunk decodes that took longer than one model chunk of audio (1.28 s for the bundled
         * English model) — the decodes that outpaced the decoder's own real-time budget and so forced
         * the queue toward its ceiling. The tail count the capacity decision (P1) is gated on, as a
         * plain integer instead of something read off a graph.
         */
        val decodeSlowCount: Long = 0,
        /**
         * [decodeSlowCount] split by what the decode was actually doing during the overrun, which is
         * the only way to tell a tail that needs a code change from one that needs a scheduler one:
         *
         *  - [decodeSlowGcCount] — ART collected during the decode. A stop-the-world pause inflates
         *    the wall-clock of a decode that burned almost no CPU, so it looks exactly like a
         *    deschedule from the outside; the cumulative GC counter is the signal that separates them.
         *  - [decodeSlowCpuBoundCount] — no GC, and the worker thread's own CPU time covered ≥ 80% of
         *    the decode. The work itself was that long, so neither a thread rank nor a GC fix helps.
         *  - the rest (slow − gc − cpu) — the thread was off-CPU without a GC: outranked by whatever
         *    else holds the cores. This is the bucket a thread-priority change can actually move.
         */
        val decodeSlowGcCount: Long = 0,
        val decodeSlowCpuBoundCount: Long = 0,
        /** Most model chunks drained by one [feed] call: shows how bursty the catch-up work is. */
        val drainMaxChunks: Long = 0,
        /**
         * Audio one model chunk covers, milliseconds — the recognizer's own measurement of the audio
         * it was fed against the decodes it ran. A streaming transducer only decodes once it has
         * collected a full chunk, so this is also the floor on how far behind live its partials can
         * be: the model bundled for English carries `decode_chunk_len = 128` (128 feature frames =
         * 1.28 s), which is why [decodeTotalMs]/[decodeChunksTotal] must be read against *this*
         * number, not against a guessed ~320 ms. 0 = not measured.
         */
        val chunkAudioMs: Long = 0,
    ) {
        companion object {
            val NONE = RecognizerMeters()
        }
    }

    val meters: RecognizerMeters get() = RecognizerMeters.NONE

    /**
     * One-line description of the decoder settings actually in force — beam width, ONNX threads,
     * hotword weight, blank penalty, decode method — for the diagnostics line. A tuning run is only
     * attributable if the reading says which values produced it (the pipeline surfaces this as `cfg`),
     * so an engine built on knobs that cost CPU reports them here. Engines with no such knobs (the
     * offline Whisper fallback) return the empty default and the field is omitted.
     */
    val decoderSummary: String get() = ""
}

/** Builds a [StreamingWhisper]; bound in Koin so the pipeline can resolve it by type. */
fun interface WhisperFactory {
    /**
     * Create the recognizer for a playback session in [sourceLanguage]. A streaming Kroko model is
     * used when one covers the language (English is bundled as assets; fr/de/es must be downloaded);
     * otherwise the bundled Whisper fallback.
     */
    fun create(context: Context, sourceLanguage: String): StreamingWhisper
}

/**
 * Production factory: prefers a streaming Kroko model for the source language and falls back to the
 * bundled Whisper multilingual model (an APK asset, always available) for everything else. English
 * uses its built-in (asset) streaming engine, so it works with no download; other streaming
 * languages need their model on disk first.
 */
class SherpaOnnxWhisperFactory(
    private val modelStore: StreamingModelStore,
) : WhisperFactory {
    override fun create(context: Context, sourceLanguage: String): StreamingWhisper {
        // English ships inside the APK as assets — always ready, no disk check needed.
        if (StreamingModelCatalog.isBuiltInEnglish(sourceLanguage)) {
            return KrokoStreamingRecognizer.fromAssets(
                context,
                StreamingModelCatalog.BUILT_IN_ENGLISH_ASSETS,
                // Built for biasing: the English engine is the one whose token table the hotword
                // lists were written for and the only one shipping a bpe.vocab. It starts with NO
                // list, though — the pipeline feeds [StreamingWhisper.setHotwords] the scene it
                // recognises on the channel, so ordinary television decodes unbiased.
                biasable = true,
            )
        }
        val model = StreamingModelCatalog.forLanguage(sourceLanguage)
        if (model != null && modelStore.isDownloaded(model)) {
            return KrokoStreamingRecognizer.fromDisk(modelStore.dirFor(model))
        }
        return SherpaOnnxWhisper(context, sourceLanguage)
    }
}

/**
 * The Whisper language code to pin the decoder to, or "" to keep its auto-detect.
 *
 * Whisper's decoder must be told the language through a fixed token table (sherpa-onnx
 * `GetWhisperLanguageTokenId`); a code outside it maps to -1, which is not a valid language token.
 * So only a code from the app's own list — every one of which is in Whisper's table — is forwarded;
 * anything else (an imported backup, a channel override with an unrecognised value, the empty
 * "auto" selection) falls back to "" and Whisper detects the language itself, exactly as before.
 */
private fun whisperLanguage(sourceLanguage: String): String =
    sourceLanguage.trim().lowercase().takeIf { it in AiSubtitleLanguages.CODES } ?: ""

/**
 * Recognizer via **sherpa-onnx** (offline, on-device), using the Whisper **tiny** multilingual
 * model. The decoder is pinned to the session's [sourceLanguage] (see [whisperLanguage]); only when
 * that language is unknown does Whisper fall back to detecting it from the audio.
 *
 * Whisper is a non-streaming model: it transcribes a complete utterance at once, so a Silero VAD
 * slices the incoming PCM into speech segments and each finished segment is recognised in one
 * pass. The three Whisper model files ship *inside* the APK as assets
 * (`assets/ai/whisper-tiny/…`) and are handed to sherpa-onnx through the Android AssetManager,
 * so no download and no copy to disk is involved:
 *
 * ```
 * assets/ai/whisper-tiny/tiny-encoder.int8.onnx
 * assets/ai/whisper-tiny/tiny-decoder.int8.onnx
 * assets/ai/whisper-tiny/tiny-tokens.txt
 * assets/ai/whisper-tiny/silero_vad.onnx
 * ```
 *
 * Both the recognizer and the VAD are constructed with [Context.assets], which is the exact
 * mechanism the Silero VAD has always used (its model is an asset path). The recognizer reads
 * the ONNX weights into memory via the asset manager instead of mmap'ing a disk file.
 */
class SherpaOnnxWhisper(
    context: Context,
    sourceLanguage: String,
) : StreamingWhisper {

    private val tag = "Whisper"
    private val modelInAssets = "ai/whisper-tiny"
    private val vadInAssets = "ai/whisper-tiny/silero_vad.onnx"
    private val recognizer: OfflineRecognizer
    private val vad: Vad

    init {
        val featureConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80)
        val modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = "$modelInAssets/tiny-encoder.int8.onnx",
                decoder = "$modelInAssets/tiny-decoder.int8.onnx",
                // Pin the decoder to the configured language instead of letting Whisper guess it
                // from each segment. Auto-detect is unreliable on the short VAD segments this engine
                // is handed: it can mislabel a clipped sentence and transcribe the whole segment in
                // the wrong language. "" keeps auto-detect, for the languages we cannot name.
                language = whisperLanguage(sourceLanguage),
                task = "transcribe",
            ),
            tokens = "$modelInAssets/tiny-tokens.txt",
            modelType = "whisper",
            // 4 intra-op threads like the streaming engine: a whole-utterance Whisper pass on 2
            // threads runs far slower than real time on low-end TV boxes, which piles the feed queue
            // up and jumps live audio away before it can be transcribed.
            numThreads = 4,
            provider = "cpu",
            debug = false,
        )
        val config = OfflineRecognizerConfig(
            featConfig = featureConfig,
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(assetManager = context.assets, config = config)

        val vadConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadInAssets,
                threshold = 0.5f,
                minSilenceDuration = 0.35f,
                minSpeechDuration = 0.2f,
                windowSize = 512,
                maxSpeechDuration = 15.0f,
            ),
            sampleRate = 16_000,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        vad = Vad(context.assets, vadConfig)
        Log.i(tag, "sherpa-onnx whisper-tiny multilingual (assets) + silero VAD (assets) ready")
    }

    override fun feed(samples: ShortArray, sampleRate: Int): StreamingWhisper.CompiledResult {
        // sherpa-onnx streams want floats in -1..1; scale the 16-bit PCM accordingly.
        val floats = FloatArray(samples.size)
        for (i in samples.indices) floats[i] = samples[i] / 32768f
        vad.acceptWaveform(floats)

        var finished = false
        var finalText = ""
        // Samples always arrive pre-resampled to 16k by the manager (PcmResampler), matching the VAD
        // and the recognizer's FeatureConfig. Transcribe at that fixed rate; a VAD segment's samples
        // are already the 16k speech slice.
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val recognized = transcribe(segment.samples, PcmResampler.TARGET_RATE)
            if (recognized.isNotEmpty()) {
                finished = true
                finalText = recognized
            }
        }
        return StreamingWhisper.CompiledResult(finished, finalText)
    }

    private fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val stream: OfflineStream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun partialText(): String = ""

    // The recognizer itself is stateless between utterances (each uses a fresh OfflineStream in
    // transcribe), but the Silero VAD carries a half-open speech segment across feeds. Resetting it
    // matters both across pipeline sessions and when the live-audio queue jumps over a backlog: the
    // samples on either side of that jump are NOT contiguous, so letting the VAD glue them into one
    // segment would transcribe a splice of two unrelated stretches.
    override fun reset() = vad.reset()

    override fun close() {
        runCatching { vad.release() }
        runCatching { recognizer.release() }
    }
}
