package tv.own.owntv.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.ui.theme.mpx

/**
 * A simple TV dialog with one text field (e.g. renaming a channel/category). [onConfirm] receives the
 * trimmed text — possibly empty, which callers treat as "reset to original". [onDelete] is optional
 * and renders a destructive button (tinted with the app's favorite/error color) at the left end of
 * the button row — used by the custom-category rename dialog to delete the category.
 */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String? = null,
    confirmLabel: String? = null,
    hint: String? = null,
    onDelete: (() -> Unit)? = null,
    allowBlank: Boolean = true,
) {
    val resolvedLabel = label ?: stringResource(R.string.common_name)
    val resolvedConfirmLabel = confirmLabel ?: stringResource(R.string.common_save)
    var value by remember { mutableStateOf(initial) }
    val fieldFocus = remember { FocusRequester() }
    // Its own platform window: a hard focus boundary from the screen or popup it was opened from, so a
    // nested editor (Rule builder -> Rule value) can always reach its field. Focus waits for the window.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { fieldFocus.requestFocus() }
    }
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = onDismiss,
        title = title,
        body = hint,
        width = 820.mpx,
        buttons = {
            if (onDelete != null) tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_delete), onClick = onDelete, height = 56.mpx, textSize = 19)
            tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_cancel), onClick = onDismiss, height = 56.mpx, textSize = 19)
            tv.own.owntv.ui.stage.StageButton(resolvedConfirmLabel, onClick = { if (allowBlank || value.isNotBlank()) onConfirm(value.trim()) }, height = 56.mpx, textSize = 19, tinted = true)
        },
    ) {
        tv.own.owntv.features.settings.StageFieldRow(
            icon = tv.own.owntv.ui.components.OwnTVIcon.PENCIL,
            label = resolvedLabel,
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
        )
    }
}
