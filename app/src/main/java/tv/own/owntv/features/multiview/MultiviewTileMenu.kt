package tv.own.owntv.features.multiview

import androidx.compose.foundation.layout.width
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.trapAllFocusExit

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
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
    BackHandler { onDismiss() }

    // The long-press menu look, as the channel and settings-row menus draw it.
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
      androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(tv.own.owntv.ui.theme.StageColors.Scrim).longPressMenuGuard(),
        contentAlignment = Alignment.Center,
      ) {
      tv.own.owntv.ui.stage.StageMenu(Modifier.width(560.mpx).trapAllFocusExit().focusGroup()) {
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
    tv.own.owntv.ui.stage.StageMenuItem(label, onClick = onClick, modifier = modifier, icon = icon)
}
