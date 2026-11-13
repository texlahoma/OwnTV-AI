package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon

/**
 * Maintainer-only tools, opened from More › Developer (as on the phone). Reached only when
 * BuildConfig.DEV_TOOLS is set, so R8 removes this screen from every published APK; English by the
 * same rule.
 */
@Composable
fun DeveloperScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: HomeSettingsViewModel = koinViewModel()
    val devRebuild by vm.devRebuild.collectAsStateWithLifecycle()

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstFocus.requestFocus() } }
    BackHandler { onBack() }
    // Drawn as the other More pages: the 42 px title, then Stage rows.
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxSize().focusGroup(),
        verticalArrangement = Arrangement.spacedBy(6.mpx),
    ) {
        Text("Developer", style = stageText(42, 800), color = StageColors.Text, modifier = Modifier.padding(bottom = 20.mpx))
        StageSettingRow(
            icon = OwnTVIcon.SHARE,
            title = "Rebuild Now Trending",
            desc = "Forces a fresh TMDB trending download for every playlist, ignoring the multi-day fetch timer.",
            value = if (devRebuild == HomeSettingsViewModel.DevRebuildState.STARTED) SettingValue.Action(stringResource(R.string.settings_rebuilding)) else null,
            onClick = { vm.rebuildTrendingNow() },
            modifier = Modifier.focusRequester(firstFocus),
        )
    }
}
