package tv.own.owntv.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.theme.mpx
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.core.brand.AppIconSwitcher

/**
 * The icon colours as two rows of tiles, each showing its own flat mark, with the focused (or
 * chosen) colour's name underneath. Used by the setup step and by Settings.
 */
@Composable
fun AppIconPicker(
    selected: AppIcon,
    onPick: (AppIcon) -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
) {
    var focusedIcon by remember { mutableStateOf<AppIcon?>(null) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Two rows of five (the 8 colours, Signal, Pixel): ten tiles in one row are wider than the panel.
        AppIcon.entries.chunked(5).forEachIndexed { row, icons ->
            if (row > 0) Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                icons.forEach { icon ->
                    tv.own.owntv.ui.stage.StageSurface(
                        onClick = { onPick(icon) },
                        radius = 20.dp,
                        modifier = Modifier
                            .size(84.dp)
                            .then(if (icon == selected && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
                        focusStyle = tv.own.owntv.ui.stage.StageFocus.FX,
                        // The chosen icon keeps an accent ring while focus is elsewhere.
                        idle = if (icon == selected) Modifier.border(3.mpx, tv.own.owntv.ui.theme.stageAccent.accent, RoundedCornerShape(20.dp)) else Modifier,
                        contentAlignment = Alignment.Center,
                    ) { focused ->
                        LaunchedEffect(focused) { if (focused) focusedIcon = icon }
                        BrandMark(icon, 56.dp, followAccent = false)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource((focusedIcon ?: selected).label),
            style = tv.own.owntv.ui.theme.stageText(19, 500),
            color = tv.own.owntv.ui.theme.StageColors.Text,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Settings → App icon: one panel. It shows the six tiles, and after a pick that differs from the
 * launcher's icon the same panel turns into the restart question, so popups never chain.
 */
@Composable
fun AppIconSettingsDialog(chosen: AppIcon, onPick: (AppIcon) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val applied = remember(context) { AppIconSwitcher.applied(context) }
    var picked by remember { mutableStateOf<AppIcon?>(null) }
    val tileFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { tileFocus.requestFocus() } }
    AppIconPanel(onDismiss, width = 640) {
        val restartFor = picked
        if (restartFor != null) {
            RestartContent(restartFor, onDismiss)
        } else {
            Text(stringResource(R.string.settings_app_icon), style = tv.own.owntv.ui.theme.stageText(38, 800), color = tv.own.owntv.ui.theme.StageColors.Text)
            Text(
                stringResource(R.string.settings_app_icon_summary), style = tv.own.owntv.ui.theme.stageText(18, 400), color = tv.own.owntv.ui.theme.StageColors.Muted,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.mpx, bottom = 24.mpx),
            )
            AppIconPicker(
                selected = chosen,
                onPick = { icon ->
                    onPick(icon)
                    if (icon != applied) picked = icon else onDismiss()
                },
                firstFocus = tileFocus,
            )
        }
    }
}

/**
 * "Restart OwnTV?" on its own, for a restore that brought in a different icon than the launcher's.
 * Restart now switches the icon and reopens the app through it; Later leaves it to [AppIconSwitcher],
 * which applies it the next time the app is in the background.
 */
@Composable
fun AppIconRestartDialog(icon: AppIcon, onDismiss: () -> Unit) {
    AppIconPanel(onDismiss, width = 520) { RestartContent(icon, onDismiss) }
}

@Composable
private fun AppIconPanel(onDismiss: () -> Unit, width: Int, content: @Composable () -> Unit) {
    tv.own.owntv.ui.stage.StagePopup(onDismiss = onDismiss, title = null, eyebrow = null, width = (width * 1.8f).mpx) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun RestartContent(icon: AppIcon, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val restartFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { restartFocus.requestFocus() } }
    BrandMark(icon, 72.dp, followAccent = false)
    Text(
        stringResource(R.string.app_icon_restart_title), style = tv.own.owntv.ui.theme.stageText(34, 800), color = tv.own.owntv.ui.theme.StageColors.Text,
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.mpx),
    )
    Text(
        stringResource(R.string.app_icon_restart_message), style = tv.own.owntv.ui.theme.stageText(18, 400), color = tv.own.owntv.ui.theme.StageColors.Muted,
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.mpx),
    )
    Row(Modifier.padding(top = 28.mpx), horizontalArrangement = Arrangement.spacedBy(14.mpx)) {
        tv.own.owntv.ui.stage.StageButton(stringResource(R.string.update_later), onClick = onDismiss, height = 56.mpx, textSize = 19)
        tv.own.owntv.ui.stage.StageButton(
            stringResource(R.string.app_icon_restart_now),
            onClick = { context.findActivity()?.let { AppIconSwitcher.restartWith(it, icon) } },
            height = 56.mpx, textSize = 19, tinted = true, modifier = Modifier.focusRequester(restartFocus),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
