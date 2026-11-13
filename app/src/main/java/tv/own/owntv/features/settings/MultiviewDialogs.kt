package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.live.MAX_MULTIVIEW_TILES
import tv.own.owntv.core.live.MIN_MULTIVIEW_TILES
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * The **most** tiles the Multiview grid may have. Every value is always offered — nothing is greyed
 * out (D5) — and the grid still opens with two, growing only when the user asks for more.
 */
@Composable
internal fun MultiviewTilesDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
    BackHandler { onDismiss() }
    Box(
        modifier = Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.dialogPanel(width = 420.dp, padding = 24.dp)) {
            Text(
                stringResource(R.string.settings_multiview_tiles_max),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            (MIN_MULTIVIEW_TILES..MAX_MULTIVIEW_TILES).forEach { tiles ->
                FocusableSurface(
                    onClick = { onPick(tiles) },
                    selected = tiles == current,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (tiles == current) Modifier.focusRequester(focus) else Modifier),
                ) {
                    Text(
                        // Each option is a ceiling too, so it reads the same as the row it came from.
                        text = stringResource(R.string.settings_multiview_tiles_max_value, tiles),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * Three or four tiles need three or four provider connections and three or four hardware decoders.
 *
 * Shaped exactly like the Auto-frame-rate warning, and asked in Settings only (D12). It never
 * refuses: "Use anyway" gives the count the user picked, and a tile that then cannot start says so
 * itself (D5).
 */
@Composable
internal fun MultiviewWarningDialog(onUseAnyway: () -> Unit, onKeepTwo: () -> Unit) {
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
    BackHandler { onKeepTwo() }
    Box(
        modifier = Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.dialogPanel(width = 500.dp, padding = 28.dp)) {
            Text(
                stringResource(R.string.settings_multiview_warning_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.settings_multiview_warning_description),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OwnTVButton(
                    stringResource(R.string.settings_multiview_keep_two),
                    onClick = onKeepTwo,
                    modifier = Modifier.focusRequester(focus),
                )
                Spacer(Modifier.weight(1f))
                OwnTVButton(
                    stringResource(R.string.settings_multiview_use_anyway),
                    onClick = onUseAnyway,
                    style = OwnTVButtonStyle.SECONDARY,
                )
            }
        }
    }
}
