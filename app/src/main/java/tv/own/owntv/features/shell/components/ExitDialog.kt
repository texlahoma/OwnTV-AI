package tv.own.owntv.features.shell.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.ui.stage.StageConfirm
import tv.own.owntv.ui.theme.mpx

/**
 * The exit question shown when the user presses Back from the main menu, as a small Stage popup.
 * Cancel takes initial focus so an accidental second Back doesn't quit the app.
 */
@Composable
fun ExitDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    StageConfirm(
        title = stringResource(R.string.content_exit_owntv),
        body = stringResource(R.string.content_exit_confirmation),
        confirm = stringResource(R.string.common_exit),
        onConfirm = onConfirm,
        onCancel = onDismiss,
        focusCancel = true,
        eyebrow = null,
        width = 560.mpx,
    )
}
