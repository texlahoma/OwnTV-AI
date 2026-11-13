package tv.own.owntv.features.subtitles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.core.database.dao.LinkedSubtitle
import tv.own.owntv.ui.components.OwnTVIcon

/**
 * Per-item "Delete subtitles" popup opened from a movie/episode long-press.
 * Lists that item's downloaded subtitles; tapping one deletes it individually. Shares the app's selected
 * popup styling. [items] is re-supplied by the caller after each delete (empty → the caller closes).
 */
@Composable
fun SubtitleDeletePopup(
    contentTitle: String,
    items: List<LinkedSubtitle>,
    onDelete: (LinkedSubtitle) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    // Re-run after each delete: the focused row is disposed with the deletion, so land on the first
    // remaining subtitle (when none remain, the caller closes the popup).
    LaunchedEffect(items.size) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onDismiss() }
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = onDismiss,
        title = stringResource(R.string.player_subtitles_delete_title),
        body = contentTitle,
        eyebrow = null,
        width = 760.mpx,
        scroll = false,
        buttons = { tv.own.owntv.ui.stage.StageButton(stringResource(R.string.settings_close), onClick = onDismiss, height = 56.mpx, textSize = 19) },
    ) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.mpx)) {
            items(items, key = { it.cacheId }) { item ->
                tv.own.owntv.ui.stage.StagePopupOption(
                    title = item.languageName ?: item.language ?: stringResource(R.string.player_subtitles_subtitle),
                    subtitle = item.releaseName,
                    onClick = { onDelete(item) },
                    modifier = if (item == items.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    leading = { tv.own.owntv.ui.stage.StagePopupIcon(OwnTVIcon.TRASH, tv.own.owntv.ui.theme.StageColors.Danger) },
                )
            }
        }
    }
}
