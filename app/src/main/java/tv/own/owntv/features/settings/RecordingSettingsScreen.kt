package tv.own.owntv.features.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.features.recordings.RecordingsViewModel
import tv.own.owntv.ui.components.NumberInputDialog
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.restoreAfterDialogClose
import tv.own.owntv.ui.theme.mpx

private enum class RecordingDialog { NONE, PRE_ROLL, POST_ROLL }

/** This screen's rows as Settings search finds them, by their own titles. `SettingsSearchRowsTest` holds it to the rows below. */
internal val RECORDING_SEARCH_ROWS: List<Int> = listOf(
    R.string.settings_recording_reserve, R.string.settings_record_watching,
    R.string.settings_recording_pre_roll, R.string.settings_recording_post_roll,
)

/**
 * The three things recording can be told, and one thing it has to tell the user — the RECORDING part
 * of Settings › Watching & recording, drawn inside that page under its own sub-heading.
 *
 * There is deliberately **no** "what to do when space runs low": that behaviour is fixed — a
 * recording stops with 500 MB free, keeps what it captured, and never deletes anything to make room.
 * A setting implies a choice, and there isn't one.
 */
@Composable
internal fun RecordingSettingsRows(
    scrollState: androidx.compose.foundation.ScrollState,
    vm: SettingsViewModel = koinViewModel(),
    recordingsVm: RecordingsViewModel = koinViewModel(),
) {
    val reserve by vm.recordingReserveConnection.collectAsStateWithLifecycle()
    val preRoll by vm.recordingPreRollMinutes.collectAsStateWithLifecycle()
    val postRoll by vm.recordingPostRollMinutes.collectAsStateWithLifecycle()
    val recordWatching by vm.recordWhatImWatching.collectAsStateWithLifecycle()

    val preRollFocus = remember { FocusRequester() }
    val postRollFocus = remember { FocusRequester() }
    var dialog by remember { mutableStateOf(RecordingDialog.NONE) }
    var dialogReturn by remember { mutableStateOf<FocusRequester?>(null) }
    var showWatchingWarning by remember { mutableStateOf(false) }

    var savedScroll by remember { mutableIntStateOf(0) }
    LaunchedEffect(dialog) {
        if (dialog != RecordingDialog.NONE) {
            savedScroll = scrollState.value
            return@LaunchedEffect
        }
        if (dialogReturn != null) restoreAfterDialogClose(dialogReturn, scrollState, savedScroll)
        dialogReturn = null
    }

    StageSettingsHeading(stringResource(R.string.recording_settings_group), 4)
    // Said here and nowhere else, because this is where a user wonders why a recording began at
    // 20:03. Not a setting — the app cannot grant itself the permission — but the one place the
    // consequence belongs.
    if (!recordingsVm.timersAreExact) {
        Text(
            stringResource(R.string.settings_recording_timers_inexact),
            style = tv.own.owntv.ui.theme.stageText(15.5f, 500),
            color = tv.own.owntv.ui.theme.StageColors.Danger,
            modifier = Modifier.padding(start = 22.mpx, bottom = 6.mpx),
        )
    }
    KeyedSettingRow(
        icon = OwnTVIcon.LIVE_TV,
        title = stringResource(R.string.settings_recording_reserve),
        desc = stringResource(R.string.settings_recording_reserve_description),
        value = SettingValue.Switch(reserve),
        helpKey = "rec_reserve",
        onClick = { vm.setRecordingReserveConnection(!reserve) },
    )
    // The player's record button does not exist until this is on, and turning it on is
    // where the one-connection trade-off is explained and accepted. Turning it OFF needs no
    // dialog: nothing is being traded away.
    KeyedSettingRow(
        icon = OwnTVIcon.PLAY,
        title = stringResource(R.string.settings_record_watching),
        desc = stringResource(R.string.settings_record_watching_description),
        value = SettingValue.Switch(recordWatching),
        helpKey = "rec_watching",
        onClick = {
                if (recordWatching) vm.setRecordWhatImWatching(false) else showWatchingWarning = true
            },
    )
    KeyedSettingRow(
        icon = OwnTVIcon.HISTORY,
        title = stringResource(R.string.settings_recording_pre_roll),
        desc = stringResource(R.string.settings_recording_pre_roll_description),
        value = SettingValue.Choice(pluralStringResource(R.plurals.recording_minutes, preRoll, preRoll)),
        helpKey = "rec_pre_roll",
        onClick = { dialogReturn = preRollFocus; dialog = RecordingDialog.PRE_ROLL },
        modifier = Modifier.focusRequester(preRollFocus),
    )
    KeyedSettingRow(
        icon = OwnTVIcon.HISTORY,
        title = stringResource(R.string.settings_recording_post_roll),
        desc = stringResource(R.string.settings_recording_post_roll_description),
        value = SettingValue.Choice(pluralStringResource(R.plurals.recording_minutes, postRoll, postRoll)),
        helpKey = "rec_post_roll",
        onClick = { dialogReturn = postRollFocus; dialog = RecordingDialog.POST_ROLL },
        modifier = Modifier.focusRequester(postRollFocus),
    )

    // The auto-frame-rate precedent, and Multiview's after it: name the trade-off, then offer both
    // answers with the safer one first.
    if (showWatchingWarning) {
        RecordWatchingWarningDialog(
            onKeepOff = { showWatchingWarning = false },
            onTurnOn = { vm.setRecordWhatImWatching(true); showWatchingWarning = false },
        )
    }

    when (dialog) {
        // min = 0 on both: "no padding at all" is a legitimate choice for a provider whose guide
        // times are exact, and the dialog's usual min of 1 would quietly refuse it.
        RecordingDialog.PRE_ROLL -> NumberInputDialog(
            title = stringResource(R.string.settings_recording_pre_roll),
            value = preRoll,
            min = 0,
            max = MAX_ROLL_MINUTES,
            fieldLabel = stringResource(R.string.common_minutes),
            // Persist only — never close here. [NumberInputDialog] fires onSet live on every − / +
            // press, so closing in it shuts the dialog on the first nudge; Save and Back are what
            // dismiss it, exactly as on the channel-navigation dialogs.
            onSet = vm::setRecordingPreRollMinutes,
            onReset = {
                vm.setRecordingPreRollMinutes(tv.own.owntv.core.recording.RecordingSchedule.DEFAULT_PRE_ROLL_MINUTES)
            },
            onDismiss = { dialog = RecordingDialog.NONE },
        )
        RecordingDialog.POST_ROLL -> NumberInputDialog(
            title = stringResource(R.string.settings_recording_post_roll),
            value = postRoll,
            min = 0,
            max = MAX_ROLL_MINUTES,
            fieldLabel = stringResource(R.string.common_minutes),
            onSet = vm::setRecordingPostRollMinutes,
            onReset = {
                vm.setRecordingPostRollMinutes(tv.own.owntv.core.recording.RecordingSchedule.DEFAULT_POST_ROLL_MINUTES)
            },
            onDismiss = { dialog = RecordingDialog.NONE },
        )
        RecordingDialog.NONE -> Unit
    }
}

/** Core's own cap, mirrored here so the picker cannot offer a value the store would clamp. */
private const val MAX_ROLL_MINUTES = tv.own.owntv.core.recording.RecordingSchedule.MAX_ROLL_MINUTES

/**
 * The trade-off behind "Record what I'm watching", said before it is switched on.
 *
 * Shaped exactly like the Multiview and auto-frame-rate warnings before it: a title naming the
 * constraint, a paragraph explaining what is gained and what is given up, and two buttons with the
 * safer answer first and focused.
 */
@Composable
private fun RecordWatchingWarningDialog(onKeepOff: () -> Unit, onTurnOn: () -> Unit) {
    tv.own.owntv.ui.stage.StageConfirm(
        title = stringResource(R.string.settings_record_watching_warning_title),
        body = stringResource(R.string.settings_record_watching_warning_description),
        cancel = stringResource(R.string.settings_record_watching_keep_off),
        confirm = stringResource(R.string.settings_record_watching_turn_on),
        onConfirm = onTurnOn,
        onCancel = onKeepOff,
        focusCancel = true,
    )
}
