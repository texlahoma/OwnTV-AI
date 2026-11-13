package tv.own.owntv.player

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.AuxEffectInfo
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.player.PlaybackAudioTap.CaptureInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Regression for the d-build "seg 0 / words 0 / fmt ?" failure.
 *
 * Media3 1.11's MediaCodecAudioRenderer asks about formats through
 * `AudioSink.supportsFormat(Format)` only — it never calls `getFormatSupport(Format)`, and
 * ForwardingAudioSink.supportsFormat forwards straight to the delegate. A capture hook placed on
 * getFormatSupport is therefore dead code: the tap kept its 24 kHz / mono / s16 defaults, parsed
 * real 48 kHz stereo TV buffers against them, and fed the recognizer time-stretched interleaved
 * garbage (aud/wall ≈ 2.3, words 0). The authoritative call is the non-final
 * `configure(AudioSinkConfig)` overload, which the renderer invokes with the exact decoded PCM
 * format right before the first buffer.
 */
@UnstableApi
class TapAudioSinkFormatCaptureTest {

    private data class Pcm(val samples: ShortArray, val sampleRate: Int)

    private lateinit var sink: TapAudioSink
    private val received = ArrayList<Pcm>()

    @Before
    fun setUp() {
        received.clear()
        PlaybackAudioTap.nonPcmBuffers.set(0)
        assertTrue(PlaybackAudioTap.start { samples, rate, _ -> received.add(Pcm(samples.copyOf(), rate)) })
        sink = TapAudioSink(NoopAudioSink())
    }

    @After
    fun tearDown() {
        PlaybackAudioTap.stop()
    }

    private fun rawFormat(rate: Int, channels: Int, encoding: Int = C.ENCODING_PCM_16BIT) =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(rate)
            .setChannelCount(channels)
            .setPcmEncoding(encoding)
            .build()

    private fun rawFormatWithMask(rate: Int, channels: Int, mask: Int) =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(rate)
            .setChannelCount(channels)
            .setChannelMask(mask)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()

    private fun encodedFormat(rate: Int, channels: Int) =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setSampleRate(rate)
            .setChannelCount(channels)
            .setPcmEncoding(C.ENCODING_AC3)
            .build()

    private fun configure(format: Format, target: TapAudioSink = sink) {
        target.configure(AudioSink.AudioSinkConfig.Builder(format).build())
    }

    private fun feedShorts(vararg samples: Short) {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(samples)
        assertTrue(sink.handleBuffer(buf, 0L, 1))
    }

    private fun feedFloats(vararg samples: Float) {
        val buf = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(samples)
        assertTrue(sink.handleBuffer(buf, 0L, 1))
    }

    /** The exact device failure: no support queries at all, only configure + buffers. */
    @Test
    fun configureAloneCapturesTrueFormatAndDownmixes() {
        configure(rawFormat(48_000, 2))

        // Interleaved stereo frames.
        feedShorts(100, 200, -100, -200, 30_000, 30_000, -30_000, -30_000)

        assertEquals(1, received.size)
        val pcm = received.first()
        assertEquals(48_000, pcm.sampleRate)
        assertArrayEquals(shortArrayOf(150, -150, 30_000, -30_000), pcm.samples)
        assertEquals(
            CaptureInfo(48_000, 2, C.ENCODING_PCM_16BIT, MimeTypes.AUDIO_RAW, raw = true),
            PlaybackAudioTap.captureInfo,
        )
        assertEquals("48000Hz/2ch/s16", PlaybackAudioTap.captureInfo?.label())
    }

    /**
     * The observed 48 kHz/8ch service: a stereo programme carried in a 7.1 layout, so only the front
     * pair holds signal. Dividing by all eight speakers attenuated the mix ~18 dB (on screen:
     * `fmt 48000Hz/8ch/s16 · rms 29` ≈ −61 dBFS, sparse recognition). The front pair must come
     * through at its own level.
     */
    @Test
    fun eightChannelLayoutKeepsTheLevelOfTheSpeakersThatCarryTheProgramme() {
        configure(rawFormat(48_000, 8))

        feedShorts(
            1_000, 1_000, 0, 0, 0, 0, 0, 0,
            -1_000, -1_000, 0, 0, 0, 0, 0, 0,
        )

        assertEquals(1, received.size)
        assertArrayEquals(shortArrayOf(1_000, -1_000), received.first().samples)
    }

    /** LFE is low-frequency only: folding it into the speech mix adds rumble, never words. */
    @Test
    fun lfeIsExcludedFromTheSpeechDownmix() {
        configure(rawFormat(48_000, 6))

        // 5.1 frame: FL FR FC LFE BL BR, with the LFE far louder than every speaker that has speech.
        feedShorts(1_000, 1_000, 1_000, 30_000, 1_000, 1_000)

        assertArrayEquals(shortArrayOf(1_000), received.first().samples)
    }

    /** A silent multichannel frame stays silent (no divide by a phantom contributor set). */
    @Test
    fun silentMultichannelFrameDownmixesToSilence() {
        configure(rawFormat(48_000, 8))

        feedShorts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        assertArrayEquals(shortArrayOf(0, 0), received.first().samples)
    }

    /**
     * The football case. A 5.1 mix carries the commentary in the centre and the crowd in every
     * other speaker, so averaging the six channels folds the crowd into the commentary. The centre
     * alone is the signal the recognizer needs; here it is a third of the level of the speakers
     * around it and must still be what comes out.
     */
    @Test
    fun frontCentreCarryingTheProgrammeIsTakenAlone() {
        configure(rawFormat(48_000, 6))

        // FL FR FC LFE BL BR — a quiet centre against a loud crowd around it.
        feedShorts(3_000, 3_000, 1_000, 0, 3_000, 3_000)

        assertArrayEquals(shortArrayOf(1_000), received.first().samples)
    }

    /**
     * A stereo programme carried in a 5.1 layout leaves the centre silent. There the fallback must
     * hold, or the front pair — where the programme actually is — would be thrown away for silence.
     */
    @Test
    fun silentCentreLeavesTheFrontPairCarryingTheProgramme() {
        configure(rawFormat(48_000, 6))

        // FL FR FC LFE BL BR — centre empty, programme on the front pair.
        feedShorts(2_000, 2_000, 0, 0, 0, 0)

        assertArrayEquals(shortArrayOf(2_000), received.first().samples)
    }

    /**
     * Multichannel does not imply a centre: quad (4ch: FL FR BL BR) has none, and index 2 there is
     * a *back* speaker. The count alone must not promote it, so the frame stays averaged.
     */
    @Test
    fun quadWithoutAMaskIsNotReadAsHavingACentre() {
        configure(rawFormat(48_000, 4))

        feedShorts(1_000, 1_000, 8_000, 8_000)

        assertArrayEquals(shortArrayOf(4_500), received.first().samples)
    }

    /**
     * A declared mask — not the channel count — says where the centre is. With no FRONT_CENTRE in
     * it, even a five-channel layout keeps to the averaging path instead of taking index 2 alone.
     */
    @Test
    fun aMaskWithoutAFrontCentreKeepsTheFrameAveraged() {
        configure(
            rawFormatWithMask(
                48_000,
                5,
                AudioFormat.CHANNEL_OUT_FRONT_LEFT or AudioFormat.CHANNEL_OUT_FRONT_RIGHT or
                    AudioFormat.CHANNEL_OUT_BACK_LEFT or AudioFormat.CHANNEL_OUT_BACK_RIGHT or
                    AudioFormat.CHANNEL_OUT_BACK_CENTER,
            ),
        )

        // FL FR BL BR BC — index 2 is a surround speaker, not a commentary channel.
        feedShorts(1_000, 1_000, 8_000, 8_000, 8_000)

        assertArrayEquals(shortArrayOf(5_200), received.first().samples)
    }

    /**
     * The same 7.1 layout as the level regression above, but with the mask a real container
     * declares: the centre is now located exactly and taken alone.
     */
    @Test
    fun aDeclaredMaskLocatesTheCentre() {
        configure(
            rawFormatWithMask(
                48_000,
                8,
                AudioFormat.CHANNEL_OUT_FRONT_LEFT or AudioFormat.CHANNEL_OUT_FRONT_RIGHT or
                    AudioFormat.CHANNEL_OUT_FRONT_CENTER or AudioFormat.CHANNEL_OUT_LOW_FREQUENCY or
                    AudioFormat.CHANNEL_OUT_BACK_LEFT or AudioFormat.CHANNEL_OUT_BACK_RIGHT or
                    AudioFormat.CHANNEL_OUT_SIDE_LEFT or AudioFormat.CHANNEL_OUT_SIDE_RIGHT,
            ),
        )

        // FL FR FC LFE BL BR SL SR
        feedShorts(2_000, 2_000, 900, 0, 2_000, 2_000, 2_000, 2_000)

        assertArrayEquals(shortArrayOf(900), received.first().samples)
    }

    /** The realistic 1.11 call sequence: encoded probe, RAW probe, configure — none of them steered. */
    @Test
    fun supportQueriesCaptureRateAndChannelsWithoutChangingRouting() {
        // The encoded probe is answered by the delegate: the tap must not force an in-app decode.
        assertTrue(sink.supportsFormat(encodedFormat(48_000, 2)))
        // Renderer probes a synthetic s16 RAW format derived from the candidate.
        assertTrue(sink.supportsFormat(rawFormat(48_000, 2)))
        configure(rawFormat(48_000, 2))

        feedShorts(1_000, 3_000)
        assertEquals(48_000, received.first().sampleRate)
        assertArrayEquals(shortArrayOf(2_000), received.first().samples)
    }

    /** Once the tap is disarmed the wrapper must be invisible to format selection (it always is now). */
    @Test
    fun encodedFormatPassesThroughWithoutConsumer() {
        PlaybackAudioTap.stop()
        assertTrue(sink.supportsFormat(encodedFormat(48_000, 2)))
    }

    /** Float decoder output (float output enabled) must not be parsed as 16-bit shorts. */
    @Test
    fun floatBuffersAreConvertedUsingConfiguredEncoding() {
        configure(rawFormat(16_000, 1, C.ENCODING_PCM_FLOAT))

        feedFloats(0.5f, -1.0f, 0.25f)

        val pcm = received.first()
        assertEquals(16_000, pcm.sampleRate)
        assertArrayEquals(shortArrayOf(16_383, -32_767, 8_191), pcm.samples)
        assertEquals("f32", PlaybackAudioTap.captureInfo?.label()?.substringAfterLast('/'))
    }

    /**
     * A renderer flush (initial start, seek, snap-to-live) invalidates the media timeline: the next
     * buffer is not a continuation of the previous one. The consumer must be told so the recognizer
     * restarts instead of decoding across an invisible gap — the e-build failure where live audio
     * arrived at ~57 % of wall clock with almost no words decoded.
     */
    @Test
    fun flushNotifiesConsumerOfTimelineDiscontinuity() {
        PlaybackAudioTap.stop()
        var discontinuities = 0
        PlaybackAudioTap.start(object : PlaybackAudioTap.PcmConsumer {
            override fun onPcm(samples: ShortArray, sampleRate: Int, presentationTimeUs: Long) = Unit
            override fun onDiscontinuity() { discontinuities++ }
        })
        configure(rawFormat(48_000, 2))
        feedShorts(10, 20, 30, 40)
        sink.flush()
        sink.flush()

        assertEquals(2, discontinuities)
    }

    /** No consumer attached: flush simply forwards, with no callback. */
    @Test
    fun flushWithoutConsumerIsHarmless() {
        PlaybackAudioTap.stop()
        sink.flush() // must not throw
    }

    /** A stream-format change mid-playback re-captures the new layout. */
    @Test
    fun formatChangeReconfiguresCapture() {
        configure(rawFormat(48_000, 2))
        feedShorts(10, 20)
        configure(rawFormat(24_000, 1))
        feedShorts(42)

        assertEquals(2, received.size)
        assertEquals(48_000, received[0].sampleRate)
        assertEquals(1, received[0].samples.size)
        assertEquals(24_000, received[1].sampleRate)
        assertArrayEquals(shortArrayOf(42), received[1].samples)
        assertEquals("24000Hz/1ch/s16", PlaybackAudioTap.captureInfo?.label())
    }

    /**
     * The real failure this suite was extended for: the sink was configured for a bypassed bitstream
     * (the renderer chose passthrough while no consumer was armed, so our supportsFormat gate was
     * never consulted this time), and the tap was handed AC-3 access units. Reading them as s16 PCM
     * yields loud noise the recognizer transcribes as blank for minutes (verified: rms~10.4k, 0
     * words) — the "words 4 over 81 s" report. The tap must refuse to read them, count them, and
     * label the capture so the state is provable on screen.
     */
    @Test
    fun bypassedEncodedBitstreamIsNeverReadAsPcm() {
        configure(encodedFormat(48_000, 2))

        // A compressed access unit's bytes, not samples.
        feedShorts(0x0B77.toShort(), 0x1234, 0x5678, 0x0000)

        assertTrue("consumer must not see a bitstream as PCM", received.isEmpty())
        assertEquals(1L, PlaybackAudioTap.nonPcmBuffers.get())
        assertEquals("48000Hz/2ch/ac3!", PlaybackAudioTap.captureInfo?.label())
    }

    /**
     * A probe after configure must not relabel a decoded-PCM stream as encoded (or vice versa): the
     * rate/channel/encoding triple is identical either way, so a stale probe would hide the real
     * state behind a healthy-looking `s16`.
     */
    @Test
    fun lateProbeCannotRelabelConfiguredCapture() {
        configure(rawFormat(48_000, 2))

        sink.supportsFormat(encodedFormat(44_100, 6))

        assertEquals("48000Hz/2ch/s16", PlaybackAudioTap.captureInfo?.label())
    }

    /**
     * A refused buffer must be tapped once — neither twice nor not at all. `AudioSink.handleBuffer`
     * returning false is a "not yet", not a rejection: MediaCodecAudioRenderer keeps the output
     * buffer pending and calls handleBuffer AGAIN with the same object (see its
     * `nextBufferToWritePresentationTimeUs` / `processOutputBufferResultHolder`), releasing the codec
     * buffer only on the accepted path. Two opposite bugs grew from that:
     *
     *  * Tapping on every submission fed the recognizer each strained buffer more than once — the
     *    capture ran ahead of wall clock (`aud 472/284s`, ~1.66x) while the duplicates burned decode
     *    CPU and overflowed the PCM queue.
     *  * Tapping only on the accepted submission tapped the buffer after the sink had consumed it
     *    (`DefaultAudioSink` accepts exactly when the input has no bytes left), i.e. nothing at all —
     *    the capture then starved (`aud 125/358s`, ~0.35x) and whole buffers went unrecognised.
     *
     * Here the sink drains the whole buffer before refusing, so the accepted retry sees an empty
     * remainder: the tap must still deliver the full audio, exactly once.
     */
    @Test
    fun refusedBufferIsTappedOnceWhenTheSinkFinallyAccepts() {
        val retrying = TapAudioSink(NoopAudioSink(rejectionsBeforeAccept = 1))
        configure(rawFormat(48_000, 2), retrying)
        val buf = ByteBuffer.allocate(4 * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(shortArrayOf(100, 200, -100, -200))

        // First submission: the sink queues it all and is not ready, so the renderer keeps it pending.
        assertFalse(retrying.handleBuffer(buf, 0L, 1))
        assertTrue("a refused buffer must not reach the consumer", received.isEmpty())

        // The renderer retries the SAME, now-empty buffer; the whole chunk is tapped exactly once.
        assertTrue(retrying.handleBuffer(buf, 0L, 1))
        assertEquals(1, received.size)
        assertArrayEquals(shortArrayOf(150, -150), received.first().samples)
    }

    /**
     * The accepted re-submission is only the buffer's TAIL: DefaultAudioSink queues part of the
     * input, advances `position` and returns false, then accepts the rest next time. Reading at the
     * accept alone would tap the tail and silently drop the head — on device that halves the capture
     * (`aud 125/358s`). The whole buffer must reach the consumer exactly once.
     */
    @Test
    fun retriedBufferIsTappedWholeNotJustItsTail() {
        // One stereo frame (4 bytes) is queued and refused; the remaining three frames are accepted.
        val retrying = TapAudioSink(NoopAudioSink(rejectionsBeforeAccept = 1, drainOnReject = 4))
        configure(rawFormat(48_000, 2), retrying)
        val buf = ByteBuffer.allocate(8 * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(shortArrayOf(100, 200, -100, -200, 30_000, 30_000, -30_000, -30_000))

        assertFalse(retrying.handleBuffer(buf, 0L, 1))
        assertTrue("nothing may be tapped before the sink accepts", received.isEmpty())

        assertTrue(retrying.handleBuffer(buf, 0L, 1))
        assertEquals(1, received.size)
        assertArrayEquals(
            "the head queued before the retry must not be lost",
            shortArrayOf(150, -150, 30_000, -30_000),
            received.first().samples,
        )
    }

    /** The signal the live engine uses to recover a sink that was settled on passthrough *before* the
     * tap armed — the "first channel, switch AI subtitles on, streaming recognition dead until you
     * zap out and back in" report. It must track the tap's lifetime exactly, or the engine either
     * never rebuilds (the bug) or rebuilds on every collect.
     */
    @Test
    fun armedSignalFollowsTapLifetime() {
        PlaybackAudioTap.stop()
        assertFalse(PlaybackAudioTap.armed.value)

        assertTrue(PlaybackAudioTap.start { _, _, _ -> })
        assertTrue(PlaybackAudioTap.armed.value)

        // A second attach must not take the tap (single owner) and must not disturb the signal.
        assertFalse(PlaybackAudioTap.start { _, _, _ -> })
        assertTrue(PlaybackAudioTap.armed.value)

        PlaybackAudioTap.stop()
        assertFalse(PlaybackAudioTap.armed.value)
    }

    /**
     * The DEFAULT (non-live) tap observes audio and must never change how the renderer routes it: a
     * VOD or hero-preview sink keeps the routing subtitles-off playback chooses, and the app is told
     * instead (passThroughAudio).
     *
     * The live sink is the deliberate exception — [TapAudioSink.pcmOnly], see the test below: a
     * bypassed track carries no PCM, so on live TV there would be neither words to recognise nor
     * audio to hold back.
     */
    @Test
    fun encodedAudioIsReportedNotForcedToDecode() {
        val encoded = encodedFormat(48_000, 2)
        // The tap is armed by setUp; the sink itself is the default (non-live) one.
        assertTrue("format selection must match subtitles-off playback", sink.supportsFormat(encoded))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(encoded))

        // An authoritative configure on the bypassed track is what tells the app there is no PCM.
        PlaybackAudioTap.clearPassThroughNotice()
        assertNull(PlaybackAudioTap.passThroughAudio.value)
        configure(encoded)
        assertEquals(MimeTypes.AUDIO_AC3, PlaybackAudioTap.passThroughAudio.value)

        // A decoded stream clears it again: the recognizer can hear that one.
        configure(rawFormat(48_000, 2))
        assertNull(PlaybackAudioTap.passThroughAudio.value)
    }

    /**
     * A channel that settled on passthrough *before* the tap armed (MediaCodecAudioRenderer never
     * re-asks the sink while the same track stays selected) must be reported at arm time — the app
     * stands the session down instead of waiting for words that can never arrive.
     */
    @Test
    fun armingOverAnAlreadyBypassedChannelReportsIt() {
        PlaybackAudioTap.stop()
        PlaybackAudioTap.clearPassThroughNotice()
        configure(encodedFormat(48_000, 2))

        assertTrue(PlaybackAudioTap.start { _, _, _ -> })
        assertEquals(MimeTypes.AUDIO_AC3, PlaybackAudioTap.passThroughAudio.value)
        PlaybackAudioTap.stop()
        PlaybackAudioTap.clearPassThroughNotice()
    }

    /**
     * The LIVE sink refuses to bypass an encoded track, because MediaCodecAudioRenderer only creates
     * a decoder for it when the sink says it cannot play it directly (`getDecoderInfos`: supported →
     * decrypt-only codec, unsupported → a real decoder). Without this the TV decodes the bitstream
     * itself, no PCM ever reaches the tap, and the caption session has nothing to hear or to delay —
     * the on-screen `dly 8s/0ms/8s` with the switch turning itself off a moment after a tune.
     */
    @Test
    fun liveSinkForcesInAppDecodeOfEncodedAudio() {
        val live = TapAudioSink(NoopAudioSink(), pcmOnly = true)
        val encoded = encodedFormat(48_000, 2)

        assertFalse(live.supportsFormat(encoded))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, live.getFormatSupport(encoded))
        // …and it still accepts the decoded stream the decoder will actually hand it.
        assertTrue(live.supportsFormat(rawFormat(48_000, 2)))
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
            live.getFormatSupport(rawFormat(48_000, 2)),
        )
    }
}

/**
 * Minimal AudioSink: TapAudioSink only needs delegation to succeed; it never plays audio here.
 *
 * [rejectionsBeforeAccept] models the real sink under pressure: `handleBuffer` returns false until
 * its buffer has room, which makes MediaCodecAudioRenderer re-submit the same buffer (see the
 * refused-buffer regression above). [drainOnReject] models what DefaultAudioSink does before it
 * returns that false: it queues the head of the input into its processing pipeline and advances
 * `buffer.position()`, so the accepted re-submission sees only the unconsumed remainder — possibly
 * an empty one. A mock that left the position untouched would make the old read-on-accept bug look
 * harmless.
 */
@UnstableApi
private class NoopAudioSink(
    private var rejectionsBeforeAccept: Int = 0,
    private val drainOnReject: Int = Int.MAX_VALUE,
) : AudioSink {
    override fun setListener(listener: AudioSink.Listener) = Unit
    override fun supportsFormat(format: Format): Boolean = true
    override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = Unit
    override fun configure(config: AudioSink.AudioSinkConfig) = Unit
    override fun play() = Unit
    override fun handleDiscontinuity() = Unit
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (rejectionsBeforeAccept > 0) {
            rejectionsBeforeAccept--
            val drain = minOf(drainOnReject, buffer.remaining())
            buffer.position(buffer.position() + drain)
            return false
        }
        return true
    }
    override fun playToEndOfStream() = Unit
    override fun isEnded(): Boolean = true
    override fun hasPendingData(): Boolean = false
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
    override fun getSkipSilenceEnabled(): Boolean = false
    override fun setAudioAttributes(audioAttributes: AudioAttributes) = Unit
    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT
    override fun setAudioSessionId(audioSessionId: Int) = Unit
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit
    override fun getAudioTrackBufferSizeUs(): Long = 0L
    override fun enableTunnelingV21() = Unit
    override fun disableTunneling() = Unit
    override fun setVolume(volume: Float) = Unit
    override fun pause() = Unit
    override fun flush() = Unit
    override fun reset() = Unit
}
