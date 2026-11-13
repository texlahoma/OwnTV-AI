package tv.own.owntv.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.core.util.FriendlySyncFailure
import tv.own.owntv.core.util.classifySyncFailure
import tv.own.owntv.ui.components.displayText
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.formatCount

/** Semi-automatic EPG flow after a playlist import: ask → sync with a live programme count → done. */
sealed interface EpgSyncUi {
    data object Hidden : EpgSyncUi
    data class Ask(val sourceName: String) : EpgSyncUi
    data class Syncing(val count: Int) : EpgSyncUi
    data object Done : EpgSyncUi
    data class Failed(val failure: FriendlySyncFailure) : EpgSyncUi
}

/**
 * After a playlist imports, ask whether to sync its TV guide now (syncing it automatically,
 * which was slow). "Sync now" runs in the foreground and shows a **live programme count** — exactly like the
 * playlist import — then a brief "Done", and closes itself.
 */
@Composable
fun EpgSyncDialog(
    state: EpgSyncUi,
    onSync: () -> Unit,
    onDismiss: () -> Unit,
    // When non-null, the "Syncing" step offers "Run in background": enter the app now while the guide
    // keeps downloading (the sync runs in the activity-scoped ViewModel, so it survives). Null hides it.
    onBackground: (() -> Unit)? = null,
) {
    if (state is EpgSyncUi.Hidden) return
    val focus = remember { FocusRequester() }
    LaunchedEffect(state::class) {
        if (state !is EpgSyncUi.Syncing || onBackground != null) {
            delay(80)
            runCatching { focus.requestFocus() }
        }
    }
    if (state is EpgSyncUi.Done) LaunchedEffect(Unit) { delay(1_800); onDismiss() } // auto-close
    val body = tv.own.owntv.ui.theme.stageText(18, 400)
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = onDismiss,
        // Back does nothing while it syncs: the user leaves with "Run in background", not by accident.
        dismissOnBackPress = state !is EpgSyncUi.Syncing,
        title = when (state) {
            is EpgSyncUi.Ask -> stringResource(R.string.settings_sync_guide_question)
            is EpgSyncUi.Syncing -> stringResource(R.string.settings_syncing_guide)
            is EpgSyncUi.Done -> stringResource(R.string.settings_guide_synced)
            is EpgSyncUi.Failed -> stringResource(R.string.settings_guide_sync_failed)
            EpgSyncUi.Hidden -> null
        },
        body = when (state) {
            is EpgSyncUi.Ask -> stringResource(R.string.settings_sync_guide_description, state.sourceName)
            is EpgSyncUi.Failed -> state.failure.displayText()
            else -> null
        },
        width = 760.mpx,
        buttons = {
            when (state) {
                is EpgSyncUi.Ask -> {
                    tv.own.owntv.ui.stage.StageButton(stringResource(R.string.settings_not_now), onClick = onDismiss, height = 56.mpx, textSize = 19)
                    tv.own.owntv.ui.stage.StageButton(stringResource(R.string.settings_sync_now), onClick = onSync, height = 56.mpx, textSize = 19, tinted = true, modifier = Modifier.focusRequester(focus))
                }
                is EpgSyncUi.Syncing -> if (onBackground != null) {
                    tv.own.owntv.ui.stage.StageButton(stringResource(R.string.settings_run_background), onClick = onBackground, icon = OwnTVIcon.PLAY, height = 56.mpx, textSize = 19, modifier = Modifier.focusRequester(focus))
                }
                is EpgSyncUi.Done -> tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_done), onClick = onDismiss, height = 56.mpx, textSize = 19, tinted = true, modifier = Modifier.focusRequester(focus))
                is EpgSyncUi.Failed -> tv.own.owntv.ui.stage.StageButton(stringResource(R.string.content_close), onClick = onDismiss, height = 56.mpx, textSize = 19, tinted = true, modifier = Modifier.focusRequester(focus))
                EpgSyncUi.Hidden -> Unit
            }
        },
    ) {
        if (state is EpgSyncUi.Syncing) Row(verticalAlignment = Alignment.CenterVertically) {
            OwnTVSpinner(sizeDp = 36)
            Spacer(Modifier.width(20.mpx))
            Text(
                if (state.count > 0) formatCount(state.count) else stringResource(R.string.settings_connecting),
                style = tv.own.owntv.ui.theme.stageText(36, 800), color = tv.own.owntv.ui.theme.stageAccent.accent,
            )
        }
    }
}
