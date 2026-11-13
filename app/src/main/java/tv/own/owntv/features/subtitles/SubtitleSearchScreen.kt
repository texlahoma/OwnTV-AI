package tv.own.owntv.features.subtitles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.OwnTVTextField
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.PopupFontTheme

/**
 * OpenSubtitles search overlay, opened from the player HUD's ADD SUBTITLES entry (subtitle plan §6).
 * Shows results for the playing movie/episode; selecting one downloads, attaches, and remembers it.
 */
@Composable
fun SubtitleSearchScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SubtitleSearchViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val applying by vm.applying.collectAsStateWithLifecycle()
    val quotaNote by vm.quotaNote.collectAsStateWithLifecycle()

    // Fresh search each time the overlay opens (the ViewModel is reused across opens), and close on
    // the one-shot "applied" event.
    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(Unit) { vm.applied.collect { onDismiss() } }

    var editing by remember { mutableStateOf(false) }
    BackHandler { if (editing) editing = false else onDismiss() }

    PopupFontTheme {
        Box(
            modifier
                .fillMaxSize()
                .modalScrim()
                .trapAllFocusExit()
                .focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.dialogPanel(width = 620.dp, padding = 24.dp)) {
                Text(stringResource(tv.own.owntv.R.string.player_subtitles_search_title), style = MaterialTheme.typography.titleLarge, color = OwnTVTheme.colors.onSurface)
                Spacer(Modifier.height(4.dp))
                quotaNote?.let { quota ->
                    Text(
                        if (quota.reset != null) {
                            pluralStringResource(tv.own.owntv.R.plurals.player_subtitles_remaining_reset, quota.remaining, quota.remaining, quota.reset)
                        } else if (quota.remaining == 1) {
                            pluralStringResource(tv.own.owntv.R.plurals.player_subtitles_remaining_count, quota.remaining, quota.remaining)
                        } else {
                            pluralStringResource(tv.own.owntv.R.plurals.player_subtitles_remaining_count, quota.remaining, quota.remaining)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = OwnTVTheme.colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (editing) {
                    EditSearchField(
                        initial = vm.initialQuery,
                        onSubmit = { q -> editing = false; vm.editSearch(q) },
                        onCancel = { editing = false },
                    )
                } else {
                    when (val s = state) {
                        // Not signed in (or session expired) — sign-in lives in Settings only.
                        is SubtitleSearchViewModel.UiState.SignedOut -> Message(
                            if (s.sessionExpired) {
                                stringResource(tv.own.owntv.R.string.player_subtitles_session_expired)
                            } else {
                                stringResource(tv.own.owntv.R.string.player_subtitles_account_needed)
                            },
                            primary = stringResource(tv.own.owntv.R.string.settings_close), onPrimary = onDismiss,
                        )
                        SubtitleSearchViewModel.UiState.Loading ->
                            Centered { OwnTVSpinner(); Spacer(Modifier.height(12.dp)); Text(stringResource(tv.own.owntv.R.string.player_subtitles_working), color = OwnTVTheme.colors.onSurfaceVariant) }
                        is SubtitleSearchViewModel.UiState.Empty -> Message(
                            if (s.showingAllLanguages) {
                                stringResource(tv.own.owntv.R.string.player_subtitles_no_matches_all_languages)
                            } else {
                                stringResource(tv.own.owntv.R.string.player_subtitles_no_matches_chosen_language)
                            },
                            primary = stringResource(tv.own.owntv.R.string.player_subtitles_edit_search), onPrimary = { editing = true },
                            secondary = stringResource(tv.own.owntv.R.string.player_subtitles_show_all_languages).takeIf { !s.showingAllLanguages },
                            onSecondary = vm::showAllLanguages,
                            tertiary = stringResource(tv.own.owntv.R.string.settings_close), onTertiary = onDismiss,
                        )
                        is SubtitleSearchViewModel.UiState.Error -> Message(
                            if (s.kind == SubtitleSearchViewModel.UiState.ErrorKind.LIMIT_REACHED) {
                                stringResource(tv.own.owntv.R.string.player_subtitles_limit_reached)
                            } else {
                                stringResource(tv.own.owntv.R.string.player_subtitles_network_error)
                            },
                            primary = stringResource(tv.own.owntv.R.string.player_subtitles_try_again), onPrimary = vm::retry,
                            tertiary = stringResource(tv.own.owntv.R.string.settings_close), onTertiary = onDismiss,
                        )
                        is SubtitleSearchViewModel.UiState.Results -> ResultsList(
                            results = s.results,
                            applyingFileId = applying,
                            onSelect = vm::select,
                            onEdit = { editing = true },
                            onShowAll = if (!s.showingAllLanguages) vm::showAllLanguages else null,
                            onClose = onDismiss,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    OpenSubtitlesAttribution()
                }
            }
        }
    }

    // Sign-in is handled in Settings → Video player → Subtitles → OpenSubtitles only.
}

/** Logo + credit line, mirroring the TMDB attribution in Metadata settings. */
@Composable
private fun OpenSubtitlesAttribution() {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(tv.own.owntv.R.drawable.ic_opensubtitles_logo),
        contentDescription = stringResource(tv.own.owntv.R.string.settings_open_subtitles),
        modifier = Modifier.height(28.dp),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(tv.own.owntv.R.string.player_subtitles_api_notice),
        style = MaterialTheme.typography.bodySmall,
        color = OwnTVTheme.colors.onSurfaceVariant,
    )
}

@Composable
private fun ResultsList(
    results: List<SubtitleSearchViewModel.Result>,
    applyingFileId: Long?,
    onSelect: (SubtitleSearchViewModel.Result) -> Unit,
    onEdit: () -> Unit,
    onShowAll: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    Column {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results, key = { it.fileId }) { r ->
                val isApplying = applyingFileId == r.fileId
                FocusableSurface(
                    onClick = { onSelect(r) },
                    enabled = applyingFileId == null,
                    modifier = if (r == results.first()) Modifier.fillMaxWidth().focusRequester(firstFocus) else Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentAlignment = Alignment.CenterStart,
                    surface = GlassSurface.DIALOGS,
                ) { _ ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                r.languageName ?: r.language ?: stringResource(tv.own.owntv.R.string.player_subtitles_subtitle),
                                style = MaterialTheme.typography.titleSmall, color = colors.onSurface, fontWeight = FontWeight.SemiBold,
                            )
                            Text(r.releaseName ?: stringResource(tv.own.owntv.R.string.player_subtitles_subtitle), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val tags = buildList {
                                if (r.fromTrusted) add(stringResource(tv.own.owntv.R.string.player_subtitles_trusted))
                                if (r.hearingImpaired) add(stringResource(tv.own.owntv.R.string.player_subtitles_sdh))
                                if (r.aiTranslated) add(stringResource(tv.own.owntv.R.string.player_subtitles_ai))
                                if (r.downloads > 0) add(pluralStringResource(tv.own.owntv.R.plurals.player_subtitles_download_count, r.downloads, r.downloads))
                            }
                            if (tags.isNotEmpty()) {
                                Text(tags.joinToString(stringResource(tv.own.owntv.R.string.player_subtitles_tags_separator)), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                            }
                        }
                        if (isApplying) OwnTVSpinner()
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OwnTVButton(stringResource(tv.own.owntv.R.string.player_subtitles_edit_search), onClick = onEdit, style = OwnTVButtonStyle.SECONDARY)
            onShowAll?.let { OwnTVButton(stringResource(tv.own.owntv.R.string.player_subtitles_all_languages), onClick = it, style = OwnTVButtonStyle.SECONDARY) }
            Spacer(Modifier.weight(1f))
            OwnTVButton(stringResource(tv.own.owntv.R.string.settings_close), onClick = onClose, style = OwnTVButtonStyle.SECONDARY)
        }
    }
}

@Composable
private fun EditSearchField(initial: String, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { fieldFocus.requestFocus() }
    }
    Column {
        Text(stringResource(tv.own.owntv.R.string.player_subtitles_edit_search), style = MaterialTheme.typography.titleSmall, color = OwnTVTheme.colors.onSurface)
        Spacer(Modifier.height(10.dp))
        OwnTVTextField(value = value, onValueChange = { value = it }, label = stringResource(tv.own.owntv.R.string.player_subtitles_title_label), modifier = Modifier.fillMaxWidth(), focusRequester = fieldFocus)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OwnTVButton(stringResource(tv.own.owntv.R.string.common_cancel), onClick = onCancel, style = OwnTVButtonStyle.SECONDARY)
            Spacer(Modifier.weight(1f))
            OwnTVButton(stringResource(tv.own.owntv.R.string.player_subtitles_search), onClick = { onSubmit(value.trim()) })
        }
    }
}

@Composable
private fun Message(
    text: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
    tertiary: String? = null,
    onTertiary: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
    Column {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = OwnTVTheme.colors.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            secondary?.let { OwnTVButton(it, onClick = { onSecondary?.invoke() }, style = OwnTVButtonStyle.SECONDARY) }
            tertiary?.let { OwnTVButton(it, onClick = { onTertiary?.invoke() }, style = OwnTVButtonStyle.SECONDARY) }
            Spacer(Modifier.weight(1f))
            OwnTVButton(primary, onClick = onPrimary, modifier = Modifier.focusRequester(focus))
        }
    }
}

@Composable
private fun Centered(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
