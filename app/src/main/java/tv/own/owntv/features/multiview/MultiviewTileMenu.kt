package tv.own.owntv.features.multiview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * MENU on a tile: what can be done with that one tile without disturbing the others.
 *
 * Same shape as the Live channel menu — a scrimmed panel that traps focus and guards against the
 * long-press that opened it auto-clicking the first row. **"Record this" is deliberately absent**
 * until Feature A exists; an entry that does nothing is worse than one that is not there yet.
 */
@Composable
fun MultiviewTileMenu(
    filled: Boolean,
    soundOnly: Boolean,
    /** Null once the grid has reached the maximum the user allowed in Settings. */
    onAddTile: (() -> Unit)?,
    onChangeChannel: () -> Unit,
    onFullscreen: () -> Unit,
    onSound: () -> Unit,
    onSoundOnly: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
    BackHandler { onDismiss() }

    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
    Box(
        modifier = Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup().longPressMenuGuard(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.dialogPanel(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuRow(
                label = stringResource(R.string.multiview_tile_change_channel),
                icon = OwnTVIcon.LIVE_TV,
                onClick = onChangeChannel,
                modifier = Modifier.focusRequester(focus),
            )
            // Growing the grid is a deliberate act, which is what makes the Settings number a
            // ceiling rather than a size: four allowed does not mean four every time.
            if (onAddTile != null) {
                MenuRow(stringResource(R.string.multiview_add_channel), OwnTVIcon.ADD, onAddTile)
            }
            if (filled) {
                MenuRow(stringResource(R.string.multiview_tile_fullscreen), OwnTVIcon.EXPAND, onFullscreen)
                MenuRow(stringResource(R.string.multiview_audio_tile), OwnTVIcon.VOLUME_HIGH, onSound)
                // Give up this tile's picture and keep only its sound — or take the picture back.
                MenuRow(
                    label = stringResource(
                        if (soundOnly) R.string.multiview_tile_show_picture else R.string.multiview_tile_sound_only,
                    ),
                    icon = OwnTVIcon.HEADPHONES,
                    onClick = onSoundOnly,
                )
                MenuRow(stringResource(R.string.multiview_tile_remove), OwnTVIcon.CLOSE, onRemove)
            }
            MenuRow(stringResource(R.string.content_close), OwnTVIcon.CLOSE, onDismiss)
        }
    }
    }
}

@Composable
private fun MenuRow(
    label: String,
    icon: OwnTVIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OwnTVIcon(icon, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        }
    }
}
