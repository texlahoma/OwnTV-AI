package tv.own.owntv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R

/**
 * Back on a live channel whose saved copy was kept while the user was away: continue from where
 * they left, or watch live. Resume is focused (the likelier wish after an accidental exit); Back
 * leaves them live, where the channel already is.
 */
@Composable
fun TimeshiftResumeDialog(onResume: () -> Unit, onGoLive: () -> Unit) {
    tv.own.owntv.ui.stage.StageConfirm(
        title = stringResource(R.string.player_timeshift_resume_title),
        body = stringResource(R.string.player_timeshift_resume_message),
        cancel = stringResource(R.string.player_go_live),
        confirm = stringResource(R.string.common_resume),
        onConfirm = onResume,
        onCancel = onGoLive,
        eyebrow = null,
    )
}
