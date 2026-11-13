package tv.own.owntv.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.ui.theme.mpx

/**
 * Small "Resume playback?" prompt shown (in the "Ask to resume" mode) when a movie/episode has a
 * saved position. Resume is pre-focused; Back dismisses without starting playback.
 */
@Composable
fun ResumeDialog(
    positionMs: Long,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Resume is focused: carrying on is the common answer.
    tv.own.owntv.ui.stage.StageConfirm(
        title = stringResource(R.string.common_resume_prompt),
        body = stringResource(R.string.common_resume_position, formatTimestamp(positionMs)),
        cancel = stringResource(R.string.common_start_over),
        confirm = stringResource(R.string.common_resume),
        onConfirm = onResume,
        onCancel = onStartOver,
        onBack = onDismiss,
        eyebrow = null,
        width = 640.mpx,
    )
}

/**
 * 0:42 / 23:45 / 1:23:45 style timestamp — the app's one duration format.
 *
 * Used for a resume position, the player's elapsed/remaining readout and the audio bar alike. The
 * player used to carry two private copies of this arithmetic against a duplicate pair of string
 * resources (`player_track_*`), which were byte-identical to these in every locale.
 */
@Composable
fun formatTimestamp(ms: Long): String = formatTimestamp(LocalResources.current, ms)

/** The same format outside composition — a value read at a button press rather than on every tick. */
fun formatTimestamp(res: android.content.res.Resources, ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        res.getString(R.string.common_timestamp_hours, h, m, s)
    } else {
        res.getString(R.string.common_timestamp_minutes, m, s)
    }
}
