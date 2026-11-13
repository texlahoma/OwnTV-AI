package tv.own.owntv.features.shell.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * The top-bar quick switcher: pick which playlist the whole app shows right now. "All playlists" (id -1)
 * is the merged view; any other row narrows Live/Movies/Series/EPG/Search/Home to that one playlist. The
 * choice persists (it writes the same "Default" setting used in Settings → Sources), so it survives a restart.
 */
@Composable
fun PlaylistPickerDialog(
    playlists: List<SourceEntity>,
    activeId: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    // Focus the currently-selected row on open so the user starts on their current choice.
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { selectedFocus.requestFocus() }
    }
    BackHandler { onDismiss() }

    tv.own.owntv.ui.theme.PopupFontTheme {
    Box(
        Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        // scroll = false: the playlist list below scrolls itself, and two vertical scrollers cannot nest.
        Column(
            Modifier.dialogPanel(width = 280.dp, corner = 16.dp, padding = 14.dp, scroll = false),
        ) {
            Text(stringResource(R.string.content_playlist_picker_title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.content_playlist_picker_description),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier.fillMaxWidth().heightScrollCap().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaylistRow(
                    label = stringResource(R.string.content_all_playlists),
                    selected = activeId <= 0,
                    modifier = if (activeId <= 0) Modifier.focusRequester(selectedFocus) else Modifier,
                    onClick = { onSelect(-1L); onDismiss() },
                )
                playlists.forEach { source ->
                    PlaylistRow(
                        label = source.name,
                        selected = source.id == activeId,
                        modifier = if (source.id == activeId) Modifier.focusRequester(selectedFocus) else Modifier,
                        onClick = { onSelect(source.id); onDismiss() },
                    )
                }
            }
        }
    }
    }
}

/** Caps the list height so a long playlist set scrolls inside the dialog instead of overflowing it —
 *  relative to the screen so the dialog never clips on small/low-res displays. */
@Composable
private fun Modifier.heightScrollCap(): Modifier {
    val max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 180.dp).coerceIn(140.dp, 240.dp)
    return this.heightIn(max = max)
}

@Composable
private fun PlaylistRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        selected = selected,
        shape = RoundedCornerShape(14.dp),
        focusedContainerColor = colors.surfaceContainerHighest,
        unfocusedContainerColor = colors.surfaceContainer,
        selectedContainerColor = colors.secondaryContainer,
        contentAlignment = Alignment.CenterStart,
        surface = GlassSurface.DIALOGS,
    ) { focused ->
        val content = when {
            selected -> colors.onSecondaryContainer
            focused -> colors.onSurface
            else -> colors.onSurfaceVariant
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).then(
                    if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
                ),
            )
            if (selected) {
                // Accent dot marks the current choice (no dedicated check glyph in OwnTVIcon).
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(999.dp)).background(colors.primary))
            }
        }
    }
}
