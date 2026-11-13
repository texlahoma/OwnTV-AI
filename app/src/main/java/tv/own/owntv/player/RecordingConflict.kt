package tv.own.owntv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import tv.own.owntv.core.recording.RecordingActivityTracker
import tv.own.owntv.core.recording.RecordingManager
import tv.own.owntv.core.database.dao.RecordingDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What the player's failure screen should offer when a **recording** is why the picture stopped.
 *
 * Null means this is an ordinary failure and the screen keeps its single Retry. Non-null means the
 * user is being offered the swap, and [stopAndRetry] is the act behind the first button.
 */
class RecordingConflict internal constructor(
    private val onStopAndRetry: () -> Unit,
) {
    fun stopAndRetry() = onStopAndRetry()
}

/**
 * Decide whether this failure is the recording conflict: **something is recording, and the playback
 * error is the provider refusing a second stream**.
 *
 * Both halves are required, and the second is what stops the swap being offered for every failure
 * that happens to coincide with a recording. The app knows the user pressed record, so it can
 * attribute the refusal instead of showing a generic reconnect spinner — but only when the refusal
 * really is a session limit.
 */
@Composable
fun rememberRecordingConflict(error: PlaybackFailure?): RecordingConflict? {
    val tracker: RecordingActivityTracker = koinInject()
    val manager: RecordingManager = koinInject()
    val recordingDao: RecordingDao = koinInject()
    val active by tracker.active.collectAsStateWithLifecycle()
    if (active.isEmpty() || error == null) return null
    if (!isSessionLimitFailure(error)) return null

    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val ids = active.keys.toList()
    return remember(ids) {
        RecordingConflict {
            // Stop every running recording, not merely the first: the user asked to watch, and
            // leaving a second one holding the connection would fail again for the same reason.
            scope.launch {
                ids.forEach { id -> recordingDao.getById(id)?.let { manager.stop(it) } }
            }
        }
    }
}

/**
 * Is this playback failure one a busy provider produces?
 *
 * Matched on the type rather than on rendered text. Two shapes qualify:
 *
 * - **`AccountInUse`**, and **`Raw` carrying HTTP 458** from older paths — the provider saying outright "your account's session is already in
 *   use", which is the code `LiveStreamQuirks.isSessionLimit` already recognises.
 * - **`StreamUnavailable`** — the generic refusal. On its own it means little, which is why this is
 *   only ever consulted *after* the caller has established that something is recording. The
 *   app should attribute the failure precisely because it knows the user pressed
 *   record; without a recording running, none of this is reached.
 *
 * Deliberately not `LostConnection` or the decoder failures: those have nothing to do with the
 * provider's stream count, and offering to stop a recording would be a lie about the cause.
 */
private fun isSessionLimitFailure(error: PlaybackFailure): Boolean = when (error) {
    PlaybackFailure.AccountInUse -> true
    is PlaybackFailure.Raw -> error.message.contains(SESSION_LIMIT_CODE.toString())
    is PlaybackFailure.StreamUnavailable -> true
    else -> false
}

private const val SESSION_LIMIT_CODE = 458
