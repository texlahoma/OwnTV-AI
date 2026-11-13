package tv.own.owntv.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** When the live ladder may call an mpv tune opened, against the same combine [EnginePair] runs. */
@OptIn(ExperimentalCoroutinesApi::class)
class MpvOutcomeTest {

    @Test fun `a fresh load with no error and no playback is still opening`() {
        assertNull(mpvOutcomeOf(playbackStarted = false, error = null))
    }

    @Test fun `playback moving is opened`() {
        assertEquals(MpvOutcome(opened = true, error = null), mpvOutcomeOf(playbackStarted = true, error = null))
    }

    @Test fun `an error is a failure, even with playback started`() {
        val outcome = mpvOutcomeOf(playbackStarted = true, error = PlaybackFailure.Channel)
        assertEquals(false, outcome?.opened)
        assertEquals(PlaybackFailure.Channel.toString(), outcome?.error)
    }

    @Test fun `the flows waiting at their load-time values settle nothing until playback starts`() = runTest {
        val started = MutableStateFlow(false)
        val error = MutableStateFlow<PlaybackFailure?>(null)
        val outcome = async { combine(started, error, ::mpvOutcomeOf).first { it != null } }
        runCurrent()
        assertFalse("must not read as opened before playback moves", outcome.isCompleted)
        started.value = true
        assertEquals(MpvOutcome(opened = true, error = null), outcome.await())
    }

    @Test fun `the flows settle as failed when mpv reports an error`() = runTest {
        val started = MutableStateFlow(false)
        val error = MutableStateFlow<PlaybackFailure?>(null)
        val outcome = async { combine(started, error, ::mpvOutcomeOf).first { it != null } }
        runCurrent()
        error.value = PlaybackFailure.StreamLink
        assertEquals(false, outcome.await()?.opened)
    }
}
