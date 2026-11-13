package tv.own.owntv.features.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.core.i18n.SupportedLocale
import tv.own.owntv.core.i18n.SupportedLocales
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.SearchBar
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

/** Compact first-launch dropdown. The list itself uses [OwnTVPopup], so it follows the same popup
 * scale, typography, centering, focus isolation, and keyboard-safe geometry as the rest of OwnTV. */
@Composable
fun FirstRunLanguageSelector(modifier: Modifier = Modifier) {
    val viewModel: LanguageSettingsViewModel = koinViewModel()
    val currentTag by viewModel.currentTag.collectAsStateWithLifecycle()
    val selectedLocale = remember(currentTag, viewModel.pickerRows) {
        viewModel.pickerRows.firstOrNull { it.languageTag == currentTag }
    }
    val triggerFocus = remember { FocusRequester() }
    var showPicker by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val colors = OwnTVTheme.colors

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { triggerFocus.requestFocus() }
    }
    LaunchedEffect(showPicker) {
        if (!showPicker && restoreFocus) {
            kotlinx.coroutines.delay(80)
            runCatching { triggerFocus.requestFocus() }
            restoreFocus = false
        }
    }

    FocusableSurface(
        onClick = {
            restoreFocus = true
            showPicker = true
        },
        modifier = modifier
            .width(284.dp)
            .height(61.dp)
            .focusRequester(triggerFocus),
        shape = RoundedCornerShape(20.dp),
        focusedContainerColor = colors.primaryContainer,
        unfocusedContainerColor = colors.surfaceContainerHigh,
        focusedScale = 1.03f,
        glowElevation = 14,
        surface = GlassSurface.CARDS,
        glassIdleRimAlpha = 0.12f,
        contentAlignment = Alignment.CenterStart,
    ) { focused ->
        val foreground = if (focused) colors.onPrimaryContainer else colors.onSurface
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            OwnTVIcon(
                icon = OwnTVIcon.LANGUAGE,
                tint = if (focused) foreground else colors.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = selectedLocale?.endonym ?: stringResource(R.string.settings_language_system_default),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.SansSerif),
                    color = foreground,
                )
                Text(
                    text = selectedLocale?.englishName ?: stringResource(R.string.settings_language_system_default_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = foreground.copy(alpha = 0.78f),
                )
            }
            OwnTVIcon(
                icon = OwnTVIcon.CHEVRON,
                tint = foreground.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp).rotate(90f),
            )
        }
    }

    if (showPicker) {
        FirstRunLanguagePopup(
            viewModel = viewModel,
            currentTag = currentTag,
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun FirstRunLanguagePopup(
    viewModel: LanguageSettingsViewModel,
    currentTag: String,
    onDismiss: () -> Unit,
) {
    val selectedIndex = remember(currentTag, viewModel.pickerRows) {
        if (currentTag.isEmpty()) 0
        else (viewModel.pickerRows.indexOfFirst { it.languageTag == currentTag } + 1).coerceAtLeast(0)
    }
    val selectedFocus = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)

    fun choose(tag: String) {
        viewModel.setLocale(tag)
        onDismiss()
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { selectedFocus.requestFocus() }
    }
    BackHandler { onDismiss() }

    OwnTVPopup(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .modalScrim()
                .trapAllFocusExit()
                .focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.dialogPanel(
                    width = 330.dp,
                    corner = 18.dp,
                    padding = 18.dp,
                    scroll = false,
                ),
            ) {
                Text(
                    text = stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.titleLarge,
                    color = OwnTVTheme.colors.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item(key = SupportedLocales.SYSTEM_DEFAULT_TAG) {
                        LanguageRow(
                            endonym = stringResource(R.string.settings_language_system_default),
                            englishName = stringResource(R.string.settings_language_system_default_description),
                            coverage = null,
                            selected = currentTag.isEmpty(),
                            onClick = { choose(SupportedLocales.SYSTEM_DEFAULT_TAG) },
                            modifier = if (currentTag.isEmpty()) Modifier.focusRequester(selectedFocus) else Modifier,
                        )
                    }
                    items(viewModel.pickerRows, key = { it.languageTag }) { locale ->
                        val selected = locale.languageTag == currentTag
                        LanguageRow(
                            endonym = locale.endonym,
                            englishName = locale.englishName,
                            coverage = null,
                            selected = selected,
                            onClick = { choose(locale.languageTag) },
                            modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

/**
 * In-app language picker. System default is pinned first; remaining rows are A–Z by English name.
 * Same-script switches recompose instantly via [tv.own.owntv.core.i18n.LocalizedContent];
 * cross-script switches trigger one [android.app.Activity.recreate].
 */
@Composable
fun LanguageSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: LanguageSettingsViewModel = koinViewModel()
    val currentTag by viewModel.currentTag.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showContribution by remember { mutableStateOf(false) }

    val systemLabel = stringResource(R.string.settings_language_system_default)
    val systemDesc = stringResource(R.string.settings_language_system_default_description)
    val filtered = remember(query, viewModel.pickerRows) {
        val q = query.trim()
        if (q.isEmpty()) {
            viewModel.pickerRows
        } else {
            viewModel.pickerRows.filter { locale ->
                locale.endonym.contains(q, ignoreCase = true) ||
                    locale.englishName.contains(q, ignoreCase = true) ||
                    locale.languageTag.contains(q, ignoreCase = true)
            }
        }
    }
    val showSystemDefault = remember(query, systemLabel, systemDesc) {
        val q = query.trim()
        q.isEmpty() ||
            systemLabel.contains(q, ignoreCase = true) ||
            systemDesc.contains(q, ignoreCase = true)
    }

    val selectedFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    // Land on the currently selected row once when the screen opens; fall back to search if the
    // row was filtered out. requestFocus() reports failure via Boolean, not exceptions.
    fun requestPreferredFocus() {
        if (!selectedFocus.requestFocus()) {
            searchFocus.requestFocus()
        }
    }
    // Changing locale replaces the localized Compose subtree, and a cross-script change also
    // recreates the Activity. Wait for the newly selected row to own selectedFocus, then restore
    // focus there instead of allowing Compose's fallback search to land on the main sidebar.
    LaunchedEffect(currentTag) {
        kotlinx.coroutines.delay(80)
        requestPreferredFocus()
    }
    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            .focusProperties {
                onEnter = { requestPreferredFocus() }
            }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Header(title = stringResource(R.string.settings_language), onBack = onBack)
        Spacer(Modifier.height(12.dp))

        SearchBar(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.settings_language_search_hint),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocus),
            surface = GlassSurface.CARDS,
        )
        Spacer(Modifier.height(12.dp))

        Spacer(Modifier.height(16.dp))
        OwnTVButton(
            label = stringResource(R.string.settings_language_help_translate),
            onClick = { showContribution = true },
            modifier = Modifier.fillMaxWidth(),
            style = OwnTVButtonStyle.SECONDARY,
        )
        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(12.dp))

        if (showSystemDefault) {
            LanguageRow(
                endonym = systemLabel,
                englishName = systemDesc,
                coverage = null,
                selected = currentTag.isEmpty(),
                onClick = { viewModel.setLocale(SupportedLocales.SYSTEM_DEFAULT_TAG) },
                modifier = if (currentTag.isEmpty()) {
                    Modifier.focusRequester(selectedFocus)
                } else {
                    Modifier
                },
            )
            if (filtered.isNotEmpty()) {
                Divider()
            }
        }

        filtered.forEach { locale ->
            val selected = locale.languageTag == currentTag
            LanguageRow(
                endonym = locale.endonym,
                englishName = locale.englishName,
                coverage = coverageBadgePercent(locale),
                selected = selected,
                onClick = { viewModel.setLocale(locale.languageTag) },
                modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier,
            )
        }
    }

    if (showContribution) {
        TranslationContributionDialog(onDismiss = { showContribution = false })
    }
}

internal fun coverageBadgePercent(locale: SupportedLocale): Int? =
    SupportedLocales.coverageBadgePercent(locale)

internal fun openContributionLink(context: Context, url: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)

internal fun copyContributionLink(context: Context, url: String): Boolean = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.settings_language_help_translate), url))
    true
}.getOrDefault(false)

@Composable
private fun TranslationContributionDialog(onDismiss: () -> Unit) {
    val colors = OwnTVTheme.colors
    val context = LocalContext.current
    val url = SupportedLocales.CONTRIBUTION_PROJECT_URL
    val requestUrl = SupportedLocales.LANGUAGE_REQUEST_URL
    val urlFocus = remember { FocusRequester() }
    val qr = remember(url) { CompanionLink.renderQr(url) }
    var status by remember { mutableStateOf<Int?>(null) }
    var copyUrl by remember { mutableStateOf(url) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        urlFocus.requestFocus()
    }
    BackHandler { onDismiss() }

    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .modalScrim()
            .trapAllFocusExit()
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.dialogPanel(width = 760.dp, padding = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.settings_language_help_translate),
                style = MaterialTheme.typography.titleLarge,
                color = colors.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_language_contribution_description),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_language_request_workflow),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (qr != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(8.dp),
                ) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = stringResource(R.string.settings_language_contribution_qr_description),
                        modifier = Modifier.size(220.dp),
                    )
                }
            } else {
                Text(
                    stringResource(R.string.settings_language_contribution_qr_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            // The URL is itself a focusable action so TV users can activate it with OK; the copy
            // action below provides a second accessible way to transfer the exact same URL.
            OwnTVButton(
                label = url,
                onClick = {
                    status = if (openContributionLink(context, url)) {
                        null
                    } else {
                        copyUrl = url
                        R.string.settings_language_contribution_open_failed
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(urlFocus),
                style = OwnTVButtonStyle.SECONDARY,
            )
            Spacer(Modifier.height(10.dp))
            OwnTVButton(
                label = stringResource(R.string.settings_language_request_new),
                onClick = {
                    status = if (openContributionLink(context, requestUrl)) {
                        null
                    } else {
                        copyUrl = requestUrl
                        R.string.settings_language_contribution_open_failed
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                style = OwnTVButtonStyle.SECONDARY,
            )
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OwnTVButton(
                    label = stringResource(R.string.settings_language_contribution_copy),
                    onClick = {
                        status = if (copyContributionLink(context, copyUrl)) {
                            R.string.settings_language_contribution_copied
                        } else {
                            R.string.settings_language_contribution_copy_failed
                        }
                    },
                    style = OwnTVButtonStyle.SECONDARY,
                )
                OwnTVButton(
                    label = stringResource(R.string.settings_close),
                    onClick = onDismiss,
                    style = OwnTVButtonStyle.SECONDARY,
                )
            }
        }
    }
    }
}

@Composable
private fun LanguageRow(
    endonym: String,
    englishName: String,
    coverage: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        selected = selected,
        shape = RoundedCornerShape(16.dp),
        selectedContainerColor = colors.primaryContainer,
        surface = GlassSurface.CARDS,
        contentAlignment = Alignment.CenterStart,
    ) { _ ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RadioIndicator(selected = selected)
            Column(modifier = Modifier.weight(1f)) {
                // SansSerif so CJK / Arabic / Hebrew endonyms get platform Noto fallbacks (Lora has none).
                Text(
                    endonym,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.SansSerif),
                    color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                )
                Text(
                    englishName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) colors.onPrimaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                )
            }
            if (coverage != null) {
                Text(
                    stringResource(R.string.settings_language_coverage, coverage),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) colors.onPrimaryContainer else colors.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) colors.primary.copy(alpha = 0.25f) else colors.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RadioIndicator(selected: Boolean) {
    val colors = OwnTVTheme.colors
    Box(
        modifier = Modifier
            .size(24.dp)
            .then(
                if (selected) {
                    Modifier.background(colors.primary, CircleShape)
                } else {
                    Modifier.border(2.dp, colors.outline, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Canvas(modifier = Modifier.size(14.dp)) {
                val stroke = Stroke(width = size.minDimension * 0.18f, cap = StrokeCap.Round)
                val checkColor = colors.onPrimary
                drawLine(
                    color = checkColor,
                    start = Offset(size.width * 0.18f, size.height * 0.52f),
                    end = Offset(size.width * 0.42f, size.height * 0.75f),
                    strokeWidth = stroke.width,
                    cap = stroke.cap,
                )
                drawLine(
                    color = checkColor,
                    start = Offset(size.width * 0.42f, size.height * 0.75f),
                    end = Offset(size.width * 0.82f, size.height * 0.28f),
                    strokeWidth = stroke.width,
                    cap = stroke.cap,
                )
            }
        }
    }
}
