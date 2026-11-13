package tv.own.owntv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * A numeric input dialog: type a number directly OR nudge it with − / + buttons. Mirrors
 * [StepperDialog]'s chrome but adds a real text field (so big values like 100 are one tap, not 100
 * stepper presses) and an advisory warning when the value exceeds [warnAbove].
 *
 * The [onSet] callback fires live as the value changes (typed or stepped), exactly like
 * [StepperDialog] — so the caller persists immediately and the chip behind the dialog stays in sync.
 *
 * Validation:
 *  - The text field only accepts digits; on Done it is parsed, clamped to [min]..[max], and committed.
 *  - [max] is a hard cap (protects against typos like 999999 on slow TVs).
 *  - [warnAbove] is *advisory only*: a warning line appears, but the user can still save — matching the
 *    product decision "if he still says yes it goes".
 */
@Composable
fun NumberInputDialog(
    title: String,
    value: Int,
    min: Int = 1,
    max: Int,
    step: Int = 1,
    warnAbove: Int? = null,
    warningText: String? = null,
    suffix: String = "",
    /** What the field holds. Defaults to the channel-skip wording this dialog was first written for. */
    fieldLabel: String? = null,
    onSet: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val fieldFocus = remember { FocusRequester() }
    val minusFocus = remember { FocusRequester() }
    val plusFocus = remember { FocusRequester() }
    val keyboard: SoftwareKeyboardController? = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf(value.toString()) }

    // Keep the field in sync with the incoming value when it changes from outside (e.g. − / + taps).
    LaunchedEffect(value) { text = value.toString() }
    // Land on a stepper rather than the field. Focusing a text field on a television puts the remote
    // into edit mode and raises the on-screen keyboard, which then swallows the D-pad: the − / +
    // buttons and Reset / Save all become hard to reach for the one job most people open this dialog
    // to do, nudging a number by one. The field is still one D-pad press away for typing a big value.
    //
    // − is disabled at the minimum and + at the maximum, and a disabled button cannot take focus —
    // asking it to would leave the dialog with no focus at all, which is the very complaint this
    // change answers. So land on whichever stepper is live; at 0 of 0..max that is +.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { (if (value > min) minusFocus else plusFocus).requestFocus() }
    }

    fun commit(parsed: Int) {
        val clamped = parsed.coerceIn(min, max)
        text = clamped.toString()
        onSet(clamped)
    }
    fun commitText() {
        val parsed = text.trim().toIntOrNull()
        if (parsed != null) commit(parsed) else { text = value.toString() }
    }

    BackHandler {
        keyboard?.hide()
        onDismiss()
    }

    OwnTVPopup(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.dialogPanel(width = 320.dp, corner = 16.dp, padding = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                Spacer(Modifier.height(12.dp))

                // Bottom-align: the text field carries a label above its input box, so it's taller than
                // the − / + buttons. Aligning to the bottom lines the buttons up with the input box row.
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepBtn(stringResource(R.string.common_minus), enabled = value > min, modifier = Modifier.focusRequester(minusFocus)) {
                        commit(value - step)
                    }
                    // The numeric text field. Numeric keyboard; Done commits + moves focus back to −.
                    OwnTVTextField(
                        value = text,
                        onValueChange = { raw ->
                            // Digits only; never let a non-numeric char into the field.
                            text = raw.filter { it.isDigit() }.take(7) // take(7) guards against paste bombs
                        },
                        label = fieldLabel ?: stringResource(R.string.common_items_per_skip),
                        modifier = Modifier.width(130.dp),
                        focusRequester = fieldFocus,
                        placeholder = value.toString(),
                        keyboardType = KeyboardType.Number,
                    )
                    StepBtn(stringResource(R.string.common_plus), enabled = value < max, modifier = Modifier.focusRequester(plusFocus)) {
                        commit(value + step)
                    }
                }

                if (suffix.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(suffix, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }

                // Advisory warning — never blocks Save. Only shown above warnAbove.
                val showWarn = warnAbove != null && value > warnAbove && warningText != null
                if (showWarn) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.tertiaryContainer.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            warningText,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onTertiaryContainer,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OwnTVButton(stringResource(R.string.common_reset), onClick = onReset, style = OwnTVButtonStyle.SECONDARY)
                    Spacer(Modifier.weight(1f))
                    OwnTVButton(stringResource(R.string.common_save), onClick = {
                        commitText()
                        keyboard?.hide()
                        onDismiss()
                    })
                }
            }
        }
    }
}

/** Square − / + button (matches the one in VideoPlayerSettingsScreen's StepperDialog). */
@Composable
private fun StepBtn(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        contentAlignment = Alignment.Center,
        surface = GlassSurface.DIALOGS,
    ) { _ ->
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) colors.onSurface else colors.outline,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
