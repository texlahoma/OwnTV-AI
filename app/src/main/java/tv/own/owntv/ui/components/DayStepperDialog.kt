package tv.own.owntv.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.key
import tv.own.owntv.ui.theme.mpx

/**
 * A day count, chosen with left and right.
 *
 * One focusable value that steps by a day; the remote's own key repeat handles holding. The range
 * clamps rather than wraps, so a held key settles on an end instead of jumping from the top back to
 * the bottom.
 *
 * Shared, because three settings ask the same question in different words — the playlist refresh
 * interval, the EPG refresh interval, and how many days of guide to keep — and they were about to be
 * three copies of the same stepper.
 */
@Composable
internal fun DayStepperDialog(
    title: String,
    hint: String,
    initialDays: Int,
    minDays: Int,
    maxDays: Int,
    label: @Composable (Int) -> String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var days by remember { mutableIntStateOf(initialDays.coerceIn(minDays, maxDays)) }
    // ◀ ▶ step a day (the remote's key repeat handles holding); OK keeps it, Back leaves it as it was.
    val stepper: @Composable () -> Unit = {
        tv.own.owntv.features.settings.PanelStepper(
            value = label(days),
            onStep = { d -> days = (days + d).coerceIn(minDays, maxDays) },
            onReset = null,
            onDone = { onConfirm(days) },
            confirm = true,
        )
    }
    // On a settings page it opens in the panel; elsewhere a Stage popup.
    if (tv.own.owntv.features.settings.panelEditor(onDismiss) { stepper() }) return
    tv.own.owntv.ui.stage.StagePopup(onDismiss = onDismiss, title = title, body = hint, width = 760.mpx) { stepper() }
}
