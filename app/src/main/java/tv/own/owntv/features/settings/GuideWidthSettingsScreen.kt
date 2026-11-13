package tv.own.owntv.features.settings

import tv.own.owntv.ui.theme.StageColors
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.core.settings.GuideWidthLimits
import tv.own.owntv.core.settings.GuideWidthShares
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.trapAllFocusExit

/** Layout setting for the Guide's pinned channel column and scrollable programme timeline. */
@Composable
fun GuideWidthSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = koinViewModel()
    val enabled by vm.guideWidthEnabled.collectAsStateWithLifecycle()
    val shares by vm.guideWidthShares.collectAsStateWithLifecycle()
    val current = shares ?: GuideWidthLimits.defaults
    val rowFocus = remember { FocusRequester() }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { rowFocus.requestFocus() } }
    LaunchedEffect(showDialog) {
        if (!showDialog) runCatching { rowFocus.requestFocus() }
    }
    // The TV Guide row and Reset; the panel draws the two columns at their widths.
    val rules = stringResource(R.string.settings_guide_width_help, *NO_ARGS)
    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_layout)),
        title = stringResource(R.string.settings_guide_width),
        count = pluralStringResource(R.plurals.settings_setting_count, 2, 2),
        onBack = onBack,
        modifier = modifier,
    ) {
        val title = stringResource(R.string.content_epg_title)
        val value = SettingValue.Opens(stringResource(if (enabled) R.string.settings_live_latency_custom else R.string.settings_subtitle_default))
        StageSettingRow(
            icon = OwnTVIcon.EPG,
            title = title,
            desc = stringResource(R.string.settings_guide_width_summary, current.channels, current.epg),
            value = value,
            onClick = { showDialog = true },
            help = SettingHelp(
                title, rules,
                hints = settingHints(value, pinnable = false),
                extra = { androidx.compose.foundation.layout.Box(Modifier.padding(top = 18.mpx)) { GuideWidthDiagram(current) } },
            ),
            modifier = Modifier.focusRequester(rowFocus),
        )
        val reset = stringResource(R.string.common_reset)
        StageSettingRow(
            icon = OwnTVIcon.REFRESH,
            title = reset,
            desc = stringResource(R.string.settings_guide_width_summary, GuideWidthLimits.defaults.channels, GuideWidthLimits.defaults.epg),
            value = null,
            onClick = { vm.setGuideWidths(false, GuideWidthLimits.defaults) },
            help = SettingHelp(reset, rules, hints = settingHints(null, pinnable = false)),
        )
        // In the page's panel.
        if (showDialog) panelEditor({ showDialog = false }) {
            GuideWidthDialog(savedEnabled = enabled, savedShares = current, onSave = vm::setGuideWidths, onDismiss = { showDialog = false })
        }
    }

}

@Composable
private fun GuideWidthDialog(
    savedEnabled: Boolean,
    savedShares: GuideWidthShares,
    onSave: (Boolean, GuideWidthShares) -> Unit,
    onDismiss: () -> Unit,
) {
    // The two columns always add up to 100%, so there is one number to set: the channel column's
    // width; the guide takes the rest. In the page's panel, saved as it changes.
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    androidx.compose.foundation.layout.Column(Modifier.trapAllFocusExit().focusGroup()) {
        tv.own.owntv.ui.stage.StagePopupOption(
            title = stringResource(R.string.settings_panel_width_customize), onClick = { onSave(!savedEnabled, savedShares) },
            modifier = Modifier.focusRequester(first),
            trailing = { tv.own.owntv.ui.stage.StageSwitch(savedEnabled) },
        )
        if (savedEnabled) {
            StepRow(stringResource(R.string.settings_guide_width_channels), savedShares.channels, GuideWidthLimits.MIN, GuideWidthLimits.MAX, GuideWidthLimits.STEP) {
                onSave(true, GuideWidthShares(it, GuideWidthLimits.TOTAL - it))
            }
            Box(Modifier.padding(horizontal = 8.mpx, vertical = 14.mpx)) { GuideWidthDiagram(savedShares) }
        }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 8.mpx), horizontalArrangement = Arrangement.spacedBy(12.mpx, Alignment.End)) {
            tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_reset), onClick = { onSave(savedEnabled, GuideWidthLimits.defaults) }, height = 52.mpx, textSize = 18)
            tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_done), onClick = onDismiss, height = 52.mpx, textSize = 18, tinted = true)
        }
    }
}

@Composable
private fun GuideWidthDiagram(shares: GuideWidthShares) {
    // The two columns at their widths, in the Stage colours: channels in accent, the guide plain.
    val a = tv.own.owntv.ui.theme.stageAccent
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(52.mpx), horizontalArrangement = Arrangement.spacedBy(6.mpx)) {
        listOf(shares.channels to a.accent.copy(alpha = 0.32f), shares.epg to tv.own.owntv.ui.theme.StageColors.Ink.copy(alpha = 0.08f)).forEach { (w, fill) ->
            Box(Modifier.weight(w.toFloat()).fillMaxSize().background(fill, RoundedCornerShape(10.mpx)), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.common_percent, w), style = tv.own.owntv.ui.theme.stageText(15, 700), color = tv.own.owntv.ui.theme.StageColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
