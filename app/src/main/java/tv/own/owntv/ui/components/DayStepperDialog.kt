package tv.own.owntv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tv.own.owntv.R
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.core.i18n.HorizontalDirection
import tv.own.owntv.core.i18n.horizontalDirection

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
    OwnTVPopup(onDismissRequest = onDismiss) {
        val colors = OwnTVTheme.colors
        val layoutDirection = LocalLayoutDirection.current
        var days by remember { mutableIntStateOf(initialDays.coerceIn(minDays, maxDays)) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(80)
            runCatching { focus.requestFocus() }
        }
        BackHandler { onDismiss() }
        Box(Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(), contentAlignment = Alignment.Center) {
            Column(Modifier.dialogPanel(width = 460.dp, padding = 28.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Spacer(Modifier.height(18.dp))
                FocusableSurface(
                    onClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            val step = when (event.key.horizontalDirection(layoutDirection)) {
                                HorizontalDirection.START -> -1
                                HorizontalDirection.END -> +1
                                null -> return@onKeyEvent false
                            }
                            days = (days + step).coerceIn(minDays, maxDays)
                            true
                        },
                    shape = RoundedCornerShape(14.dp),
                    contentAlignment = Alignment.Center,
                    surface = GlassSurface.CARDS,
                ) { _ ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StepperGlyph("−", days > minDays)
                        Text(
                            label(days),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.primary,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                        )
                        StepperGlyph("+", days < maxDays)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OwnTVButton(stringResource(R.string.common_cancel), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY)
                    Spacer(Modifier.weight(1f))
                    OwnTVButton(stringResource(R.string.common_ok), onClick = { onConfirm(days) })
                }
            }
        }
    }
}

@Composable
private fun StepperGlyph(glyph: String, enabled: Boolean) {
    val colors = OwnTVTheme.colors
    Text(
        glyph,
        style = MaterialTheme.typography.titleLarge,
        color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
        modifier = Modifier.width(32.dp),
        textAlign = TextAlign.Center,
    )
}
