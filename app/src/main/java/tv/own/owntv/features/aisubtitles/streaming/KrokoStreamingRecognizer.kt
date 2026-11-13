package tv.own.owntv.features.aisubtitles.streaming

import android.content.Context
import android.content.res.AssetManager
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import tv.own.owntv.features.aisubtitles.whisper.StreamingWhisper
import java.io.File
import kotlin.math.ceil

/**
 * True-streaming recognizer over a **Kroko** Zipformer model.
 *
 * Unlike the bundled Whisper fallback (which buffers a whole utterance before transcribing), a
 * streaming transducer decodes every pushed chunk immediately, so recognised words can be shown as
 * a live-partial line while the actor is still speaking. This is the engine the AI-subtitle feature
 * prefers for the languages that have a published Kroko model (en/fr/de/es).
 *
 * Two loading modes, symmetrical through the same sherpa-onnx interface:
 *
 *  - **From disk** ([KrokoStreamingRecognizer.fromDisk]): a downloaded model under
 *    `filesDir/ai/streaming/<archiveName>/…`. sherpa-onnx's file constructor mmaps the weights so a
 *    large encoder is not copied again.
 *  - **From assets** ([KrokoStreamingRecognizer.fromAssets]): the **built-in English** engine,
 *    bundled inside the APK at `assets/ai/streaming-en/…`. The AssetManager hands the weights to
 *    sherpa-onnx in memory, exactly like the bundled Whisper model — no download, no copy to disk.
 *
 * ## Optional contextual biasing
 *
 * When the recognizer is built with [biasable] (the built-in English engine — it is the only one
 * shipping the model's `bpe.vocab`), decoding runs as **modified beam search** instead of greedy and
 * each stream may carry a `/`-separated hotword list. sherpa-onnx encodes that list into the
 * stream's own context graph, so a name the acoustic model would otherwise flatten to its nearest
 * word (`Salace`, `Osullivan`) is decoded correctly.
 *
 * The list is NOT fixed for the recognizer's life: [setHotwords] swaps it by rebuilding the stream,
 * which is what lets the pipeline bias only the scene a channel is actually showing (see
 * `SportSceneDetector`) and decode ordinary television with an EMPTY list — beam search with no
 * context graph, no bias at all. Switching costs one context-graph encode, paid only when the scene
 * changes. Engines built without [biasable] (the downloaded fr/de/es models, which carry no
 * `bpe.vocab`) stay on the cheaper greedy decode and ignore [setHotwords].
 */
class KrokoStreamingRecognizer private constructor(
    private val assetManager: AssetManager?,
    private val modelDir: File?,
    private val modelInAssets: String?,
    private val biasable: Boolean,
) : StreamingWhisper {

    private val tag = "Kroko"

    private val recognizer: OnlineRecognizer
    // Re-created whenever the bias changes (see [setHotwords]): the hotword list lives on the
    // STREAM's context graph, not the recognizer. Only the audio worker thread touches it — feed,
    // reset and setHotwords all run there — so no lock is needed; close() is ordered after the
    // worker by the pipeline.
    private var stream: OnlineStream
    private var hotwords: String = ""
    // Visible to the audio worker thread: close() (from the pipeline stop) must be observed by a
    // concurrent feed() on another thread, not cached per-thread.
    @Volatile private var closed = false

    // Meter reads backing [meters]; written on the audio worker thread inside [recordFeed] and read
    // from the manager's stats push — so volatile to guarantee the raw value crosses that boundary.
    @Volatile private var lastFeedElapsedMs = 0L
    // Cost of the most recent feed that actually ran a model decode. Non-decoding feeds return in
    // ~0 ms and would always mask the expensive frames; the status chip reports THIS value.
    @Volatile private var lastDecodeMs = -1L
    @Volatile private var isReadyHits = 0L
    // Per-MODEL-CHUNK decode instrumentation (a feed drains the whole ready backlog, so the feed
    // duration is batch cost and hides the cost of one decode() call). Read from the manager's stats
    // thread, hence volatile; reset together with the stream on reset() (resident reuse).
    @Volatile private var decodeChunksTotal = 0L
    @Volatile private var decodeTotalMs = 0L
    @Volatile private var singleDecodeMaxMs = 0L
    @Volatile private var decodeSlowCount = 0L
    @Volatile private var decodeSlowGcCount = 0L
    @Volatile private var decodeSlowCpuBoundCount = 0L
    // Backing the split of a slow decode by cause (see [classifySlowDecode]). The GC count is read
    // through a TTL cache below because the read builds a Map and must not run per decode; the CPU
    // clock is a bare clock_gettime syscall and is sampled directly at both ends of the decode, which
    // is where its delta is exact. A slow decode outlives [PROBE_TTL_MS] by an order of magnitude, so
    // its end sample of the GC counter is always fresh; the start sample can be at most that stale,
    // which cannot hide a collection that happened inside the decode itself.
    @Volatile private var gcCountCache = 0L
    @Volatile private var gcProbeReadAtMs = 0L
    // Per-chunk decode durations as a coarse histogram, so the stats push can report p50/p90/p99
    // without keeping every sample: one bucket per [DECODE_HIST_BUCKET_MS], the last an overflow for
    // anything past [DECODE_HIST_BUCKETS] × that. Written once per model-chunk decode on the audio
    // worker and read from the manager's stats thread, so it is guarded by [histogramLock] — a
    // LongArray cannot be read atomically while it is being incremented. Contention is nil (one
    // increment per ~1.28 s of audio against a 1 Hz read).
    private val decodeHistogram = LongArray(DECODE_HIST_BUCKETS)
    private val histogramLock = Any()
    @Volatile private var drainMaxChunks = 0L
    // Audio handed to the stream since the counters were last reset. Against [decodeChunksTotal] it
    // yields the audio span of ONE model chunk without any assumption about the model: the encoder is
    // only ever asked to decode once it has collected a full chunk, so fedSamples/decodeCalls is that
    // chunk. The number matters because it is the floor on caption latency (the bundled English model
    // carries decode_chunk_len = 128 → 1.28 s) and because the per-chunk decode cost is only readable
    // as real-time headroom next to it.
    @Volatile private var fedSamplesSinceReset = 0L

    /**
     * Samples fed since the recognizer STREAM was last reset. sherpa's token timestamps are relative
     * to the stream's start, so turning them into "how long ago was this word heard" must use a
     * counter that restarts whenever the stream does (endpoint, discontinuity, bias switch) — unlike
     * [fedSamplesSinceReset], which deliberately survives those so the audio-per-chunk average holds.
     */
    private var samplesSinceStreamReset = 0L
    // Scratch buffer for the short→float conversion in [feed], kept at exactly the feed's sample
    // count (never larger — the JNI hands the whole array to the model, so extra elements would be
    // fed as audio). A fresh FloatArray per feed was ~10 KB of garbage every 160 ms on the ASR
    // worker, tens of MB across a session; on a TV box that churn surfaces as GC pauses between
    // decodes, exactly when the worker has to be draining the PCM queue. Only [feed] touches it, and
    // only ever from the ASR worker thread, so it needs no synchronisation.
    private var feedScratch = FloatArray(0)

    init {
        // Assets mode uses relative asset paths; disk mode uses absolute file paths. Both resolve to
        // the same OnlineTransducerModelConfig shape, so the two sources are indistinguishable to
        // sherpa-onnx once the right constructor is chosen below.
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = modelPath("encoder.onnx"),
                    decoder = modelPath("decoder.onnx"),
                    joiner = modelPath("joiner.onnx"),
                ),
                tokens = modelPath("tokens.txt"),
                modelType = "zipformer2",
                // Only the hotword encoder reads these: it splits each name into the model's own BPE
                // units, which needs the vocabulary the model was trained with. Left empty (greedy
                // path) sherpa-onnx never touches the file, so models without one stay unaffected.
                modelingUnit = if (biasable) "bpe" else "",
                bpeVocab = if (biasable) modelPath("bpe.vocab") else "",
                // Low-end TV SoCs (4× A53/A55-class cores) already spend 2+ cores on MediaCodec video
                // and the audio HAL. With 4 ONNX intra-op threads the feed loop's catch-up decode
                // saturated the box: a single feed drained seven model chunks in 2235 ms, during which
                // the renderer was starved and delivered only ~57% of the wall-clock audio (aud
                // 81/141 s). Two threads avoid the big.LITTLE oversubscription / barrier-stall regime
                // — on these cores a Zipformer chunk decodes FASTER on 2 threads than on 4 contending
                // ones — while still leaving a worker thread free per playback job. Bumped only after
                // the on-screen `dec avg` meter proves a chunk costs more than its ~320 ms audio span.
                numThreads = NUM_THREADS,
                provider = "cpu",
                debug = false,
            ),
            // Contextual biasing is implemented only by the modified-beam-search decoder: it is the
            // one that carries a context graph, and a stream can only be given a hotword list when
            // the recognizer was built this way. It is therefore chosen for the whole recognizer
            // (not per stream — a stream with no list simply carries no graph), which is why the
            // built-in English engine, the only one whose list ever changes at runtime, is built
            // biasable and everything else keeps the cheaper greedy decode it was tuned for.
            decodingMethod = if (biasable) "modified_beam_search" else "greedy_search",
            // Beam width. Measured against the model: narrowing this to 2 drops the corrections again
            // (the name's path never survives), so the wider beam is what buys the biasing.
            maxActivePaths = MAX_ACTIVE_PATHS,
            // How hard the decoder is pushed toward the names. Verified on the English model: 1.5 and
            // 2.0 left `Salace`/`Osullivan` uncorrected, 3.0 corrected them without disturbing
            // ordinary speech — which the curated, ASCII-only hotword list is what keeps true.
            hotwordsScore = HOTWORDS_SCORE,
            enableEndpoint = true,
            // A streaming transducer emits blank for most frames; with the default zero penalty the
            // joiner on a small model strongly prefers blank on noisy (crowd/stadium) audio, so
            // continuous commentary decodes to only a few of the spoken words. Verified against
            // sherpa-onnx upstream (online-transducer-greedy-search-decoder.cc): when
            // blank_penalty > 0 the decoder SUBTRACTS it from the blank-id logit
            // (`p_logit[0] -= blank_penalty`), so a positive value makes a real token beat blank
            // more often. 2.0 is a deliberately moderate bias: raise it if a noisy stream still
            // under-emits words, lower it if silence starts producing hallucinated words.
            blankPenalty = BLANK_PENALTY,
        )
        recognizer = OnlineRecognizer(assetManager = assetManager, config = config)
        // Hotwords belong to the stream, not the recognizer, and are encoded into its context graph
        // when it is created. Start with none — the pipeline only supplies a list once it has
        // recognised a scene (SportSceneDetector), so ordinary television decodes unbiased.
        stream = recognizer.createStream()
        Log.i(
            tag,
            "kroko streaming recognizer ready from " +
                "${if (assetManager != null) "assets:$modelInAssets" else modelDir?.name}" +
                if (biasable) " (biasing available)" else "",
        )
    }

    private fun modelPath(name: String): String = when {
        assetManager != null -> "$modelInAssets/$name"
        else -> File(modelDir, name).absolutePath
    }

    /**
     * The decoder settings this engine was built with, e.g. `beam4/2t/hs3.0/bp2.0` — beam width 4, 2
     * ONNX threads, hotword score 3.0, blank penalty 2.0. Reported on the diagnostics line so a
     * reading can be attributed to the configuration that produced it. An engine that carries no
     * `bpe.vocab` cannot be biased ([biasable] false) and decodes greedily, which is also what its
     * summary says, since the beam width and hotword score are unused on that path.
     */
    override val decoderSummary: String
        get() = if (biasable) {
            "beam$MAX_ACTIVE_PATHS/${NUM_THREADS}t/hs$HOTWORDS_SCORE/bp$BLANK_PENALTY"
        } else {
            "greedy/${NUM_THREADS}t/bp$BLANK_PENALTY"
        }

    override fun feed(samples: ShortArray, sampleRate: Int): StreamingWhisper.CompiledResult {
        if (closed) return StreamingWhisper.CompiledResult(false, "")
        val t0 = SystemClock.elapsedRealtime()
        // Tracks whether either decode loop below actually advanced — i.e. isReady returned true at
        // least once this feed. That is the "isReady hit" the on-screen chip reports as a rate.
        var decodedThisFeed = false
        // Model chunks drained by THIS feed: the while-loops catch up on every ready chunk, so on a
        // CPU-bound box one feed bursts through many of them (observed: 7 in 2235 ms). drainMaxChunks
        // makes that burstiness visible separately from the cost of a single chunk.
        var drainedThisFeed = 0L
        if (feedScratch.size != samples.size) feedScratch = FloatArray(samples.size)
        val floats = feedScratch
        for (i in samples.indices) floats[i] = samples[i] * INV_I16
        fedSamplesSinceReset += samples.size
        samplesSinceStreamReset += samples.size
        stream.acceptWaveform(floats, sampleRate)
        // Drain decode: each decode() call consumes exactly one model chunk. A single gated decode per
        // feed (as audio keeps flowing) falls permanently behind on live streams, where one decoded
        // ExoPlayer chunk is far larger than the model chunk size — recognizer "isReady" stays true but
        // only one chunk advances per feed, so the partial hypothesis never locks in words and subtitles
        // stay blank for minutes while playback looks smooth. Loop until the recognizer has consumed all
        // buffered frames, exactly as sherpa-onnx's own streaming pattern (`while (is_ready) decode`).
        while (recognizer.isReady(stream)) {
            decodedThisFeed = true
            drainedThisFeed++
            timedDecode()
        }

        val partialResult = recognizer.getResult(stream)
        val partial = partialResult.text.trim()
        val fedSeconds = samplesSinceStreamReset / 16_000f
        if (recognizer.isEndpoint(stream)) {
            // Endpoint hit: mark the stream finished, then keep decoding until the recognizer drains
            // the tail frames. Whisper-style/streaming recognizers buffer the final tokens until the
            // end-of-input; skipping this flush and resetting immediately would drop the last word or
            // two of the utterance. Only then read the completed text and start a fresh stream.
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                decodedThisFeed = true
                drainedThisFeed++
                timedDecode()
            }
            val finalResult = recognizer.getResult(stream)
            val finalText = finalResult.text.trim()
            recordFeed(t0, decodedThisFeed, drainedThisFeed)
            val result = StreamingWhisper.CompiledResult(
                finished = finalText.isNotEmpty(),
                text = finalText,
                wordEndAgesMs = wordEndAgesMs(finalResult, fedSeconds),
                audioAgeMs = (fedSeconds * 1_000f).toLong(),
            )
            recognizer.reset(stream)
            // The stream restarted, so do its token clock: the next utterance's timestamps are
            // relative to the new stream's first sample, not to this one's.
            samplesSinceStreamReset = 0L
            return result
        }
        recordFeed(t0, decodedThisFeed, drainedThisFeed)
        return StreamingWhisper.CompiledResult(
            finished = false,
            text = partial,
            wordEndAgesMs = wordEndAgesMs(partialResult, fedSeconds),
            audioAgeMs = (fedSeconds * 1_000f).toLong(),
        )
    }

    /**
     * Age of each word's END relative to the last sample just fed, ms (see
     * [StreamingWhisper.CompiledResult.wordEndAgesMs]).
     *
     * sherpa reports one timestamp per token — the token's START, in seconds from the stream's start —
     * and a token opening with the SentencePiece word marker "▁" (or with a leading space, for models
     * that emit whole words) starts a new word. A word therefore ends where the next word begins, and
     * the last word ends with the audio just fed. Special and empty tokens are skipped. An empty array
     * means "no exact times", which makes the caller fall back to frame-anchored timing.
     */
    private fun wordEndAgesMs(result: OnlineRecognizerResult, fedSeconds: Float): FloatArray {
        val starts = ArrayList<Float>(result.tokens.size)
        val count = minOf(result.tokens.size, result.timestamps.size)
        for (i in 0 until count) {
            val token = result.tokens[i]
            if (token.isEmpty() || token.startsWith("<")) continue
            if (i == 0 || token.startsWith("▁") || token.startsWith(" ")) starts.add(result.timestamps[i])
        }
        if (starts.isEmpty()) return StreamingWhisper.CompiledResult.EMPTY_WORD_AGES
        return FloatArray(starts.size) { k ->
            val endSec = if (k + 1 < starts.size) starts[k + 1] else fedSeconds
            (fedSeconds - endSec) * 1_000f
        }
    }

    /** Run exactly one model-chunk decode and time that single native call (not the whole batch). */
    private fun timedDecode() {
        // Sampled BEFORE the clock starts so neither probe read is charged to the decode.
        val gcBefore = gcCount()
        val cpuBeforeNs = Debug.threadCpuTimeNanos()
        val d0 = SystemClock.elapsedRealtime()
        recognizer.decode(stream)
        val d = SystemClock.elapsedRealtime() - d0
        decodeChunksTotal++
        decodeTotalMs += d
        if (d > singleDecodeMaxMs) singleDecodeMaxMs = d
        if (d > DECODE_SLOW_MS) {
            decodeSlowCount++
            classifySlowDecode(d, gcBefore, cpuBeforeNs)
        }
        val bucket = (d / DECODE_HIST_BUCKET_MS).toInt().coerceIn(0, DECODE_HIST_BUCKETS - 1)
        synchronized(histogramLock) { decodeHistogram[bucket]++ }
    }

    /**
     * Attribute one over-budget decode to GC, to its own work, or to being off-CPU without a GC —
     * see [StreamingWhisper.RecognizerMeters.decodeSlowGcCount]. Run only for decodes past
     * [DECODE_SLOW_MS], so the two probes it needs cost tens of calls a session rather than one per
     * chunk, and cannot perturb the very distribution they describe.
     *
     * GC is tested first because a pause is the more specific event: it also deschedules the thread,
     * so a collection that lands inside a starved decode would otherwise be counted as starvation and
     * point the fix at thread ranks instead of at allocation.
     */
    private fun classifySlowDecode(d: Long, gcBefore: Long, cpuBeforeNs: Long) {
        if (gcCount() > gcBefore) {
            decodeSlowGcCount++
            return
        }
        val cpuAfterNs = Debug.threadCpuTimeNanos()
        if (cpuBeforeNs < 0L || cpuAfterNs < cpuBeforeNs) return
        val cpuMs = (cpuAfterNs - cpuBeforeNs) / 1_000_000L
        if (cpuMs * 100L >= d * SLOW_CPU_BOUND_PERCENT) decodeSlowCpuBoundCount++
    }

    /**
     * Cumulative ART collection count, or the last reading when the runtime does not report it.
     * TTL-cached because the underlying call builds a Map: sampled at most once per [PROBE_TTL_MS],
     * which is short enough that [classifySlowDecode] always observes a fresh value after a decode it
     * is classifying. Called only from the ASR worker, so no lock is needed.
     */
    private fun gcCount(): Long {
        val now = SystemClock.elapsedRealtime()
        if (gcProbeReadAtMs != 0L && now - gcProbeReadAtMs < PROBE_TTL_MS) return gcCountCache
        gcProbeReadAtMs = now
        runCatching { Debug.getRuntimeStats()?.get("art.gc.gc-count")?.toLongOrNull() }
            .getOrNull()?.let { gcCountCache = it }
        return gcCountCache
    }

    /**
     * The decode duration at [fraction] of the session's samples, in ms — the smallest bucket whose
     * cumulative count reaches the fraction, read at its upper edge so the estimate is a ceiling
     * rather than a midpoint (a p99 target of 1.5 s should not be passed by rounding down). Returns
     * 0 before any decode has been sampled.
     */
    private fun decodePercentile(fraction: Double): Long {
        synchronized(histogramLock) {
            var total = 0L
            for (c in decodeHistogram) total += c
            if (total == 0L) return 0L
            val target = ceil(total * fraction).toLong().coerceAtLeast(1L)
            var acc = 0L
            for (i in decodeHistogram.indices) {
                acc += decodeHistogram[i]
                if (acc >= target) return (i + 1).toLong() * DECODE_HIST_BUCKET_MS
            }
            return DECODE_HIST_BUCKETS.toLong() * DECODE_HIST_BUCKET_MS
        }
    }

    /** Update the cross-thread meter reads backing [meters]. */
    private fun recordFeed(t0: Long, decoded: Boolean, drained: Long) {
        val elapsed = SystemClock.elapsedRealtime() - t0
        lastFeedElapsedMs = elapsed
        if (decoded) {
            isReadyHits++
            // Only decoding feeds spend real time in inference; this is the number that proves a
            // device can't keep up, while non-decoding feeds sit at 0 ms and hide it.
            lastDecodeMs = elapsed
            if (drained > drainMaxChunks) drainMaxChunks = drained
        }
    }

    override val meters: StreamingWhisper.RecognizerMeters
        get() = StreamingWhisper.RecognizerMeters(
            lastFeedElapsedMs = lastFeedElapsedMs,
            lastDecodeMs = lastDecodeMs,
            isReadyHits = isReadyHits,
            decodeChunksTotal = decodeChunksTotal,
            decodeTotalMs = decodeTotalMs,
            singleDecodeMaxMs = singleDecodeMaxMs,
            decodeP50Ms = decodePercentile(0.50),
            decodeP90Ms = decodePercentile(0.90),
            decodeP99Ms = decodePercentile(0.99),
            decodeSlowCount = decodeSlowCount,
            decodeSlowGcCount = decodeSlowGcCount,
            decodeSlowCpuBoundCount = decodeSlowCpuBoundCount,
            drainMaxChunks = drainMaxChunks,
            // Samples are 16 kHz mono, so /16 is milliseconds of audio per model chunk.
            chunkAudioMs = if (decodeChunksTotal > 0L) fedSamplesSinceReset / 16L / decodeChunksTotal else 0L,
        )

    override fun partialText(): String {
        if (closed) return ""
        return recognizer.getResult(stream).text.trim()
    }

    // Reuse for a new pipeline session: drop the recognizer's buffered stream so audio from the
    // previous session never contaminates the new one. The built-in English instance is process-
    // resident and borrowed by many sessions, so its cumulative decode meters restart here too —
    // otherwise the on-screen `dec avg` would be blended with every earlier session.
    override fun reset() {
        if (closed) return
        recognizer.reset(stream)
        lastFeedElapsedMs = 0L
        lastDecodeMs = -1L
        isReadyHits = 0L
        decodeChunksTotal = 0L
        decodeTotalMs = 0L
        singleDecodeMaxMs = 0L
        decodeSlowCount = 0L
        decodeSlowGcCount = 0L
        decodeSlowCpuBoundCount = 0L
        synchronized(histogramLock) { decodeHistogram.fill(0L) }
        drainMaxChunks = 0L
        // Keeps the audio-per-chunk ratio consistent: it is derived from this counter and the decode
        // count, so both restart together (see resetStream(), where neither does).
        fedSamplesSinceReset = 0L
        samplesSinceStreamReset = 0L
    }

    // In-session discontinuity (live-jump / renderer flush): decoder state must restart, but this
    // is the SAME session — its decode counters keep accumulating (frequent jumps on a slow box
    // would otherwise restart the average before enough samples built up).
    override fun resetStream() {
        if (closed) return
        recognizer.reset(stream)
        samplesSinceStreamReset = 0L
    }

    /**
     * Swap the decoder's contextual-bias vocabulary — the pipeline calls this when the scene detector
     * recognises (or loses) a sport scene, so only the scene a channel is actually showing ever biases
     * it. An empty [next] means no bias at all, which is what ordinary television gets.
     *
     * sherpa-onnx stores the hotwords on the *stream* (encoded into its context graph), so a change
     * means rebuilding the stream. That drops whatever partial hypothesis was in flight — the caller
     * runs this at a scene boundary, one sentence at worst — and re-encodes the list once. Called on
     * the audio worker thread only (never concurrently with [feed]), so no synchronisation is needed
     * beyond the [closed] guard; engines built without `biasable` ignore it entirely.
     */
    override fun setHotwords(next: String) {
        if (closed || !biasable || next == hotwords) return
        // Build the replacement BEFORE releasing the live stream. createStream() re-encodes the list
        // into a context graph and allocates native state, so it can fail — and releasing first left
        // [stream] pointing at a freed handle on that path, which the next [feed] then used: a
        // use-after-free on the ASR thread, i.e. a native crash rather than a lost bias. Holding the old
        // stream until the new one exists costs only the switch (the bias stays as it was); the old
        // stream is still released below, never leaked. `hotwords` is updated only on success, so a
        // failed switch is simply retried at the next scene boundary.
        val replacement = try {
            if (next.isBlank()) recognizer.createStream() else recognizer.createStream(next)
        } catch (e: Throwable) {
            Log.w(tag, "kroko bias switch failed; keeping the current vocabulary", e)
            return
        }
        hotwords = next
        runCatching { stream.inputFinished() }
        runCatching { stream.release() }
        stream = replacement
        // A replacement stream restarts its own token clock (see [samplesSinceStreamReset]).
        samplesSinceStreamReset = 0L
        Log.i(tag, "kroko bias -> ${if (next.isBlank()) "none" else "${next.count { it == '/' } + 1} terms"}")
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { stream.inputFinished() }
        runCatching { stream.release() }
        runCatching { recognizer.release() }
    }

    companion object {
        /** 1/32768 as a multiply: the per-sample conversion of [feed] must not pay a division. */
        private const val INV_I16 = 1.0f / 32768f

        // The decoder's tunables, named rather than inlined into the config below so that
        // [decoderSummary] reports the values a session ACTUALLY decoded with — a run to tune any of
        // them is unattributable without that. The rationale for each stays with its use in `init`.
        /** ONNX intra-op threads per decode (the box has 4 cores and shares them with video decode). */
        private const val NUM_THREADS = 2

        /** Beam width of the modified-beam-search decoder; carries the hotword context graph. */
        private const val MAX_ACTIVE_PATHS = 4

        /** How hard the decoder is pushed toward a hotword list. */
        private const val HOTWORDS_SCORE = 3.0f

        /** Logit penalty that stops a small model emitting blank on noisy stadium audio. */
        private const val BLANK_PENALTY = 2.0f

        /**
         * Bucket width of the decode-duration histogram behind p50/p90/p99, ms. 20 ms resolves the
         * 1.28–1.5 s range the p99 target lives in finely enough to act on, while keeping the array
         * at [DECODE_HIST_BUCKETS] entries.
         */
        private const val DECODE_HIST_BUCKET_MS = 20

        // 160 × 20 ms = 3.2 s, comfortably past the worst decode observed (2.84 s); anything longer
        // clamps into the last bucket, which is fine — it is already far outside any target.
        private const val DECODE_HIST_BUCKETS = 160

        /**
         * A decode slower than one model chunk of audio is past real time for that chunk. 1.28 s is
         * the bundled English model's `decode_chunk_len = 128` (128 feature frames); the count of
         * decodes above it is the `> 1.28s` tail the capacity trade (P1) turns on.
         */
        private const val DECODE_SLOW_MS = 1_280L

        /**
         * Shortest interval between two reads of the runtime's GC counter, ms. The read builds a Map
         * and sits on the ASR worker's decode path, so it is cached; a slow decode lasts 1.28 s or
         * more, which is why a cache this short still yields an exact reading across it.
         */
        private const val PROBE_TTL_MS = 250L

        /**
         * CPU-time share of a slow decode above which it is counted as computing rather than starved.
         * The decode is split over [NUM_THREADS] intra-op threads and ONNX's calling thread takes part
         * in the parallel work, so its own CPU clock does not cover the whole job: it sits near 100%
         * of the wall-clock when both threads are saturated and collapses to a few percent when the
         * thread is parked waiting for a core. 80% is the middle of that gap, and the residual
         * "neither GC nor CPU" bucket stays visible in the status line, so a wrong call here shows up
         * as a bucket that never fills rather than as a silently misread tail.
         */
        private const val SLOW_CPU_BOUND_PERCENT = 80L

        /** Recognizer over a downloaded model directory on disk (greedy; the archives carry no bpe.vocab). */
        fun fromDisk(modelDir: File): KrokoStreamingRecognizer =
            KrokoStreamingRecognizer(
                assetManager = null,
                modelDir = modelDir,
                modelInAssets = null,
                biasable = false,
            )

        /**
         * Recognizer over the shipped English model inside the APK assets.
         * [modelInAssets] is the assets-relative directory (e.g. `ai/streaming-en`).
         *
         * [biasable] must be true only for a model directory that also carries a `bpe.vocab` (the
         * built-in English one does): it selects the modified-beam-search decoder and lets the caller
         * later hand the stream a list through [setHotwords]. The list itself is NOT passed here —
         * the pipeline starts unbiased and supplies a scene's list once it has recognised one.
         */
        fun fromAssets(
            context: Context,
            modelInAssets: String,
            biasable: Boolean = false,
        ): KrokoStreamingRecognizer =
            KrokoStreamingRecognizer(
                assetManager = context.assets,
                modelDir = null,
                modelInAssets = modelInAssets,
                biasable = biasable,
            )
    }
}