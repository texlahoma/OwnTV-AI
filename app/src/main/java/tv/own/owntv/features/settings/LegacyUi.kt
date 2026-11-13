package tv.own.owntv.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.settings.SubtitleStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StagePopupButton
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Bridges the AI-subtitle screens (written against the pre-Stage dialog kit) onto the Stage
 * system that replaced it. Every shim keeps the old call signature and renders with the current
 * components, so the screens needed no edits while upstream rebuilt the UI layer underneath.
 */

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** The old button style knob: the Stage system tints the primary variant instead. */
enum class OwnTVButtonStyle { PRIMARY, SECONDARY }

@Composable
internal fun OwnTVButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: OwnTVIcon? = null,
    style: OwnTVButtonStyle = OwnTVButtonStyle.PRIMARY,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    StagePopupButton(
        label = label, onClick = onClick, modifier = modifier, icon = icon,
        primary = style == OwnTVButtonStyle.PRIMARY, enabled = enabled, compact = compact,
    )
}

@Composable
internal fun OwnTVButton(
    icon: OwnTVIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: OwnTVButtonStyle = OwnTVButtonStyle.PRIMARY,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    StagePopupButton(
        label = "", onClick = onClick, modifier = modifier, icon = icon,
        primary = style == OwnTVButtonStyle.PRIMARY, enabled = enabled, compact = compact,
    )
}

/** Legacy call form: (text, onClick) positionally, everything else by name. */
@Composable
internal fun OwnTVButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: OwnTVIcon? = null,
    style: OwnTVButtonStyle = OwnTVButtonStyle.PRIMARY,
    enabled: Boolean = true,
) {
    StagePopupButton(
        label = text, onClick = onClick, modifier = modifier, icon = icon,
        primary = style == OwnTVButtonStyle.PRIMARY, enabled = enabled,
    )
}

@Composable
internal fun OwnTVTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    Column(modifier = modifier) {
        Text(label, style = stageText(14, 400), color = StageColors.Muted)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = stageText(17, 400).copy(color = StageColors.Text),
            cursorBrush = SolidColor(stageAccent.accent),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(),
            keyboardActions = KeyboardActions(),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequesterCompat(focusRequester) else Modifier)
                .background(StageColors.Ink.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

private fun Modifier.focusRequesterCompat(requester: FocusRequester): Modifier =
    this.then(Modifier.focusRequester(requester))

/** Legacy scrim: the Stage wash, as a plain modifier. */
@Composable
internal fun Modifier.modalScrim(): Modifier =
    background(StageColors.Scrim)

/** Legacy dialog panel: one glass-ish rounded surface, as the old dialogPanel modifier drew. */
@Composable
internal fun Modifier.dialogPanel(width: androidx.compose.ui.unit.Dp? = null, padding: androidx.compose.ui.unit.Dp = 24.dp): Modifier {
    var m = this
        .background(StageColors.Ink.copy(alpha = 0.94f), RoundedCornerShape(20.dp))
        .padding(padding)
    if (width != null) m = m.width(width)
    return m
}

/** Legacy rounded panel used for inline cards inside the screen. */
@Composable
internal fun Modifier.roundedPanel(): Modifier =
    background(StageColors.Ink.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(16.dp)

/** Screen header: title + a back affordance, in Stage typography. */
@Composable
internal fun Header(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = stageText(30, 800), color = StageColors.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        StagePopupButton(label = stringResource(R.string.common_back), onClick = onBack, primary = false, compact = true)
    }
}

/** Section label inside a settings sub-screen. */
@Composable
internal fun GroupLabel(text: String) {
    Text(text, style = stageText(16, 700), color = stageAccent.accent, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

/** One settings row: icon, title, description, right-aligned chip, optional chevron. */
@Composable
internal fun ServiceSettingsRow(
    icon: OwnTVIcon,
    title: String,
    desc: String? = null,
    chip: String? = null,
    /** True when the chip states the row's PRIMARY choice (rendered in the accent tone). */
    primaryChip: Boolean = false,
    chevron: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    tv.own.owntv.ui.stage.StageSurface(
        onClick = onClick ?: {},
        radius = 14.dp,
        enabled = onClick != null,
        idle = Modifier.background(StageColors.Ink.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OwnTVIcon(icon, StageColors.Text, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = stageText(18, 500), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (desc != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(desc, style = stageText(14, 400), color = StageColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (chip != null) {
                Spacer(Modifier.width(10.dp))
                Text(chip, style = stageText(16, 400), color = if (primaryChip) stageAccent.accent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (chevron) {
                Spacer(Modifier.width(6.dp))
                OwnTVIcon(OwnTVIcon.CHEVRON, StageColors.Dim, Modifier.size(14.dp))
            }
        }
    }
}

/** The position picker the AI screen opens: same choice set, drawn as a Stage popup. */
@Composable
internal fun SubtitlePositionDialog(
    position: SubtitleStyle.Position,
    onSelect: (SubtitleStyle.Position) -> Unit,
    onDismiss: () -> Unit,
) {
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = onDismiss,
        title = stringResource(R.string.ai_subtitles_position),
        eyebrow = null,
        width = 640.mpx,
    ) {
        val options = listOf(SubtitleStyle.Position.DEFAULT) + SubtitleStyle.Position.ANCHORS
        options.forEach { pos ->
            tv.own.owntv.ui.stage.StagePopupOption(
                title = subtitlePositionName(pos),
                onClick = { onSelect(pos) },
                chosen = pos == position,
                leading = { f -> tv.own.owntv.ui.stage.StagePopupRadio(pos == position, f) },
            )
        }
    }
}
