package tv.own.owntv.features.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.download.DownloadWatchGuard
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupCheck
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.theme.mpx

/**
 * Core's question when playback opens on a one-stream playlist that a download was using — the
 * download is already paused by then. "Keep downloading" closes the player through [onClosePlayer].
 * Back is the same as watching: the picture is already on, and the download stays paused.
 */
@Composable
fun DownloadWatchPrompt(onClosePlayer: () -> Unit, guard: DownloadWatchGuard = koinInject()) {
    val prompt by guard.prompt.collectAsStateWithLifecycle()
    val p = prompt ?: return
    key(p) {
        var remember by remember { mutableStateOf(false) }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
        StagePopup(
            onDismiss = { guard.answer(watch = true, remember = remember) },
            title = stringResource(R.string.content_download_watch_title),
            body = stringResource(R.string.content_download_watch_body, p.playlistName),
            width = 820.mpx,
            buttons = {
                StageButton(
                    stringResource(R.string.content_download_watch_keep), height = 56.mpx, textSize = 19,
                    onClick = { guard.answer(watch = false, remember = remember); onClosePlayer() },
                )
                StageButton(
                    stringResource(R.string.content_download_watch_watch), height = 56.mpx, textSize = 19, tinted = true,
                    onClick = { guard.answer(watch = true, remember = remember) },
                    modifier = Modifier.focusRequester(first),
                )
            },
        ) {
            Column(Modifier.padding(top = 4.mpx)) {
                StagePopupOption(
                    title = stringResource(R.string.content_download_watch_remember),
                    onClick = { remember = !remember },
                    leading = { f -> StagePopupCheck(remember, f) },
                )
            }
        }
    }
}
