package tv.own.owntv.features.customize

import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText
import tv.own.owntv.ui.components.OwnTVIcon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.core.customize.BULK_RENAME_MAX_ROWS
import tv.own.owntv.core.customize.BulkRenameSession
import tv.own.owntv.core.customize.RenameRules
import tv.own.owntv.features.settings.PickerDialog
import tv.own.owntv.ui.components.TextInputDialog

/** Which field of a rule-builder row a nested picker is editing (focus returns to that row after). */
private enum class RuleField { TYPE, PLACEMENT, VALUE }


/**
 * Hosts whichever bulk-rename dialog the session's screen calls for, and restores D-pad focus to
 * [returnFocus] (the row/pill that opened the flow) once the whole flow closes.
 */
@Composable
fun BulkRenameFlow(session: BulkRenameSession, returnFocus: FocusRequester? = null) {
    val screen by session.screen.collectAsStateWithLifecycle()
    var wasOpen by remember { mutableStateOf(false) }
    // The opener row can change while the flow is idle (a second span started) — always restore to
    // the LATEST row, not the one from the composition that saw the flow open.
    val currentReturnFocus by rememberUpdatedState(returnFocus)
    LaunchedEffect(screen) {
        if (screen != BulkRenameSession.Screen.NONE) {
            wasOpen = true
        } else if (wasOpen) {
            wasOpen = false
            currentReturnFocus?.let { opener ->
                kotlinx.coroutines.delay(60)
                runCatching { opener.requestFocus() }
            }
        }
    }
    when (screen) {
        BulkRenameSession.Screen.CHOICE -> BulkRenameChoicePopup(session)
        BulkRenameSession.Screen.BUILDER -> BulkRuleBuilderDialog(session)
        BulkRenameSession.Screen.REVIEW -> BulkReviewDialog(session)
        BulkRenameSession.Screen.RESTORE_CONFIRM -> BulkRestoreConfirmDialog(session)
        BulkRenameSession.Screen.REFUSED -> BulkRefusedDialog(session)
        BulkRenameSession.Screen.NONE -> Unit
    }
}

/** The four-way choice that opens a bulk rename. */
@Composable
private fun BulkRenameChoicePopup(session: BulkRenameSession) {
    val count = session.entries.collectAsStateWithLifecycle().value.size
    val addFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { addFocus.requestFocus() } }
    BackHandler { session.close() }
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = { session.close() },
        title = stringResource(R.string.settings_bulk_rename_title),
        body = pluralStringResource(R.plurals.settings_bulk_rename_selected, count, count),
        width = 760.mpx,
        buttons = { tv.own.owntv.ui.stage.StagePopupButton(stringResource(R.string.common_cancel), onClick = { session.close() }, primary = false) },
    ) {
        tv.own.owntv.ui.stage.StagePopupOption(
            title = stringResource(R.string.settings_bulk_rename_add_rule), onClick = { session.openBuilder() },
            modifier = Modifier.focusRequester(addFocus), leading = { tv.own.owntv.ui.stage.StagePopupIcon(OwnTVIcon.ADD) },
        )
        tv.own.owntv.ui.stage.StagePopupOption(
            title = stringResource(R.string.settings_bulk_rename_auto_cleanup), onClick = { session.autoCleanup() },
            leading = { tv.own.owntv.ui.stage.StagePopupIcon(OwnTVIcon.SPARKLE) },
        )
        tv.own.owntv.ui.stage.StagePopupOption(
            title = stringResource(R.string.settings_bulk_rename_restore_original), onClick = { session.requestRestore() },
            leading = { tv.own.owntv.ui.stage.StagePopupIcon(OwnTVIcon.REFRESH) },
        )
    }
}

/** Rule builder: rows of type/placement/value/✕, options checkboxes, Apply. */
@Composable
private fun BulkRuleBuilderDialog(session: BulkRenameSession) {
    val rules = session.rules.collectAsStateWithLifecycle().value
    val options = session.options.collectAsStateWithLifecycle().value
    // Draft rows edited locally until Apply commits them to the session (and starts the preview).
    var draft by remember { mutableStateOf(rules) }
    var trim by remember { mutableStateOf(options.trimLeftovers) }
    var ignoreCase by remember { mutableStateOf(options.ignoreCase) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    // Nested picker target: (row index, field). The row's focus is restored when it closes.
    var editing by remember { mutableStateOf<Pair<Int, RuleField>?>(null) }
    var pickerRow by remember { mutableIntStateOf(-1) }
    var pendingRowFocus by remember { mutableIntStateOf(Int.MIN_VALUE) }
    val addRuleFocus = remember { FocusRequester() }
    val rowFocusers = remember(draft.size) { List(draft.size) { FocusRequester() } }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { (rowFocusers.firstOrNull() ?: addRuleFocus).requestFocus() }
    }
    // Adding/deleting a rule disposes and rebuilds row focus nodes. Hand focus to the new row or the
    // Add button explicitly instead of relying on spatial fallback through a full-screen scrim.
    LaunchedEffect(draft.size, pendingRowFocus) {
        if (pendingRowFocus != Int.MIN_VALUE) {
            kotlinx.coroutines.delay(60)
            val target = rowFocusers.getOrNull(pendingRowFocus) ?: addRuleFocus
            runCatching { target.requestFocus() }
            pendingRowFocus = Int.MIN_VALUE
        }
    }
    // Focus the row that opened a nested picker once that picker closes (a scrim teardown can leave
    // focus in limbo otherwise).
    LaunchedEffect(editing) {
        if (editing == null && pickerRow >= 0) {
            val row = pickerRow
            pickerRow = -1
            kotlinx.coroutines.delay(60)
            runCatching { rowFocusers.getOrNull(row)?.requestFocus() }
        } else if (editing != null) {
            pickerRow = editing!!.first
        }
    }
    BackHandler { session.backToChoice() }

    fun submit() {
        val out = mutableListOf<RenameRules.Rule>()
        for (rule in draft) {
            if (rule.pattern != null) {
                // Auto-cleanup rules are internal regexes, but remain visible/removable in this
                // editor so "Edit rules" genuinely tunes the generated preset.
                out += rule
            } else if (rule.action == RenameRules.Action.ADD) {
                if (RenameRules.tokensOf(rule).size != 1) {
                    errorRes = R.string.settings_bulk_rename_add_single_value_error
                    return
                }
                out += rule
            } else if (RenameRules.tokensOf(rule).isNotEmpty()) {
                out += rule
            }
        }
        if (out.isEmpty()) {
            errorRes = R.string.settings_bulk_rename_rule_required
            return
        }
        errorRes = null
        session.submitRules(out, RenameRules.Options(trimLeftovers = trim, ignoreCase = ignoreCase))
    }

    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = { session.backToChoice() },
        title = stringResource(R.string.settings_bulk_rename_rules_title),
        body = stringResource(R.string.settings_bulk_rename_rules_description),
        width = 1240.mpx,
        buttons = {
            tv.own.owntv.ui.stage.StagePopupButton(stringResource(R.string.common_cancel), onClick = { session.backToChoice() }, primary = false)
            tv.own.owntv.ui.stage.StagePopupButton(stringResource(R.string.settings_bulk_rename_apply), onClick = { submit() })
        },
    ) {
                Column(
                    Modifier.heightIn(max = 450.mpx).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.mpx),
                ) {
                    draft.forEachIndexed { i, rule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusGroup()
                                .then(if (i < rowFocusers.size) Modifier.focusRequester(rowFocusers[i]) else Modifier),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.mpx),
                        ) {
                            tv.own.owntv.ui.stage.StagePopupButton(
                                stringResource(
                                    if (rule.action == RenameRules.Action.ADD) R.string.settings_bulk_rename_action_add
                                    else R.string.settings_bulk_rename_action_remove,
                                ) + " ▾",
                                onClick = { editing = i to RuleField.TYPE },
                                modifier = Modifier.width(180.mpx),
                                primary = false,
                            )
                            tv.own.owntv.ui.stage.StagePopupButton(
                                stringResource(
                                    if (rule.placement == RenameRules.Placement.PREFIX) R.string.settings_bulk_rename_before
                                    else R.string.settings_bulk_rename_after,
                                ) + " ▾",
                                onClick = { editing = i to RuleField.PLACEMENT },
                                modifier = Modifier.width(180.mpx),
                                primary = false,
                            )
                            tv.own.owntv.ui.stage.StagePopupButton(
                                rule.autoLabel?.let { label ->
                                    stringResource(
                                        when (label) {
                                            RenameRules.AutoLabel.COUNTRY_PROVIDER -> R.string.settings_bulk_rename_auto_country_provider
                                            RenameRules.AutoLabel.QUALITY_CODEC -> R.string.settings_bulk_rename_auto_quality_codec
                                            RenameRules.AutoLabel.EMOJI_SYMBOLS -> R.string.settings_bulk_rename_auto_emoji_symbols
                                        },
                                    )
                                } ?: rule.value.ifBlank { stringResource(R.string.settings_bulk_rename_value_example) },
                                onClick = { editing = i to RuleField.VALUE },
                                modifier = Modifier.weight(1f),
                                primary = false,
                            )
                            tv.own.owntv.ui.stage.StagePopupButton(
                                "✕",
                                onClick = {
                                    val next = draft.toMutableList().apply { removeAt(i) }
                                    draft = next
                                    pendingRowFocus = if (next.isEmpty()) -1 else i.coerceAtMost(next.lastIndex)
                                },
                                primary = false,
                            )
                        }
                    }
                    tv.own.owntv.ui.stage.StagePopupButton(
                        stringResource(R.string.settings_bulk_rename_add_another_rule),
                        onClick = {
                            pendingRowFocus = draft.size
                            draft = draft + RenameRules.Rule(RenameRules.Action.ADD, RenameRules.Placement.PREFIX, "")
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(addRuleFocus),
                        primary = false,
                    )
                }
                tv.own.owntv.ui.stage.StagePopupDivider()
                // The two options (Trim leftover spaces / Ignore case, both default ON).
                tv.own.owntv.ui.stage.StagePopupOption(
                    title = stringResource(R.string.settings_bulk_rename_trim_spaces), onClick = { trim = !trim },
                    leading = { f -> tv.own.owntv.ui.stage.StagePopupCheck(trim, f) },
                )
                tv.own.owntv.ui.stage.StagePopupOption(
                    title = stringResource(R.string.settings_bulk_rename_ignore_case), onClick = { ignoreCase = !ignoreCase },
                    leading = { f -> tv.own.owntv.ui.stage.StagePopupCheck(ignoreCase, f) },
                )
                errorRes?.let {
                    Text(stringResource(it), style = stageText(17, 600), color = StageColors.Danger, modifier = Modifier.padding(top = 10.mpx))
                }

        when (editing?.second) {
            RuleField.TYPE -> PickerDialog(
                title = stringResource(R.string.settings_bulk_rename_rule_type),
                options = listOf(
                    "ADD" to stringResource(R.string.settings_bulk_rename_action_add),
                    "REMOVE" to stringResource(R.string.settings_bulk_rename_action_remove),
                ),
                selected = draft[editing!!.first].action.name,
                onSelect = { value ->
                    runCatching { RenameRules.Action.valueOf(value) }.getOrNull()?.let { action ->
                        val i = editing!!.first
                        draft = draft.toMutableList().apply {
                            set(i, get(i).copy(action = action, pattern = null, autoLabel = null))
                        }
                    }
                    editing = null
                },
                onDismiss = { editing = null },
            )
            RuleField.PLACEMENT -> PickerDialog(
                title = stringResource(R.string.settings_bulk_rename_where),
                options = listOf(
                    "PREFIX" to stringResource(R.string.settings_bulk_rename_before),
                    "SUFFIX" to stringResource(R.string.settings_bulk_rename_after),
                ),
                selected = draft[editing!!.first].placement.name,
                onSelect = { value ->
                    runCatching { RenameRules.Placement.valueOf(value) }.getOrNull()?.let { placement ->
                        val i = editing!!.first
                        draft = draft.toMutableList().apply {
                            set(i, get(i).copy(placement = placement, pattern = null, autoLabel = null))
                        }
                    }
                    editing = null
                },
                onDismiss = { editing = null },
            )
            RuleField.VALUE -> TextInputDialog(
                title = stringResource(R.string.settings_bulk_rename_rule_value),
                initial = draft[editing!!.first].value,
                hint = stringResource(R.string.settings_bulk_rename_value_hint),
                onConfirm = { value ->
                    val i = editing!!.first
                    draft = draft.toMutableList().apply {
                        set(i, get(i).copy(value = value, pattern = null, autoLabel = null))
                    }
                    editing = null
                },
                onDismiss = { editing = null },
            )
            null -> Unit
        }
    }
}

/** Review modeled on EpgMatchReviewDialog: left rows, right centred bulk column. */
@Composable
private fun BulkReviewDialog(session: BulkRenameSession) {
    val rows = session.preview.collectAsStateWithLifecycle().value
    BackHandler { session.done() }
    // Focus lands on the first APPLICABLE row (an unchanged first row has no buttons); if nothing
    // can be applied, it lands on "Apply all" instead. Mirrors EpgMatchReviewDialog's first-row focus.
    val firstChanged = rows.indexOfFirst { !it.unchanged }
    val firstApplyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstApplyFocus.requestFocus() } }

    // Popup(focusable=true) creates a hard focus boundary — applying/declining removes rows from the
    // LazyColumn, but focus stays inside instead of escaping to the screen behind (same pattern and
    // reason as EpgMatchReviewDialog).
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = { session.done() },
        title = stringResource(R.string.settings_bulk_rename_review),
        body = stringResource(R.string.settings_bulk_rename_review_description),
        width = 1240.mpx,
        scroll = false,
    ) {
               val listHeight = 480.mpx
               Row(Modifier.fillMaxWidth()) {
                   // Left: the pending rows. Rows are removed on Apply/Decline; keys keep the list stable.
                   LazyColumn(
                       Modifier.weight(1f).height(listHeight),
                       verticalArrangement = Arrangement.spacedBy(6.mpx),
                   ) {
                       itemsIndexed(rows, key = { _, r -> r.key }) { index, r ->
                           Row(
                               Modifier.fillMaxWidth().clip(RoundedCornerShape(16.mpx)).background(StageColors.ControlFill).padding(horizontal = 18.mpx, vertical = 10.mpx),
                               verticalAlignment = Alignment.CenterVertically,
                               horizontalArrangement = Arrangement.spacedBy(10.mpx),
                           ) {
                               Column(Modifier.weight(1f)) {
                                   Text(r.oldName, style = tv.own.owntv.ui.theme.stageText(18, 400), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                   if (r.unchanged) {
                                       Text(
                                           if (r.blankRejected) stringResource(R.string.settings_bulk_rename_blank_rejected)
                                           else stringResource(R.string.settings_bulk_rename_unchanged),
                                           style = tv.own.owntv.ui.theme.stageText(15, 500),
                                           color = StageColors.Muted,
                                           maxLines = 1,
                                           overflow = TextOverflow.Ellipsis,
                                       )
                                   } else {
                                       Text(
                                           stringResource(R.string.settings_bulk_rename_result, r.newName),
                                           style = tv.own.owntv.ui.theme.stageText(15, 500),
                                           color = if (r.duplicate) StageColors.Danger else stageAccent.accent,
                                           maxLines = 1,
                                           overflow = TextOverflow.Ellipsis,
                                       )
                                       if (r.duplicate) {
                                           Text(
                                               stringResource(R.string.settings_bulk_rename_duplicate),
                                               style = tv.own.owntv.ui.theme.stageText(15, 500),
                                               color = StageColors.Danger,
                                               maxLines = 1,
                                               overflow = TextOverflow.Ellipsis,
                                           )
                                       }
                                   }
                               }
                               if (!r.unchanged) {
                                   tv.own.owntv.ui.stage.StagePopupButton(
                                       stringResource(R.string.settings_bulk_rename_apply),
                                       onClick = { session.applyRows(setOf(r.key)) },
                                       modifier = if (index == firstChanged) Modifier.focusRequester(firstApplyFocus) else Modifier,
                                       compact = true,
                                   )
                                   tv.own.owntv.ui.stage.StagePopupButton(stringResource(R.string.settings_bulk_rename_decline), onClick = { session.declineRows(setOf(r.key)) }, primary = false, compact = true)
                               }
                           }
                       }
                   }
                   Spacer(Modifier.width(18.mpx))
                   // Right: bulk actions, VERTICALLY CENTRED so D-pad right from any middle row lands
                   // here without scrolling.
                   Column(
                       Modifier.width(240.mpx).height(listHeight),
                       verticalArrangement = Arrangement.Center,
                       horizontalAlignment = Alignment.CenterHorizontally,
                   ) {
                       Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.mpx)) {
                           Text(
                               pluralStringResource(
                                   R.plurals.settings_bulk_rename_will_change,
                                   rows.count { !it.unchanged },
                                   rows.count { !it.unchanged },
                               ),
                               style = tv.own.owntv.ui.theme.stageText(19, 700),
                               color = StageColors.Text,
                               maxLines = 1,
                               overflow = TextOverflow.Ellipsis,
                           )
                           Text(
                               pluralStringResource(
                                   R.plurals.settings_bulk_rename_unchanged_count,
                                   rows.count { it.unchanged },
                                   rows.count { it.unchanged },
                               ),
                               style = tv.own.owntv.ui.theme.stageText(19, 700),
                               color = StageColors.Muted,
                               maxLines = 1,
                               overflow = TextOverflow.Ellipsis,
                           )
                           Text(
                               pluralStringResource(
                                   R.plurals.settings_bulk_rename_duplicates_count,
                                   rows.count { it.duplicate },
                                   rows.count { it.duplicate },
                               ),
                               style = tv.own.owntv.ui.theme.stageText(19, 700),
                               color = StageColors.Danger,
                               maxLines = 1,
                               overflow = TextOverflow.Ellipsis,
                           )
                       }
                       Spacer(Modifier.height(14.mpx))
                       tv.own.owntv.ui.stage.StagePopupButton(
                           stringResource(R.string.settings_bulk_rename_apply_all),
                           onClick = { session.applyAll() },
                           modifier = Modifier.fillMaxWidth().then(if (firstChanged == -1) Modifier.focusRequester(firstApplyFocus) else Modifier),
                           icon = OwnTVIcon.PLAY,
                           compact = true,
                       )
                       Spacer(Modifier.height(8.mpx))
                       tv.own.owntv.ui.stage.StagePopupButton(stringResource(R.string.settings_bulk_rename_decline_all), onClick = { session.declineAll() }, modifier = Modifier.fillMaxWidth(), primary = false, compact = true)
                       Spacer(Modifier.height(8.mpx))
                       tv.own.owntv.ui.stage.StagePopupButton(stringResource(R.string.settings_bulk_rename_edit_rules), onClick = { session.editRules() }, modifier = Modifier.fillMaxWidth(), primary = false, compact = true)
                       Spacer(Modifier.height(8.mpx))
                       tv.own.owntv.ui.stage.StagePopupButton(stringResource(R.string.common_done), onClick = { session.done() }, modifier = Modifier.fillMaxWidth(), primary = false, compact = true)
                   }
               }


           // scroll = false: this column holds a LazyColumn, which manages its own scrolling.


        // PopupFontTheme
    } // Popup
}

/** Confirm before ↺ Restore original names — the only undo for a bulk apply, so it's not optional. */
@Composable
private fun BulkRestoreConfirmDialog(session: BulkRenameSession) {
    tv.own.owntv.ui.stage.StageConfirm(
        title = stringResource(R.string.settings_bulk_rename_restore_title),
        body = stringResource(R.string.settings_bulk_rename_restore_description),
        confirm = stringResource(R.string.settings_bulk_rename_restore),
        onConfirm = { session.confirmRestore() },
        onCancel = { session.backToChoice() },
    )
}

/** The > [BULK_RENAME_MAX_ROWS] refusal: a clear message, nothing else. */
@Composable
private fun BulkRefusedDialog(session: BulkRenameSession) {
    tv.own.owntv.ui.stage.StageNotice(
        title = stringResource(R.string.settings_bulk_rename_too_many_title),
        body = stringResource(R.string.settings_bulk_rename_too_many_description),
        onDismiss = { session.dismissRefused() },
    )
}
