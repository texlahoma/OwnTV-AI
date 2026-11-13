package tv.own.owntv.features.aisubtitles

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong
import tv.own.owntv.core.database.entity.PlaybackPrefsEntity
import tv.own.owntv.core.player.PlaybackPrefsStore
import tv.own.owntv.features.aisubtitles.streaming.StreamingModelCatalog
import tv.own.owntv.features.aisubtitles.streaming.StreamingModelStore
import tv.own.owntv.features.aisubtitles.translate.FallbackTranslator
import tv.own.owntv.features.aisubtitles.translate.SpeechTranslator
import tv.own.owntv.features.aisubtitles.translate.TranslationHttpException
import tv.own.owntv.features.aisubtitles.whisper.PcmResampler
import tv.own.owntv.features.aisubtitles.whisper.StreamResampler16k
import tv.own.owntv.features.aisubtitles.whisper.StreamingWhisper
import tv.own.owntv.features.aisubtitles.whisper.WhisperFactory
import tv.own.owntv.player.LiveAudioDelay
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlaybackAudioTap
import tv.own.owntv.player.PlaybackEngine
import tv.own.owntv.player.PlaybackSession

/** On-screen pipeline counters exposed via [AiSubtitleManager.stats]. */
data class PipelineStats(
    val fedChunks: Long = 0,
    val blankChunks: Long = 0,
    val partialChunks: Long = 0,
    /** Cumulative recognised word count for the current session; see [recognizedWords]. */
    val recognizedWords: Long = 0,
    /**
     * Frames discarded to keep live audio ahead of a slower recognizer. A rising count means the
     * recognizer can't decode in real time on this device; the queue jumps to the newest audio in
     * whole stretches (see [RealtimePcmQueue]) instead of feeding time-compressed fragments.
     */
    val droppedFrames: Long = 0,
    /** Wall-clock ms the most recent recognizer decode spent in native inference. */
    val feedMs: Long = 0,
    /**
     * Worst wall-clock ms ONE recognizer feed call took this session — the whole `reco.feed()`
     * (float conversion + front-end + the `while (isReady) decode` loop), not just the native decode
     * [decodeMaxMs] reports. Read the two together to split a stall: feed ≈ dec means native
     * inference owns it, feed ≫ dec means the time went outside the decode (JNI, feature extraction,
     * or the worker being descheduled — see [gcCount]).
     */
    val feedMaxMs: Long = 0,
    /** Seconds since the previous non-empty partial was recognised (how fresh the English line is). */
    val partialIntervalSec: Long = 0,
    /** Percent of recognizer feeds in which its `while (isReady) decode` loop actually advanced. */
    val isReadyHitPercent: Int = 100,
    /** Caption windows handed by the [CaptionSegmenter] to the translation worker. */
    val committedSegments: Long = 0,
    /** Average wall-clock ms a single model-chunk `decode()` cost this session (real-time litmus). */
    val decodeAvgMs: Long = 0,
    /** Slowest single model-chunk decode observed this session, ms. */
    val decodeMaxMs: Long = 0,
    /**
     * Per-chunk decode cost at the 50th/90th/99th percentile this session, ms — the distribution
     * [decodeAvgMs] flattens and [decodeMaxMs] lets one outlier dominate (see
     * [StreamingWhisper.RecognizerMeters.decodeP50Ms]). The p99 is what the `≤ 1.5 s` target is
     * read against.
     */
    val decodeP50Ms: Long = 0,
    val decodeP90Ms: Long = 0,
    val decodeP99Ms: Long = 0,
    /**
     * Model-chunk decodes slower than 1.28 s of audio — the tail that proves the decoder cannot keep
     * up on this device without help (see [StreamingWhisper.RecognizerMeters.decodeSlowCount]).
     */
    val decodeSlowCount: Long = 0,
    /**
     * [decodeSlowCount] split by cause — GC, the decode's own work, or the thread being off-CPU for
     * neither reason (outranked). Read together: a tail that is mostly `gc` is fixed by allocation,
     * one that is mostly `cpu` is not fixable by any thread rank, and one that is neither is the only
     * case where raising priority helps (see
     * [StreamingWhisper.RecognizerMeters.decodeSlowGcCount]).
     */
    val decodeSlowGcCount: Long = 0,
    val decodeSlowCpuBoundCount: Long = 0,
    /**
     * Share of the session wall-clock the ASR worker spent inside its per-frame loop, percent. Near
     * 100% means the queue never starves it (decode is at or behind real time); a low value means it
     * is mostly blocked waiting for audio. Distinguishes "not enough work" from "too much work" for
     * a busy reading of [decodeAvgMs].
     */
    val consumerBusyPercent: Int = 0,
    /**
     * Audio a single model chunk covers, ms — read from the recognizer (it measures the audio it was
     * fed against the decodes it ran). [decodeAvgMs] only answers "can this box keep up?" next to
     * this number: 670 ms looks alarming until it turns out one chunk is 1280 ms of audio.
     */
    val chunkAudioMs: Long = 0,
    /**
     * Real-time factor: model-chunk decode ms per chunk of *audio* ms. Below 1.0 the recognizer
     * decodes faster than the speech arrives and captions can keep up; at or above 1.0 it is losing
     * ground and the live queue will start dropping audio.
     */
    val realtimeFactor: Double = 0.0,
    /** Gain the pipeline currently applies to the frames it feeds the recognizer (see [AsrGain]). */
    val gainRatio: Double = 1.0,
    /** Most model chunks one feed call ever drained at once (catch-up burst depth). */
    val drainMaxChunks: Long = 0,
    /** Deepest the 160 ms-frame queue ever got; × 160 ms is the worst lag behind live. */
    val queueHighWater: Int = 0,
    /** Timeline discontinuities (overload jumps + renderer flushes/seeks) observed this session. */
    val generationJumps: Long = 0,
    /** Loudness (RMS, 0..32767) of the most recent frame the recognizer consumed. ~0 = silent tap. */
    val lastRms: Int = 0,
    /** Seconds of 16 kHz audio actually pushed to the recognizer since the session started. */
    val audioSeconds: Long = 0,
    /**
     * Seconds the player was actually PLAYING (not paused/buffering/reconnecting) this session.
     * This — not raw wall clock — is the denominator [audioSeconds] must match: aud/playing ≈ 1 on
     * a healthy capture even if the user spent minutes in menus, while aud/playing ≪ 1 during
     * continuous playback means the tap/recognizer is starving.
     */
    val playingSeconds: Long = 0,
    /** Wall-clock seconds the session has been running; compare with [playingSeconds] for paused time. */
    val wallSeconds: Long = 0,
    /**
     * Seconds the player spent buffering this session (network starvation), as opposed to the
     * paused/menu time that [wallSeconds] − [playingSeconds] lumps together with it. A large value
     * alongside `jmp`'s flush half means a stall was the stream's, not the recognizer's.
     */
    val bufferingSeconds: Long = 0,
    /** The tap's current capture layout label, e.g. `48000Hz/2ch/s16` ("" until a format is seen). */
    val captureFormat: String = "",
    /** Translation round trips that came back with Chinese this session. */
    val translationTrips: Long = 0,
    /**
     * Average wall-clock ms of one translation round trip (request → finished Chinese line). This is
     * the part of the Chinese lag the backend and the network own, not the recognizer.
     */
    val translationAvgMs: Long = 0,
    /**
     * Average ms from sending a request to the FIRST Chinese fragment arriving — the number that
     * decides how far the Chinese line visibly trails the English one. A backend that answers in one
     * piece (the free endpoint, or a gateway ignoring `stream`) has no first fragment, so its whole
     * round trip is counted here.
     */
    val translationTtftAvgMs: Long = 0,
    /**
     * Seconds since the Chinese line last changed. Read next to [partialIntervalSec]: the two being
     * equal means the lines track each other, while partial ≪ zh means the English is live and the
     * Chinese has stalled (a slow or failing gateway — see the translation averages above).
     */
    val chineseIntervalSec: Long = 0,
    /**
     * Measured end-to-end latency of the newest published Chinese line, ms: the time from the audio
     * behind that line arriving at the tap to the line reaching the screen. Unlike
     * [translationTtftAvgMs] (request → first fragment) this spans the WHOLE chain — capture, queue,
     * model chunk, ASR, round trip — and is what the `≤ 3 s` target is read against. The unit carries
     * the capture timestamp of its newest frame (see [TranslationUnit.capturedAtMs]).
     *
     * When the live hold-back is on (`dly` on the status line), part of this reading is deliberate:
     * the audio is waiting in the sink for its turn, and the caption is released with it. Read the
     * residual against `dly`'s measured value rather than against 3 s, which describes plain live.
     */
    val endToEndMs: Long = 0,
    /** Largest [endToEndMs] seen this session, ms. */
    val endToEndMaxMs: Long = 0,
    /**
     * The session worst of each stage of the chain [endToEndMs] covers, ms, in the order the chain
     * runs: capture → ASR hypothesis ([latencyAsrMaxMs]), hypothesis → window offered
     * ([latencyPrepMaxMs]), the entity resolver inside that stage ([latencyEntityMaxMs]), window
     * offered → request dispatched ([latencyReqMaxMs]), request dispatched → answer
     * ([latencyLlmMaxMs]), answer → Chinese on screen ([latencyUiMaxMs]). Each is the WORST reading,
     * because a healthy average hides the one stall that matters — and they are measured at their own
     * boundaries, so they need not all come from one publication and need not sum to
     * [endToEndMaxMs]. When the end-to-end spike is real, one of these reads in the seconds and names
     * the culprit; see [TranslationUnit] for where each stamp is taken.
     */
    val latencyAsrMaxMs: Long = 0,
    /** See [latencyAsrMaxMs]. */
    val latencyPrepMaxMs: Long = 0,
    /** See [latencyAsrMaxMs]; a sub-meter of [latencyPrepMaxMs]. */
    val latencyEntityMaxMs: Long = 0,
    /** See [latencyAsrMaxMs]. */
    val latencyReqMaxMs: Long = 0,
    /** See [latencyAsrMaxMs]. */
    val latencyLlmMaxMs: Long = 0,
    /** See [latencyAsrMaxMs]. */
    val latencyUiMaxMs: Long = 0,
    /**
     * Cumulative ART GC count this session, or 0 when the runtime does not report one. Read next to
     * [gcTimeMs] and [feedMaxMs] to name garbage collection as (or clear it of) the cause of a feed
     * stall: a `feed` max dwarfing `dec` max with gc time climbing is the collector, while the same
     * gap with gc flat points at CPU contention from another thread.
     */
    val gcCount: Long = 0,
    /** Cumulative ART GC wall-clock ms this session; see [gcCount]. */
    val gcTimeMs: Long = 0,
)

/**
 * The bilingual caption pair currently on screen. [source] is the exact recognised window
 * [translated] was produced from — usually a shorter version of the English line now showing, which
 * is why the pair is re-published (with a growing [source]) as the recognizer adds words; the two
 * are written together (see [AiSubtitleManager.publishLiveLine]) so the display never shows
 * {old Chinese, new English}. [translated] is null when the translation failed or is intentionally
 * skipped (target == source language) — the source line then renders alone.
 *
 * Top-level rather than nested because [CaptionTimeline] holds these as queued cues; the timeline
 * must not depend on the manager just to name the value it publishes.
 */
data class SubtitlePair(val source: String, val translated: String?)

/**
 * Orchestrates the AI-subtitle pipeline:
 *
 * 1. When [AiSubtitleConfig.enabled] turns on, register once as the [PlaybackAudioTap] consumer.
 *    Every decoded PCM chunk is resampled to 16 kHz and sliced into 160 ms frames, so the recognizer
 *    decodes continuously in real time rather than being handed one giant (stale) PCM block.
 * 2. The recognizer's ever-growing live partial runs through a [CaptionSegmenter], which keeps only
 *    the sentence the caption is showing. That window is both the English line
 *    ([CaptionSegmenter.currentSentenceText]) and the text handed to the translator
 *    ([CaptionSegmenter.liveUnitForTranslation]), so the two caption lines always describe the same
 *    words. The window is re-offered only once it grew enough to be worth a round trip, which is what
 *    keeps the Chinese moving *with* the speech instead of falling steadily further behind it:
 *    translating the whole growing hypothesis per frame saturated the worker (the line on screen then
 *    described text from one full round trip ago), and cutting speech into clauses meant nothing was
 *    sent for the first couple of seconds of an utterance.
 * 3. A dedicated worker drains those windows **one round trip at a time** — ASR never awaits the
 *    cloud. The window is not translated again from its first word: a [LiveTranslationAccumulator]
 *    diffs it against what the Chinese line already covers and asks the gateway only for the words
 *    that have no Chinese yet, handing the already-translated words over as context
 *    ([SpeechTranslator.translateStreaming]). Its answer is appended, so the line only ever grows:
 *    latency is set by the new words rather than by the length of the sentence, which is what keeps
 *    the Chinese a fraction of a round trip behind the English line instead of seconds behind it.
 *    Windows that pile up while the worker is busy are conflated ([LiveTranslationBuffer]) into the
 *    newest one, so a slow gateway costs freshness rather than leaving holes in the caption. The pair
 *    is published as a [SubtitlePair] bound to the source line it was produced from, which the
 *    player's [AiSubtitleOverlay] renders.
 *
 * **Live hold-back and sync:** with [AiSubtitleConfig.liveDelaySeconds] set, the engine sizes its
 * audio buffer so decoded audio runs ahead of the speaker (see `LiveAudioDelay`). Every tapped frame
 * is stamped with when its sound will actually be heard — the measured distance, never the requested
 * one — and a [CaptionTimeline] releases the English and Chinese lines at exactly that moment, so a
 * caption appears WITH its audio instead of a round trip after it. The same lookahead is what lets
 * the translator see the commentary that follows a window (as disambiguation evidence) and the
 * prompt carry a deeper history, which is the quality the delay budget buys.
 *
 * **Engine selection:** the recognizer comes from [WhisperFactory], which uses a true-streaming
 * Kroko Zipformer model when one is downloaded for the source language and falls back to the bundled
 * Whisper model otherwise. When the source language has a downloadable streaming model that is not
 * on disk yet, [startPipeline] kicks off its on-demand download and keeps the default Whisper
 * fallback live until the download finishes, then restarts the pipeline so the streaming engine
 * takes over. Progress and readiness are exposed through [working] lines reported by the store.
 *
 * **Per-channel memory:** what actually runs is [effective] — the global [config] overridden by the
 * currently loaded item's remembered AI-subtitle switch and source language ([PlaybackPrefsStore]).
 * A channel the user last played with subtitles on and a language picked is returned to exactly
 * that state when it loads again, instead of re-detecting the language every play. Channel values
 * take priority; only when a channel has no memory does the global Settings value apply.
 *
 * Lives for the whole process (a Koin `single`). It never touches the player UI directly and never
 * blocks playback: heavy ASR decode runs on its own worker, and translation on a separate worker.
 *
 * **Capture timing note:** the PCM tap lives in the engine's audio sink, built when a stream starts.
 * Enabling the feature during playback therefore captures from the *next* stream — enable it
 * up-front and it captures live from the very first frame.
 */
class AiSubtitleManager(
    private val context: Context,
    private val configStore: AiSubtitleConfigStore,
    private val translator: SpeechTranslator,
    private val whisperFactory: WhisperFactory,
    private val modelStore: StreamingModelStore,
    private val playbackPrefs: PlaybackPrefsStore,
    private val player: OwnTVPlayer,
    private val playbackSession: PlaybackSession,
) : android.content.ComponentCallbacks2 {

    private val tag = "AiSubtitle"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _pair = MutableStateFlow<SubtitlePair?>(null)
    /**
     * The bilingual caption pair currently on screen: the exact recognised source line and its
     * translation, swapped together atomically. Keeping them in one state guarantees the display can
     * never show {old Chinese, new English} — the Chinese line is always bound to the English line
     * that produced it (see the translation worker in [startTranslationWorker]). Null until the first
     * translation lands; the live [asrText] partial stands in until then.
     */
    val pair: StateFlow<SubtitlePair?> = _pair.asStateFlow()

    private val _asrText = MutableStateFlow<String?>(null)
    /** The raw recognised source-language text of the utterance currently being recognised. */
    val asrText: StateFlow<String?> = _asrText.asStateFlow()

    /**
     * Cues waiting for the audio they describe. With live audio held back (see `LiveAudioDelay`)
     * recognition and translation run seconds *ahead* of the speaker, so a produced line is parked
     * here until its moment instead of being flashed early and then contradicted by the sound; see
     * [CaptionTimeline]. Its clock is [presentationNowMs], which stops while playback is paused.
     */
    private val captionTimeline = CaptionTimeline()
    private var timelineJob: Job? = null
    /**
     * The words of the recognizer hypothesis the English line was last built from, for the
     * one-hypothesis confirmation in [stableSourceText]. Reset with the caption state (new session,
     * new sentence, discontinuity), because a confirmation only means anything within one stream.
     */
    private var prevSourceWords: List<String> = emptyList()
    /**
     * Wall-clock milliseconds spent paused since the pipeline started, subtracted from the elapsed
     * clock to get [presentationNowMs]. Accumulated by the presentation ticker, so a cue scheduled
     * before a pause waits through it rather than expiring behind a frozen picture.
     */
    @Volatile private var pausedAccumMs = 0L

    /**
     * Worst cue lateness of the last ~4 s, ms (see [CaptionTimeline.Update.lateMs]).
     *
     * Every other number on the diagnostics line describes a stage; this one is the bottom line of
     * sync: how long after its own audio a caption's cue actually went up. 0 means the line was
     * waiting for its moment (the design), >0 means the line arrived after its audio had already
     * played — which no anchor can repair, only more budget or a faster recognizer.
     */
    @Volatile private var cueLateMs = 0L

    /** Requested hold-back this session is running with, ms (0 = plain live). */
    @Volatile private var liveDelayMs = 0

    /**
     * The newest corrected hypothesis the recognizer has produced, for the translation worker's
     * look-ahead context. With a hold-back the worker runs seconds behind the recognizer, so by the
     * time it translates a window the recognizer usually knows the words that follow it — evidence
     * that settles an ambiguous surname (`City` / `United`) or a pronoun before it is rendered.
     * Written on the ASR worker, read on the translation worker, hence volatile.
     */
    @Volatile private var latestHeardText = ""

    private val _status = MutableStateFlow("idle")
    /**
     * The semantic status line ("idle" / "kroko en:ready" / "kroko en:working" / "tiny it:working" /
     * "error: …"). Live debug counters ([composeStatus]) are appended to it on a throttled cadence,
     * so [status] reads e.g. "kroko en:working · chunk 123 · blank 4 · words 56".
     */
    @Volatile private var statusBase = "idle"
    /** One-line status (engine:language:mode + chunk/blank/words debug). */
    val status: StateFlow<String> = _status.asStateFlow()

    /** Global (Settings) config — switch, source/target language, gateway. Read by the Settings screen. */
    val config: StateFlow<AiSubtitleConfig> =
        configStore.config.stateIn(scope, SharingStarted.Eagerly, AiSubtitleConfig())

    // --- Per-channel override looked up from playback_prefs, keyed by the loaded item ---
    private val _activeKey = MutableStateFlow<String?>(null)
    private val _channelEnabled = MutableStateFlow<Boolean?>(null)
    private val _channelSourceLanguage = MutableStateFlow<String?>(null)

    /**
     * Bumped by every per-channel DECISION — a tune, the switch, a language pick. The row read that
     * follows a tune is asynchronous, and without this a read that lands after the user has already
     * pressed the switch put the channel's *old* value back, undoing the press: the reported "first
     * press does nothing, second press works" (the first press was reverted a moment after it).
     */
    private val channelEpoch = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * The config the pipeline actually runs: [config] with the current item's remembered switch and
     * recognition language applied over it. Channel values win; a null column means "follow global".
     *
     * The AI-subtitle master switch is NOT part of the global Settings anymore — it lives on the
     * in-player HUD, remembered per channel. So subtitles default OFF: without a remembered per-channel
     * on-switch the pipeline stays down even if the global config's legacy `enabled` field is true.
     */
    val effective: StateFlow<AiSubtitleConfig> =
        combine(config, _activeKey, _channelEnabled, _channelSourceLanguage) { cfg, _, chEnabled, chLang ->
            cfg.copy(
                enabled = chEnabled ?: false,
                sourceLanguage = chLang?.takeIf { it.isNotBlank() } ?: cfg.sourceLanguage,
            )
        }.stateIn(scope, SharingStarted.Eagerly, AiSubtitleConfig())

    private var worker: Job? = null
    /**
     * The single thread the [worker] coroutine runs on, so [Process.setThreadPriority] ranks only the
     * ASR loop. Re-created per session and closed with it (see [startPipelineLocked] /
     * [stopPipelineLocked]); a plain [Dispatchers.Default] launch would rank whichever pool thread
     * happened to pick the coroutine up and leave it ranked for every unrelated task that followed,
     * which is a CPU-wide side effect this class has no business having.
     */
    private var workerDispatcher: ExecutorCoroutineDispatcher? = null
    private var downloadJob: Job? = null
    // 1 Hz diagnostics ticker while a pipeline runs: keeps the on-screen counters fresh during
    // silent/starved stretches (the feed-driven push only fires when audio flows) and accumulates
    // the PLAYING-time denominator for the aud/playing ratio. Re-created per session.
    private var statsJob: Job? = null
    // Read from the status-line builder (worker/ticker threads) as well as the pipeline mutex, so the
    // diagnostics line only claims a delay while a session is actually running.
    @Volatile private var tapArmed = false
    // Ms the player spent actually playing this session (isPlaying == true), accumulated by the
    // stats ticker. audio/playing ≈ 1 = healthy capture; ≪ 1 during playback = starved tap.
    @Volatile private var playingMsAccum = 0L
    // Ms the player spent BUFFERING this session (player.buffering == true), accumulated by the same
    // ticker. This is the only meter here that isolates network stalls from everything else: while
    // buffering, isPlaying is false AND no audio arrives, so both aud/wall and aud/playing freeze
    // together and the stall hides inside "not playing" — indistinguishable from time spent in a
    // menu. Splitting that idle time by the player's own buffering flag makes "网络波动造成的偶发
    // 卡顿" countable, and keeps a live-edge reposition (which flushes the queue and restarts the
    // recognizer, see [RealtimePcmQueue.jump]) out of the local-performance blame.
    @Volatile private var bufferingMsAccum = 0L
    // How the buffering above was distributed, which the total alone cannot say: one 6 s freeze and
    // twelve half-second ones read identically as `buf 6s`, yet they are different defects (a dead
    // stream versus a marginal one). `buf 6s/12ep/900ms` answers the "网络波动会造成直播偶发卡顿"
    // question directly — the episode COUNT is what a network change has to move. Sampled by the same
    // 1 Hz ticker, so an episode shorter than a tick is below its resolution.
    @Volatile private var bufferingEpisodes = 0L
    @Volatile private var bufferingMaxMs = 0L
    @Volatile private var lastStatsTickMs = 0L
    // Times the scene gate actually re-biased the recognizer. Each one re-creates the decode stream
    // (see [SportSceneDetector]), dropping the sentence in flight and reloading the context graph, so
    // a 3-minute `scene none` vs `football` comparison is only valid if this stayed ~0 inside each
    // run: a flip mid-run spends decode time on a rebuild and silently truncates a sentence, either
    // of which would be misread as the hotword list's cost.
    @Volatile private var sceneSwitches = 0L
    // The translator outlives a session, so its counters are cumulative across every pipeline started
    // in this process. Snapshotted at session start and reported as a delta, like every other counter
    // on the line, which is per-session — otherwise a restart would show a Chinese-quality reading
    // that included the previous run's fallbacks.
    @Volatile private var fallbackTripsBaseline = 0L
    @Volatile private var fellBackAfterPrimaryBaseline = 0L
    /** Source language the current recognizer was built for; null while stopped. */
    private var startedLanguage: String? = null
    /**
     * True when the current session reuses the process-resident English [preloadedEnglish] instance,
     * so teardown reset()s it instead of releasing it (it must stay resident). False for session-owned
     * recognizers (Whisper fallback, downloaded fr/de/es) which are created and closed per session.
     */
    private var borrowedResident = false

    // Serialises startPipeline/stopPipeline so a download-completed restart can never race the
    // collectLatest drive (two concurrent startPipeline calls both pass the tapArmed guard, leak a
    // recognizer and fight over the tap). Both pipeline functions are suspend and take this lock.
    private val pipelineMutex = Mutex()

    // --- Live translation: ASR and the cloud are fully decoupled. The ASR worker only offers the
    // caption window it is showing to the session's buffer (non-blocking); a dedicated worker drains
    // it, runs one round trip at a time and applies the result to the Chinese line. Superseded windows
    // are conflated away, so there is no stale backlog to grind through. Re-created per pipeline
    // session; the worker captures the session's buffer and drops its remaining work once a restart
    // replaces it (the `liveBuffer !== buffer` guards below).
    @Volatile private var liveBuffer: LiveTranslationBuffer = LiveTranslationBuffer()
    private var translateWorker: Job? = null
    /**
     * The single round trip currently being run by the translation worker, if any — cancelled by
     * [markNewSentence] the moment the sentence it belongs to ends, so a dead round trip cannot delay
     * the new sentence's line by its whole duration.
     */
    @Volatile private var inFlightTranslation: Job? = null
    /**
     * Whether [inFlightTranslation] is carrying a `final` unit — a sentence's closing flush rather
     * than a live window. Read by [markNewSentence] only to say WHICH kind of request its unconditional
     * cancel just killed: killing a live window is the intended, free behaviour, while killing a
     * closing flush drops the sentence's last words before [publishLiveLine]'s closing-flush exemption
     * can let them through. Diagnostic only — it changes no decision.
     */
    @Volatile private var inFlightUnitFinal = false
    /**
     * Set while a sentence's closing flush (see [CaptionSegmenter.takeClosedFinal]) is waiting to be run
     * by the translation worker, cleared by the worker once it has run a `final` unit.
     *
     * The closing flush and the next sentence's live window would both go through the buffer's
     * **conflated** channel, where the newer offer overwrites the older — the live window would drop the
     * flush before the worker could take it, losing exactly the last words the flush exists to deliver.
     * So the live window is not offered while this is set: the sentence switch waits for the flush to
     * finish, which is also what keeps the English line and the Chinese line on the same sentence.
     */
    @Volatile private var closedFlushPending = false
    /**
     * Epoch of the sentence currently being spoken, set from [CaptionSegmenter.sentenceEpoch] the
     * moment a partial starts a new sentence (or the audio timeline jumps; see [markNewSentence]). A
     * translation result carrying an older epoch describes speech that is no longer on screen, so
     * [publishLiveLine] drops it instead of reviving the line the viewer has moved past.
     */
    @Volatile private var activeEpoch = 0

    /**
     * The sport currently on the decoder as a prompt label (see [SubtitleGlossary.sceneLabel]), or ""
     * when it recognised none. Written by the ASR worker when the decoder's bias is switched and read
     * by the translation worker, so it is volatile; session-scoped, so a fresh channel starts with no
     * scene. Handed to the translator with [recentLines] on a sentence's first request only.
     */
    @Volatile private var appliedSceneLabel = ""

    /**
     * The sport the decoder's bias is CURRENTLY carrying, for the on-screen diagnostics line, or null
     * while it carries none. Same event as [appliedSceneLabel] and written in the same place, but kept
     * separately because the two answer different questions: the label is prose for the translator,
     * this is the short readout that makes "is contextual biasing even on for this channel?" answerable
     * from a screenshot instead of logcat. It matters because the biasing is the only thing that makes
     * a club or player name the acoustic model cannot spell (`Tottenham` → `Totten and Am`) decode
     * correctly — and the scene gate reads the SAME recognised words, so if the gate never opens the
     * names never get their bias and look "never recognised", which is exactly what a silent gate
     * would produce. Session-scoped; reset with the label on a fresh channel.
     */
    @Volatile private var appliedScene: SubtitleGlossary.Sport? = null

    /**
     * The clubs the decoder is CURRENTLY scoped to, or empty when none is scoped. Written by the ASR
     * worker when the bias is switched and read by the translation worker, so it is volatile. It is
     * the set the prompt's squad table is built from (see
     * [SubtitleGlossary.playerPromptSection]) — a name the commentary will say, pinned for the whole
     * fixture rather than only the sentence that happens to mention the club.
     */
    @Volatile private var appliedTeamsScope: Set<SubtitleGlossary.Team> = emptySet()

    /**
     * Per-session diagnostic sink (see [AiSubtitleDiagnostics]): one row per closed sentence plus the
     * capitalised-unknown counts, written under the app's files dir so a real session can be pulled
     * off the device. Created at start, flushed and dropped at stop, so a session never mixes with
     * the previous one's rows. Volatile because the ASR worker counts words while the stop path
     * flushes it.
     */
    @Volatile private var diagnostics: AiSubtitleDiagnostics? = null

    /**
     * The raw recognition and corrected text of a sentence that has closed but whose Chinese has not
     * settled yet, keyed by sentence epoch. Written on the ASR worker the moment a sentence closes and
     * drained on the translation worker when that sentence's settled line is published (see
     * [publishLiveLine]); the map bridges those two threads, hence the concurrent type. Cleared on an
     * audio jump, where the sentence being assembled describes speech that is no longer playing.
     */
    private val pendingSentenceDiag = ConcurrentHashMap<Int, PendingSentence>()

    /** One closed sentence's ASR-side facts, waiting for its Chinese to settle. */
    private class PendingSentence(
        val raw: String,
        val corrected: String,
        val scene: String,
        val teams: String,
    )

    /**
     * How many hotwords are actually riding the decoder right now, i.e. the length of the list
     * [SubtitleGlossary.recognitionHotwords] last handed to [setHotwords] — the club-and-terminology
     * list a football scene opens with, plus the squads of the clubs the window has named. The scene
     * gate alone cannot say whether scoping fired, and this count is the on-screen proof that it
     * did: the unscoped list is the small vocabulary the scene is RECOGNISED from, and a scoped one
     * is longer by the named clubs' squads, which is what makes the decode-time effect of scoping
     * readable from a screenshot.
     */
    @Volatile private var appliedHotwordCount = 0

    /**
     * How many clubs the decoder's list is currently scoped to — the `t0`/`t2` token on the
     * diagnostics line. [appliedHotwordCount] shows how big the list is; this shows WHY, and tells
     * "no club was named" (`t0`) apart from "clubs were named and their squads added" (`t1`/`t2`),
     * which are the readings a screenshot of a match has to distinguish. Session-scoped; reset with
     * the rest on a fresh channel or a reposition.
     */
    @Volatile private var appliedTeamCount = 0

    /**
     * The club scope's raw state — the `SportSceneDetector.scopeSummary` of the last scored batch.
     * [appliedTeamCount] alone cannot answer the question a scoping investigation is stuck on:
     * `t0` means "nothing was scoped", but not whether no club was named at all, or clubs were named
     * and their players' surnames never matched, or the mentions fell out of the horizon — three
     * different faults with three different fixes. This carries the window's size, each club's
     * mention count over the horizon with the scoped ones marked, the spoken surnames those scoped
     * squads are matching, and the fixture a change is waiting to be allowed, so a
     * screenshot says which. Written on the ASR worker and read by the status line, so volatile like
     * the counters beside it. Session-scoped; reset with the rest on a fresh channel or a reposition.
     */
    @Volatile private var scopingDiagnostics = "off"

    /**
     * The most recent finished sentences, as `source → translation`, for the translator's
     * cross-sentence context — what carries a name or a term from one sentence to the next, where the
     * within-sentence context is cleared at every boundary ([SpeechTranslator.translateStreaming]
     * receives this as `recent`). Session-scoped; capped at [RECENT_CONTEXT_SENTENCES].
     *
     * Owned and read on the translation worker alone — [publishLiveLine] writes it, [translateUnit]
     * reads it — so no synchronisation is needed. The same sentence's earlier streamed version
     * replaces its entry rather than appending, so the list holds whole sentences and not fragments.
     */
    private val recentLines = ArrayDeque<RecentSentence>()

    /** One finished sentence held for [recentLines]. */
    private data class RecentSentence(val epoch: Int, val source: String, val translation: String)

    // --- diagnostics (throttled) to locate "no subtitles on English channel" without a debugger.
    // Feed pipeline is time-sliced; counting here catches both halves of the split: fedChunks proves
    // decoded audio reaches the worker, blankChunks proves how much of it the recognizer ignores.
    // All three are written on one thread (fedChunks on the audio-tap thread in sendPcm; blankChunks /
    // partialChunks in the ASR worker) and read on the other from pushStats(), so they are volatile to
    // guarantee a fresh value crosses threads for the on-screen diagnostics.
    @Volatile private var fedChunks = 0L
    @Volatile private var blankChunks = 0L
    @Volatile private var partialChunks = 0L
    // Cumulative recognised words for THIS pipeline session — grows with speech, so the status line's
    // `words` counter makes "is the caption updating?" answerable on screen (a live but silent feed
    // keeps fedChunks rising while words stays put). Reset on every pipeline (re)start.
    @Volatile private var recognizedWords = 0L
    // Stable caption segments committed by the segmenter since the session started — rising means the
    // sentence splitter is actually cutting the streaming hypothesis into translatable chunks.
    @Volatile private var committedSegments = 0L
    // [committedSegments] split by which half of the sentence lifecycle produced the window: a
    // sentence's CLOSING flush (its withheld last words) or a LIVE window on the sentence being
    // spoken. The total alone cannot answer "is the Chinese stalling on sentence tails or on live
    // speech?" — and the two have different causes: tails are the units [markNewSentence] cancels,
    // live windows are the ones a generation jump restarts from zero. Sums to [committedSegments].
    @Volatile private var committedClosings = 0L
    @Volatile private var committedLive = 0L
    // --- translation-side telemetry (see [PipelineStats]): the meters above answer "is the English
    // line fresh?", these answer the other half of the latency complaint — how far behind it the
    // Chinese line is, and whether that lag is one round trip or a stall. Written on the translation
    // worker and read from pushStats, so volatile; session-scoped like the counters above.
    // Finished round trips plus the summed wall-clock and first-fragment costs the averages are of.
    @Volatile private var translationTrips = 0L
    @Volatile private var translationTotalMs = 0L
    @Volatile private var translationTtftTotalMs = 0L
    @Volatile private var translationTtftTrips = 0L

    // --- Why the Chinese line can sit still, by cause. [translationTrips] counts only round trips
    // that SUCCEEDED with a non-blank line, so it is a survivor count: it cannot see the requests
    // that never came back, which is exactly the shape of "中文 18 秒不更新" — and it is why `n75` and
    // a healthy 799 ms average are fully compatible with a frozen line. These four account for every
    // unit the worker was handed, so the totals close:
    //
    //   requests = trips + failures + cancels
    //   units    = requests + skips
    //
    // [translationSkipCount] is the one worth watching most: the accumulator refused to build a
    // request because the new sentence had not yet yielded enough stable words. Skipped repeatedly,
    // it means the recognizer kept restarting the sentence before it could grow — a generation-jump
    // storm read from the Chinese side, with no network involved. See [LiveTranslationAccumulator].
    @Volatile private var translationRequests = 0L
    @Volatile private var translationFailures = 0L
    @Volatile private var translationCancels = 0L
    @Volatile private var translationSkipCount = 0L
    // [translationSkipCount] split by the accumulator's own verdict ([LiveTranslationAccumulator.lastSkip]),
    // so `skip 125` can be read as "most were duplicate windows, a few were genuinely too-short
    // sentences" instead of one opaque number. Mutually exclusive by first match and summing to the
    // total; the split is what tells a dropped team/player name (LOW_CONFIDENCE/UNSTABLE, worth
    // chasing) from the harmless re-offer of an already-translated window (DUPLICATE/STALE).
    @Volatile private var skipShort = 0L
    @Volatile private var skipDuplicate = 0L
    @Volatile private var skipUnstable = 0L
    @Volatile private var skipLowConfidence = 0L
    @Volatile private var skipStale = 0L
    // [translationFailures] split by cause, because the two mean opposite things: a TIMEOUT is the
    // network or the gateway being slow (the box and the round trip are fine, the answer is late),
    // while an HTTP status is the gateway refusing outright (bad key, no quota, model gone). They
    // point at different fixes, and a single `fail` count cannot tell them apart — the exact gap that
    // made "中文 18 秒不更新" unattributable. [translationLastHttpCode] keeps the most recent status so
    // a repeated 429 is readable without logcat. Anything else that throws counts only in the total.
    @Volatile private var translationTimeouts = 0L
    @Volatile private var translationHttpErrors = 0L
    @Volatile private var translationLastHttpCode = 0

    // Sentence turnovers that found a CLOSING flush still in flight. Those units get a deliberate
    // exemption from the staleness rule in [publishLiveLine] (`final && epoch == activeEpoch − 1`)
    // because they alone carry the sentence's last words, withheld until the round trip finishes.
    //
    // This used to be the count of closing flushes CANCELED here, and the cancel ran before that
    // exemption could be consulted, making it unreachable in exactly the case it was written for —
    // the sentence tail was dropped outright. They are now WAITED for instead (see [markNewSentence]),
    // so the counter is a price gauge rather than a loss gauge: each one costs up to one round trip
    // (~0.7–0.8 s measured) of delay before the next sentence's window is offered. Reported as `cfx`;
    // read it against `seg`'s closing count, not against `fail`. A live window is still cancelled —
    // that is correct and free, because whatever it had streamed stays on screen.
    @Volatile private var closingFlushesAtTurnover = 0L
    // Wall-clock of the last time the Chinese line changed. Read against [lastPartialAtMs] it says
    // whether the Chinese trails the English by roughly one round trip or has stopped updating at all.
    @Volatile private var lastChineseAtMs = 0L
    // Feed health for the diagnostics chip: how many recognizer feeds ran, how recent a non-empty
    // partial last arrived, and the recognizer's own decode meters (see [StreamingWhisper.meters]).
    // Written on the ASR worker (consumePcm) and read from pushStats on the same thread each run.
    private var recognizerFeedCalls = 0L
    @Volatile private var lastPartialAtMs = 0L
    @Volatile private var lastFeedMs = 0L
    // Worst single `reco.feed()` wall-clock of the session (see [PipelineStats.feedMaxMs]). Written
    // on the ASR worker with [lastFeedMs]; the MAX is what survives, because most feeds only buffer
    // (isReady false) and finish in ~0 ms, hiding the one stall that costs the caption its freshness.
    @Volatile private var feedMaxMs = 0L
    // Feeds slower than [FEED_STALL_MS]. Every one of them is long enough for the producer to fill
    // the entire PCM queue, so this is the count of events that can (and in this pipeline do) cost a
    // whole 1.28 s backlog. Read next to `gc` to attribute the stalls -- see the increment site.
    @Volatile private var feedStallCount = 0L
    // Wall-clock of the most recent feed that ACTUALLY ran a model decode. Most feeds just buffer
    // (isReady false) and finish in ~0 ms, so showing the last feed's cost hides the expensive
    // frames that prove the decoder is CPU-bound. -1 = no decoding feed observed yet.
    @Volatile private var lastDecodeMs = -1L
    @Volatile private var isReadyHits = 0L
    // Total 16 kHz samples handed to frames since session start. audioSeconds = this / 16000;
    // compared against the wall clock it proves whether the tap delivers real-time audio (ratio ≈ 1),
    // time-stretched audio (ratio ≫ 1: wrong sample rate captured) or a starved tap (ratio ≪ 1).
    @Volatile private var fedSamples16k = 0L
    // RMS loudness of the most recent consumed frame, refreshed every frame (the log stays throttled)
    // so the on-screen diagnostics can show whether the recognizer is receiving silence or speech.
    @Volatile private var lastRms = 0
    // Gain the pipeline is currently applying to the recognizer feed (see [AsrGain]). 1.0 = the
    // capture already arrives at speech level and is passed through untouched; a higher value is the
    // correction that made a quiet capture recognisable, so the meter proves the fix on screen.
    @Volatile private var lastGain = 1.0
    // Audio-thread hand-off cost (see [sendPcm]). sendPcm runs inline on the ExoPlayer audio sink
    // thread and does the resampler's 127-tap anti-alias FIR there, so its wall-clock is the number
    // that says whether that filter is eating the renderer's budget: a cost approaching the chunk's
    // own audio span ([tapDutyPercent] → 100) holds the sink thread for as long as the audio it just
    // produced, which is the condition under which the sink underruns. The sink exposes no genuine
    // underrun callback, so this ratio is the honest proxy — a high duty is that condition, not a
    // counted underrun. Written on the audio thread, read from the UI/stats thread — hence @Volatile.
    @Volatile private var tapLastUs = 0L
    @Volatile private var tapMaxUs = 0L
    @Volatile private var tapDutyPercent = 0
    // Session start wall-clock for the audioSeconds/wallSeconds ratio in the diagnostics line.
    @Volatile private var sessionStartMs = 0L
    // Wall-clock the ASR worker spent INSIDE the consume loop this session (sum of the per-frame
    // bodies), so its share of the session wall-clock is the thread's duty cycle — see
    // [PipelineStats.consumerBusyPercent]. The worker is otherwise blocked on the queue, so a busy
    // reading near 100% means frames arrive back-to-back and it never idles (decode is at real time
    // or slower), while a low reading means it is waiting on audio. Written on the ASR worker, read
    // from the stats thread.
    @Volatile private var consumerBusyMs = 0L
    // Measured end-to-end caption latency (see [PipelineStats.endToEndMs]): the newest publication's
    // age relative to the audio it describes, and the worst such value of the session.
    @Volatile private var endToEndMs = 0L
    @Volatile private var endToEndMaxMs = 0L
    // --- [endToEndMaxMs], decomposed by stage so a 3 s spike can be BLAMED rather than merely seen
    // (see [TranslationUnit] for where each stamp is taken). Each is the session WORST of its stage,
    // because a healthy average hides the one stall that matters:
    //   asr  = capture → hypothesis text ready           (input queue + decode)
    //   prep = text ready → unit offered                 (entity resolver + segmentation + offer)
    //   ent  = SubtitleGlossary.correctNames cost        (a sub-meter of prep)
    //   req  = unit offered → request dispatched         (buffer wait + accumulator decision)
    //   llm  = request dispatched → answer               (the DeepSeek round trip, failures included)
    //   ui   = answer → Chinese on screen                (ACCEPT + publish)
    // The stages are measured at their own boundaries, so their maxes need not come from one
    // publication and need not sum to [endToEndMaxMs]; but when the spike is real, one of these alone
    // reads in the seconds and names the culprit.
    @Volatile private var latencyAsrMaxMs = 0L
    @Volatile private var latencyPrepMaxMs = 0L
    @Volatile private var latencyEntityMaxMs = 0L
    @Volatile private var latencyReqMaxMs = 0L
    @Volatile private var latencyLlmMaxMs = 0L
    @Volatile private var latencyUiMaxMs = 0L
    private var showedFirstPartial = false
    // Exposed to the overlay so "why is the caption stuck?" is answerable on screen, no adb needed:
    // fedChunks advancing means audio still reaches the worker; blankChunks growing while partialChunks
    // sticks means the recognizer is only returning empty/old text. Only pushed at the same (throttled)
    // cadence as the log lines to keep recomposition cheap.
    private val _stats = MutableStateFlow(PipelineStats())
    val stats: StateFlow<PipelineStats> = _stats.asStateFlow()

    private fun pushStats() {
        // partialIntervalSec = wall-clock since the last non-empty partial. Never negative even if the
        // timer wraps: clamp at 0. Defaults to 0 (fresh) when no partial has arrived yet.
        val intervalSec = if (lastPartialAtMs > 0L)
            ((SystemClock.elapsedRealtime() - lastPartialAtMs).coerceAtLeast(0L) / 1000L)
        else 0L
        // isReady hit rate: feeds where the recognizer's decode loop advanced / all feeds. Before the
        // first feed (or for engines reporting no meters) show 100 so the chip reads "healthy" until it
        // has data, rather than the misleading 0 that a "no meters" default would produce.
        val hit = if (recognizerFeedCalls > 0 && recognizerFeedCalls >= isReadyHits)
            (isReadyHits * 100L / recognizerFeedCalls).toInt()
        else 100
        val audioSec = fedSamples16k / PcmResampler.TARGET_RATE
        val wallSec = if (sessionStartMs > 0L)
            ((SystemClock.elapsedRealtime() - sessionStartMs).coerceAtLeast(0L) / 1000L)
        else 0L
        // Per-model-chunk decode cost straight from the engine: avg across the whole session (not
        // the last batch drain, which is feedMs) decides whether the SoC decodes in real time.
        val m = recognizer?.meters
        val decAvg = if (m != null && m.decodeChunksTotal > 0L)
            m.decodeTotalMs / m.decodeChunksTotal else 0L
        // Translation-side freshness and cost, the mirror image of `intervalSec` and the decode
        // meters above: how long the Chinese takes to start and to complete, and how stale it is.
        // 0 (fresh/unknown) before the first answer, same convention as `intervalSec`.
        val zhIntervalSec = if (lastChineseAtMs > 0L)
            ((SystemClock.elapsedRealtime() - lastChineseAtMs).coerceAtLeast(0L) / 1000L)
        else 0L
        val xlateAvg = if (translationTrips > 0L) translationTotalMs / translationTrips else 0L
        val xlateTtft = if (translationTtftTrips > 0L)
            translationTtftTotalMs / translationTtftTrips else 0L
        // ASR worker duty cycle over the session: its accumulated in-loop time against the session
        // wall-clock (the same denominator `wall` is shown with). 0 until the worker has run.
        val busyPercent = if (sessionStartMs > 0L && wallSec > 0L) {
            ((SystemClock.elapsedRealtime() - sessionStartMs).coerceAtLeast(1L))
                .let { elapsed -> (consumerBusyMs * 100L / elapsed).toInt().coerceIn(0, 100) }
        } else 0
        val (gcCount, gcTimeMs) = gcStats()
        _stats.value = PipelineStats(
            fedChunks = fedChunks,
            blankChunks = blankChunks,
            partialChunks = partialChunks,
            recognizedWords = recognizedWords,
            droppedFrames = frameQueue.droppedFrames.get(),
            feedMs = if (lastDecodeMs >= 0L) lastDecodeMs else lastFeedMs,
            feedMaxMs = feedMaxMs,
            partialIntervalSec = intervalSec,
            isReadyHitPercent = hit,
            committedSegments = committedSegments,
            decodeAvgMs = decAvg,
            decodeMaxMs = m?.singleDecodeMaxMs ?: 0L,
            decodeP50Ms = m?.decodeP50Ms ?: 0L,
            decodeP90Ms = m?.decodeP90Ms ?: 0L,
            decodeP99Ms = m?.decodeP99Ms ?: 0L,
            decodeSlowCount = m?.decodeSlowCount ?: 0L,
            decodeSlowGcCount = m?.decodeSlowGcCount ?: 0L,
            decodeSlowCpuBoundCount = m?.decodeSlowCpuBoundCount ?: 0L,
            consumerBusyPercent = busyPercent,
            chunkAudioMs = m?.chunkAudioMs ?: 0L,
            realtimeFactor = realtimeFactorOf(decAvg, m?.chunkAudioMs ?: 0L),
            gainRatio = lastGain,
            drainMaxChunks = m?.drainMaxChunks ?: 0L,
            queueHighWater = frameQueue.highWaterFrames,
            generationJumps = frameQueue.generationJumps,
            lastRms = lastRms,
            audioSeconds = audioSec,
            playingSeconds = playingMsAccum / 1000L,
            wallSeconds = wallSec,
            bufferingSeconds = bufferingMsAccum / 1000L,
            captureFormat = PlaybackAudioTap.captureInfo?.label().orEmpty(),
            translationTrips = translationTrips,
            translationAvgMs = xlateAvg,
            translationTtftAvgMs = xlateTtft,
            chineseIntervalSec = zhIntervalSec,
            endToEndMs = endToEndMs,
            endToEndMaxMs = endToEndMaxMs,
            latencyAsrMaxMs = latencyAsrMaxMs,
            latencyPrepMaxMs = latencyPrepMaxMs,
            latencyEntityMaxMs = latencyEntityMaxMs,
            latencyReqMaxMs = latencyReqMaxMs,
            latencyLlmMaxMs = latencyLlmMaxMs,
            latencyUiMaxMs = latencyUiMaxMs,
            gcCount = gcCount,
            gcTimeMs = gcTimeMs,
        )
        // Refresh [status] at the same throttled cadence so the chunk/blank/words debug stays live
        // without recomposing on every audio chunk.
        refreshStatus()
    }

    /**
     * The clock every caption cue is timed against: the elapsed clock minus the time playback was
     * PAUSED. Wall-clock time alone would let cues expire behind a frozen picture; this clock freezes
     * with the audio, so the line that belongs to the next second of sound is still waiting when
     * playback resumes.
     *
     * A buffering stall is deliberately NOT subtracted. During one the sink keeps playing what it
     * already holds — the hold-back queue drains into the speaker — so the audio timeline keeps moving
     * and the measured lead shrinks to match (see [LiveAudioDelay.measuredMs]). Freezing the clock here
     * made every caption after a stall early by the stall's length, which is what "sometimes the
     * subtitle runs ahead of the voice" was.
     */
    private fun presentationNowMs(): Long = SystemClock.elapsedRealtime() - pausedAccumMs

    /**
     * The English line with its newest word withheld until the recognizer has repeated it.
     *
     * A streaming recogniser revises its last token far more often than the ones before it, and a
     * partial can end in the middle of a word ("govern", completed as "government" one frame later),
     * so publishing the raw hypothesis made a word appear as a few letters and then grow — the
     * "words start out incomplete" report. The hold-back that feeds recognition pays for the
     * patience: one hypothesis later the word has been repeated verbatim, i.e. it is whole, and the
     * cue is still published against the audio that produced it. That also removes the apparent lag
     * of the line's leading edge, which was that half-written word: the newest word on screen has
     * already been heard when it appears.
     *
     * Null when nothing is confirmed yet (the previous line stays instead of flashing a fragment).
     */
    private fun stableSourceText(text: String): SourceLine? {
        val words = CaptionSegmenter.tokenize(text)
        if (words.isEmpty()) return null
        val prev = prevSourceWords
        prevSourceWords = words
        if (prev.isEmpty()) return null
        // Repeated unchanged by the next hypothesis → the recognizer is done revising it.
        if (prev.last() == words.last()) return SourceLine(text.trim(), droppedFromEnd = 0)
        return if (words.size > 1) {
            SourceLine(words.dropLast(1).joinToString(" "), droppedFromEnd = 1)
        } else {
            null
        }
    }

    /**
     * One English line and how many words were withheld from the end of the hypothesis to build it
     * (see [stableSourceText]). The count is what lets the caller time the line against the exact
     * audio end of its own last word (see [StreamingWhisper.CompiledResult.wordEndAgesMs]).
     */
    private data class SourceLine(val text: String, val droppedFromEnd: Int)

    /**
     * A recognizer word-age as a sane duration, ms. A negative or absurd reading (a timestamp from a
     * different stream, a model that reports nonsense) is clamped rather than allowed to park a cue
     * far in the future or in the past; the caption is then merely frame-accurate, never lost.
     */
    private fun Float.ageMs(): Long = toLong().coerceIn(0L, MAX_WORD_AGE_MS)

    /**
     * Advance the presentation clock and publish whatever cue is now due. Runs on a short ticker
     * rather than per offer: a partial can arrive dozens of times a second, while a caption only
     * needs to change often enough to look live, and the overlay should not recompose more than that.
     * Paused time is accumulated here from the same read, so the clock and the cue release cannot
     * disagree about whether playback is advancing.
     */
    private fun startPresentationClock() {
        timelineJob?.cancel()
        timelineJob = scope.launch {
            var tick = SystemClock.elapsedRealtime()
            while (true) {
                delay(PRESENTATION_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val delta = now - tick
                tick = now
                val engine: PlaybackEngine? = playbackSession.activeEngine.value
                val playing = engine?.isPlaying?.value ?: player.isPlaying.value
                val buffering = engine?.buffering?.value ?: player.buffering.value
                // Only a genuine PAUSE stops the audio the captions follow. Buffering does not: the
                // sink keeps playing what the hold-back already holds (and [LiveAudioDelay.measuredMs]
                // shrinks as the queue drains), so the cue clock has to keep moving with it — freezing
                // it here expired nothing but shifted every later cue early by the stall's length.
                // ANY paused stretch freezes the clock, however long the ticker was descheduled: a cap
                // on the accounted delta (the old 3 s one) let a long stall or process freeze advance
                // the clock anyway, so cues rushed out unsynced the moment playback resumed.
                if (!playing && !buffering) {
                    pausedAccumMs += delta
                    // Nothing is released while the picture is held: during a channel's pre-roll the
                    // recognizer is already working through the priming burst, and those captions must
                    // wait for playback to start (their cue times are offsets into that burst).
                    continue
                }
                // Decays to zero over ~4 s, so the line shows the worst lateness of the last few
                // seconds rather than a session-wide maximum that never clears.
                if (cueLateMs > 0L) cueLateMs = (cueLateMs - delta).coerceAtLeast(0L)
                // The user's sync trim shifts only the RELEASE, never the stamps: a positive value
                // makes every caption wait that much longer (subtitle later), negative releases it
                // earlier. It is the one offset nothing inside Android can measure — the delay of the
                // output chain after the platform mixer (HDMI/ARC, soundbar, TV audio DSP).
                val update = captionTimeline.advance(
                    presentationNowMs() - config.value.syncOffsetMs,
                ) ?: continue
                if (update.lateMs > cueLateMs) cueLateMs = update.lateMs
                _asrText.value = update.source
                _pair.value = update.pair
            }
        }
    }

    /**
     * Apply the configured live hold-back to the engine that is making sound. 0 restores stock
     * playback. Neither direction reopens the running channel — the engine stores the value and the
     * next tune's player is built with it (see `PlaybackEngine.applyAudioDelay`): a reopen is what
     * loses the only HEVC decoder on the TVs this is built for.
     */
    private fun applyLiveDelay(seconds: Int) {
        val ms = if (seconds <= 0) 0 else (seconds * 1_000).coerceAtLeast(LiveAudioDelay.MIN_MS)
        liveDelayMs = ms
        playbackSession.activeEngine.value?.applyAudioDelay(ms)
    }

    /**
     * A subtitle session failed to start, so no recognizer will consume the hold-back. Leaving it on
     * would keep the channel seconds behind live for captions that never appear — put stock playback
     * back. Nothing is reopened for this: the value reaches the sink on the next tune, and paying a
     * reopen to reclaim the seconds immediately is how this TV loses the picture.
     */
    private fun resetHoldBackAfterFailedStart() {
        if (liveDelayMs > 0) applyLiveDelay(0)
    }

    /**
     * Price the ASR-side half of the end-to-end chain for one [offered][LiveTranslationBuffer.offer]
     * caption window (see [PipelineStats.latencyAsrMaxMs]): capture → hypothesis text
     * ([TranslationUnit.capturedAtMs] → [TranslationUnit.readyAtMs]) and hypothesis → hand-off
     * ([TranslationUnit.readyAtMs] → [TranslationUnit.offeredAtMs]), with the entity resolver's own
     * share ([TranslationUnit.entityMs]) recorded as a sub-meter of the second. Called on the ASR
     * worker with each offer, so the maxes reflect units that actually reached a caption rather than
     * the many partials that produced no window: pricing those would fill the maxes with decode time
     * no viewer ever waited behind.
     */
    private fun noteAsrStage(unit: TranslationUnit) {
        if (unit.capturedAtMs <= 0L || unit.readyAtMs <= 0L || unit.offeredAtMs <= 0L) return
        val asr = unit.readyAtMs - unit.capturedAtMs
        if (asr > latencyAsrMaxMs) latencyAsrMaxMs = asr
        val prep = unit.offeredAtMs - unit.readyAtMs
        if (prep > latencyPrepMaxMs) latencyPrepMaxMs = prep
        if (unit.entityMs > latencyEntityMaxMs) latencyEntityMaxMs = unit.entityMs
    }

    /** "kroko en:working" vs "tiny it:working" for a pipeline running the given source language. */
    private fun workingBase(lang: String, streaming: Boolean): String =
        "${if (streaming) "kroko" else "tiny"} $lang:working"

    /**
     * Model-chunk decode cost per millisecond of *audio* that chunk carries. One chunk of the bundled
     * English model is 1.28 s of speech, so the raw per-chunk cost (`dec 670ms`) looks like a box that
     * cannot keep up, while it is really 0.52 — twice as fast as real time. Below 1.0 the recognizer
     * wins ground on the live stream; at or above it, captions lag and the queue starts dropping audio.
     * 0 = not measurable (the offline Whisper engine does not report chunk audio).
     */
    private fun realtimeFactorOf(decodeAvgMs: Long, chunkAudioMs: Long): Double =
        if (chunkAudioMs > 0L) decodeAvgMs.toDouble() / chunkAudioMs.toDouble() else 0.0

    // Last [gcStats] read and the wall-clock it was taken at, so the runtime's own stats map is
    // built at most once per [GC_STATS_TTL_MS] instead of on every status rebuild.
    @Volatile private var gcCountCache = 0L
    @Volatile private var gcTimeCache = 0L
    @Volatile private var gcReadAtMs = 0L

    /**
     * Cumulative ART garbage-collection count and time from the runtime's own stats, or 0/0 when the
     * keys are absent (older runtimes, or a vendor that strips them). Cheap enough for the 1 Hz stats
     * tick, and the one piece of evidence that separates "the decoder is too slow" from "the collector
     * stopped the world mid-feed" — see [PipelineStats.gcCount]/[PipelineStats.feedMaxMs].
     */
    private fun gcStats(): Pair<Long, Long> {
        // `Debug.getRuntimeStats()` builds a Map, and this is called both from [pushStats] (after
        // every partial, not only the 1 Hz tick) and from [currentStatusText]; the counters are
        // monotonic and cumulative since process start, so a few seconds of staleness costs nothing
        // and the per-call allocation is exactly the kind of churn the number is meant to catch.
        val now = SystemClock.elapsedRealtime()
        if (gcReadAtMs != 0L && now - gcReadAtMs < GC_STATS_TTL_MS) return gcCountCache to gcTimeCache
        val stats = runCatching { android.os.Debug.getRuntimeStats() }.getOrNull()
        if (stats != null) {
            gcCountCache = stats["art.gc.gc-count"]?.toLongOrNull() ?: gcCountCache
            gcTimeCache = stats["art.gc.gc-time"]?.toLongOrNull() ?: gcTimeCache
        }
        gcReadAtMs = now
        return gcCountCache to gcTimeCache
    }

    /**
     * Append the live debug counters to [statusBase]; "idle" and the standing ready state
     * ("kroko en:ready") are shown bare. Counters only make sense while a pipeline actually runs,
     * so they accompany a "… :working" line ("kroko en:working · chunk N · blank N · words N") but
     * not the pre-pipeline ready/idle states (where they would be frozen all-zeros noise).
     */
    private fun currentStatusText(): String {
        val base = statusBase
        if (base == "idle" || base.endsWith(":ready")) return base
        // Same decode telemetry the settings-page status line shows after chunk/blank/words — see
        // [pushStats] for what each reads. Kept inline so the bottom line can answer "why is the
        // caption stale?" on screen: feed ms = decode cost, partial s = seconds since a fresh line,
        // ready % = share of feeds the isReady loop actually decoded in.
        val intervalSec = if (lastPartialAtMs > 0L)
            ((SystemClock.elapsedRealtime() - lastPartialAtMs).coerceAtLeast(0L) / 1000L)
        else 0L
        val hit = if (recognizerFeedCalls > 0 && recognizerFeedCalls >= isReadyHits)
            (isReadyHits * 100L / recognizerFeedCalls).toInt()
        else 100
        // Capture-health group: rms proves the tap hears sound (near 0 = silence capture), aud N/M
        // compares recognizer-fed audio seconds against wall-clock seconds (≈1 healthy; ≫1 means a
        // misreported sample rate is time-stretching the feed), fmt is the layout the tap captured,
        // tap last/max ms is the audio-thread hand-off cost and the trailing % the worst ratio of that
        // cost to the chunk's own audio span (see [tapLastUs]/[tapDutyPercent] — a high % is the sink
        // being held near its budget, i.e. the FIR straining the audio thread).
        val audioSec = fedSamples16k / PcmResampler.TARGET_RATE
        val wallSec = if (sessionStartMs > 0L)
            ((SystemClock.elapsedRealtime() - sessionStartMs).coerceAtLeast(0L) / 1000L)
        else 0L
        // Two denominators, because each answers a different question: against PLAYING time aud
        // shows whether the tap starves while the player runs (≈1 healthy), against WALL time it
        // shows how much of the session never reached the recognizer at all. A playback stall
        // freezes aud and playing together, so it is invisible to both ratios and needs `buf`.
        val playingSec = playingMsAccum / 1000L
        val bufSec = bufferingMsAccum / 1000L
        val fmt = PlaybackAudioTap.captureInfo?.label() ?: "?"
        // Non-zero means the sink is bypassing an encoded bitstream (see CaptureInfo.raw) and the
        // recognizer is getting no PCM at all — the one failure the fmt label alone cannot rule out,
        // so it is shown separately instead of hiding behind a healthy-looking rate/channels.
        val nonPcm = PlaybackAudioTap.nonPcmBuffers.get()
        // Decode-health group: dec avg/max is the cost of ONE model chunk and is only readable as
        // real-time headroom next to `rtf` (that cost ÷ the chunk's own audio span, see
        // [realtimeFactorOf]); `feed` is the WORST whole feed call this session — feed ≈ dec blames
        // the native decode for a stall while feed ≫ dec points outside it (JNI, front-end, or the
        // worker being descheduled; read `gc` for that); drn is the biggest catch-up batch one feed
        // drained, qmax the deepest backlog against the ceiling (×160 ms = worst lag).
        //
        // `drop` and `jmp` are each split overflow/flush (see [RealtimePcmQueue]): `drop 12/396 · jmp
        // 2/51` reads as "the recognizer overflowed twice and truly lost 12 frames; the other 51 jumps
        // were the renderer repositioning a live stream, which is not our backlog at all". Without the
        // split, 408 frames look like one throughput problem when they may be two unrelated ones.
        val m = recognizer?.meters
        val decAvg = if (m != null && m.decodeChunksTotal > 0L)
            m.decodeTotalMs / m.decodeChunksTotal else 0L
        val decMax = m?.singleDecodeMaxMs ?: 0L
        val drn = m?.drainMaxChunks ?: 0L
        val rtf = realtimeFactorOf(decAvg, m?.chunkAudioMs ?: 0L)
        // Decode tail: the percentiles `dec avg` flattens and `dec max` lets one outlier dominate
        // (see [PipelineStats.decodeP50Ms]); `slow` counts the decodes past one model chunk of audio,
        // the ones the queue capacity is traded against (P1). ASR-worker duty cycle and the measured
        // end-to-end caption latency round the group out.
        val decP50 = m?.decodeP50Ms ?: 0L
        val decP90 = m?.decodeP90Ms ?: 0L
        val decP99 = m?.decodeP99Ms ?: 0L
        val decSlow = m?.decodeSlowCount ?: 0L
        // ...and what each of those decodes was doing, so the tail is actionable rather than just
        // visible: `gc` = a collection ran inside it, `cpu` = the work itself took that long, and the
        // remainder (slow − gc − cpu) = the thread was off-CPU with neither excuse, the only bucket a
        // thread rank can move. See [StreamingWhisper.RecognizerMeters.decodeSlowGcCount].
        val decSlowGc = m?.decodeSlowGcCount ?: 0L
        val decSlowCpu = m?.decodeSlowCpuBoundCount ?: 0L
        val busyPercent = if (sessionStartMs > 0L) {
            val elapsed = (SystemClock.elapsedRealtime() - sessionStartMs).coerceAtLeast(1L)
            (consumerBusyMs * 100L / elapsed).toInt().coerceIn(0, 100)
        } else 0
        // Translation-health group: xlat first/whole is the average ms until the Chinese line starts
        // arriving and until one round trip completes, zh is seconds since the Chinese last changed.
        // Read zh against `partial` above: equal means the two lines track each other, partial ≪ zh
        // means the English line is live and the Chinese has stalled behind a slow or failing gateway.
        val zhSec = if (lastChineseAtMs > 0L)
            ((SystemClock.elapsedRealtime() - lastChineseAtMs).coerceAtLeast(0L) / 1000L)
        else 0L
        val xlateAvg = if (translationTrips > 0L) translationTotalMs / translationTrips else 0L
        val xlateTtft = if (translationTtftTrips > 0L)
            translationTtftTotalMs / translationTtftTrips else 0L
        // The failure split, appended to `fail` only once something has failed, so a healthy line is
        // not padded with `0to/0h`. `fail 3(2to/1h→429)` reads as two timeouts and one refusal, and
        // names the refusal. `to` and `h` are lower bounds on `fail`: a malformed or empty answer is
        // neither, so it can exceed their sum.
        val failDetail = if (translationFailures > 0L)
            "${translationFailures}(${translationTimeouts}to/${translationHttpErrors}h" +
                (if (translationLastHttpCode > 0) "→$translationLastHttpCode)" else ")")
        else "$translationFailures"
        // What the decoder is actually configured with, so a reading can be attributed to a setting.
        // A run to tune `maxActivePaths` or `numThreads` is worthless if the line does not say which
        // values produced it. Empty for engines that expose no such knobs.
        val decoderCfg = recognizer?.decoderSummary.orEmpty()
        // Translation served by the free endpoint instead of the configured gateway. `fail` cannot
        // show this — the fallback swallows the gateway's error and the trip still succeeds — so
        // without it a dead key/quota is indistinguishable from good Chinese. `/gwN!` is the subset
        // where the gateway was configured and FAILED: the only form of this that is a fault rather
        // than the zero-config path (see [FallbackTranslator]).
        val fb = translator as? FallbackTranslator
        val fbTrips = (fb?.fallbackTrips ?: 0L) - fallbackTripsBaseline
        val fbBypassed = (fb?.fellBackAfterPrimary ?: 0L) - fellBackAfterPrimaryBaseline
        // One token either way, rather than a suffix concatenated onto a shared prefix: the "gateway
        // was bypassed" reading is the one worth catching the eye, and an empty `else` also costs an
        // unreferenced literal in the hardcoded-text inventory.
        val xfbToken = if (fbBypassed > 0L) " · xfb $fbTrips/gw$fbBypassed!"
        else " · xfb $fbTrips"
        // Request / queued / plumbed: `dly 8s/8000ms/8s` = 8 s asked for, 8 s of PCM actually sitting
        // in the sink's hold-back queue, and a sink that has the machinery at all. `dly 8s/0ms/8s`
        // while a session runs means the audio never reached the processor (a bypassed bitstream, not
        // a device cap). The middle number is what caption sync runs on.
        val delayToken = if (tapArmed && liveDelayMs > 0) {
            val plumbedToken = if (LiveAudioDelay.plumbed) "${liveDelayMs / 1000}s" else "off"
            // `q` is the hold-back queue's own depth: `dly 8s/8623ms(q8000)/8s` splits the measured
            // lead into what this process holds and what the sink's track adds. That split is what
            // tells a genuine device latency from a clock reading that has drifted.
            " · dly ${liveDelayMs / 1000}s/${LiveAudioDelay.measuredMs}ms(q${LiveAudioDelay.queuedMs})/$plumbedToken"
        } else {
            ""
        }
        val (gcCount, gcTimeMs) = gcStats()
        return "$base · chunk ${fedChunks}" +
            " · drop ${frameQueue.droppedOnOverflow.get()}/${frameQueue.droppedOnFlush.get()}" +
            " · jmp ${frameQueue.overflowJumpCount}/${frameQueue.flushJumpCount} · blank ${blankChunks}" +
            " · words ${recognizedWords} · seg ${committedSegments}(${committedClosings}c/${committedLive}l)" +
            " · xlat ${xlateTtft}/${xlateAvg}ms · zh ${zhSec}s (n${translationTrips})" +
            // Measured end-to-end caption latency (capture → Chinese on screen) and its session worst.
            " · e2e ${endToEndMs}/${endToEndMaxMs}ms" +
            // The real sync error: see [cueLateMs]. `lag 0ms` means every caption reached the screen
            // at its cue time; anything above it is audio that had already been heard.
            " · lag ${cueLateMs}ms" +
            delayToken +
            // The e2e worst split by stage, in chain order (see [PipelineStats.latencyAsrMaxMs]):
            // asr/prep/ent/req/llm/ui maxes in ms, so a 3 s spike is BLAMED on one stage rather than
            // merely seen. `ent` is a sub-meter of `prep`.
            " · lat ${latencyAsrMaxMs}/${latencyPrepMaxMs}/${latencyEntityMaxMs}/" +
            "${latencyReqMaxMs}/${latencyLlmMaxMs}/${latencyUiMaxMs}ms" +
            // Why the Chinese line moves or does not: every unit the worker was handed, by outcome.
            // `req` is requests that actually went out, `fail` gateway errors/timeouts (split by
            // cause — see [failDetail]), `can` requests this class cancelled (mostly
            // [markNewSentence] at a sentence boundary), `skip` units the accumulator refused to
            // build a request for. `n` above only counts the successes, so `req = n + fail + can`
            // and `skip` is the fifth outcome that has no request at all.
            // `cfx` counts sentence turnovers that found a CLOSING flush in flight and WAITED for it.
            // This is a price gauge, not a loss gauge: it used to count those flushes being cancelled
            // (which dropped the sentence tail outright), and now measures the round trip of delay each
            // wait costs before the next sentence's window is offered. See [closingFlushesAtTurnover].
            " · req ${translationRequests} · fail $failDetail · can ${translationCancels}" +
            " · skip ${translationSkipCount}" +
            "(s$skipShort/d$skipDuplicate/u$skipUnstable/l$skipLowConfidence/st$skipStale)" +
            " · cfx $closingFlushesAtTurnover" +
            xfbToken +
            " · dec ${decAvg}/${decMax}ms · dp50/90/99 ${decP50}/${decP90}/${decP99} · >1.28s $decSlow" +
            // The split of the tail in parens: gc/own-work/neither. Bare `>1.28s 9` says a tail
            // exists but not whether it is allocation, arithmetic or scheduling.
            " (gc$decSlowGc/cpu$decSlowCpu/yd${decSlow - decSlowGc - decSlowCpu})" +
            " · feed ${feedMaxMs}ms · stall $feedStallCount" +
            " · rtf ${"%.2f".format(Locale.ROOT, rtf)} · drn $drn" +
            // Is contextual biasing on, and how big is the list riding the decoder? `none` means the
            // scene gate has not opened, so club/player names are decoded with no help at all — the
            // state that reads as "names are never recognised". `football/394` is the bias plus the
            // size of the context graph it walks each frame, which is what `dec` above is spending on.
            " · scene ${appliedScene?.let { "${it.name.lowercase()}/$appliedHotwordCount" } ?: "none"}" +
            // How many clubs the current list is scoped to: `t2` is a proved fixture, `t0` an
            // unscoped list. Without it a small hotword count cannot be told apart from a genuinely
            // unscoped one, which is exactly the "did scoping ever fire?" question.
            " · t$appliedTeamCount" +
            // The state behind `t`: `w` the rolling window's size, `near` the clubs the window NAMES
            // with how many of their squad surnames sit beside it (the `n/3` belief needs), `mem` the
            // remembered proof with its age, and the verdict. `t0` alone cannot separate "no club was
            // ever named" from "a club was named but its surnames never matched" from "a club was
            // proved and then aged out" — the three faults a scoping run has to tell apart.
            " · scope $scopingDiagnostics" +
            // How often that bias was rebuilt. Each switch re-creates the decode stream, so a scene
            // comparison run with a rising `scn` is measuring rebuilds, not the hotword list.
            " · scn $sceneSwitches" +
            (if (decoderCfg.isEmpty()) "" else " · cfg $decoderCfg") +
            " · qmax ${frameQueue.highWaterFrames}/$PCM_CHANNEL_CAPACITY" +
            // Recent mean backlog against the same ceiling, in frames. qmax only says how deep the
            // queue once got; this says whether it comes back down. A mean parked near the ceiling
            // means every overflow jump discards the full buffer and a capacity increase would only
            // enlarge each loss, so it is the gate on that change (see [RealtimePcmQueue]).
            " · qavg ${"%.1f".format(Locale.ROOT, frameQueue.recentAvgDepthFrames)}" +
            " · partial ${intervalSec}s · ready ${hit}%" +
            " · rms ${lastRms} · gain ${"%.1f".format(Locale.ROOT, lastGain)}x" +
            " · tap ${"%.1f".format(Locale.ROOT, tapLastUs / 1000.0)}/" +
            "${"%.1f".format(Locale.ROOT, tapMaxUs / 1000.0)}ms" +
            " ${tapDutyPercent}% · busy $busyPercent%" +
            // GC pair: cumulative count/time, the tie-breaker for a `feed ≫ dec` stall (see gcStats).
            " · gc ${gcCount}/${gcTimeMs}ms" +
            // All three denominators, because each rules out a different loss mode and no single one
            // can: `aud/play` ≈ 1 proves the capture is live whenever the player is (a lower value is
            // a starved tap), while `play/wall` ≪ 1 says the player was NOT running and the reason is
            // split by `buf` — buffering is network, the remainder is paused/menu time. Previously
            // only wall clock was shown, which made every stall and every menu visit look identical.
            " · aud ${audioSec}/${playingSec}play/${wallSec}wall · buf ${bufSec}s/${bufferingEpisodes}ep/${bufferingMaxMs}ms · fmt $fmt" +
            if (nonPcm > 0L) " · passthru ${nonPcm}!" else ""
    }

    /** Push the status line onto the StateFlow, taking its current debug counters. */
    private fun refreshStatus() {
        _status.value = currentStatusText()
    }

    // Bounded real-time queue because ASR decode can be slower than real-time audio on a TV box: an
    // unbounded queue would grow without limit while a slow utterance is still decoding. Re-created
    // per pipeline session so a cancelled worker's undrained samples/tombstones from a previous
    // session can never be picked up by the next one (which would kill it immediately).
    @Volatile private var frameQueue: RealtimePcmQueue = RealtimePcmQueue(PCM_CHANNEL_CAPACITY)

    // The built-in English streaming engine, loaded into memory once at app start and kept resident
    // for the whole process. Being an APK asset it needs no download, but constructing it still
    // reads the ONNX weights into memory, which is the audible delay between toggling subtitles and
    // the recognizer coming up. Preloading here means the first English channel recognises
    // immediately (reset() clears any leftover buffer between sessions). Only touched by the init
    // preload coroutine and the pipeline (which holds pipelineMutex), so no extra synchronisation.
    @Volatile private var preloadedEnglish: StreamingWhisper? = null

    init {
        // Preload the built-in English streaming engine into memory at app start so the first
        // English channel recognises without waiting for the ONNX weights to be read. Readiness is
        // independent of AI subtitles being on — this makes the "idle → recognition" wait on an
        // English channel go away regardless of when the switch is flipped.
        scope.launch {
            val warm = runCatching {
                whisperFactory.create(context, StreamingModelCatalog.BUILT_IN_ENGLISH_LANGUAGE)
            }.getOrNull()
            if (warm != null) {
                preloadedEnglish = warm
                Log.i(tag, "english streaming model preloaded and resident")
                // A resident (constructed but not yet running) English engine is surfaced as ready
                // once preload completes, so the bottom status goes "idle" → "kroko en:ready".
                if (!tapArmed) {
                    statusBase = workingBase(StreamingModelCatalog.BUILT_IN_ENGLISH_LANGUAGE, true)
                        .replace(":working", ":ready")
                    refreshStatus()
                }
            } else {
                Log.w(tag, "english streaming model preload failed; will load on demand")
            }
        }

        // Follow the loaded item: reload its remembered switch + language (channel memory) when it
        // changes, so [effective] always reflects the channel that is actually on screen.
        scope.launch {
            player.currentMeta.map { it.contentKey }.distinctUntilChanged().collectLatest { key ->
                val epoch = channelEpoch.incrementAndGet()
                _activeKey.value = key
                _channelEnabled.value = null
                _channelSourceLanguage.value = null
                PlaybackAudioTap.forgetChannel()
                if (key != null) {
                    val row: PlaybackPrefsEntity? = playbackPrefs.prefsFor(key)
                    if (epoch != channelEpoch.get()) return@collectLatest
                    _channelEnabled.value = row?.aiSubtitleEnabled
                    _channelSourceLanguage.value = row?.aiSubtitleSourceLanguage
                }
                Log.d(tag, "active item key=${key ?: "none"} enabled=${_channelEnabled.value ?: "global"} src=${_channelSourceLanguage.value ?: "global"}")
            }
        }

        // Drive the pipeline from the effective config: per-channel memory decides whether it is on
        // and which language to recognise, not the global switch alone.
        scope.launch {
            effective.collectLatest { cfg ->
                // Runs to completion even when a newer emission arrives: a start interrupted half-way
                // (the tap armed, no worker) used to leave a session that recognised nothing while the
                // next start returned early because the tap was already armed — one lost press.
                withContext(NonCancellable) {
                    // The hold-back belongs to the caption session: on means the sink may lag the
                    // speaker, off means live playback must be left exactly as it was. Applied before
                    // the start/stop so the engine the session will read from is already rebuilt.
                    //
                    // A channel switch passes through `enabled == false` for a moment while the new
                    // channel's remembered row is being read (`_channelEnabled` is cleared first). That
                    // is not the user turning subtitles off, and bouncing the hold-back to 0 and back
                    // would re-tune the channel twice on every zap — so the delay is only dropped when
                    // the row explicitly says off, or when nothing is loaded any more.
                    if (cfg.enabled) {
                        applyLiveDelay(cfg.liveDelaySeconds)
                    } else if (_channelEnabled.value == false || _activeKey.value == null) {
                        applyLiveDelay(0)
                    }
                    if (cfg.enabled) startPipeline(cfg) else stopPipeline()
                }
            }
        }

        // The device could not decode this channel's audio to PCM. The engine has already reopened the
        // channel on passthrough so the picture keeps playing; there is nothing for the recognizer to
        // hear, so stand the session down instead of leaving a pipeline that can never produce a word.
        // The per-channel switch shows off (the user can retry it; the engine's verdict is per load),
        // and the hold-back is dropped so playback is not left seconds behind live for no captions.
        scope.launch {
            PlaybackAudioTap.passThroughAudio.collect { mime ->
                if (mime == null) return@collect
                Log.w(tag, "audio kept on passthrough ($mime) — AI subtitles unavailable on this channel")
                statusBase = "unavailable: ${mime.substringAfterLast('/')} passthrough"
                refreshStatus()
                _channelEnabled.value = false
                applyLiveDelay(0)
                if (tapArmed) stopPipeline()
            }
        }

        // A channel whose engine attaches AFTER the config already said "AI subtitles on" must still
        // get the hold-back: the engine may have been built and started before the value reached it,
        // and it reopens itself when the request differs from what it is running with.
        scope.launch {
            playbackSession.activeEngine.collect { engine -> engine?.applyAudioDelay(liveDelayMs) }
        }

        // A Koin `single` lives for the whole process, so its preloaded English instance would stay
        // resident (tens of MB of ONNX weights) even when the system is under memory pressure and the
        // feature is off. Register as a memory-aware callback and, on pressure, release the resident
        // engine when it is not currently borrowed by a live pipeline — the next English channel
        // simply rebuilds it on demand (the preload only removed the one-time startup wait).
        (context.applicationContext as? android.app.Application)
            ?.registerComponentCallbacks(this)
    }

    /**
     * Explicitly feed the currently-loaded item's preference key. The manager also follows
     * [OwnTVPlayer.currentMeta], but Live TV on the ExoPlayer engine plays through LivePreviewEngine,
     * which never writes that meta — so the live screen calls this on every tune (and null on stop)
     * to keep the per-channel AI-subtitle memory aligned on both engines. No-op-safe to repeat.
     */
    fun setActiveKey(key: String?) {
        val epoch = channelEpoch.incrementAndGet()
        _activeKey.value = key
        _channelEnabled.value = null
        _channelSourceLanguage.value = null
        // Nothing the channel we just left observed may decide this one: its captured format and any
        // "this audio is a bypassed bitstream" notice made the switch refuse to stay on for a channel
        // whose audio was fine (the press was answered by the previous channel's verdict).
        PlaybackAudioTap.forgetChannel()
        if (key != null) {
            scope.launch {
                val row = playbackPrefs.prefsFor(key)
                // A newer tune, or the user's own press, outranks this read.
                if (epoch != channelEpoch.get()) return@launch
                _channelEnabled.value = row?.aiSubtitleEnabled
                _channelSourceLanguage.value = row?.aiSubtitleSourceLanguage
            }
        }
    }

    /**
     * Flip the AI-subtitle switch for the CURRENT item and remember it for that channel. No channel
     * context (nothing loaded) does nothing — the global Settings toggle is the only control then.
     */
    fun toggleAiSubtitles() {
        val key = _activeKey.value ?: run {
            Log.d(tag, "toggle ignored: no active item to remember for")
            return
        }
        // The switch's own state, NOT [effective]: that StateFlow is recomputed on this scope's
        // dispatcher, so a press that follows the previous one closely still read the old value and
        // produced the same `next` again (a press that appears to do nothing).
        val next = !(_channelEnabled.value ?: false)
        // A press is a decision: a row read still in flight must not overwrite it (see [channelEpoch]).
        channelEpoch.incrementAndGet()
        _channelEnabled.value = next
        Log.i(tag, "toggleAI subtitles for key=$key -> $next")
        scope.launch { playbackPrefs.rememberAiSubtitleEnabled(key, next) }
    }

    /**
     * Set the AI-subtitle recognition (source) language for the CURRENT item and remember it for
     * that channel. An empty [language] forgets the channel pick and follows the global language.
     */
    fun setSourceLanguage(language: String) {
        val key = _activeKey.value ?: run {
            Log.d(tag, "setSourceLanguage ignored: no active item to remember for")
            return
        }
        val stored = language.ifBlank { null }
        channelEpoch.incrementAndGet()
        _channelSourceLanguage.value = stored
        Log.i(tag, "set source language for key=$key -> ${stored ?: "global"}")
        scope.launch { playbackPrefs.rememberAiSubtitleSourceLanguage(key, stored) }
    }

    private suspend fun startPipeline(cfg: AiSubtitleConfig) = pipelineMutex.withLock {
        startPipelineLocked(cfg)
    }

    /** [startPipeline] body, run with [pipelineMutex] held (so it may call [stopPipelineLocked]). */
    private suspend fun startPipelineLocked(cfg: AiSubtitleConfig) {
        // Re-run while the same source language stays selected is a no-op (collectLatest fires on
        // every config change). Only when the source language actually changes do we tear the old
        // recognizer down — otherwise a language switch would silently keep decoding in the old one.
        if (tapArmed && startedLanguage == cfg.sourceLanguage) return
        if (tapArmed) stopPipelineLocked()
        Log.i(tag, "starting pipeline: src=${cfg.sourceLanguage} target=${cfg.targetLanguage}")

        // Streaming engine selection:
        //  - English ships inside the APK as assets, so it is always usable — build the recognizer
        //    straight off assets, no disk scan, no download, no readiness snapshot.
        //  - fr/de/es trust the remembered-good readiness snapshot ("last saved correct config") when
        //    present, else a real disk scan; a naming engine is used once it is on disk, otherwise we
        //    build the bundled Whisper fallback now and stream the model in during the download.
        //  - Other languages have no streaming engine: the bundled Whisper fallback (an APK asset) is
        //    always ready, so the pipeline starts immediately without any model fetch.
        val builtInEnglish = StreamingModelCatalog.isBuiltInEnglish(cfg.sourceLanguage)
        val streaming = StreamingModelCatalog.forLanguage(cfg.sourceLanguage)
        // English needs no readiness snapshot (its engine is an APK asset); only fr/de/es consult the
        // remembered-good set, so read it just for them (avoids a DataStore read on every start).
        val useStreaming = builtInEnglish ||
            (streaming != null && (streaming.language in configStore.readinessSnapshot() || modelStore.isDownloaded(streaming)))

        // Build the recognizer first. Bail without arming the tap on failure, so decoded audio is
        // never routed into a channel that no worker consumes (which would grow without bound). For
        // English, reuse the process-resident preloaded instance (reset() clears any leftover buffer
        // from a prior session) instead of constructing a fresh one — that construction is exactly
        // the delay the preload is for.
        val recognizer = if (builtInEnglish) {
            preloadedEnglish?.also { it.reset() }
                ?: runCatching { whisperFactory.create(context, cfg.sourceLanguage) }
                    .onFailure { e ->
                        Log.w(tag, "recognizer init failed", e)
                        // A snapshot fast-path may have trusted a stale "ready" flag (files deleted
                        // behind our back): forget it so the next launch re-validates / re-downloads.
                        if (useStreaming && streaming != null) {
                            scope.launch { configStore.setStreamingModelsReady(configStore.readinessSnapshot() - streaming.language) }
                        }
                        _status.value = "error: ${e.message}"
                    }
                    .getOrNull()
        } else {
            runCatching { whisperFactory.create(context, cfg.sourceLanguage) }
                .onFailure { e ->
                    Log.w(tag, "recognizer init failed", e)
                    // A snapshot fast-path may have trusted a stale "ready" flag (files deleted
                    // behind our back): forget it so the next launch re-validates / re-downloads.
                    if (useStreaming && streaming != null) {
                        scope.launch { configStore.setStreamingModelsReady(configStore.readinessSnapshot() - streaming.language) }
                    }
                    _status.value = "error: ${e.message}"
                }
                .getOrNull()
        } ?: run {
            resetHoldBackAfterFailedStart()
            return
        }
        // Track whether this session borrows the resident English instance so its teardown does not
        // release it (it must stay resident for the next session).
        val borrowedResident = recognizer === preloadedEnglish
        this.recognizer = recognizer
        this.borrowedResident = borrowedResident
        // A fresh queue per session: anything a previous cancelled worker never drained is thrown
        // away, so this worker starts on empty state and is guaranteed to keep running. Its depth
        // grows with the live hold-back: priming the sink hands the tap several seconds of audio in
        // one burst, and a queue sized for plain live (2.56 s) would drop most of it as overflow —
        // exactly the first audio the viewer hears. The extra frames hold that burst while the
        // recognizer works through it, which the delay budget gives it time to do.
        val delayFrames = (liveDelayMs / FRAME_MS).toInt()
        val fresh = RealtimePcmQueue(PCM_CHANNEL_CAPACITY + delayFrames)
        frameQueue = fresh
        startedLanguage = cfg.sourceLanguage
        // Send into the session-local queue captured here, so an old session can never cross-feed.
        // A full queue (worker backlogged on a slow utterance) jumps to the newest audio a whole
        // stretch at a time and marks a generation boundary, so the recognizer is reset on the
        // discontinuity instead of being fed time-compressed fragments. The slicer is session-local
        // too, so a stopped session's partial 16 kHz tail can never leak in.
        val slicer = FrameSlicer(FRAME_SAMPLES)
        // Session-local streaming resampler: it carries the sub-decimation-group tail across
        // decoder chunks (a stateless per-chunk 48k→16k clipped up to 2 samples every callback) and
        // applies the anti-image integrate-and-dump stage. Reset on a renderer discontinuity.
        val resampler = StreamResampler16k()
        val tapConsumer = object : PlaybackAudioTap.PcmConsumer {
            override fun onPcm(samples: ShortArray, sampleRate: Int, presentationTimeUs: Long) =
                sendPcm(fresh, resampler, slicer, samples, sampleRate)

            override fun onDiscontinuity() {
                // Renderer flushed (seek / snap to live edge / stream restart): the buffered tail
                // and everything in the frame queue belongs to a media position that no longer
                // exists. Drop both and bump the queue generation so the consumer restarts the
                // recognizer before the next buffer instead of gluing two timelines together.
                resampler.reset()
                fresh.jump(SystemClock.elapsedRealtime())
                // Cues not yet shown belonged to the discarded timeline; the line already on screen
                // stays until the new timeline produces one. The measured hold-back is dropped too:
                // the flush empties the sink, so the next buffers play near-live until it refills,
                // and a stale multi-second reading would schedule them far into the future.
                captionTimeline.clearPending()
                LiveAudioDelay.resetLead()
                Log.i(tag, "audio discontinuity: renderer flushed; frame queue jumped to new timeline")
            }
        }
        // Clear the caption state BEFORE the tap is armed: a decoded buffer can reach the consumer
        // the instant it starts, and it must not be timed against the previous session's pause
        // accumulator or land in a queue still holding the old channel's cues.
        captionTimeline.reset()
        // A new session's first hypothesis has no predecessor to confirm its last word against.
        prevSourceWords = emptyList()
        _asrText.value = null
        _pair.value = null
        pausedAccumMs = 0L
        latestHeardText = ""
        if (!PlaybackAudioTap.start(tapConsumer)) {
            // Another owner already holds the tap (should be impossible under the mutex, but never
            // leak the recognizer we just built). A borrowed resident English instance is left
            // alone — it must stay resident, and it has no pipeline backing it here anyway.
            if (!borrowedResident) recognizer.close()
            this.recognizer = null
            this.borrowedResident = false
            Log.w(tag, "audio tap busy; ai subtitles cannot start")
            _status.value = "error: audio tap busy"
            resetHoldBackAfterFailedStart()
            return
        }
        tapArmed = true
        // A previous failed start may have reverted the hold-back; restore it now that a recognizer is
        // actually consuming the audio. A no-op in the normal path, where the config collector applied
        // the same value before starting.
        applyLiveDelay(cfg.liveDelaySeconds)
        // A fresh session resets the debug counters so the status line reflects THIS pipeline only.
        fedChunks = 0; blankChunks = 0; partialChunks = 0; recognizedWords = 0; committedSegments = 0
        recognizerFeedCalls = 0; lastPartialAtMs = 0L; lastFeedMs = 0L; lastDecodeMs = -1L; isReadyHits = 0L
        feedMaxMs = 0L; feedStallCount = 0L
        translationTrips = 0; translationTotalMs = 0L; translationTtftTotalMs = 0L
        translationTtftTrips = 0; lastChineseAtMs = 0L
        translationRequests = 0L; translationFailures = 0L; translationCancels = 0L
        translationTimeouts = 0L; translationHttpErrors = 0L; translationLastHttpCode = 0
        translationSkipCount = 0L; closingFlushesAtTurnover = 0L
        skipShort = 0L; skipDuplicate = 0L; skipUnstable = 0L; skipLowConfidence = 0L; skipStale = 0L
        committedClosings = 0L; committedLive = 0L
        // A new session must not inherit the previous one's bypassed-buffer count, or the diagnostics
        // would keep shouting `passthru N!` even after the stream started decoding to PCM.
        PlaybackAudioTap.nonPcmBuffers.set(0)
        fedSamples16k = 0L; lastRms = 0; showedFirstPartial = false
        tapLastUs = 0L; tapMaxUs = 0L; tapDutyPercent = 0
        consumerBusyMs = 0L
        endToEndMs = 0L; endToEndMaxMs = 0L
        latencyAsrMaxMs = 0L; latencyPrepMaxMs = 0L; latencyEntityMaxMs = 0L
        latencyReqMaxMs = 0L; latencyLlmMaxMs = 0L; latencyUiMaxMs = 0L
        sessionStartMs = SystemClock.elapsedRealtime()
        // Playing-time accumulator starts empty; the 1 Hz ticker below advances it only while
        // player.isPlaying is true, so aud/playing is not skewed by paused/menu time.
        playingMsAccum = 0L
        bufferingMsAccum = 0L
        bufferingEpisodes = 0L; bufferingMaxMs = 0L
        sceneSwitches = 0L
        scopingDiagnostics = "off"
        (translator as? FallbackTranslator)?.let {
            fallbackTripsBaseline = it.fallbackTrips
            fellBackAfterPrimaryBaseline = it.fellBackAfterPrimary
        }
        lastStatsTickMs = SystemClock.elapsedRealtime()
        statusBase = workingBase(cfg.sourceLanguage, useStreaming)
        refreshStatus()
        // A fresh segmenter + live-translation buffer per session: the ASR worker cuts the sentence
        // window with the segmenter and offers it to the buffer the translation worker owns.
        val segmenter = CaptionSegmenter()
        // Whole-sentence (movie) mode offers one unit per sentence, and a conflated channel would
        // silently replace a finished sentence's unit with the next one — losing that line's Chinese
        // entirely. A short FIFO keeps every sentence the translator can still reach.
        val buffer = LiveTranslationBuffer(
            queueDepth = if (liveDelayMs > 0) SENTENCE_QUEUE_DEPTH else 0,
        )
        // The incremental Chinese line for the sentence being spoken. Owned by the translation worker
        // alone (it is the only thing that knows which words already have Chinese), session-scoped so
        // a cancelled session's accumulation cannot bleed into the next one.
        val accumulator = LiveTranslationAccumulator()
        liveBuffer = buffer
        // Nothing on screen yet from this session: every epoch is live, and no flush is outstanding.
        activeEpoch = 0
        closedFlushPending = false
        // A fresh session starts with an empty caption timeline and a clock at zero pause, so no
        // cue from the previous channel can surface here and cue times line up with this session's
        // audio. The ticker is started with the pipeline and stopped with it.
        startPresentationClock()
        // One diagnostic sink per session (see [AiSubtitleDiagnostics]). Creating it touches disk, so
        // it happens off the caller's thread; any previous session's pending rows are dropped with it.
        diagnostics = withContext(Dispatchers.IO) { AiSubtitleDiagnostics(context.filesDir) }
        pendingSentenceDiag.clear()
        startTranslationWorker(buffer, accumulator)
        // The ASR worker gets its OWN thread, ranked above ordinary CPU work, instead of a shared
        // [Dispatchers.Default] slot. On a 4K stream the measured failure was not throughput but
        // DESCHEDULING: `dec 823/2839ms` (one model chunk averaging 823 ms, the worst taking 2.84 s)
        // with `rtf 0.63`, so the decoder had ~37 % of real time to spare and still overran the whole
        // 16-frame (2.56 s) queue a few times a session — `feed 2840ms` matches the worst DECODE to
        // the millisecond, so the feed was blocked inside one chunk, not queueing more of them
        // (`drn 1`). Every overrun discards the ENTIRE backlog (`drop` == 16 × `jmp`, to the frame),
        // so those few events were the whole residual loss. [ASR_THREAD_PRIORITY] is what stops
        // whoever was holding the cores for ~2 s at a time from doing it again.
        val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "AiSubtitleAsr") }
            .asCoroutineDispatcher()
        workerDispatcher = dispatcher
        worker = scope.launch(dispatcher) {
            // Ranks the CALLING thread, so it must run here rather than on the shared scope; a
            // refusal is not fatal (the pipeline simply keeps the priority it had), and letting it
            // escape would kill the worker and with it every caption, so it is swallowed and logged.
            runCatching { Process.setThreadPriority(ASR_THREAD_PRIORITY) }
                .onFailure { Log.w(tag, "ASR worker stayed at default priority", it) }
            consumePcm(recognizer, fresh, segmenter, buffer)
        }
        // 1 Hz diagnostics/playing-time ticker. Feed-driven pushStats only happens while audio
        // chunks flow — during a silent or starved stretch the overlay would otherwise freeze; this
        // keeps counters live and accumulates the playing-time denominator independently of feed.
        statsJob = scope.launch {
            var tick = lastStatsTickMs
            // Buffering-episode edge state for this ticker; see the accumulation below.
            var wasBuffering = false
            var bufferingEpisodeStart = 0L
            while (true) {
                delay(STATS_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val delta = now - tick
                tick = now
                if (delta in 1..2_999L) {
                    // Read the engine that is ACTUALLY making sound, not necessarily the mpv player:
                    // Live TV promotes the ExoPlayer [tv.own.owntv.player.LivePreviewEngine], which
                    // never touches [OwnTVPlayer]'s own isPlaying/buffering, so asking the player
                    // directly reported `play 0` (and `buf 0s`) through a whole live session —
                    // aud/play and play/wall both unreadable. [PlaybackSession.activeEngine] is the
                    // one place the app records which engine owns the speaker; fall back to the mpv
                    // player only while nothing is attached.
                    val engine: PlaybackEngine? = playbackSession.activeEngine.value
                    val isPlaying = engine?.isPlaying?.value ?: player.isPlaying.value
                    val isBuffering = engine?.buffering?.value ?: player.buffering.value
                    if (isPlaying) playingMsAccum += delta
                    // Counting buffering separately (not as "idle") is the whole point: it is the
                    // one idle reason that is the network's fault rather than the viewer's.
                    if (isBuffering) {
                        bufferingMsAccum += delta
                        // Episodes are delimited by the ticker, so a contiguous buffer is counted
                        // once no matter its length; the max is the longest single stretch.
                        if (!wasBuffering) {
                            bufferingEpisodes++
                            bufferingEpisodeStart = now
                        }
                        bufferingMaxMs = maxOf(bufferingMaxMs, now - bufferingEpisodeStart)
                        wasBuffering = true
                    } else {
                        wasBuffering = false
                    }
                }
                pushStats()
            }
        }
        // Remember the engine that just proved usable so later launches skip the detection dance.
        if (useStreaming && streaming != null) {
            scope.launch { configStore.setStreamingModelsReady(configStore.readinessSnapshot() + streaming.language) }
        }
        // Stream the better engine in when one exists for this language but is not on disk yet;
        // Whisper stays live until the download finishes, then the pipeline restarts onto it.
        // English is built in, so there is nothing to fetch for it.
        if (streaming != null && !useStreaming) ensureStreamingModel(cfg)
    }

    /**
     * Non-blocking bounded hand-off from the ExoPlayer audio thread (never suspends, never throws).
     *
     * Resamples the decoded chunk to 16 kHz once and slices it into 160 ms frames, so the recognizer
     * decodes continuously instead of buffering a giant PCM block and "catching up" only when it all
     * arrives (the stale-batch behaviour that made captions lag). When the recognizer falls behind
     * real time, [RealtimePcmQueue] discards the WHOLE backlog and bumps its generation rather than
     * evicting one frame at a time — see that class for why single-frame eviction produced
     * time-compressed, unrecognisable audio.
     *
     * This is also the tap's audio-thread cost probe: the resampler's anti-alias FIR runs *here*, on
     * the sink thread, so the wall-clock this takes is what decides whether the filter is straining
     * the renderer (see [tapLastUs]/[tapDutyPercent]).
     */
    private fun sendPcm(
        queue: RealtimePcmQueue,
        resampler: StreamResampler16k,
        slicer: FrameSlicer,
        samples: ShortArray,
        sampleRate: Int,
    ) {
        // Time the whole hand-off, not just the FIR: this is what the sink thread actually pays before
        // it can return to the renderer. Nanos, because a chunk's cost is well under the 1 ms
        // [SystemClock.elapsedRealtime] resolves.
        val t0 = System.nanoTime()
        // Capture time for every frame this callback slices: audio delivered to the tap NOW is the
        // start of the end-to-end latency, which publication measures itself against (see
        // [PipelineStats.endToEndMs]). Stamped before the resampler so the tap's own cost is inside it.
        val capturedAtMs = SystemClock.elapsedRealtime()
        val resampled = resampler.push(samples, sampleRate)
        fedSamples16k += resampled.size
        // The audio reaching the tap now has not been heard yet: the sink is holding it back by the
        // measured amount (see LiveAudioDelay), which is the budget recognition and translation get.
        //
        // Each frame is stamped from its OWN place in the audio, not from the chunk that completed it.
        // The slicer emits a frame the moment 160 ms have accumulated — up to a frame and a half
        // before the chunk that fed it ends — so "chunk end + one frame" (what this used to do) put
        // every cue 160-320 ms after its audio. That was the residual lag the viewer still saw on the
        // English line. Anchoring on the slicer's sample count makes the stamp exactly the instant
        // the frame's last sample reaches the speaker.
        val frames = slicer.push(resampled)
        val chunkEndPresentMs = presentationNowMs() + LiveAudioDelay.measuredMs
        // Present time of the slicer's first sample; recomputed per chunk (rather than fixed once) so a
        // resampler that returns a sample more or less than the exact ratio cannot skew it over time.
        val audioStartPresentMs =
            chunkEndPresentMs - (slicer.pushedSamples / 16.0).roundToLong()
        val firstFrameIndex = slicer.emittedFrames - frames.size
        for ((k, frame) in frames.withIndex()) {
            val startMs = audioStartPresentMs + (firstFrameIndex + k) * FRAME_MS
            queue.offer(frame, capturedAtMs, startMs)
        }
        val costUs = (System.nanoTime() - t0) / 1_000L
        tapLastUs = costUs
        if (costUs > tapMaxUs) tapMaxUs = costUs
        // The chunk's own real-time span is the budget this work must stay far under; the worst ratio
        // of the session is the one number that answers "is the filter straining the audio thread?".
        if (samples.isNotEmpty() && sampleRate > 0) {
            val spanUs = samples.size.toLong() * 1_000_000L / sampleRate
            if (spanUs > 0L) {
                val duty = (costUs * 100L / spanUs).toInt()
                if (duty > tapDutyPercent) tapDutyPercent = duty
            }
        }
        // Count only — deliberately NO logging and NO stats rebuild on this thread. This runs inline
        // on the ExoPlayer audio sink thread, and both of those are exactly the work that cannot run
        // there: a formatted Log.i can block when logd is contended, and [pushStats] builds the whole
        // diagnostics line and allocates a PipelineStats (GC) — on a chunk whose own real-time span
        // is tens of ms that is the measured `tap max 49.6 ms / 640 %`, i.e. the renderer held for
        // 6× the audio it just produced, which is the condition the sink underruns in. The 1 Hz
        // ticker publishes the same counters (including [tapLastUs]/[tapDutyPercent]) off the audio
        // thread, and the ASR worker logs the capture cadence, so nothing is lost by keeping this
        // thread to the resample + offer it exists for.
        fedChunks++
    }

    /**
     * Splits a continuous 16 kHz PCM stream into fixed-size frames (160 ms / [frameSize] samples),
     * carrying the sub-frame tail between calls. Used exclusively from the audio-tap thread via a
     * per-session instance, so it needs no synchronisation.
     */
    private class FrameSlicer(private val frameSize: Int) {
        // The partial frame carried between calls lives in ONE fixed buffer with a fill count, in place
        // of the old `pending + samples` concatenation plus a `copyOfRange` tail. Those allocated a
        // fresh whole-backlog array AND a fresh tail array on EVERY audio callback: with ~32 ms of
        // audio per callback against a 160 ms frame that was ~9 KB of garbage per callback (a frame's
        // own 5 KB on top), ~31 callbacks/s, ~120 MB across a seven-minute session — the largest
        // single allocation source in the pipeline, and it ran on the renderer's own thread (the `gc`
        // pair on the diagnostics line read 26 s of collection time in that window).
        private val pending = ShortArray(frameSize)
        private var pendingCount = 0

        /**
         * 16 kHz samples ever pushed, and complete frames ever emitted.
         *
         * The pair is what lets the tap stamp a frame with the instant its OWN last sample is heard.
         * A frame is emitted the moment 160 ms have accumulated, which is up to a frame and a half
         * before the chunk that completed it ends; stamping from the chunk instead (what the tap used
         * to do) put every caption 160-320 ms late, which is exactly the residual "the line still
         * trails the voice" — audible on the English line as well as the Chinese.
         */
        var pushedSamples = 0L
            private set
        var emittedFrames = 0L
            private set

        /** Emits the complete frames contained in [samples] + any leftover; keeps the tail for next call. */
        fun push(samples: ShortArray): List<ShortArray> {
            if (samples.isEmpty()) return emptyList()
            pushedSamples += samples.size
            val frames = ArrayList<ShortArray>(samples.size / frameSize + 1)
            var off = 0
            while (off < samples.size) {
                val take = minOf(frameSize - pendingCount, samples.size - off)
                System.arraycopy(samples, off, pending, pendingCount, take)
                pendingCount += take
                off += take
                if (pendingCount == frameSize) {
                    // The one allocation left, and an unavoidable one: the queue takes ownership of the
                    // frame and holds it until the worker consumes it, so it cannot be a shared buffer.
                    frames.add(pending.copyOf())
                    emittedFrames++
                    pendingCount = 0
                }
            }
            return frames
        }
    }

    /**
     * If the source language has a downloadable streaming model and it is not on disk, download it
     * in the background; on success, restart the pipeline so the recognizer switches to streaming.
     */
    private fun ensureStreamingModel(cfg: AiSubtitleConfig) {
        val model = StreamingModelCatalog.forLanguage(cfg.sourceLanguage) ?: return
        if (modelStore.isDownloaded(model)) return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            Log.i(tag, "downloading streaming model ${model.language} (${model.sizeBytes} bytes)")
            val ok = modelStore.ensureDownloaded(model)
            Log.i(tag, "streaming model ${model.language} download finished: $ok")
            // Relinquish this job before restart: stopPipeline() cancels downloadJob, and that would
            // otherwise cancel the very coroutine we are running in.
            val self = coroutineContext[Job]
            if (downloadJob === self) downloadJob = null
            if (ok && effective.value.enabled) {
                // Stop and restart so WhisperFactory now resolves the streaming engine. Do it inside
                // the pipeline mutex and re-check enabled re-entrantly: if the user toggled the switch
                // (or the channel changed) while we were mid-download, effective may now be disabled —
                // forcing a restart here would arm a "ghost" pipeline (occupied tap + leaked recognizer)
                // that nothing would ever tear down.
                pipelineMutex.withLock {
                    if (effective.value.enabled) {
                        if (!tapArmed) startPipelineLocked(effective.value) else {
                            stopPipelineLocked()
                            startPipelineLocked(effective.value)
                        }
                    }
                }
            } else if (!ok && tapArmed) {
                // The Whisper fallback stays live underneath; keep the "tiny … :working" line (the
                // download only failed, recognition did not). startPipelineLocked re-establishes it.
                refreshStatus()
            }
        }
    }

    private suspend fun stopPipeline() = pipelineMutex.withLock {
        stopPipelineLocked()
    }

    /** [stopPipeline] body, run with [pipelineMutex] held. */
    private suspend fun stopPipelineLocked() {
        Log.i(tag, "stopping pipeline")
        // The counters only say HOW MANY jumps happened; this says WHEN and WHY, which is what tells
        // an opening warm-up burst apart from a stall later in the session (see [JumpEvent]).
        frameQueue.recentJumps.takeIf { it.isNotEmpty() }?.let { jumps ->
            Log.i(tag, "queue jumps: " + jumps.joinToString(", ") { "${it.cause.name.lowercase()}@${it.atMs}" })
        }
        // The whole teardown is cancellation-proof: [effective.collectLatest] cancels this
        // coroutine on every config change (a fast channel switch / toggle fires several in a row),
        // and a cancellation landing on worker.join() would skip recognizer.close() — leaving the
        // tap armed with no worker (stale subtitle state) and, if a feed() was still in flight,
        // releasing the native recognizer underneath it (use-after-free SIGSEGV, the idle→recognition
        // crash). join() below is a long, blocking native-call wait that cancellation cannot
        // interrupt; running it inside [NonCancellable] guarantees the in-flight feed finishes and
        // the native objects are released before any of these fields are reset. The block only ever
        // delays a stop by however long the worker takes to finish (bounded by the translate timeout).
        withContext(NonCancellable) {
            downloadJob?.cancel()
            downloadJob = null
            statsJob?.cancel()
            statsJob = null
            timelineJob?.cancel()
            timelineJob = null
            captionTimeline.reset()
            prevSourceWords = emptyList()
            pausedAccumMs = 0L
            latestHeardText = ""
            worker?.cancel()
            worker?.join()
            worker = null
            // The dedicated thread belongs to this session alone, so release it here: a channel
            // switch re-creates it, and without the close each switch would leak one ranked thread
            // for the life of the process.
            workerDispatcher?.close()
            workerDispatcher = null
            // A session that borrowed the resident English instance must not release it — reset() it
            // instead so its internal stream is clean, and keep it resident for the next session.
            if (borrowedResident) {
                runCatching { recognizer?.reset() }
            } else {
                recognizer?.close()
            }
            recognizer = null
            borrowedResident = false
            startedLanguage = null
            if (tapArmed) {
                PlaybackAudioTap.stop()
                tapArmed = false
            }
            _asrText.value = null
            fedChunks = 0; blankChunks = 0; partialChunks = 0; recognizedWords = 0; committedSegments = 0
            recognizerFeedCalls = 0; lastPartialAtMs = 0L; lastFeedMs = 0L; lastDecodeMs = -1L; isReadyHits = 0L
            feedMaxMs = 0L; feedStallCount = 0L
            translationTrips = 0; translationTotalMs = 0L; translationTtftTotalMs = 0L
            translationTtftTrips = 0; lastChineseAtMs = 0L
            translationRequests = 0L; translationFailures = 0L; translationCancels = 0L
            translationTimeouts = 0L; translationHttpErrors = 0L; translationLastHttpCode = 0
            translationSkipCount = 0L; closingFlushesAtTurnover = 0L
        skipShort = 0L; skipDuplicate = 0L; skipUnstable = 0L; skipLowConfidence = 0L; skipStale = 0L
            committedSegments = 0L; committedClosings = 0L; committedLive = 0L
            fedSamples16k = 0L; lastRms = 0; showedFirstPartial = false; sessionStartMs = 0L
            tapLastUs = 0L; tapMaxUs = 0L; tapDutyPercent = 0
            consumerBusyMs = 0L
            endToEndMs = 0L; endToEndMaxMs = 0L
            latencyAsrMaxMs = 0L; latencyPrepMaxMs = 0L; latencyEntityMaxMs = 0L
            latencyReqMaxMs = 0L; latencyLlmMaxMs = 0L; latencyUiMaxMs = 0L
            playingMsAccum = 0L; bufferingMsAccum = 0L; lastStatsTickMs = 0L
            bufferingEpisodes = 0L; bufferingMaxMs = 0L; sceneSwitches = 0L
            // Back to the resident engine's idle state: ready if it is still resident, idle otherwise.
            statusBase = if (preloadedEnglish != null)
                workingBase(StreamingModelCatalog.BUILT_IN_ENGLISH_LANGUAGE, true).replace(":working", ":ready")
            else "idle"
            refreshStatus()
            // Stop the translation worker and invalidate its session BEFORE clearing the pair: replace
            // the buffer first so the worker's `liveBuffer !== buffer` guard trips even if it is
            // mid-round-trip, then the stale pair write is dropped instead of re-publishing after the
            // clear below.
            translateWorker?.cancel()
            translateWorker = null
            inFlightTranslation = null
            liveBuffer = LiveTranslationBuffer()
            activeEpoch = 0
            closedFlushPending = false
            // Session-scoped translation context: a fresh channel must not inherit the previous one's
            // sport cue or sentences.
            appliedSceneLabel = ""
            appliedScene = null
            appliedTeamsScope = emptySet()
            appliedHotwordCount = 0
            appliedTeamCount = 0
            scopingDiagnostics = "off"
            // Flush the per-sentence diagnostics before dropping the sink — what has been written by
            // the sentence log is what the session is diagnosed from, and the ranked unknown-name
            // table is only useful once its counts are complete.
            diagnostics?.let { d ->
                d.flush()
                Log.i(tag, "diagnostics: ${d.sentencesFile.absolutePath} · ${d.unknownNamesFile.absolutePath}")
            }
            diagnostics = null
            pendingSentenceDiag.clear()
            recentLines.clear()
            _pair.value = null
        }
    }

    private suspend fun consumePcm(
        reco: StreamingWhisper,
        queue: RealtimePcmQueue,
        segmenter: CaptionSegmenter,
        buffer: LiveTranslationBuffer,
    ) {
        // Bound to the queue of this session only; terminated by worker cancellation on stop.
        var audioDiag = 0L
        // Generation of the last processed frame; a difference means the producer discarded a whole
        // stale backlog (see [RealtimePcmQueue]) and this frame's audio is discontinuous.
        var lastGeneration = 0L
        // The previous partial's words within the current utterance: a streaming engine re-emits the
        // WHOLE hypothesis every feed, so both the newly-recognised-word count and the scene detector
        // need only the GROWTH — but the words themselves, not just a count, so a revised word is
        // diffed out by longest common prefix instead of re-adding everything after it.
        var prevWords: List<String> = emptyList()
        // Word count of the previous partial, for [recognizedWords]: a session-local so the count does
        // not carry across channels, reset together with [prevWords] at every utterance boundary.
        var prevPartialWords = 0
        // One gain control per session: the capture level is a property of the stream, so its state
        // must not carry from one channel to the next (see [AsrGain]).
        val gain = AsrGain()
        // One scene detector per session: it reads the SAME recognised words and decides which sport —
        // if any — this channel is showing, so the decoder's bias can be switched to match (see
        // [SportSceneDetector]). Per-session so a fresh channel always starts unbiased, but the scene
        // is re-scored continuously and CAN change mid-session — one channel may show several sports —
        // and [appliedSport] tracks what is currently on the decoder so only a real change rebuilds it.
        val sceneDetector = SportSceneDetector()
        // Session name memory for the cross-sentence half of [SubtitleGlossary.resolveSurnames]:
        // commentary introduces a player in full and then says only his surname for minutes, so the
        // full name heard earlier is what tells two same-surnamed players apart. Owned by the ASR
        // worker alone, so no synchronisation.
        val nameMemory = SessionNameMemory()
        // What the detector currently believes, scored on every new word but only APPLIED to the
        // decoder at a sentence boundary (see the feed loop) — rebuilding the stream drops the
        // sentence being decoded and the encoder context behind it.
        var detectedSport: SubtitleGlossary.Sport? = null
        // What is actually on the decoder right now, so only a real change rebuilds it.
        var appliedSport: SubtitleGlossary.Sport? = null
        // The clubs the scope carries, and the ones actually on the decoder. Tracked beside the sport
        // because scoping is a SECOND, independent reason to rebuild: a club being named ADDS its
        // squad to the decoder's vocabulary without the sport changing, and a rebuild that only
        // compared sports would leave those surnames off the decoder for the rest of the match.
        var detectedTeams: Set<SubtitleGlossary.Team> = emptySet()
        var appliedTeams: Set<SubtitleGlossary.Team> = emptySet()
        for (frame in queue.channel) {
            // Time this frame's processing so the worker's duty cycle is measurable (see
            // [PipelineStats.consumerBusyPercent]). The loop's own suspension while the queue is
            // empty is time this does NOT count, which is exactly the idle reading wanted.
            val frameStartMs = SystemClock.elapsedRealtime()
            queue.markConsumed()
            if (frame.generation != lastGeneration) {
                // The samples here do NOT continue the previous frames: the decoder fell behind live
                // audio and the queue jumped to the newest position (or the renderer flushed after a
                // seek/live reposition). Feeding a streaming transducer across that gap makes it
                // emit garbage on top of the stale encoder state for a long stretch, so restart the
                // recognizer stream AND the segmenter at the new position. resetStream keeps this
                // session's decode-health counters; between two generation boundaries every frame
                // is contiguous 16 kHz PCM.
                val delta = frame.generation - lastGeneration
                lastGeneration = frame.generation
                runCatching { reco.resetStream() }
                segmenter.reset()
                markNewSentence(segmenter.sentenceEpoch)
                prevPartialWords = 0
                // The recognizer stream restarted above, so the next partial is a fresh hypothesis:
                // forget the previous words or the diff would treat a repeated phrase as newly heard.
                // The scene detector keeps its rolling window — a live reposition does not change
                // which match is on air, and it may be a different sport on the same channel.
                prevWords = emptyList()
                // The look-ahead the translator can see belongs to the discarded timeline as well.
                latestHeardText = ""
                // The session's name memory describes speech on the timeline that was just thrown
                // away; keeping it could re-attribute a surname to a player from the old position.
                nameMemory.clear()
                // The sentences the segmenter had closed belong to the discarded timeline: their
                // Chinese can never be published, so drop their diagnostic rows rather than leave
                // them to be drained by an epoch that will never come.
                pendingSentenceDiag.clear()
                Log.w(
                    tag,
                    "audio: jumped to live position (generation +$delta," +
                        " ${queue.droppedFrames.get()} frames dropped total); recognizer+segmenter reset",
                )
            }
            val pcm = frame.samples
            // Measure loudness of EVERY frame (cheap): lastRms backs the on-screen diagnostics so a
            // silent tap is visible without logcat; the log itself stays on the throttled cadence.
            var peak = 0
            var sumSq = 0L
            for (i in pcm.indices) {
                val v = pcm[i].toInt()
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                sumSq += v.toLong() * v
            }
            lastRms = kotlin.math.sqrt(sumSq / pcm.size.toDouble()).toInt()
            // Log the first few chunks and then on the throttled cadence. If the user can hear
            // playback but these stay near 0, the tap is delivering silence and recognition can
            // never yield a word — that forks the bug into "capture is silent" vs "capture has
            // audio but the engine decodes nothing".
            if (audioDiag < 5 || (audioDiag % DIAG_DECIMATION) == 0L) {
                Log.i(tag, "capture: chunk#${audioDiag} n=${pcm.size} rms=$lastRms peak=$peak")
            }
            audioDiag++
            // Feed health fed from the recognizer's own meters (Kroko instruments them; Whisper's
            // default is all-zero). Sync once per feed so the chip's isReadyHit reflects live decode.
            val meters = reco.meters
            recognizerFeedCalls++
            isReadyHits = meters.isReadyHits
            if (meters.lastDecodeMs >= 0L) lastDecodeMs = meters.lastDecodeMs
            // Every recognizer gets the 16 kHz frames the tap already sliced to (160 ms each), so the
            // engine decodes continuously in small increments instead of being handed one giant PCM
            // block per sink callback (which it would buffer and decode in one stale batch). The frame
            // is normalised to speech level first: a broadcast laid out in 6/8 channels reaches this
            // point well below what the model was trained on (measured `rms 299` ≈ −41 dBFS), and a
            // transducer handed a signal that quiet emits blank for most frames and drops words from
            // the rest — which is what the translator then had to work from. [lastRms] above stays the
            // level as captured, so the meter keeps showing the raw capture and the gain that fixed it.
            val fed = gain.apply(pcm)
            lastGain = gain.current
            val feedStartMs = SystemClock.elapsedRealtime()
            val out = runCatching {
                reco.feed(fed, PcmResampler.TARGET_RATE)
            }.getOrElse { e ->
                // A recognizer that throws mid-stream must not silently kill the worker: the tap
                // would stay armed feeding a channel nobody consumes, the status would freeze at
                // "recognition" with no captions, and the recognizer would never be released. Report
                // and tear the pipeline down cleanly (a Java-level VAD/recognizer error; a native
                // SIGSEGV is a process fault and cannot be caught here).
                Log.w(tag, "recognizer feed failed; stopping pipeline", e)
                _status.value = "error: recognizer failed"
                scope.launch { stopPipeline() }
                return
            }
            // Wall-clock the recognizer spent in this feed (float conversion + native decode). High/
            // climbing values mean the decoder is CPU-bound and can't keep up with live audio.
            lastFeedMs = SystemClock.elapsedRealtime() - feedStartMs
            if (lastFeedMs > feedMaxMs) feedMaxMs = lastFeedMs
            // A feed this slow is a stall, not a decode: it ran longer than the ENTIRE input window, so
            // the producer filled every slot and [RealtimePcmQueue.offer] had to discard the backlog.
            // The window is the base capacity PLUS the hold-back's depth, so with captions on a 3 s
            // decode is no longer an overrun — using the base figure there counted the budget itself as
            // a fault, and `stall` stopped being comparable with `jmp` (which is what it is for: `stall`
            // ≈ `gc` means the collector, `stall` ≫ `gc` means this thread was descheduled).
            val feedWindowMs = (PCM_CHANNEL_CAPACITY + (liveDelayMs / FRAME_MS).toInt()) * FRAME_MS
            if (lastFeedMs > feedWindowMs) feedStallCount++
            // Diagnostics: report the first recognised text, then silently. If recognition returns only
            // blank text for a long stretch, surface that once so logcat shows where the chain breaks.
            if (out.text.isBlank() && (++blankChunks % DIAG_DECIMATION) == 0L) {
                Log.w(tag, "recognizer: blank result #$blankChunks (audio fed, no words decoded yet)")
                pushStats()
            }
            if (!out.text.isBlank()) {
                partialChunks++
                lastPartialAtMs = SystemClock.elapsedRealtime()
                // End of the ASR stage: the decoder has returned a hypothesis. Stamped here, before
                // the correction/segmentation below, so the stage split attributes the decode and its
                // input queue to ASR rather than to the translation worker downstream.
                val readyAtMs = lastPartialAtMs
                // Exact word timing for this hypothesis. [CompiledResult.wordEndAgesMs] carries, per
                // word, how long ago its audio ENDED relative to the last sample this feed supplied —
                // the recognizer's own token timestamps, not an estimate. A caption line is then due
                // at the moment its last word has actually been heard instead of at the 160 ms audio
                // frame that happened to carry it, which is what removes the frame-sized band.
                // Empty when the engine reports no token times, or when a name correction changed the
                // word count: both fall back to the frame anchor, i.e. the previous behaviour.
                val fedEndPresentMs = frame.presentAtMs + FRAME_MS
                val hypWords = CaptionSegmenter.tokenize(out.text)
                val wordAges = out.wordEndAgesMs
                val exactWordTimes = wordAges.isNotEmpty() && wordAges.size == hypWords.size
                // Repair the recogniser's slips on the domain names the glossary knows (Trunk →
                // Trump) BEFORE the words are segmented or translated, so both the English line and
                // the Chinese built from it show the same, right name. A slip left in place survives
                // on both lines and no translation-side rewrite can recover it.
                //
                // The hand-written table covers the marquee names; the squad-correction pass then
                // repairs a near-miss of the SCOPED fixture's own surnames (`Salo` → `Salah`), which
                // is what a newcomer the decoder was never biased with needs. It reads the same scope
                // the decoder is currently biased with, so it can only ever choose among the two
                // squads on the pitch (see SubtitleGlossary.correctNames).
                val entityStartMs = SystemClock.elapsedRealtime()
                val corrected = SubtitleGlossary.correctNames(
                    SubtitleGlossary.correct(out.text),
                    appliedTeamsScope,
                )
                // Cross-sentence player evidence, gathered before the surname pass so a whole name in
                // THIS text can already resolve a bare surname elsewhere in it: remember the whole
                // names just heard, then resolve with the three kinds of evidence (this sentence's
                // club, a recent whole name, a recent club mention).
                nameMemory.remember(corrected, appliedTeamsScope, readyAtMs)
                val text = SubtitleGlossary.resolveSurnames(
                    corrected,
                    appliedTeamsScope,
                    recentClubs = nameMemory.recentClubs(readyAtMs),
                    rememberedNames = nameMemory.recentNames(readyAtMs),
                )
                // The mention in this text is now part of the recent commentary for the next one.
                nameMemory.noteClubs(text, readyAtMs)
                // The translation worker reads this to see what the commentary said AFTER the window
                // it is translating (see [latestHeardText]); with the live hold-back it is usually
                // seconds of speech ahead of that window.
                latestHeardText = text
                // Priced separately from the decode: the entity resolver is a name-table pass over the
                // fixture's scoped squads, so its cost must be attributable on its own rather than
                // buried in the ASR stage ([PipelineStats.latencyEntityMaxMs]). Cheap enough that the
                // exact per-partial read matters only when a pathological table makes it not.
                val entityMs = SystemClock.elapsedRealtime() - entityStartMs
                // Count only words that are NEW vs the previous partial of this utterance, not the
                // whole re-emitted hypothesis (see prevPartialWords).
                // Split the hypothesis ONCE. The word count and the scene detector's diff want the
                // same token list, and the old code ran two `split(Regex("\\s+"))` per recognised
                // partial — two fresh `Pattern.compile` calls and two token arrays for one string.
                val currentWords = text.trim().split(WORD_SPLIT)
                val wordCount = currentWords.size
                recognizedWords += (wordCount - prevPartialWords).coerceAtLeast(0)
                prevPartialWords = wordCount
                // The scene detector is the one consumer that needs the actual new words, not just
                // their count: it scores whole terms, so a revised word must be diffed out by longest
                // common prefix rather than re-added, or a re-emitted hypothesis would count the same
                // `penalty` dozens of times and open a football scene off a single mention.
                val newWords = if (prevWords.isEmpty()) currentWords else {
                    val common = currentWords.zip(prevWords).takeWhile { (a, b) -> a == b }.size
                    currentWords.drop(common)
                }
                prevWords = currentWords
                // Forward-looking diagnostics: every proper-noun-shaped word the glossary does NOT
                // carry is a name worth adding next (see [AiSubtitleDiagnostics]). Counted from the
                // growth rather than the re-emitted hypothesis so one name is not counted per partial.
                diagnostics?.let { sink -> newWords.forEach { sink.noteWord(it) } }
                // Feed ONLY the growth to the detector; a non-blank slice may still yield no scene
                // change. The decision it returns is NOT applied here: rebuilding the decoder's bias
                // throws away the sentence being decoded and the encoder context behind it, so doing
                // it mid-sentence is heard as a stutter and re-opens the same sentence's Chinese. It
                // is applied a few lines below, once this partial has landed on a sentence boundary.
                if (newWords.isNotEmpty()) {
                    detectedSport = sceneDetector.observe(newWords.joinToString(" "))
                    // The club scope is read from the same observe: the detector ranks it on the
                    // mentions seen so far, so they are used together at the boundary below.
                    detectedTeams = sceneDetector.teams
                    // Read beside the verdict, from the same observe: the scope's raw state is what
                    // makes a `t0` diagnosable from the status line alone (see [scopingDiagnostics]).
                    scopingDiagnostics = sceneDetector.scopeSummary()
                }
                if (!showedFirstPartial) {
                    showedFirstPartial = true
                    Log.i(tag, "recognizer: first partial (len=${out.text.length}) '${takeDiag(out.text)}'")
                    pushStats()
                }
                // The ever-growing hypothesis is NOT a caption by itself — hand it to the segmenter,
                // which is the only thing that knows where one sentence ends and the next begins. It
                // reports whether this partial opened a new sentence: when it did, the round trips
                // still in flight for the previous sentence are invalidated so they cannot be
                // published over the new line (markNewSentence); the Chinese already on screen stays
                // until its replacement streams, so the caption does not blank out per sentence.
                // The bias in force while this partial was decoded, snapshotted before the boundary switch
                // below can replace it: a sentence's diagnostic row must report the scene/teams it was
                // actually decoded under, not the next sentence's.
                val spokenSceneLabel = appliedSceneLabel
                val spokenTeams = appliedTeams.joinToString("+") { it.name.lowercase() }
                val startedNewSentence = segmenter.onPartial(text)
                // A sentence closed by terminal punctuation or the word cap still owes its Chinese to
                // the words withheld while it was live: only a `final` request releases them, and only
                // under the epoch the sentence actually had. Handing the whole window over HERE — before
                // markNewSentence moves the epoch on — is what stops every punctuated sentence losing
                // its last one or two words, which in commentary is regularly the point of the sentence
                // ("…and that is the equaliser." did not translate "equaliser"). See
                // [CaptionSegmenter.takeClosedFinal] and [closedFlushPending].
                // Whole-sentence mode needs the engine's own word times: without them the sentence's
                // first word has no audio instant to be anchored on (only the frame boundary, which is
                // its END), and a line anchored there is a whole sentence late. Engines that report no
                // token clock (the offline Whisper fallback) therefore keep the per-word path even with
                // a delay set, which is the only behaviour that can still be on time for them.
                val sentenceMode = liveDelayMs > 0 && out.audioAgeMs > 0L
                // Present time of a sentence's FIRST word — the instant a whole-sentence (movie
                // style) line belongs on screen. Index i's onset is the end of word i-1, which the
                // recognizer reports exactly; a sentence opening at word 0 began where the stream did,
                // which is the fed audio's present time minus the audio fed since the reset.
                fun sentenceStartAt(index: Int): Long = when {
                    index > 0 && index - 1 < wordAges.size ->
                        fedEndPresentMs - wordAges[index - 1].ageMs()
                    out.audioAgeMs > 0L -> fedEndPresentMs - out.audioAgeMs
                    else -> frame.presentAtMs
                }
                val closingFlush = segmenter.takeClosedFinal()
                if (closingFlush != null) {
                    committedSegments++
                    committedClosings++
                    closedFlushPending = true
                    // Hold the closed sentence's ASR-side facts for the translation worker: the raw
                    // recognition (the hypothesis tail matched to the corrected length) and the
                    // corrected text it was segmented from, beside the bias it was decoded under.
                    pendingSentenceDiag[closingFlush.epoch] = PendingSentence(
                        raw = rawSentenceTail(out.text, closingFlush.text),
                        corrected = closingFlush.text,
                        scene = spokenSceneLabel,
                        teams = spokenTeams,
                    )
                    // Stamp the unit with the capture time of the frame that closed the sentence, so
                    // its publication can be timed end to end (see [PipelineStats.endToEndMs]).
                    // The sentence's own first word, not the partial that happened to close it: the
                    // whole line goes up when the sentence BEGINS to be heard, and its Chinese with it.
                    val sentenceAt = sentenceStartAt(closingFlush.sourceStartIndex)
                    if (sentenceMode) captionTimeline.offerSource(sentenceAt, closingFlush.text)
                    val flushUnit = closingFlush.copy(
                        capturedAtMs = frame.capturedAtMs,
                        readyAtMs = readyAtMs,
                        offeredAtMs = SystemClock.elapsedRealtime(),
                        entityMs = entityMs,
                        presentAtMs = sentenceAt,
                    )
                    buffer.offer(flushUnit)
                    noteAsrStage(flushUnit)
                }
                if (startedNewSentence) markNewSentence(segmenter.sentenceEpoch)
                // The decoder's bias is rebuilt only now, on a sentence boundary. [SportSceneDetector]
                // was asked on every new word, but applying it re-creates the recognizer's stream —
                // dropping the sentence in flight and the encoder context with it — so a mid-sentence
                // switch was heard as a stutter and re-opened the sentence's Chinese.
                // Scoping is a second, independent reason to rebuild: the sport stays football while a
                // club is named and the list grows from the scene vocabulary to that vocabulary plus
                // the club's squad, and comparing sports alone would leave those surnames off the
                // decoder for the rest of the match.
                if ((startedNewSentence || out.finished) &&
                    (detectedSport != appliedSport || detectedTeams != appliedTeams)
                ) {
                    appliedSport = detectedSport
                    appliedTeams = detectedTeams
                    // The scoped set the translation worker reads to pin the squads' Chinese
                    // renderings; written beside the decoder's bias so the two never disagree about
                    // which fixture is on the pitch.
                    appliedTeamsScope = detectedTeams
                    sceneSwitches++
                    // The translation worker reads this (volatile) on the next sentence's first
                    // request, so the domain cue rides the same sentence boundary the bias switch does.
                    appliedSceneLabel = SubtitleGlossary.sceneLabel(detectedSport)
                    appliedScene = detectedSport
                    val hotwords = SubtitleGlossary.recognitionHotwords(detectedSport, detectedTeams)
                    appliedHotwordCount = if (hotwords.isEmpty()) 0 else hotwords.count { it == '/' } + 1
                    appliedTeamCount = detectedTeams.size
                    reco.setHotwords(hotwords)
                    Log.i(
                        tag,
                        "scene: bias -> ${detectedSport?.name ?: "none"}" +
                            (if (detectedTeams.isEmpty()) ""
                            else " scoped to ${detectedTeams.joinToString("+") { it.name.lowercase() }}") +
                            " ($appliedHotwordCount hotwords)",
                    )
                }
                // The English line renders the SENTENCE being spoken, not the whole utterance the
                // recognizer keeps growing until its 20 s endpoint rule. That window is exactly what
                // the translator is handed (see below), so the two caption lines always describe the
                // same words instead of the Chinese looking seconds behind a line that has already run
                // on into the next sentence.
                //
                // While a closing flush is outstanding the line stays on the sentence being flushed —
                // that flush is published against it, and showing the next sentence beside the previous
                // sentence's Chinese would read as a wrong translation. The flush is the sentence's
                // last few words, so the pause is short.
                var stableLine: SourceLine? = null
                // The instant the English line is due. Reused as the bilingual pair's cue time and as
                // the anchor of its English half (see [TranslationUnit.displayText]) so both lines
                // change on exactly the same frame — anchoring the pair on the newest spoken word
                // instead is what made the Chinese/双语行 lag the audio by one word.
                var lineAtMs = frame.presentAtMs
                if (sentenceMode) {
                    // SENTENCE MODE: the line is never the words spoken so far. It is published once,
                    // whole, when the sentence completes (punctuation/word-cap close below, or the
                    // endpoint), and it waits until that sentence's own first word is heard. That is
                    // what makes it a movie caption instead of a live transcript: complete text, one
                    // change per sentence, and words that have not been spoken yet already readable.
                } else if (closedFlushPending) {
                    if (closingFlush != null) captionTimeline.offerSource(frame.presentAtMs, closingFlush.text)
                } else {
                    // Which words the line shows depends on whether a budget exists, and that choice
                    // is the difference between "one word stale" and "current" (see below).
                    segmenter.currentSentenceText()?.let { text ->
                        // With a hold-back the recognizer runs seconds ahead of the speaker, so a
                        // revision of the newest word lands ~7 s before its cue is due — the pending
                        // cue is replaced before anyone can see it, and showing that word is safe.
                        // Withholding it unconditionally (the `govern` → `government` flicker, real
                        // when there is no budget) cost a whole word of CONTENT: the line always
                        // described speech one word old, which reads as a perceptible lag even though
                        // every cue was on time. Without a budget the old rule stays.
                        val line = if (liveDelayMs > 0) {
                            SourceLine(text.trim(), droppedFromEnd = 0)
                        } else {
                            stableSourceText(text)
                        }
                        if (line != null) {
                            stableLine = line
                            // Due at the boundary where the newest word begins: the line's last
                            // word ends exactly there when it was withheld, and begins exactly
                            // there when it was confirmed. The previous rule anchored the confirmed
                            // case at the END OF THE FED AUDIO instead, which trails the last word
                            // by however much look-ahead or silence the recognizer had already
                            // consumed — an overestimate the viewer sees as the line arriving late.
                            lineAtMs = when {
                                exactWordTimes && wordAges.size >= 2 ->
                                    fedEndPresentMs - wordAges[wordAges.size - 2].ageMs()
                                exactWordTimes -> fedEndPresentMs - wordAges.last().ageMs()
                                else -> frame.presentAtMs
                            }
                            captionTimeline.offerSource(lineAtMs, line.text)
                        }
                    }
                }
                // Hand the caption window to the translation worker — but only when it has actually
                // changed, so a slow gateway is never fed work that another round trip has already
                // superseded. The accumulator, not the segmenter, decides which part of the window
                // still needs a round trip. At an endpoint the window is forced over even when it did
                // not change, and is marked final so the sentence's last words — which the live
                // round trips held back as the most-revised ones — still get their Chinese instead of
                // being dropped when the sentence ends.
                //
                // A live offer is skipped while a closing flush is outstanding: the buffer is conflated,
                // so the next sentence's window would overwrite that flush before the worker could pick
                // it up and the tail would be lost anyway. The endpoint offer is skipped for the SAME
                // reason — a sentence that closed on this very partial has just put its flush in the
                // buffer, and offering the endpoint's window here would overwrite it. The flush wins:
                // it carries the finished sentence's own tail, where this window is only the one- or
                // two-word start of the sentence it opened.
                if (out.finished) {
                    if (!closedFlushPending) {
                        // The endpoint closes this sentence: in sentence mode the whole window goes up
                        // now, anchored where the sentence's first word is heard — including the words
                        // that were only just decoded, i.e. text the viewer has not heard yet.
                        val sentenceAt = sentenceStartAt(segmenter.sentenceStartIndex)
                        if (sentenceMode) {
                            segmenter.currentSentenceText()?.let { captionTimeline.offerSource(sentenceAt, it) }
                        }
                        segmenter.liveUnitForTranslation(force = true)?.let {
                            committedSegments++
                            committedLive++
                            // An endpoint-closed sentence is as final as a punctuated one, so it gets
                            // the same diagnostic row.
                            pendingSentenceDiag[segmenter.sentenceEpoch] = PendingSentence(
                                raw = rawSentenceTail(out.text, it),
                                corrected = it,
                                scene = spokenSceneLabel,
                                teams = spokenTeams,
                            )
                            val unit = TranslationUnit(
                                it,
                                segmenter.sentenceEpoch,
                                final = true,
                                capturedAtMs = frame.capturedAtMs,
                                readyAtMs = readyAtMs,
                                offeredAtMs = SystemClock.elapsedRealtime(),
                                entityMs = entityMs,
                                // In sentence mode the Chinese belongs on screen with the sentence's
                                // first word, not with its last: the two lines change together.
                                presentAtMs = if (sentenceMode) sentenceAt else lineAtMs,
                            )
                            buffer.offer(unit)
                            noteAsrStage(unit)
                        }
                    }
                } else if (!closedFlushPending && !sentenceMode) {
                    segmenter.liveUnitForTranslation(force = false)?.let {
                        committedSegments++
                        committedLive++
                        val unit = TranslationUnit(
                            it,
                            segmenter.sentenceEpoch,
                            final = false,
                            capturedAtMs = frame.capturedAtMs,
                            readyAtMs = readyAtMs,
                            offeredAtMs = SystemClock.elapsedRealtime(),
                            entityMs = entityMs,
                            // Exactly the English line's cue: same words on screen, same instant. A
                            // live unit's text also carries the newest (still-revisable) word, which
                            // the line must not show before it is spoken — that word is what
                            // [displayText] removes.
                            presentAtMs = lineAtMs,
                            displayText = stableLine?.text ?: it,
                        )
                        buffer.offer(unit)
                        noteAsrStage(unit)
                    }
                }
            }
            // The recognizer signalled an endpoint: the window above already carried its final
            // hypothesis, so flush() only marks that the NEXT partial opens a new sentence. The line
            // on screen (and the round trip still in flight for it) is deliberately left alone, so the
            // finished sentence keeps its Chinese through the pause and is replaced only when the new
            // sentence's words actually arrive.
            if (out.finished) {
                segmenter.flush()
                // The next partial belongs to a brand-new utterance after the recognizer reset.
                prevPartialWords = 0
                prevWords = emptyList()
                latestHeardText = ""
            }
            consumerBusyMs += (SystemClock.elapsedRealtime() - frameStartMs).coerceAtLeast(0L)
        }
    }

    /**
     * The raw recognition behind a corrected sentence: the last N words of the recogniser's
     * hypothesis, where N is the corrected sentence's word count. The streaming engine re-emits its
     * WHOLE hypothesis and the glossary repair preserves word count, so the sentence being closed is
     * the tail of the raw hypothesis — the closest read of "what was heard" available without running
     * a second segmenter over the uncorrected text.
     */
    private fun rawSentenceTail(rawHypothesis: String, corrected: String): String {
        val rawWords = rawHypothesis.trim().split(WORD_SPLIT)
        val n = corrected.trim().split(WORD_SPLIT).count { it.isNotEmpty() }
        if (rawWords.isEmpty() || n <= 0) return rawHypothesis.trim()
        return rawWords.takeLast(n).joinToString(" ")
    }

    /**
     * A new sentence has started (or the audio timeline jumped): mark every epoch older than [epoch]
     * dead, so a round trip that is still in flight for the previous sentence can never finish and
     * publish a translation of speech the viewer has already moved past (see [publishLiveLine]).
     *
     * The Chinese line currently on screen is deliberately **left alone**. Blanking it here — as the
     * pipeline used to — showed an empty target line for one whole round trip at every sentence
     * boundary, which reads exactly like "the Chinese is seconds behind the English". The new
     * sentence's line replaces it the moment its first translation delta streams.
     */
    private fun markNewSentence(epoch: Int) {
        if (epoch <= activeEpoch) return
        activeEpoch = epoch
        // A different sentence's hypothesis cannot confirm the last word of this one.
        prevSourceWords = emptyList()
        // A round trip still running for the sentence that just ended can never be published — its
        // epoch is now older than the one on screen (see [publishLiveLine]) — yet the worker runs its
        // round trips one at a time, so it would finish that dead one before it even starts the new
        // sentence's window. That cost a whole round trip (seconds, against a real gateway) at *every*
        // sentence boundary, during which the Chinese line still showed the previous sentence while the
        // English line had already moved on: the "Chinese lags by seconds / freezes" report. Cancelling
        // it frees the worker to pick up the new sentence's window immediately; whatever the cancelled
        // round trip had already streamed stays on screen.
        //
        // That reasoning holds for a LIVE window, whose already-streamed text is safe on screen. It does
        // NOT hold for a closing flush: its whole payload is words withheld until the round trip ends, so
        // cancelling it discards the sentence tail outright — and it is the one unit [publishLiveLine]
        // grants a staleness exemption to, an exemption the old unconditional cancel ran ahead of, making
        // it unreachable in the very case it was written for. So the cancel is now conditional: a live
        // window is cancelled (free), a closing flush is waited for (bounded, and counted here).
        inFlightTranslation?.let { job ->
            if (inFlightUnitFinal && !job.isCompleted) {
                // A closing flush is WAITED for, never cancelled. Its whole payload — the sentence's
                // last words — is withheld until the round trip finishes, so cancelling it does not
                // merely delay that Chinese, it destroys it; and the exemption [publishLiveLine]
                // grants such a unit is only reachable if the round trip is allowed to reach it.
                // The cost of waiting is one bounded round trip before the next sentence's window is
                // offered, paid in latency where the cancel was paid in lost words. See
                // [closingFlushesAtTurnover].
                closingFlushesAtTurnover++
            } else {
                // A live window in flight for the sentence that just ended can never be published,
                // and the worker runs round trips one at a time, so finishing it would delay the new
                // sentence's window for nothing. Whatever it had already streamed stays on screen.
                job.cancel()
            }
        }
    }

    /**
     * The dedicated translation worker: drains the session's buffer and runs **one round trip at a
     * time, start to finish**, fully decoupled from ASR — a slow LLM round-trip delays translations
     * but never the recognizer feed loop. The buffer is conflated (see [LiveTranslationBuffer]), so
     * while the worker is busy it keeps only the newest window; when the round trip finishes the
     * worker immediately starts the freshest text there is and nothing it does is wasted.
     *
     * Serialising the round trips is what makes the caption readable — and it is also what makes the
     * [accumulator] safe to drive from here alone. Launching a fresh round trip on every offer instead
     * let three or four gateway requests run concurrently on one sentence, and their deltas
     * interleaved into `pair` in completion order, so the Chinese line jumped between translations of
     * different-length text.
     *
     * The round trips are serial *and* incremental: each one carries only the words the Chinese line
     * does not cover yet (see [LiveTranslationAccumulator]), so the answer is short, arrives quickly,
     * and is appended to what is already on screen. The streamed deltas (see [translateUnit]) keep it
     * visibly live while the model writes.
     *
     * The `liveBuffer !== buffer` guard drops this worker's remaining work once a restart replaces the
     * session's buffer, and stops the stale write that a cancelled in-flight round trip could
     * otherwise publish after teardown has cleared the pair.
     */
    private fun startTranslationWorker(
        buffer: LiveTranslationBuffer,
        accumulator: LiveTranslationAccumulator,
    ) {
        translateWorker?.cancel()
        // Runs on IO, not the Default pool the ASR feed loop occupies: that loop never suspends and
        // keeps a native decode on its thread, so on a 2–4 core TV box it can hold every Default
        // worker for hundreds of ms and delay the translation worker between two round trips. The
        // whole worker is network-bound (the call itself already hops to IO), so IO is where it
        // belongs and where the recognizer cannot starve it.
        translateWorker = scope.launch(Dispatchers.IO) {
            for (unit in buffer.units) {
                if (liveBuffer !== buffer) return@launch
                // Each round trip is its own job only so [markNewSentence] can cancel *it* without
                // touching this loop. The loop still runs the round trips one at a time — see the
                // class comment — because their deltas would otherwise interleave different windows
                // into the same caption line.
                // Set before the launch, so the flag a concurrent [markNewSentence] reads is the one
                // belonging to this job and not the previous iteration's.
                inFlightUnitFinal = unit.final
                val roundTrip = launch { translateUnit(buffer, accumulator, unit) }
                inFlightTranslation = roundTrip
                roundTrip.join()
                if (inFlightTranslation === roundTrip) inFlightTranslation = null
                // A `final` unit has now been run: if it was a sentence's closing flush, the next
                // sentence's live window may be offered again (see [closedFlushPending]). Cleared for
                // every final — an endpoint's flush is one too, and finds the flag already false.
                if (unit.final) closedFlushPending = false
            }
        }
    }

    /**
     * One round trip for [unit]: asks the accumulator what part of the window still has no Chinese,
     * translates exactly that (with the already-translated words as context), and applies the answer
     * as the model writes it and again when it finishes.
     */
    private suspend fun translateUnit(
        buffer: LiveTranslationBuffer,
        accumulator: LiveTranslationAccumulator,
        unit: TranslationUnit,
    ) {
        val cfg = effective.value
        val request = accumulator.nextRequest(
            window = unit.text,
            epoch = unit.epoch,
            final_ = unit.final,
            nowMs = SystemClock.elapsedRealtime(),
            targetLanguage = cfg.targetLanguage,
            // With the hold-back the answer is not needed for seconds yet, so let the window grow to
            // a whole clause before the first round trip; plain live keeps the accumulator's own
            // smaller floor (0 = its default).
            minTailWords = if (liveDelayMs > 0) BATCH_MIN_TAIL_WORDS else 0,
        ) ?: run {
            // The accumulator refused to build a request: the sentence has not yet produced enough
            // stable words to be worth a round trip. This is the silence the status line could not
            // previously show — a Chinese line that is stale because NO request exists, which looks
            // identical, from `n` and `xlat` alone, to one whose requests are failing. The verdict
            // itself comes from the accumulator, so `skip N` can be split into its causes rather than
            // left as a single opaque number (see the counters and the status line).
            translationSkipCount++
            when (accumulator.lastSkip) {
                SkipReason.SHORT -> skipShort++
                SkipReason.DUPLICATE -> skipDuplicate++
                SkipReason.UNSTABLE -> skipUnstable++
                SkipReason.LOW_CONFIDENCE -> skipLowConfidence++
                SkipReason.STALE -> skipStale++
                // Unreachable here: a null return always records a refusal, never NONE.
                SkipReason.NONE -> Unit
            }
            return
        }
        if (liveBuffer !== buffer) return
        // Domain and cross-sentence context ride only on the sentence's FIRST request — the one with
        // nothing of this sentence translated yet, so its `context` is empty. They change the system
        // prompt, and carrying them on every incremental request would defeat a gateway's prefix cache
        // for a line that is otherwise append-only (each request reuses the previous one's prompt).
        val firstOfSentence = request.context.isBlank()
        val scene = if (firstOfSentence) appliedSceneLabel else ""
        val recent = if (firstOfSentence) recentContextFor(unit.epoch) else ""
        // The fixture's Chinese renderings ride EVERY request, unlike the scene cue: they are stable
        // for the match, and an incremental fragment naming a player whose surname is also an ordinary
        // word (`Rice`, `Mount`) — or a club the model would render two ways — is exactly the case the
        // pin exists for. Empty while no club is scoped.
        val players = SubtitleGlossary.fixturePromptSection(appliedTeamsScope, cfg.targetLanguage)
        // What the commentary says next, when the recognizer is running ahead of this window. It is
        // disambiguation evidence only — the translator is told not to translate or output it.
        val lookahead = lookaheadAfter(unit.text)
        var lastPublishedMs = 0L
        // Translation-side latency runs from here: the first delta is the moment the Chinese line
        // starts moving, the return is one whole round trip (see [PipelineStats.translationTtftAvgMs]).
        val sentAtMs = SystemClock.elapsedRealtime()
        // The stage between the window being offered and the request actually going out: the
        // conflation wait (an earlier round trip still running) plus the accumulator's decision. It is
        // the one stage that is entirely this pipeline's own scheduling, so it is priced on its own
        // rather than folded into `llm` (see [PipelineStats.latencyReqMaxMs]).
        if (unit.offeredAtMs > 0L) {
            val req = sentAtMs - unit.offeredAtMs
            if (req > latencyReqMaxMs) latencyReqMaxMs = req
        }
        var firstDeltaMs = -1L
        // Counted here, the moment the request actually goes out, so `requests = trips + failures +
        // cancels` closes and any gap is provably a skip rather than an accounting slip.
        translationRequests++
        val result = try {
            // The request's own base target is the Chinese line already on screen for the unchanged
            // source prefix; handing it back to the model is what keeps names, terminology and register
            // consistent from one fragment of the sentence to the next.
            translator.translateStreaming(request.text, request.context, cfg, request.baseTarget, scene, recent, players, lookahead) { partial ->
                if (liveBuffer !== buffer) return@translateStreaming
                // Measured before the publish throttle below: this meter reports the backend, not
                // this class's own repaint cadence.
                if (firstDeltaMs < 0L) firstDeltaMs = SystemClock.elapsedRealtime() - sentAtMs
                val now = SystemClock.elapsedRealtime()
                if (now - lastPublishedMs < STREAM_PUBLISH_MIN_MS) return@translateStreaming
                lastPublishedMs = now
                val line = accumulator.accept(request, partial, commit = false)
                    ?: return@translateStreaming
                // [now] is the delta's arrival, so the publish measures answer → screen (see
                // [PipelineStats.latencyUiMaxMs]).
                publishLiveLine(unit, line, answerAtMs = now)
            }
        } catch (e: CancellationException) {
            // The pipeline stopped, or the sentence moved on and [markNewSentence] cancelled this round
            // trip: whatever the deltas already put on screen stands, and the accumulator falls back to
            // the last complete line so the words are simply requested again rather than half-shown.
            accumulator.abandon(request)
            // Cancels are counted for two different reasons — [markNewSentence] moving on, and the
            // pipeline shutting down — because only the first is a cost the design accepted on
            // purpose. Both are this class's own doing, which is what separates them from the
            // gateway-side failures below: a rising `cancel` at a flat `fail` is a local, sentence-
            // boundary effect, not a network one.
            translationCancels++
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: a failure would otherwise take the whole translation worker
            // down with it and the Chinese would never come back for the rest of the session.
            Log.w(tag, "translation failed (${e.message}); keeping the line already on screen")
            accumulator.abandon(request)
            // The gateway's fault — HTTP error, timeout, malformed stream. This is the number the
            // "中文不更新" investigation needed and never had: [translationTrips] excludes exactly
            // these, so a request that burned a full timeout and one that never existed looked alike.
            translationFailures++
            // Split by cause, because the fix differs: a timeout is the answer arriving too late for a
            // live caption (network/gateway load), an HTTP status is the gateway refusing it outright
            // (key, quota, model). Anything else counts only in the total, which is the honest reading
            // — it is neither of those two. See [translationTimeouts].
            when {
                e is SocketTimeoutException -> translationTimeouts++
                e is TranslationHttpException -> {
                    translationHttpErrors++
                    translationLastHttpCode = e.code
                }
            }
            null
        }
        if (liveBuffer !== buffer) return
        // One whole round trip: dispatched → answer, successful or not. Read before the outcome is
        // branched on so a request that burned a full timeout — the worst `e2e` spike, and invisible
        // in `xlat` because [translationTrips] excludes failures — is priced in `llm` rather than lost
        // (see [PipelineStats.latencyLlmMaxMs]).
        val elapsedMs = SystemClock.elapsedRealtime() - sentAtMs
        if (elapsedMs > latencyLlmMaxMs) latencyLlmMaxMs = elapsedMs
        val answer = result
        val finished = answer?.text?.trim()?.takeIf { it.isNotBlank() }
        if (answer == null || finished == null) return
        translationTrips++
        translationTotalMs += elapsedMs
        // A backend that answers in one piece has no first fragment: its whole answer IS the first
        // Chinese the viewer sees, so its round trip is what "time to first text" means for it.
        translationTtftTotalMs += if (firstDeltaMs >= 0L) firstDeltaMs else elapsedMs
        translationTtftTrips++
        val line = accumulator.accept(request, finished, commit = true, wholeLine = answer.wholeLine)
            ?: return
        // The committed answer, so a closed sentence's diagnostic row is written once with its FINAL
        // Chinese rather than with the first streamed prefix (see [publishLiveLine]).
        publishLiveLine(unit, line, settled = true, answerAtMs = SystemClock.elapsedRealtime())
    }

    /**
     * Apply a produced (possibly still being written) line to the Chinese caption.
     *
     * The epoch guard is what keeps the caption coherent: a round trip that finishes after its
     * sentence has scrolled off describes speech the viewer has moved past, so it is dropped rather
     * than published over the line that describes the speech they are hearing. Within one sentence no
     * such guard is needed — the [LiveTranslationAccumulator] owns the whole line, either appending
     * the new fragment or replacing the line with a whole-window translation, and it never hands back
     * a shorter line than the one already published.
     *
     * The English line is deliberately NOT re-paired on failure: leaving the last coherent
     * {source, translation} pair untouched keeps the two lines describing each other. Re-pairing the
     * previous Chinese with a newer source put a caption on screen whose two lines were translations of
     * different sentences, which reads as a *wrong* translation rather than as a network hiccup.
     *
     * [answerAtMs] is when the translation this line comes from arrived, so the call can price
     * answer → screen ([PipelineStats.latencyUiMaxMs]); 0 = unknown, and the ui stage is then left
     * unrecorded rather than measured from a wrong origin.
     */
    private fun publishLiveLine(
        unit: TranslationUnit,
        line: String,
        settled: Boolean = false,
        answerAtMs: Long = 0L,
    ) {
        // A sentence's closing flush carries the epoch its sentence had *before* the switch — exactly
        // one behind the live one — and is the only unit that carries that sentence's last words, so
        // the staleness rule must not discard it. It still cannot revive an arbitrary older line: the
        // live window is withheld until the flush has been run (see [closedFlushPending]), so this is
        // the newest thing the worker can be holding, and anything more than one epoch behind is a
        // round trip the sentence switch really did orphan.
        val closingFlush = unit.final && unit.epoch == activeEpoch - 1
        if (unit.epoch < activeEpoch && !closingFlush) return
        val translated = line.takeIf { it.isNotBlank() && it != unit.text } ?: return
        // Not straight to the overlay: with live audio held back, this translation describes words
        // the speaker has not reached yet. The cue is released when its audio plays, which is what
        // puts the Chinese on screen WITH the sound instead of a round trip after it.
        captionTimeline.offerPair(
            unit.presentAtMs,
            SubtitlePair(unit.displayText.ifEmpty { unit.text }, translated),
        )
        // Keep the sentence's line for the NEXT sentences' `recent`. The same sentence's later
        // (streamed) answer REPLACES its entry rather than appending — the accumulator never returns a
        // shorter line, so once a sentence is superseded its entry is its complete translation.
        if (recentLines.lastOrNull()?.epoch == unit.epoch) {
            recentLines[recentLines.size - 1] = RecentSentence(unit.epoch, unit.text, translated)
        } else {
            recentLines.addLast(RecentSentence(unit.epoch, unit.text, translated))
            while (recentLines.size > RECENT_CONTEXT_SENTENCES_WITH_DELAY) recentLines.removeFirst()
        }
        // Freshness stamp for the diagnostics (see [PipelineStats.chineseIntervalSec]) — set here so
        // both a streamed fragment and a finished line count as "the Chinese just moved".
        val now = SystemClock.elapsedRealtime()
        lastChineseAtMs = now
        // The last stage of the chain, answer → the Chinese actually leaving for the overlay: measured
        // so a stall that lives on this side (a busy main thread delaying the recomposition) is
        // separated from one in the round trip. Recorded only on a real publish, since the guards above
        // return before here.
        if (answerAtMs > 0L) {
            val ui = now - answerAtMs
            if (ui > latencyUiMaxMs) latencyUiMaxMs = ui
        }
        // End-to-end latency for this publication: the audio behind [unit] arrived at the tap at
        // [unit.capturedAtMs] and the line is reaching the screen now, so the difference spans the
        // whole chain. A stale stamp (from before an audio jump) could read absurdly, so anything past
        // [MAX_END_TO_END_MS] is ignored rather than posted.
        if (unit.capturedAtMs > 0L) {
            val e2e = now - unit.capturedAtMs
            if (e2e in 1..MAX_END_TO_END_MS) {
                endToEndMs = e2e
                if (e2e > endToEndMaxMs) endToEndMaxMs = e2e
            }
        }
        // A closed sentence's settled Chinese is the moment its diagnostic row can be written — the
        // last line the viewer will see for that source — and the ASR-side facts were left waiting by
        // the ASR worker (see [pendingSentenceDiag]). Only a committed answer counts: a streamed delta
        // is a prefix of the line, not the final Chinese the row records. Removing the entry keys the
        // write to the sentence, so re-publications of the same epoch cannot duplicate the row.
        if (settled && unit.final) {
            pendingSentenceDiag.remove(unit.epoch)?.let { facts ->
                diagnostics?.recordSentence(facts.raw, facts.corrected, translated, facts.scene, facts.teams)
            }
        }
    }

    /**
     * The cross-sentence context for the sentence [epoch] is about to translate: the last
     * [RECENT_CONTEXT_SENTENCES] sentences BEFORE it as `source → translation` lines, or "" when there
     * is none. Only older epochs are included, so the sentence being built is never handed to the
     * model as its own history.
     */
    private fun recentContextFor(epoch: Int): String {
        // The hold-back buys time, so when it is on the model may see a deeper history: four
        // sentences instead of two is what carries a player's name through a lull in the commentary.
        val limit = if (liveDelayMs > 0) RECENT_CONTEXT_SENTENCES_WITH_DELAY else RECENT_CONTEXT_SENTENCES
        val prior = recentLines.filter { it.epoch < epoch }.takeLast(limit)
        if (prior.isEmpty()) return ""
        return prior.joinToString("\n") { "${it.source} → ${it.translation}" }
    }

    /**
     * The commentary that follows the window being translated, when the recognizer is already
     * ahead of it (which the live hold-back normally makes true). Only words after an exact
     * occurrence of [window] count, and the tail is capped so a long continuation cannot turn into
     * a second paragraph of prompt. This is evidence for the model — never text to translate.
     */
    private fun lookaheadAfter(window: String): String {
        if (liveDelayMs <= 0 || window.isBlank()) return ""
        val heard = latestHeardText
        if (heard.isBlank()) return ""
        val at = heard.indexOf(window, ignoreCase = true)
        if (at < 0) return ""
        val tail = heard.substring(at + window.length).trim()
        if (tail.isBlank()) return ""
        return tail.split(WORD_SPLIT).take(LOOKAHEAD_MAX_WORDS).joinToString(" ")
    }

    fun close() {
        (context.applicationContext as? android.app.Application)
            ?.unregisterComponentCallbacks(this)
        runBlocking { stopPipeline() }
        // Release the process-resident English instance now that the whole manager is going away.
        preloadedEnglish?.close()
        preloadedEnglish = null
        scope.cancel()
    }

    // --- ComponentCallbacks2 ------------------------------------------------------------
    // Hardware and UI memory regimes release the resident English engine when the pipeline is not
    // actively using it; the preload is only an optimisation (removes the one-time startup load), so
    // dropping it under pressure costs no correctness — the engine is rebuilt on demand.
    override fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) releaseResidentEnglish()
    }

    override fun onLowMemory() = releaseResidentEnglish()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}

    private fun releaseResidentEnglish() {
        if (preloadedEnglish == null) return
        scope.launch {
            pipelineMutex.withLock {
                // Never release while a session is borrowing it as its live recognizer (that would
                // tear the native engine out from under an active feed — use-after-free). Only the
                // un-borrowed resident instance is safe to drop.
                if (recognizer === preloadedEnglish) return@withLock
                preloadedEnglish?.close()
                preloadedEnglish = null
                Log.i(tag, "released resident english model on memory pressure")
                // With no resident engine there is nothing standing by: drop back to idle if we are not
                // mid-pipeline (an armed session keeps its working line, unchanged until it stops).
                if (!tapArmed) {
                    statusBase = "idle"
                    refreshStatus()
                }
            }
        }
    }

    @Volatile private var recognizer: StreamingWhisper? = null

    private companion object {
        // Bounded tap buffer between the ExoPlayer audio thread and the ASR worker. Each element is
        // a 160 ms frame, so the capacity IS the caption lag ceiling: the deepest the queue can get
        // is exactly how far behind live the recognizer is allowed to fall. On overflow the WHOLE
        // backlog is discarded in one jump (see RealtimePcmQueue) rather than evicting one frame per
        // offer, which permanently fed the recognizer time-compressed fragments.
        //
        // History: 24 (3.8 s) was raised to 48 (7.7 s) to stop `drop 2232 · jmp 94` from tearing the
        // audio up — and it did fix the loss. But a 48-frame ceiling also licensed 7.7 s of LAG, and
        // the worker's effective throughput sits at ~1.0× real time (rtf 0.72 for the native decode
        // alone, then gain/segment/publish on top), so any stall it does not immediately repay leaves
        // the queue parked near its ceiling. The next device session showed exactly that: `qmax 49`
        // (full) with `drop 240` = five whole-backlog dumps, i.e. captions living 3–7.7 s behind the
        // speech — the "English subtitles are noticeably delayed" report.
        //
        // Sized for freshness instead: 8 × 160 ms = 1.28 s, one model chunk — the reasoning being
        // that the model's own chunk gate already smooths everything shorter.
        //
        // MEASUREMENT FALSIFIED THAT, and the arithmetic is exact. The gate means a decode runs only
        // once a full 1.28 s chunk has accumulated, and NOTHING is consumed from this queue while it
        // runs, so the producer's backlog peaks at `decAvg / 160` frames — independent of the ceiling.
        // Measured on a 4K football stream: `dec 827ms` (rtf 0.65) peaked at 5.2 frames and `dec
        // 1152ms` (rtf 0.90, the same channel minutes later) at 7.2. One chunk of capacity is thus
        // ALREADY consumed by the average decode, leaving 8 ≈ the peak and no margin at all for the
        // slower decodes: `qmax 8/8` says the ceiling was reached, and the loss is exactly the
        // whole-backlog dumps — 224 jumps × 8 frames = `drop 1792`, and 51 × 8 = `drop 408` in the
        // earlier sample, both to the frame. 34 % of the audio never reached the recognizer.
        //
        // So the ceiling is set to cover the DECODE, not the chunk gate: 16 × 160 ms = 2.56 s absorbs
        // any single decode up to 2.56 s, which the measured `dec avg` (1.15 s, and degrading ~40 %
        // over seven minutes as the box heats up) clears with real margin. The lag cost is smaller
        // than it looks: the queue still drains to empty every decode cycle, so the TYPICAL lag is
        // unchanged, and only a stall that actually fills 16 frames now reads 2.56 s behind live —
        // which is the case this queue exists to trade against, and 34 % loss is the worse deal.
        const val PCM_CHANNEL_CAPACITY = 16

        // One 16 kHz frame fed per recognizer call: 160 ms of mono audio. Coarser than the old 40 ms
        // feed, which called acceptWaveform/isReady 25× per second; streaming engines decode on their
        // own model-chunk gate (`while (isReady) decode`) regardless of feed granularity, so 160 ms
        // keeps partials live while cutting JNI/feature-extraction overhead ~4× on slow TV-box CPUs.
        const val FRAME_SAMPLES = 2560

        /** Audio length of one [FRAME_SAMPLES] frame at the recognizer's 16 kHz, ms. */
        const val FRAME_MS = FRAME_SAMPLES * 1_000L / PcmResampler.TARGET_RATE

        /**
         * How often the caption timeline is advanced, ms. A cue is published on the first tick after
         * its time, so this is a hard bound on how late any caption can be. It is now half a display
         * frame's worth at 60 Hz rather than the old 80 ms: the timeline advance is a few deque
         * operations and it only touches the overlay when a cue is actually due, so the tighter tick
         * costs nothing measurable and takes the tick out of the alignment budget.
         */
        const val PRESENTATION_TICK_MS = 30L

        /**
         * Sanity ceiling for a recognizer-reported word age, ms (see [Float.ageMs]). Broadcast speech
         * never puts a word minutes behind the audio it belongs to, so anything past this is a
         * timestamp from a different stream and is clamped rather than believed.
         */
        const val MAX_WORD_AGE_MS = 30_000L

        /**
         * Nice level the ASR worker thread ranks at, applied with [Process.setThreadPriority] on that
         * thread (see [workerDispatcher]).
         *
         * [Process.THREAD_PRIORITY_AUDIO] (−16) is chosen because the work that was starving this
         * thread is itself ranked highly: the capture's resampler runs INLINE on the renderer's thread
         * while it hands PCM over, which the on-screen meter reports as `tap max 109.7ms 342%`. A
         * worker launched at default priority loses to that by construction, so a session could see
         * one `dec` chunk take 2.84 s against an 823 ms average and overrun the entire 2.56 s queue.
         * Matching the rank makes CFS share the core instead.
         *
         * Deliberately NOT [Process.THREAD_PRIORITY_URGENT_AUDIO] (−19): that outranks the renderer
         * this worker shares the box with, and a caption is not worth an audio underrun. The decode
         * needs only ~64 % of one core (`rtf 0.63`), so an even share is enough — the failure being
         * fixed is the occasional ~2 s deschedule, not a CPU shortage.
         *
         * Read `tap` and `buf` against this on the next sample: if the audio duty climbs or buffering
         * episodes appear where they did not, the rank is too high and
         * [Process.THREAD_PRIORITY_DISPLAY] (−4) is the fallback — still above default-priority work,
         * still below the audio path.
         */
        const val ASR_THREAD_PRIORITY = Process.THREAD_PRIORITY_AUDIO

        /**
         * Whitespace run for splitting a recognised hypothesis, compiled once.
         *
         * The ASR worker re-splits the same partial for its word count, the scene detector and the
         * segmenter on every feed; compiling `\s+` again at each of those sites was ~35
         * `Pattern.compile` calls a second, on the thread whose whole job is to keep draining the live
         * PCM queue. [CaptionSegmenter] and [LiveTranslationAccumulator] hold their own for the same
         * reason.
         */
        private val WORD_SPLIT = Regex("\\s+")

        // Period of the independent diagnostics/playing-time ticker. 1 s keeps on-screen counters
        // live during silent/starved stretches (feed-driven refreshes only happen when audio flows)
        // without costing measurable CPU.
        const val STATS_TICK_MS = 1_000L

        // How long a [gcStats] reading is reused before the runtime's stats map is rebuilt. The
        // counters are cumulative, so this only trades a little staleness for far fewer allocations
        // on the path that is itself being measured.
        const val GC_STATS_TTL_MS = 3_000L

        // Minimum spacing between two streamed translations republished to the overlay. A gateway can
        // emit deltas at token rate (tens per second); each would recompose the caption, so the line
        // is refreshed at most ~8×/s — visibly live, but not a recomposition per token. The finished
        // line is always published regardless of this floor.
        const val STREAM_PUBLISH_MIN_MS = 120L

        // Upper bound on a believable end-to-end reading (see [PipelineStats.endToEndMs]). A unit whose
        // capture stamp predates an audio jump (or a long pause) would otherwise post a minutes-long
        // figure that swamps the session maximum; anything past this is dropped as stale, not shown.
        const val MAX_END_TO_END_MS = 30_000L

        // Diagnostics log every Nth chunk/blank result, so a healthy feed shows up in logcat without
        // flooding it (audio chunks arrive many times a second).
        const val DIAG_DECIMATION = 200L

        /**
         * Finished sentences carried into the translator as cross-sentence context (see
         * [recentContextFor]). Two is enough to carry a name or a term one or two sentences forward —
         * the same-sentence context cannot, it is cleared at every boundary — without making the
         * prompt grow unboundedly.
         */
        const val RECENT_CONTEXT_SENTENCES = 2

        /**
         * Cross-sentence context while the live hold-back is on. The budget that lets captions wait
         * for their audio also lets the prompt carry a deeper history at no latency cost, which is
         * what keeps a squad name or a competition term alive through a quiet passage.
         */
        const val RECENT_CONTEXT_SENTENCES_WITH_DELAY = 4

        /**
         * New words a delayed translation waits for before a round trip of a live window.
         *
         * The hold-back buys seconds, so a slightly longer clause reads better than a two-word
         * fragment. It was 4, which is a full second of speech per increment: the Chinese line then
         * only moved every four words, and because each cue is anchored to the word it covers, the
         * visible effect was a line that stepped visibly behind the audio. Two words keeps the
         * grammar gain (the fragment is still translated with the sentence as context, see
         * [LiveTranslationAccumulator]) while halving that step; the 8 s budget absorbs both.
         */
        const val BATCH_MIN_TAIL_WORDS = 2

        /**
         * Finished sentences the translation worker may still have waiting, in whole-sentence mode.
         * Four covers a fast-closing passage without letting a slow gateway build a visible backlog:
         * beyond that the oldest line cannot reach the screen before its audio has played anyway.
         */
        const val SENTENCE_QUEUE_DEPTH = 4

        /**
         * Most words of the following commentary handed to the translator as look-ahead evidence.
         * Enough to settle a name or a pronoun; short enough that the prompt stays a prompt and the
         * model is not tempted to translate the continuation.
         */
        const val LOOKAHEAD_MAX_WORDS = 16

        /** First [n] chars of a recognised line for a log line — never the whole utterance. */
        fun takeDiag(text: String): String = text.take(40)
    }
}