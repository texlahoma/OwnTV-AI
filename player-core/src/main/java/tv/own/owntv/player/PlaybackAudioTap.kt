package tv.own.owntv.player

import android.media.AudioFormat
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bundle of decoded audio for the real-time AI-subtitle pipeline.
 *
 * The player engines decode audio far down in player-core (libmpv) and Media3 (ExoPlayer). Rather
 * than duplicate that decode in the app, the ExoPlayer audio sink forwards the decoded PCM it is
 * about to hand to the device through here, and the app subscribes once. Recognition then runs on
 * *exactly* the samples the user is hearing, with no extra decode and no OS audio-capture permission.
 *
 * Scope is process-wide and session-scoped, like [AudioOutputPolicy]: whichever ExoPlayer engine is
 * driving sound (the Live default engine, the VOD ExoPlayer handoff) feeds its PCM in here and the
 * app's AI-subtitle manager is the single consumer.
 *
 * **mpv path**: libmpv renders and resamples audio natively; exposing a hook there is a larger,
 * separate body of work, so the tap covers the ExoPlayer engines only. When playback is forced onto
 * mpv (a "force mpv" pin / mpv-only live fallback) no PCM lands here and AI subtitles stay off — a
 * documented limitation rather than a silent gap.
 */
object PlaybackAudioTap {

    /** Delivers decoded PCM chunks. Dedicated to the single AI-subtitle consumer. */
    fun interface PcmConsumer {
        /**
         * @param samples 16-bit little-endian PCM, already downmixed to mono by [TapAudioSink].
         * @param sampleRate the stream's native sample rate (Hz) those samples were decoded at.
         * @param presentationTimeUs the media presentation time of the buffer's FIRST sample, or
         *   [C.TIME_UNSET] when the sink could not report one. This is what lets a consumer that
         *   runs ahead of the loudspeaker (see [LiveAudioDelay]) bind its own clocks — a caption
         *   keyed on wall-clock capture time alone cannot tell how long the audio will wait in the
         *   sink, while this stamp can.
         */
        fun onPcm(samples: ShortArray, sampleRate: Int, presentationTimeUs: Long)

        /**
         * The renderer flushed its audio pipeline (initial stream start, a seek, or a live stream
         * repositioning to the live edge): every PCM byte that might have followed the last
         * [onPcm] is gone and the next buffer belongs to a NEW point on the media timeline. Any
         * streaming recognizer must restart its stream there — feeding a stateful transducer
         * across an invisible gap splices two unrelated stretches into one hypothesis (on live
         * TV this showed as audio arriving at only ~57 % of wall clock with almost no words
         * decoded). Fires on the audio thread; implementations must stay non-blocking. Default
         * no-op for consumers without cross-buffer state.
         */
        fun onDiscontinuity() {}
    }

    /**
     * The PCM layout [TapAudioSink] most recently observed on its way to the recognizer, published
     * purely for the AI-subtitle diagnostics line: it lets "the engine hears garbage" be split on
     * screen into a wrong sample rate (time-stretched audio), a wrong channel count (interleaved
     * channels) or a wrong sample encoding without a debugger attached. Null until a sink has seen a
     * format. Written from the audio sink thread, read from the UI/stats thread.
     */
    data class CaptureInfo(
        val sampleRate: Int,
        val channelCount: Int,
        val pcmEncoding: Int,
        /** MIME of the format the sink was last configured with, e.g. `audio/raw` / `audio/ac3`. */
        val mimeType: String? = null,
        /**
         * True when the configured format is decoded PCM (`audio/raw`), false when it is an encoded
         * bitstream the sink is playing through (AC-3 / E-AC-3 / AAC …). This is the ONE field that
         * separates "the recognizer hears decoded audio" from "it hears a bitstream read as PCM":
         * the rate/channel/encoding triple is identical in both cases, so a label built from those
         * three alone (as before) cannot tell a healthy tap from a bypassed one.
         */
        val raw: Boolean = true,
    ) {
        /**
         * Compact label for the one-line diagnostics. A decoded-PCM capture reads `48000Hz/2ch/s16`;
         * a bypassed bitstream reads `48000Hz/2ch/ac3!` — the trailing codec name plus `!` means the
         * bytes reaching the recognizer are NOT PCM and no subtitle can be expected from them.
         */
        fun label(): String {
            if (!raw) {
                val codec = mimeType?.substringAfterLast('/') ?: "enc"
                return "${sampleRate}Hz/${channelCount}ch/$codec!"
            }
            val enc = when (pcmEncoding) {
                C.ENCODING_PCM_FLOAT -> "f32"
                C.ENCODING_PCM_24BIT -> "s24"
                C.ENCODING_PCM_32BIT -> "s32"
                else -> "s16"
            }
            return "${sampleRate}Hz/${channelCount}ch/$enc"
        }
    }

    @Volatile var captureInfo: CaptureInfo? = null
        internal set

    /**
     * Buffers the tap declined to read because the sink was configured with an encoded format (see
     * [CaptureInfo.raw]). Non-zero means the recognizer is being starved by a bypassed bitstream —
     * the exact failure the on-screen `.../ac3!` label and this counter exist to make provable.
     */
    val nonPcmBuffers = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile var consumer: PcmConsumer? = null
        private set

    private val _passThroughAudio = MutableStateFlow<String?>(null)

    /**
     * Non-null while the channel being played keeps its audio on passthrough, so no PCM can reach the
     * tap. Published so the app can switch AI subtitles off for that channel instead of leaving a
     * session that will never produce a word. Playback itself is never touched by this state: whether
     * audio is decoded or bypassed is the renderer's decision, exactly the one subtitles-off playback
     * makes — the tap only observes it.
     */
    val passThroughAudio: StateFlow<String?> = _passThroughAudio.asStateFlow()

    /** This encoded MIME is what the channel plays on, so no PCM can ever reach the tap. */
    internal fun notePassThroughAudio(mime: String) {
        _passThroughAudio.value = mime
    }

    fun clearPassThroughNotice() {
        _passThroughAudio.value = null
    }

    private val claim = AtomicBoolean(false)

    private val _armed = MutableStateFlow(false)

    /**
     * True while a consumer owns the tap. Engines watch this to know when a caption session is live.
     * Emits on the arming thread.
     */
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    /** Whether any engine should bother wrapping its sink with [TapAudioSink] right now. */
    val enabled: Boolean get() = claim.get()

    /**
     * Register the consumer and arm the tap. Returns false if another consumer is already attached —
     * exactly one pipeline may own the tap per process.
     */
    fun start(newConsumer: PcmConsumer): Boolean {
        if (!claim.compareAndSet(false, true)) return false
        consumer = newConsumer
        _armed.value = true
        // A channel that was already playing on a bypassed bitstream was decided before this session
        // existed — MediaCodecAudioRenderer never re-asks the sink while the same track is selected.
        // Say so now so the app stands down instead of running a session that can hear nothing.
        val info = captureInfo
        if (info != null && !info.raw && info.mimeType != null) notePassThroughAudio(info.mimeType)
        return true
    }

    /** Detach the consumer and disarm the tap. */
    fun stop() {
        consumer = null
        claim.set(false)
        _armed.value = false
    }

    /**
     * A different channel is being tuned: nothing the previous sink observed describes it. The last
     * capture's format and any "this audio is a bitstream" notice belong to the channel that was just
     * left, and carrying them over made the caption switch refuse to stay on for a channel whose
     * audio was fine — the press was answered by the previous channel's verdict.
     */
    fun forgetChannel() {
        captureInfo = null
        clearPassThroughNotice()
        nonPcmBuffers.set(0)
    }
}

/**
 * Measurement and bounds of the live audio hold-back used by the caption pipeline.
 *
 * A live caption has to be *spoken before it can be read*: recognition and translation take seconds,
 * and no pipeline can show a line at the instant its audio plays while it only hears that audio at
 * the same instant. The way out is to let the decoder run ahead of the speaker — the sink holds the
 * audio back in application memory — so the tap sees the words early and the caption is ready when
 * the delayed audio finally plays. The requested depth lives per engine (see
 * [PlaybackEngine.applyAudioDelay]); this object carries the measurement both sides agree on.
 *
 * [measuredMs] is the hold-back's own queue depth, published by [HoldBackAudioProcessor] on every
 * buffer: that queue is the entire distance between the audio being tapped and the audio being heard,
 * so it is the one number the caption scheduler can trust. It used to be inferred from the sink's
 * playback clock, which is a different question and read 0 whenever the clock and the samples did not
 * agree — which is what `dly 8s/0ms/8s` on screen was.
 */
object LiveAudioDelay {
    /**
     * The lead the tapped audio currently has over the speaker, ms: the hold-back queue PLUS whatever
     * the sink still has to play (see [TapAudioSink.noteLead]). This is the one number the caption
     * scheduler may use — it is, by definition, how far in the future the audio reaching the tap will
     * be heard. 0 while nothing is buffered ahead (captions off, a stock sink, or just after a flush).
     */
    @Volatile var measuredMs: Int = 0

    /**
     * What [HoldBackAudioProcessor] itself is holding, ms — the queue depth alone, without the sink's
     * own track buffer. It is exact (a byte count and a byte rate) but it is NOT the lead: the audio
     * still has to get through the AudioTrack, which on a TV is several hundred ms.
     *
     * The two must not both write [measuredMs]: before this was separated, the processor overwrote it
     * with the queue depth on every buffer and [TapAudioSink.noteLead] overwrote it with the clock
     * lead right after, so the field alternated between two references ~0.6 s apart (`dly` flickering
     * between 8000 and 8600 on screen). Every caption stamped while the processor's value was current
     * was that much early, and it changed buffer by buffer.
     */
    @Volatile var queuedMs: Int = 0

    /** When the sink clock last answered, ms; 0 = never. See [publishQueued]. */
    @Volatile private var leadFromClockAtMs: Long = 0L

    /**
     * When the CURRENT fill started, ms; 0 while not filling.
     *
     * A fill is any stretch where the hold-back queue is still building toward its request — the first
     * fill after captions engage, AND every re-fill after a flush (a seek, a track transition: the
     * flush empties the queue and the depth has to be rebuilt). Watchdogs must not treat a fill as a
     * dead stream, and they must give the fill its full window measured from when THIS fill began —
     * anchoring the window to the moment the request was RAISED went stale after the first fill and
     * let a later fill be judged (and the hold-back dropped) on its first second.
     */
    @Volatile var fillStartedAtMs: Long = 0L

    /**
     * Set the filling flag, stamping a new fill's start. Every write of [filling] for a queue that is
     * building depth goes through here so the start is never missed; writes of `false` (flush, the
     * hold-back being dropped) may assign [filling] directly.
     */
    fun noteFilling(active: Boolean) {
        if (active && !filling) fillStartedAtMs = SystemClock.elapsedRealtime()
        filling = active
    }

    /**
     * What the sink adds between its queue and the speaker, ms: the AudioTrack/mixer buffer. Measured
     * as `lead − queued` on every clock reading, and it is the piece the queue depth alone cannot see.
     *
     * It exists so the fallback in [publishQueued] stays on the SAME reference as the clock. Falling
     * straight back to the bare queue dropped the number by exactly this much (the `dly` line stepping
     * 8600 → 8000 and back), and every caption stamped during that step was early by it.
     */
    @Volatile private var trackMs: Int = 0

    /**
     * The hold-back's queue depth changed. While the sink clock is answering it is the ONLY writer of
     * [measuredMs], and this only records the depth; after [CLOCK_STALE_MS] without a clock reading
     * the lead falls back to "queue + the track buffer last measured", not to the queue alone.
     */
    fun publishQueued(ms: Int) {
        queuedMs = ms
        if (SystemClock.elapsedRealtime() - leadFromClockAtMs > CLOCK_STALE_MS) {
            // + trackMs, never the bare depth: the queue is only the part of the wait that happens in
            // this process. Before the first clock reading trackMs is 0, which is the honest answer.
            measuredMs = ms + trackMs
        }
    }

    /** The sink clock answered: the lead is now measured end to end (queue + track buffer). */
    fun publishLead(ms: Int) {
        trackMs = (ms - queuedMs).coerceAtLeast(0)
        measuredMs = ms
        leadFromClockAtMs = SystemClock.elapsedRealtime()
    }

    /** Forget the lead: the hold-back was turned off, flushed, or a new channel started. */
    fun resetLead() {
        measuredMs = 0
        queuedMs = 0
        trackMs = 0
        leadFromClockAtMs = 0L
    }

    /**
     * How long a queue-depth publication is allowed to stand in for the lead, ms.
     *
     * The sink clock answers on almost every buffer, so this only matters while it does not — at the
     * very start of a fill, or on a sink that never reports a position. Half a second is longer than
     * a buffer gap and far shorter than a stale reading could mislead the caption clock.
     */
    const val CLOCK_STALE_MS = 500L

    /**
     * True while the hold-back queue is still filling, i.e. the sink is deliberately withholding the
     * audio it has been given. A silent output in this state is the delay working — the audio
     * watchdog must not read it as a dead one (see [AudioWatchdog]).
     */
    @Volatile var filling: Boolean = false

    /**
     * Whether the sinks the live engines build are hold-back capable at all (0 = nothing requested a
     * delay, or the delayed sink failed to build and the stock one took over). This is a property of
     * the PLUMBING, not of the current depth: with a live request (see [HoldBackAudioProcessor]) the
     * depth follows the switch, so "built with 8 s" no longer says anything about now.
     */
    @Volatile var plumbed: Boolean = false

    /**
     * Upper bound, ms: beyond this the delay stops being a caption aid and becomes a timeshift.
     *
     * Raised from 12 s because whole-sentence captions need the budget to hold an entire sentence
     * (plus a decode and a translation round trip) before that sentence's audio starts. The guard
     * that discards implausible clock readings tracks this value, so it stays consistent.
     */
    const val MAX_MS = 20_000

    /** Shortest useful hold-back, ms. Below it a round trip no longer fits inside the budget. */
    const val MIN_MS = 5_000

    /** A requested value, clamped to 0 (off) or [MIN_MS]..[MAX_MS]. */
    fun clamp(ms: Int): Int = if (ms <= 0) 0 else ms.coerceIn(MIN_MS, MAX_MS)
}

/**
 * An [AudioSink] that passes every decoded chunk to [PlaybackAudioTap] on the way to the hardware,
 * unchanged. Wrapping the sink (instead of inserting an AudioProcessor) means the decoded PCM is
 * seen in one place for every engine that builds one through [OwnTVRenderersFactory], and the output
 * can never be altered by the tap.
 *
 * Deliberately feel-only: if no consumer is attached the wrapper forwards with negligible overhead,
 * and it never blocks the sink's own buffer handling.
 */
@UnstableApi
class TapAudioSink(
    delegate: AudioSink,
    /**
     * Refuse encoded (bitstream) formats so MediaCodecAudioRenderer decodes them in-app instead of
     * handing them to the TV. Set on the LIVE engines' sinks: a bypassed track produces no PCM at
     * all, so there is nothing to recognise and nothing to hold back (see [supportsFormat]).
     */
    private val pcmOnly: Boolean = false,
) : ForwardingAudioSink(delegate) {

    // The delegate itself, not just the forwarding base: [noteLead] needs its playback position.
    private val inner: AudioSink = delegate

    private var channelCount = 1
    // Channel mask of the configured PCM layout when the container declares one (Format.NO_VALUE
    // otherwise). It is the only way to place the FRONT CENTRE channel without guessing — the mask
    // says both whether the layout has one and where it sits. See [downmixMono].
    private var channelMask = Format.NO_VALUE
    private var sampleRate = 24_000
    // Decoded PCM layout, captured from the input format. ExoPlayer decodes to PCM_16BIT for most
    // tracks but hands over PCM_FLOAT when float output is enabled (the default) — e.g. E-AC3 /
    // high-sample-rate streams. Treating a float buffer as 16-bit shorts corrupts every sample and
    // feeds the recognizer garbage, so the conversion below must follow this encoding.
    private var pcmEncoding = C.ENCODING_PCM_16BIT
    // Whether the authoritative configure() saw decoded PCM (`audio/raw`) or an encoded bitstream the
    // sink is playing through. Defaults false so no buffer is ever read as PCM before a configure().
    private var configuredRaw = false
    private var configuredMime: String? = null
    // Set once configure(AudioSinkConfig) has run; before that, probes may pre-fill rate/channels.
    private var hasConfigured = false
    // The buffer the sink has not yet fully accepted, and the decoded samples read from it on that
    // first submission. Media3 re-submits the SAME buffer object on every retry while advancing its
    // position, so the bytes can only be read once and must be carried here until the accept. See
    // [handleBuffer].
    private var pendingBuffer: java.nio.ByteBuffer? = null
    private var pendingDecoded: ShortArray? = null
    // Reusable output buffers for the two per-buffer allocations that dominate this thread's garbage
    // — the full interleaved decode (e.g. 24 KB for 48 kHz/8ch) and the downmixed mono frame. Both
    // are safe to reuse because nothing retains them past the call that consumes them: the decode
    // because no second buffer can be submitted while one is pending (see [handleBuffer]), the mono
    // because the consumer's resampler copies what it reads. Sizes must match EXACTLY, so a format
    // change (rate/layout/encoding) simply reallocates once rather than handing back a stale length.
    private var decodeScratch = ShortArray(0)
    private var monoScratch = ShortArray(0)

    /** An exact-[count]-sized buffer to decode into: the shared scratch when it fits exactly, else a
     * fresh exact array. Grow-only — a short (final/partial) buffer must not shrink the scratch and
     * make the next full buffer reallocate. */
    private fun decodeBuffer(count: Int): ShortArray {
        if (decodeScratch.size < count) decodeScratch = ShortArray(count)
        return if (decodeScratch.size == count) decodeScratch else ShortArray(count)
    }

    /** An exact-[count]-sized buffer to downmix into, with [decodeBuffer]'s growth rule. */
    private fun monoBuffer(count: Int): ShortArray {
        if (monoScratch.size < count) monoScratch = ShortArray(count)
        return if (monoScratch.size == count) monoScratch else ShortArray(count)
    }

    override fun supportsFormat(format: Format): Boolean {
        // The tap normally OBSERVES audio and never steers it: whether a format is decoded in-app or
        // bypassed to HDMI stays MediaCodecAudioRenderer's decision. [pcmOnly] is the one exception,
        // and it exists because a bypassed track produces NO PCM at all — nothing for the recogniser
        // to hear and nothing for the caption hold-back to queue, which is exactly the state that read
        // `dly 8s/0ms/8s` and switched the AI-subtitle switch back off the moment a channel tuned.
        // It is set only on the live engines' sinks (see OwnTVRenderersFactory), where in-app decode is
        // what every other live player on this platform does (mpv/VLC decode AAC/AC-3 themselves):
        // MediaCodecAudioRenderer does not use a decoder when the sink accepts the bitstream, so
        // answering "unsupported" is the ONLY way to make it decode, and it does decode — see
        // getDecoderInfos in that class.
        //
        // In Media3 1.11 MediaCodecAudioRenderer.supportsFormat queries THIS method (never
        // getFormatSupport) — and ForwardingAudioSink.supportsFormat forwards straight to the
        // delegate, so an override of getFormatSupport alone is dead code: captureInfo stayed null
        // ("fmt ?"), and every buffer was parsed with the 24 kHz/mono/s16 DEFAULTS, i.e. real
        // 48 kHz stereo TV audio got downsampled at the wrong ratio and left interleaved — the
        // recognizer only ever heard noise (words 0). The renderer probes a synthetic RAW format
        // (Util.getPcmFormat over the candidate's rate/channels) here, so record it as an early
        // best-effort; configure(AudioSinkConfig) below overrides it with the chosen truth.
        if (pcmOnly && isEncoded(format)) return false
        captureProbe(format)
        return super.supportsFormat(format)
    }

    override fun getFormatSupport(inputFormat: Format): Int {
        if (pcmOnly && isEncoded(inputFormat)) return AudioSink.SINK_FORMAT_UNSUPPORTED
        captureProbe(inputFormat)
        return super.getFormatSupport(inputFormat)
    }

    /**
     * Authoritative format hook. Media3 1.11's renderer configures the sink through THIS overload
     * (`configure(Format, int, int[])` is final on ForwardingAudioSink and cannot be overridden),
     * and it is called with the exact decoded PCM [AudioSink.AudioSinkConfig.format] immediately
     * before the first [handleBuffer] for that stream, and again on every stream-format change — so
     * the rate/channels/encoding used below always match the bytes about to arrive. Buffers reach
     * the wrapper BEFORE the delegate's internal channel remapping/downmix, so the channel count
     * here is the true interleaved frame layout, not the sink's possibly-reduced output.
     */
    override fun configure(config: AudioSink.AudioSinkConfig) {
        captureAuthoritative(config.format)
        super.configure(config)
    }

    /**
     * The renderer flushes on initial enable and on every seek/live reposition. After a flush the
     * next [handleBuffer] is NOT a continuation of the previous PCM — its media-time position
     * jumped — so tell the consumer to restart any stateful decoder. Without this a live stream
     * that snaps back to the live edge after a stall feeds the recognizer two disjoint stretches
     * as if contiguous, which a streaming transducer decodes as near-total silence. The initial
     * pre-first-buffer flush is harmless (nothing has been decoded yet).
     */
    override fun flush() {
        // The pending buffer belongs to the stretch before this discontinuity; its decode must not be
        // fed after the new one starts.
        pendingBuffer = null
        pendingDecoded = null
        PlaybackAudioTap.consumer?.onDiscontinuity()
        super.flush()
    }

    /**
     * Authoritative capture from [configure]. This is the last format written before the first
     * [handleBuffer] for a stream, so it — not a probe — decides how the bytes are read. It also
     * records whether the format is decoded PCM or a bypassed bitstream, the one distinction the
     * rate/channel/encoding triple cannot express (see [CaptureInfo.raw]).
     */
    private fun captureAuthoritative(format: Format) {
        val raw = !isEncoded(format)
        if (format.channelCount != Format.NO_VALUE) channelCount = format.channelCount
        if (format.sampleRate != Format.NO_VALUE) sampleRate = format.sampleRate
        if (raw && format.pcmEncoding != Format.NO_VALUE) pcmEncoding = format.pcmEncoding
        // Unconditional: a stream reconfigured without a mask must not keep the previous stream's
        // layout, or the centre of one programme would be read out of another's channel.
        channelMask = format.channelMask
        configuredRaw = raw
        configuredMime = format.sampleMimeType
        hasConfigured = true
        publishCapture()
    }

    /**
     * Best-effort capture from a format probe (track selection / capability query). Probes pass
     * candidate and synthetic formats, so they must NEVER overwrite a real [configure]'s truth —
     * otherwise a late `audio/ac3` probe could relabel a decoded stream (or vice versa) and hide a
     * bypassed bitstream behind a healthy-looking `s16` label. They only pre-fill a rate/channel
     * hint before the first configure.
     */
    private fun captureProbe(format: Format) {
        if (hasConfigured) return
        if (format.channelCount != Format.NO_VALUE) channelCount = format.channelCount
        if (format.sampleRate != Format.NO_VALUE) sampleRate = format.sampleRate
        if (format.channelMask != Format.NO_VALUE) channelMask = format.channelMask
        configuredMime = format.sampleMimeType
        configuredRaw = !isEncoded(format)
        publishCapture()
    }

    private fun publishCapture() {
        PlaybackAudioTap.captureInfo = PlaybackAudioTap.CaptureInfo(
            sampleRate, channelCount, pcmEncoding, configuredMime, configuredRaw,
        )
        // An authoritative configure may declare this channel passthrough (no PCM for the tap, so the
        // app should stand the caption session down). Probes pass candidate and synthetic formats, so
        // they must never make that call for a track that was not selected.
        if (!hasConfigured || PlaybackAudioTap.consumer == null) return
        if (configuredRaw) {
            PlaybackAudioTap.clearPassThroughNotice()
        } else {
            configuredMime?.let { PlaybackAudioTap.notePassThroughAudio(it) }
        }
    }

    /** An encoded (non-PCM) format — the ones a sink can bypass/offload as a raw bitstream. */
    private fun isEncoded(format: Format): Boolean =
        format.sampleMimeType != null && format.sampleMimeType != MimeTypes.AUDIO_RAW

    override fun handleBuffer(
        input: java.nio.ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        PlaybackAudioTap.consumer?.let { consumer ->
            // Refuse to read a bypassed bitstream as PCM. When the sink was configured with an
            // encoded format (audio/ac3, audio/eac3, audio/aac …) its "buffer" is a compressed access
            // unit, not samples: parsing it as s16 produces loud noise that the recognizer decodes as
            // blank for minutes on end — verified offline (AC-3/E-AC-3/AAC bytes read as PCM ->
            // rms~10.4k, 0 words), and indistinguishable from healthy audio by rms alone. Skip the tap
            // and let the delegate play the bitstream untouched; [PlaybackAudioTap.nonPcmBuffers] and
            // the `.../ac3!` diagnostics label make this visible instead of silently empty subtitles.
            if (!configuredRaw) {
                PlaybackAudioTap.nonPcmBuffers.incrementAndGet()
                return super.handleBuffer(input, presentationTimeUs, encodedAccessUnitCount)
            }
            // Read the buffer's samples ONCE, on its first submission, and carry them across the
            // retries in [pendingDecoded]. `handleBuffer` returning false is not a rejection but a
            // "not yet": MediaCodecAudioRenderer keeps the same output buffer pending (see its
            // `nextBufferToWritePresentationTimeUs` / `processOutputBufferResultHolder`) and calls
            // this method again with the SAME object — while DefaultAudioSink's processBuffers has
            // already queued the head of it and left `input`'s position at the unconsumed remainder.
            // The two failure modes that follows from that are exact opposites, and each was seen on
            // a device:
            //   * Tapping on every submission read the shrinking remainder repeatedly, so the capture
            //     ran ahead of wall clock (`aud 472/284s`, ~1.66x) and the duplicates burned decode
            //     CPU and overflowed the PCM queue.
            //   * Tapping only on the accepted submission reads a buffer whose position is already at
            //     its limit (`DefaultAudioSink` accepts exactly when `!inputBuffer.hasRemaining()`),
            //     i.e. an EMPTY remainder: every buffer that needed a retry contributed no audio at
            //     all (`aud 125/358s`, ~0.35x, words lost in whole chunks).
            // Reading on the first submission and feeding once on the accept gives each buffer's
            // audio exactly once, in full. `AudioSink` guarantees no new buffer is submitted while one
            // is pending, so identity is enough to recognize the retry.
            val continuation = input === pendingBuffer
            val decoded = if (continuation) pendingDecoded else decodeToShorts(input)
            // The position the speaker is actually at, read BEFORE this buffer is handed over: the
            // distance from it to this buffer's first sample is the whole wait that buffer will serve,
            // and that is the number the caption clock must use (see [noteLead]).
            val heardUs = if (pcmOnly && !continuation) heardPositionUs() else C.TIME_UNSET
            val accepted = super.handleBuffer(input, presentationTimeUs, encodedAccessUnitCount)
            if (heardUs != C.TIME_UNSET) noteLead(presentationTimeUs, heardUs)
            if (accepted) {
                if (decoded != null) {
                    consumer.onPcm(
                        if (channelCount <= 1) decoded else downmixMono(decoded),
                        sampleRate,
                        presentationTimeUs,
                    )
                }
                pendingBuffer = null
                pendingDecoded = null
            } else {
                // The sink kept this buffer and will call again with it. The bytes read above are
                // consumed by the delegate below and unreachable afterwards, so hold them until then.
                pendingBuffer = input
                pendingDecoded = decoded
            }
            return accepted
        }
        return super.handleBuffer(input, presentationTimeUs, encodedAccessUnitCount)
    }

    /** The sink's own "where is the sound now", or [C.TIME_UNSET] before it can answer. */
    private fun heardPositionUs(): Long =
        runCatching { inner.getCurrentPositionUs(false) }.getOrDefault(C.TIME_UNSET)

    /**
     * Publish how long THIS buffer will wait before it is heard, which is the lead the caption clock
     * runs on.
     *
     * The hold-back queue depth alone is not that number: between the queue's exit and the speaker
     * there is the AudioTrack's own buffer (a few hundred ms, and it changes as playback settles), so
     * a caption timed on the queue alone is systematically EARLY — the line appears before the words.
     * The sink's playback position is the other end of the same interval, so `buffer PTS − position`
     * is the whole wait, by definition, and it needs no assumption about device buffering.
     *
     * Only a live ([pcmOnly]) sink that is actually holding audio may write this: the value is
     * session-wide, and a VOD/preview sink's few hundred ms would otherwise replace a live session's
     * seconds. The queue depth stays the FLOOR (it is exact and needs no clock), and the clock reading
     * is trusted only inside the range a real sink can produce — a reading outside it means the two
     * numbers belong to different timelines (a flush mid-way) and is not a measurement.
     */
    private fun noteLead(presentationTimeUs: Long, heardUs: Long) {
        if (presentationTimeUs == C.TIME_UNSET || heardUs == C.TIME_UNSET) return
        val queuedMs = LiveAudioDelay.queuedMs
        if (queuedMs <= 0) return
        val clockLeadMs = (presentationTimeUs - heardUs) / 1_000L
        if (clockLeadMs < 0L || clockLeadMs > LiveAudioDelay.MAX_MS + TRACK_LEAD_SLACK_MS) return
        LiveAudioDelay.publishLead(maxOf(clockLeadMs, queuedMs.toLong()).toInt())
    }

    /**
     * The whole of [input] as 16-bit samples for its decoded [pcmEncoding], or null when the buffer
     * is too short to hold one sample. A view buffer shares the bytes but has its own position, so
     * reading it cannot disturb the position the delegate's [AudioSink.handleBuffer] expects. The
     * layout must follow [pcmEncoding] — parsing a float/24/32-bit buffer as if it were 16-bit shorts
     * corrupts every sample.
     *
     * The returned array is a reusable exact-size buffer (see [decodeBuffer]), NOT a fresh one: it
     * lives only until the caller has downmixed and handed it to the tap, which is guaranteed because
     * no second buffer is submitted while one is pending.
     */
    private fun decodeToShorts(input: java.nio.ByteBuffer): ShortArray? {
        val remaining = input.remaining()
        return when (pcmEncoding) {
            C.ENCODING_PCM_FLOAT -> if (remaining >= 4) floatToShort(input, remaining / 4) else null
            C.ENCODING_PCM_24BIT -> if (remaining >= 3) int24ToShort(input, remaining / 3) else null
            C.ENCODING_PCM_32BIT -> if (remaining >= 4) int32ToShort(input, remaining / 4) else null
            else -> if (remaining >= 2) {
                val view = input.asShortBuffer()
                val count = view.remaining()
                decodeBuffer(count).also { view.get(it) }
            } else null
        }
    }

    /** -1..1 float samples to 16-bit PCM (clip instead of wrapping). */
    private fun floatToShort(input: java.nio.ByteBuffer, count: Int): ShortArray {
        val view = input.asFloatBuffer()
        val out = decodeBuffer(count)
        for (i in 0 until count) {
            val s = view.get(i) * 32767f
            out[i] = s.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return out
    }

    /** 3-byte little-endian 24-bit PCM to 16-bit (shift down; the low bits are noise). */
    private fun int24ToShort(input: java.nio.ByteBuffer, count: Int): ShortArray {
        val out = decodeBuffer(count)
        var p = input.position()
        for (i in 0 until count) {
            val b0 = input.get(p++).toInt() and 0xFF
            val b1 = input.get(p++).toInt() and 0xFF
            val b2 = input.get(p++).toInt() and 0xFF
            // Little-endian: byte 0 is the LSB. b2 << 16 sign-extends the 24-bit value, then
            // shifting down by 8 keeps the top 16 bits (sign preserved).
            val raw = b0 or (b1 shl 8) or (b2 shl 16)
            out[i] = (raw shr 8).toShort()
        }
        return out
    }

    /** 32-bit PCM (int in high bytes) to 16-bit by taking the top half. */
    private fun int32ToShort(input: java.nio.ByteBuffer, count: Int): ShortArray {
        val view = input.asIntBuffer()
        val out = decodeBuffer(count)
        for (i in 0 until count) out[i] = (view.get(i) shr 16).toShort()
        return out
    }

    /**
     * Fold one frame to mono for the recognizer: a front centre that carries the programme is taken
     * alone, and everything else is averaged over only the speakers that actually carry this frame
     * (LFE excluded).
     *
     * **Why the centre comes first.** A television mix puts the thing the viewer must understand —
     * dialogue, and for a match the commentary — in the front centre, and spreads the crowd, music
     * and effects across the other speakers. Averaging the centre together with the surrounds is
     * therefore backwards for speech: it dilutes the one clean channel with exactly what the mix
     * engineer moved off it, which is how a stadium's crowd noise swamps a commentary that is clean
     * on its own. Taking the centre when it is carrying signal is the largest signal-to-noise win
     * available ahead of the recognizer.
     *
     * **Why the average is still here.** A stereo programme carried in a 5.1/7.1 layout, a passage
     * mixed wholly to the front pair, and the quad layout (no centre at all) all leave the centre
     * silent or absent, and for those the old rule stands. A flat `sum / channelCount` is wrong for
     * the 6/8-channel layouts a broadcast service hands over: the surrounds hold far less than the
     * front pair, so dividing by eight attenuated the mix by ~18 dB and what reached the recognizer
     * was a whisper — observed on a 48 kHz/8ch channel as `rms 29` of 32767 (≈ −61 dBFS) with sparse
     * recognition, while the level in the same buffer's front channels was fine. So the average
     * covers only the speakers carrying at least an eighth of the loudest speaker's energy: a
     * front-pair programme comes out at full level, a genuine 5.1 mix still folds down every speaker
     * that is carrying the programme, a quiet passage stays quiet instead of being amplified to
     * speech level, and mono/stereo keep the plain average they always had. LFE is never folded in:
     * it is pure low frequency and adds rumble, never words.
     *
     * [centreChannelIndex] decides whether a centre exists at all; a layout without one takes the
     * average path unchanged.
     *
     * The mono output comes from a reusable exact-size buffer (see [monoBuffer]) rather than a fresh
     * array per call — the single largest per-buffer allocation this thread made. Valid only until
     * the consumer has read it, which holds because the tap's resampler copies.
     */
    private fun downmixMono(decoded: ShortArray): ShortArray {
        // channelCount defaults to 1 and is only refreshed when a format reports one; a 0/1 count
        // (odd containers/raw TS) must never reach the divide below.
        if (channelCount <= 1) return decoded
        val channels = channelCount
        val frameCount = decoded.size / channels
        if (frameCount <= 0) return decoded // shorter than one full frame — nothing to downmix
        // LFE sits at index 3 in every Android layout that has one (5.1 and up); quad/5.0 do not.
        val lfe = if (channels >= LAYOUT_WITH_LFE_MIN_CHANNELS) LFE_CHANNEL_INDEX else -1
        val energy = LongArray(channels)
        var src = 0
        for (f in 0 until frameCount) {
            for (ch in 0 until channels) {
                val v = decoded[src++].toInt()
                if (ch != lfe) energy[ch] += (if (v < 0) -v else v).toLong()
            }
        }
        var loudest = 0L
        for (ch in 0 until channels) if (energy[ch] > loudest) loudest = energy[ch]
        // Nothing above the LFE carried any signal: emit silence rather than divide by no channels.
        if (loudest <= 0L) return monoBuffer(frameCount).also { it.fill(0) }
        val contributes = BooleanArray(channels)
        var contributors = 0
        for (ch in 0 until channels) {
            if (ch != lfe && energy[ch] * ACTIVE_CHANNEL_DENOMINATOR >= loudest * ACTIVE_CHANNEL_NUMERATOR) {
                contributes[ch] = true
                contributors++
            }
        }
        if (contributors == 0) return monoBuffer(frameCount).also { it.fill(0) }
        // The commentary channel, when the layout has one and this frame is carrying it. Taken
        // alone rather than folded in, so the crowd the other speakers hold cannot reach it.
        val centre = centreChannelIndex(channels)
        if (centre >= 0 && contributes[centre]) {
            val mono = monoBuffer(frameCount)
            var p = centre
            for (f in 0 until frameCount) {
                mono[f] = decoded[p]
                p += channels
            }
            return mono
        }
        val mono = monoBuffer(frameCount)
        var s = 0
        for (f in 0 until frameCount) {
            var sum = 0
            for (ch in 0 until channels) {
                val v = decoded[s++].toInt()
                if (contributes[ch]) sum += v
            }
            mono[f] = (sum / contributors).coerceIn(-32768, 32767).toShort()
        }
        // The dead `mono.size == decoded.size → decoded` aliasing check this line once carried was
        // unreachable: this path runs only with channels >= 2 (see the early return above), so the
        // frameCount-sized mono buffer can never equal the interleaved decoded array's length.
        return mono
    }

    /**
     * The index of this frame's FRONT CENTRE channel, or -1 when the layout has none.
     *
     * A declared [channelMask] settles it exactly: Android's canonical order places FRONT CENTRE
     * directly after the front pair, so its index is simply how many of FRONT_LEFT / FRONT_RIGHT
     * the mask sets (0–2). The mask is trusted only when it is internally consistent — as many bits
     * as the format's own channel count, the same check MediaCodecAudioRenderer makes before
     * forwarding one — because an inconsistent mask would name a channel the buffer does not hold.
     *
     * Without a mask (the tap is handed raw containers that declare only a count) the count is the
     * evidence, and it only reaches as far as is safe: every standard Android layout with five or
     * more channels (5.0/5.1/5.1-side/6.1/7.1) carries a front centre at index 2, while the layouts
     * that do not — quad (4ch: FL FR BL BR) and the back-centre variants at three and four channels
     * — all sit below that line. Returning -1 there leaves the frame on the averaging path instead
     * of reading a surround speaker as a centre.
     */
    private fun centreChannelIndex(channels: Int): Int {
        if (channels < 3) return -1
        val mask = channelMask
        if (mask != Format.NO_VALUE && Integer.bitCount(mask) == channels) {
            if ((mask and AudioFormat.CHANNEL_OUT_FRONT_CENTER) == 0) return -1
            val index =
                (if ((mask and AudioFormat.CHANNEL_OUT_FRONT_LEFT) != 0) 1 else 0) +
                    (if ((mask and AudioFormat.CHANNEL_OUT_FRONT_RIGHT) != 0) 1 else 0)
            return if (index < channels) index else -1
        }
        return if (channels >= LAYOUT_WITH_FRONT_CENTRE_MIN_CHANNELS) {
            FRONT_CENTRE_CHANNEL_INDEX
        } else {
            -1
        }
    }

    private companion object {
        /**
         * How much longer than the requested hold-back a real sink may report, ms: the AudioTrack's own
         * buffer plus its startup jitter. A reading beyond this belongs to another timeline (a flush
         * mid-measurement) and is discarded rather than believed.
         */
        const val TRACK_LEAD_SLACK_MS = 2_000L

        /** LFE's position in Android's 5.1/7.1 masks (FL, FR, FC, LFE, …). */
        const val LFE_CHANNEL_INDEX = 3

        /** FRONT CENTRE's position in Android's canonical order (FL, FR, FC, LFE, …). */
        const val FRONT_CENTRE_CHANNEL_INDEX = 2

        /** Every standard Android layout with at least this many channels carries a front centre. */
        const val LAYOUT_WITH_FRONT_CENTRE_MIN_CHANNELS = 5

        /** Layouts below 5.1 (mono/stereo/quad/5.0) have no LFE channel. */
        const val LAYOUT_WITH_LFE_MIN_CHANNELS = 6

        /** A speaker joins the mono mix when it carries ≥ 1/8 of the loudest speaker's energy. */
        const val ACTIVE_CHANNEL_NUMERATOR = 1L
        const val ACTIVE_CHANNEL_DENOMINATOR = 8L
    }
}