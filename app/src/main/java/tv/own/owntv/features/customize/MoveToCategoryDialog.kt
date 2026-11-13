package tv.own.owntv.features.customize

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * One destination row in the "Move to…" dialog: a user-created custom category (issue #87) plus how
 * many items it currently holds. Exposed here (not in the view models) so Live TV, Movies and Series
 * all share the same dialog and the same target model.
 */
data class MoveTarget(val id: String, val displayName: String, val count: Int)

/**
 * Shared "Move to…" dialog (issue #87): pick one of the user's custom combined categories (or create
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
    val colors = OwnTVTheme.colors
    var selectedTarget by remember { mutableStateOf<String?>(null) }
    var keepInOrigin by remember { mutableStateOf(false) }
    val newCatFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { newCatFocus.requestFocus() }
    }
    BackHandler { onDismiss() }
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.dialogPanel(width = 560.dp, padding = 28.dp),
            ) {
                Text(stringResource(R.string.settings_move_category_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.settings_move_category_description, originName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    // Do not trap the list's vertical exit: Down from its last destination must
                    // reach the keep-in-origin toggle and the footer buttons. The dialog-level
                    // trapAllFocusExit above already prevents focus from escaping the popup.
                    modifier = Modifier.height(320.dp),
                ) {
                    item(key = "new") {
                        FocusableSurface(
                            onClick = onNewCategory,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().focusRequester(newCatFocus),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                stringResource(R.string.settings_move_category_new),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurface,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            )
                        }
                    }
                    items(moveTargets, key = { it.id }) { target ->
                        val selected = selectedTarget == target.id
                        FocusableSurface(
                            onClick = { selectedTarget = target.id },
                            selected = selected,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (selected) "● " else "○ ",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                                )
                                Text(
                                    target.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    stringResource(R.string.common_number_grouped, target.count),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                // "Keep in origin" toggle — checked = copy (item stays in its provider folder / favorites).
                FocusableSurface(
                    onClick = { keepInOrigin = !keepInOrigin },
                    selected = keepInOrigin,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        (if (keepInOrigin) "☑ " else "☐ ") +
                            stringResource(R.string.settings_move_category_keep, originName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OwnTVButton(stringResource(R.string.common_cancel), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY)
                    Spacer(Modifier.weight(1f))
                    OwnTVButton(
                        stringResource(R.string.settings_move_category_action),
                        onClick = { selectedTarget?.let { onMove(it, keepInOrigin) } },
                        enabled = selectedTarget != null,
                        style = OwnTVButtonStyle.SECONDARY,
                    )
                }
            }
        }
    }
}
