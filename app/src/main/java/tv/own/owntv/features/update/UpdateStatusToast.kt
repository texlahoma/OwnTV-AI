package tv.own.owntv.features.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.stageText
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.core.update.UpdateManager
import tv.own.owntv.ui.components.OwnTVSpinner

/**
 * Small semi-transparent status card (top-right corner) for the automatic startup update check:
 * "Checking…" → "You're up to date" (auto-hides after ~2s) — or, when an update exists, it stays
 * with Update now / Later. Update now downloads with progress and hands off to the system installer.
 * Only the Available state takes D-pad focus; the transient states never interrupt browsing.
 */
@Composable
fun UpdateStatusToast(onDone: () -> Unit, onViewChangelog: () -> Unit, modifier: Modifier = Modifier) {
    val manager: UpdateManager = koinInject()
    val state by manager.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }

    // Transient outcomes hide themselves; an available update keeps the card up.
    LaunchedEffect(state) {
        when (state) {
            UpdateManager.State.UpToDate -> { delay(2_000); manager.reset(); onDone() }
            is UpdateManager.State.Failed -> { delay(2_500); manager.reset(); onDone() }
            is UpdateManager.State.Available -> runCatching { focus.requestFocus() }
            UpdateManager.State.Idle -> onDone()
            else -> Unit
        }
    }

    Column(
        modifier = modifier
            // The Stage glass card, as the top-right pills.
            .padding(top = 40.mpx, end = 40.mpx)
            .widthIn(min = 460.mpx, max = 680.mpx)
            .stageGlass(26.mpx, overContent = true)
            .padding(horizontal = 26.mpx, vertical = 20.mpx)
            .focusGroup(),
    ) {
        when (val s = state) {
            UpdateManager.State.Idle, UpdateManager.State.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                OwnTVSpinner(sizeDp = 18)
                Spacer(Modifier.width(14.mpx))
                Text(stringResource(R.string.update_checking), style = stageText(17, 500), color = StageColors.Muted)
            }
            UpdateManager.State.UpToDate -> Text(
                stringResource(R.string.update_latest, manager.currentVersion),
                style = stageText(17, 500), color = StageColors.Muted,
            )
            is UpdateManager.State.Failed -> Text(
                updateFailureText(s.failure),
                style = stageText(17, 500), color = StageColors.Muted,
            )
            is UpdateManager.State.Available -> {
                BackHandler { onDone() } // Back = Later
                Text(stringResource(R.string.update_available), style = stageText(21, 800), color = StageColors.Text)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.update_ready, s.info.version, manager.currentVersion),
                    style = stageText(17, 500), color = StageColors.Muted,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // "What's New" opens the full changelog dialog (same view the manual check uses);
                    // both update paths show the changelog before downloading.
                    tv.own.owntv.ui.stage.StageButton(stringResource(R.string.update_whats_new), onClick = onViewChangelog, height = 52.mpx, textSize = 18, tinted = true, modifier = Modifier.focusRequester(focus))
                    tv.own.owntv.ui.stage.StageButton(stringResource(R.string.update_later), onClick = onDone, height = 52.mpx, textSize = 18)
                }
            }
            is UpdateManager.State.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                OwnTVSpinner(sizeDp = 18)
                Spacer(Modifier.width(14.mpx))
                Text(stringResource(R.string.update_downloading, s.percent), style = stageText(17, 500), color = StageColors.Muted)
            }
        }
    }
}
