package tv.own.owntv.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.LinkedSubtitle
import tv.own.owntv.ui.format.localizedInteger
import tv.own.owntv.ui.stage.StageSegmented
import tv.own.owntv.ui.stage.StageTool
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.focus.focusProperties
import tv.own.owntv.ui.components.rememberDialogFocusRestore
import tv.own.owntv.ui.components.OwnTVIcon

/**
 * Settings → OpenSubtitles account → Delete subtitles. A Movies/Series toggle at
 * the top, the downloaded subtitles for the selected section below (tap to delete one), and a
 * Delete all action (all / all movies / all series).
 */
@Composable
fun DeleteSubtitlesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: DeleteSubtitlesViewModel = koinViewModel()
    val section by vm.section.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val movieCount by vm.movieCount.collectAsStateWithLifecycle()
    val seriesCount by vm.seriesCount.collectAsStateWithLifecycle()

    var confirmDelete by remember { mutableStateOf<LinkedSubtitle?>(null) }
    var showDeleteAll by remember { mutableStateOf(false) }
    val rowsFocus = remember { FocusRequester() }
    val tabsFocus = remember { FocusRequester() }
    val actionsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (!rowsFocus.requestFocus()) runCatching { tabsFocus.requestFocus() }
    }

    // "Delete all" sits in the tool row, so the confirmation closing returns focus there.
    val scrollState = rememberScrollState()
    val dialogFocus = rememberDialogFocusRestore(showDeleteAll, scrollState)
    val deleteAllFocus = remember { FocusRequester() }

    // Movies | Series and Delete all in the tool row, the files as rows, Delete in the panel.
    val total = movieCount + seriesCount
    val openSubtitles = stringResource(R.string.settings_open_subtitles)
    val sections = DeleteSubtitlesViewModel.Section.entries
    StageFullPage(
        parents = listOf(openSubtitles),
        settingsRoot = false,
        title = stringResource(R.string.settings_delete_subtitles),
        count = pluralStringResource(R.plurals.settings_subtitle_file_count, total, total),
        onBack = onBack,
        modifier = modifier,
        scroll = scrollState,
        rowsFocus = rowsFocus,
        toolbar = {
            StageSegmented(
                options = sections.map { s ->
                    val label = stringResource(if (s == DeleteSubtitlesViewModel.Section.MOVIES) R.string.settings_movies else R.string.settings_series)
                    val n = if (s == DeleteSubtitlesViewModel.Section.MOVIES) movieCount else seriesCount
                    label + " " + localizedInteger(n, grouping = false)
                },
                selected = sections.indexOf(section),
                onSelect = { vm.selectSection(sections[it]) },
                modifier = Modifier.focusRequester(tabsFocus),
            )
            if (total > 0) {
                StageTool(
                    stringResource(R.string.settings_delete_all),
                    onClick = { dialogFocus.value = deleteAllFocus; showDeleteAll = true },
                    icon = OwnTVIcon.TRASH, boxed = true, danger = true,
                    modifier = Modifier.focusRequester(deleteAllFocus),
                )
            }
        },
    ) {
        if (items.isEmpty()) {
            StageSettingsNote(
                stringResource(
                    R.string.settings_no_downloaded_subtitles,
                    stringResource(if (section == DeleteSubtitlesViewModel.Section.MOVIES) R.string.settings_movies else R.string.settings_series).lowercase(),
                ),
                null,
            )
        }
        val help = stringResource(R.string.settings_subtitle_delete_help)
        val hints = listOf(
            stringResource(R.string.common_ok) to stringResource(R.string.common_delete),
            stringResource(R.string.common_back) to openSubtitles,
        )
        val separator = dotSeparator()
        items.forEach { item ->
            val title = item.displayTitle()
            val rowFocus = remember { FocusRequester() }
            val delete = StageAction(OwnTVIcon.TRASH, stringResource(R.string.common_delete), { confirmDelete = item }, danger = true)
            StageSettingRow(
                icon = OwnTVIcon.SUBTITLE,
                title = title,
                desc = listOfNotNull(item.languageName ?: item.language, item.releaseName).joinToString(separator),
                value = null,
                onClick = { confirmDelete = item },
                modifier = Modifier.focusRequester(rowFocus).focusProperties { right = actionsFocus },
                help = SettingHelp(title, help, hints = hints, extra = { StageActionColumn(listOf(delete), rowFocus, actionsFocus) }),
            )
        }
    }

    confirmDelete?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.settings_delete_subtitle),
            message = stringResource(
                R.string.settings_delete_subtitle_message,
                item.languageName ?: item.language ?: item.fileName,
                item.displayTitle(),
            ),
            onConfirm = { vm.deleteOne(item); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }

    if (showDeleteAll) {
        PickerDialog(
            title = stringResource(R.string.settings_delete_all_subtitles),
            options = listOf(
                "ALL" to stringResource(R.string.settings_delete_all),
                "MOVIES" to stringResource(R.string.settings_delete_all_movies),
                "SERIES" to stringResource(R.string.settings_delete_all_series),
            ),
            selected = "",
            onSelect = { choice ->
                when (choice) {
                    "ALL" -> vm.deleteAll()
                    "MOVIES" -> vm.deleteAllMovies()
                    "SERIES" -> vm.deleteAllSeries()
                }
                showDeleteAll = false
            },
            onDismiss = { showDeleteAll = false },
        )
    }
}

@Composable
private fun LinkedSubtitle.displayTitle(): String {
    val episodeTitle = episodeDisplayTitleParts(mediaType, contentTitle, contentKey) ?: return contentTitle
    return stringResource(
        R.string.player_episode_context_title,
        episodeTitle.baseTitle,
        episodeTitle.season,
        episodeTitle.episode,
    )
}

internal data class EpisodeDisplayTitleParts(
    val baseTitle: String,
    val season: Int,
    val episode: Int,
)

/**
 * Normalizes subtitle links written by both schema generations. Pre-i18n rows persisted the English
 * display suffix in [contentTitle]; current rows persist only the raw series title. Strip the old
 * suffix only when its numbers exactly match [contentKey], then let Compose format it for the locale.
 */
internal fun episodeDisplayTitleParts(
    mediaType: String,
    contentTitle: String,
    contentKey: String,
): EpisodeDisplayTitleParts? {
    if (mediaType != "SERIES") return null
    val match = Regex(":S(\\d+)E(\\d+)$").find(contentKey) ?: return null
    val season = match.groupValues[1].toInt()
    val episode = match.groupValues[2].toInt()
    val legacySuffix = " · S${season}E${episode}"
    return EpisodeDisplayTitleParts(
        baseTitle = contentTitle.removeSuffix(legacySuffix),
        season = season,
        episode = episode,
    )
}
