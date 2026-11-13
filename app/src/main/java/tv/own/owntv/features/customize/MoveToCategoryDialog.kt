package tv.own.owntv.features.customize

import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.stageText
import androidx.compose.foundation.layout.heightIn
import tv.own.owntv.ui.components.OwnTVIcon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.theme.mpx

/**
 * One destination row in the "Move to…" dialog: a user-created custom category plus how
 * many items it currently holds. Exposed here (not in the view models) so Live TV, Movies and Series
 * all share the same dialog and the same target model.
 */
data class MoveTarget(val id: String, val displayName: String, val count: Int)

/**
 * Shared "Move to…" dialog: pick one of the user's custom combined categories (or create
 * a new one via [onNewCategory], which swaps this dialog for a name prompt at the call site), decide
 * whether the item stays in its origin as well, then Move. The Move button is disabled until a target
 * is selected. Scrim/trap/BackHandler match [RangeHideDialog] so D-pad focus and Back behave like the
 * other dialogs; the "＋ New category…" row owns the initial focus.
 */
@Composable
fun MoveToCategoryDialog(
    moveTargets: List<MoveTarget>,
    originName: String,
    onNewCategory: () -> Unit,
    onMove: (targetId: String, keepInOrigin: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTarget by remember { mutableStateOf<String?>(null) }
    var keepInOrigin by remember { mutableStateOf(false) }
    val newCatFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { newCatFocus.requestFocus() }
    }
    BackHandler { onDismiss() }
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_move_category_title),
        body = stringResource(R.string.settings_move_category_description, originName),
        width = 880.mpx,
        buttons = {
            tv.own.owntv.ui.stage.StagePopupButton(stringResource(R.string.common_cancel), onClick = onDismiss, primary = false)
            tv.own.owntv.ui.stage.StagePopupButton(
                stringResource(R.string.settings_move_category_action),
                onClick = { selectedTarget?.let { onMove(it, keepInOrigin) } },
                enabled = selectedTarget != null,
            )
        },
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.mpx),
            // Do not trap the list's vertical exit: Down from its last destination must
            // reach the keep-in-origin toggle and the footer buttons. The popup already
            // prevents focus from escaping.
            modifier = Modifier.heightIn(max = 420.mpx),
        ) {
            item(key = "new") {
                tv.own.owntv.ui.stage.StagePopupOption(
                    title = stringResource(R.string.settings_move_category_new),
                    onClick = onNewCategory,
                    modifier = Modifier.focusRequester(newCatFocus),
                    leading = { tv.own.owntv.ui.stage.StagePopupIcon(OwnTVIcon.ADD) },
                )
            }
            items(moveTargets, key = { it.id }) { target ->
                val selected = selectedTarget == target.id
                tv.own.owntv.ui.stage.StagePopupOption(
                    title = target.displayName,
                    onClick = { selectedTarget = target.id },
                    chosen = selected,
                    leading = { f -> tv.own.owntv.ui.stage.StagePopupRadio(selected, f) },
                    trailing = { Text(stringResource(R.string.common_number_grouped, target.count), style = stageText(18, 700), color = StageColors.Muted) },
                )
            }
        }
        tv.own.owntv.ui.stage.StagePopupDivider()
        // "Keep in origin" — ticked = copy (item stays in its provider folder / favorites).
        tv.own.owntv.ui.stage.StagePopupOption(
            title = stringResource(R.string.settings_move_category_keep, originName),
            onClick = { keepInOrigin = !keepInOrigin },
            leading = { f -> tv.own.owntv.ui.stage.StagePopupCheck(keepInOrigin, f) },
        )
    }
}
