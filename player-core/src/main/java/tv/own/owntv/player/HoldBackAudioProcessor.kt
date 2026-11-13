package tv.own.owntv.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer

/**
 * Holds decoded PCM back by a fixed amount of time **in application memory**, not in the AudioTrack.
 *
 * Why it exists: an AI caption has to hear a word before the viewer does, so the audio being decoded
 * must run ahead of the audio being played. The obvious place for that gap is the AudioTrack's own
 * buffer, but its depth is fixed by the device's audio HAL — and the TV this was built for hands back
 * a multi-second track that never starts, failing the whole channel with
 * `ERROR_CODE_AUDIO_TRACK_INIT_FAILED` and costing the (single) video decoder when the engine reopens.
 *
 * This processor puts the gap where the device cannot refuse it: the renderer feeds it at decode
 * speed, the sink underneath keeps its ordinary, small buffer, and what comes out is [holdBackMs]
 * behind what went in. The AI tap sits *outside* the sink ([TapAudioSink]), so it still sees the
 * undelayed audio and runs the recogniser that far ahead of the speaker.
 *
 * **The depth is read live, per buffer, from [holdBackMs].** It used to be a number fixed when the
 * sink was built, which made the delay take effect only on the next player rebuild — and the live
 * engine keeps one player alive across tunes, so switching captions on produced a status line that
 * requested 8 s, reported a sink built for 8 s, and measured nothing at all, forever. With a live
 * request the processor can also start holding *mid-playback*: the queue fills over the requested
 * seconds (the picture stands still for exactly as long, since the audio clock is what paces it) and
 * from then on the channel plays that far behind live. [LivePreviewEngine]'s watchdogs sit that fill
 * out (see `holdBackWarmUntilMs`) — a frozen picture here is the delay working, not a dead stream.
 *
 * Consequences, stated plainly because they are user-visible: the player's clock is the audio clock,
 * so the picture is held back by the same amount, and a live channel needs [holdBackMs] of stream
 * before the first word can play — the tune (or the moment captions are switched on) spends that long
 * frozen. Both are properties of "delay the live channel by N seconds", not of this implementation.
 */
@UnstableApi
class HoldBackAudioProcessor(private val holdBackMs: () -> Int) : BaseAudioProcessor() {

    /** Queued PCM, oldest first. */
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0

    /** Bytes of this stream per second, from [onConfigure]; turns [pendingBytes] into milliseconds. */
    private var bytesPerSecond = 0

    /**
     * The depth this queue has already been primed to, bytes (0 = not primed). Only the part above it
     * is released, which is what makes the lead steady — and what makes a drained queue resume at the
     * depth it still has instead of going silent again to rebuild a backlog the viewer already paid for.
     */
    private var primedBytes = 0

    /**
     * Always active. "Inactive" is permanent — the pipeline snapshots its active processors at flush
     * and drops the rest — so a processor that answered "not yet" to a caption session switched on
     * later could never come back without rebuilding the player, which is the one thing this design
     * exists to avoid. With a 0 ms target it simply forwards every buffer untouched.
     */
    override fun isActive(): Boolean = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        bytesPerSecond = if (inputAudioFormat.sampleRate > 0) {
            inputAudioFormat.sampleRate *
                Util.getPcmFrameSize(inputAudioFormat.encoding, inputAudioFormat.channelCount)
        } else {
            0
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val requested = targetBytes()
        val remaining = inputBuffer.remaining()
        if (remaining == 0) {
            publish(requested)
            return
        }
        val chunk = ByteArray(remaining)
        inputBuffer.get(chunk)
        pending.addLast(chunk)
        pendingBytes += remaining
        when {
            // Captions off: nothing is being withheld. The backlog leaves at real time (the sink then
            // plays it out), so the sound keeps trailing live until the next tune — never a jump in the
            // middle of a sentence. Resetting the primed depth means switching captions back on starts
            // a fresh fill instead of silently reusing this decision.
            requested <= 0 -> {
                primedBytes = 0
                if (pendingBytes > 0) release(pendingBytes)
            }
            // A deeper request than this queue was primed for (captions switched on mid-play, or a
            // longer delay configured): withhold everything until the new depth exists. This is the
            // freeze the viewer sees when the delay is turned on — the delay itself, not a fault.
            requested > primedBytes -> {
                if (pendingBytes >= requested) {
                    primedBytes = requested
                    release(pendingBytes - requested)
                }
            }
            // Primed and steady: pass exactly what arrives and keep the backlog. The queue depth is
            // therefore constant — and after a network stall drained it, playback resumes at the
            // smaller lead that is left instead of going silent for another few seconds to re-fill.
            else -> release(remaining)
        }
        publish(requested)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        pending.clear()
        pendingBytes = 0
        // A flush is a new stream/timeline: the old depth is gone and the new one has to be built.
        primedBytes = 0
        if (holdBackMs() > 0) {
            LiveAudioDelay.resetLead()
            LiveAudioDelay.filling = false
        }
    }

    override fun onReset() {
        pending.clear()
        pendingBytes = 0
        primedBytes = 0
        bytesPerSecond = 0
    }

    /** Whole frames only: releasing a partial frame at the head would shift every sample after it. */
    private fun targetBytes(): Int {
        val ms = holdBackMs().coerceAtLeast(0)
        if (ms <= 0 || bytesPerSecond <= 0) return 0
        return ((bytesPerSecond.toLong() * ms) / 1_000L).toInt()
    }

    /**
     * Publish what the recogniser's lead over the speaker actually IS: the milliseconds of PCM waiting
     * in this queue (the tap adds the sink's own buffer on top — see [TapAudioSink.noteLead]). The tap
     * used to infer the whole lead from the sink's playback clock, which read 0 whenever no PCM flowed
     * at all — the `dly 8s/0ms/8s` reading this replaces. A request of 0 means this sink is not holding
     * anything back, so it must not overwrite another engine's (or its own session's) value.
     */
    private fun publish(requested: Int) {
        if (requested <= 0) return
        if (bytesPerSecond <= 0) {
            LiveAudioDelay.resetLead()
            LiveAudioDelay.noteFilling(true)
            return
        }
        // Only the QUEUE depth: the tap adds the track buffer and publishes the real lead (see
        // LiveAudioDelay.publishQueued — two writers on one field made it alternate between two
        // references ~0.6 s apart, and every caption stamped in between was that much off).
        LiveAudioDelay.publishQueued(((pendingBytes.toLong() * 1_000L) / bytesPerSecond).toInt())
        // "Filling" means the lead has never been built yet — NOT that the queue is below its target,
        // which a network stall can make true forever. Only the first case is a deliberate silence the
        // audio watchdog must forgive; counting a drained queue as "still filling" would suppress that
        // watchdog for the rest of the session.
        LiveAudioDelay.noteFilling(primedBytes < requested)
    }

    private fun release(count: Int) {
        val out = replaceOutputBuffer(count)
        var released = 0
        while (released < count) {
            val head = pending.first()
            val take = minOf(head.size, count - released)
            out.put(head, 0, take)
            released += take
            if (take == head.size) {
                pending.removeFirst()
            } else {
                pending[0] = head.copyOfRange(take, head.size)
            }
        }
        pendingBytes -= released
        out.flip()
    }
}
