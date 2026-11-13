package tv.own.owntv.features.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.metadata.MetadataConfig
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVTextField
import tv.own.owntv.ui.components.companionLockedText
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.displayText
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * TMDB content languages (ISO 639-1, region-qualified where TMDB's coverage is meaningfully better for
 * one — e.g. pt-BR). "" keeps TMDB's own default (en-US), which is what installs used before this setting
 * existed, so an upgrade never silently changes anyone's metadata.
 *
 * Distinct from VideoPlayerSettingsScreen's LANGUAGES list, which uses 3-letter codes for audio/subtitle
 * track matching — TMDB only accepts 2-letter tags.
 */
private val TMDB_LANGUAGE_CODES = listOf(
    "", MetadataConfig.LANGUAGE_AUTO, "ar", "bg", "zh", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "he", "hi", "hu", "id", "it", "ja", "ko", "lv", "lt", "ms", "no", "fa", "pl", "pt-BR", "pt-PT", "ro", "ru", "sr", "sk", "sl", "es", "es-MX", "sv", "th", "tr", "uk", "vi",
)

@Composable
private fun tmdbLangName(code: String): String = stringResource(
    when (code) {
        "" -> R.string.settings_language_default
        MetadataConfig.LANGUAGE_AUTO -> R.string.settings_language_device
        "ar" -> R.string.settings_language_arabic
        "bg" -> R.string.settings_language_bulgarian
        "zh" -> R.string.settings_language_chinese
        "hr" -> R.string.settings_language_croatian
        "cs" -> R.string.settings_language_czech
        "da" -> R.string.settings_language_danish
        "nl" -> R.string.settings_language_dutch
        "en" -> R.string.settings_language_english
        "et" -> R.string.settings_language_estonian
        "fi" -> R.string.settings_language_finnish
        "fr" -> R.string.settings_language_french
        "de" -> R.string.settings_language_german
        "el" -> R.string.settings_language_greek
        "he" -> R.string.settings_language_hebrew
        "hi" -> R.string.settings_language_hindi
        "hu" -> R.string.settings_language_hungarian
        "id" -> R.string.settings_language_indonesian
        "it" -> R.string.settings_language_italian
        "ja" -> R.string.settings_language_japanese
        "ko" -> R.string.settings_language_korean
        "lv" -> R.string.settings_language_latvian
        "lt" -> R.string.settings_language_lithuanian
        "ms" -> R.string.settings_language_malay
        "no" -> R.string.settings_language_norwegian
        "fa" -> R.string.settings_language_persian
        "pl" -> R.string.settings_language_polish
        "pt-BR" -> R.string.settings_language_portuguese_brazil
        "pt-PT" -> R.string.settings_language_portuguese_portugal
        "ro" -> R.string.settings_language_romanian
        "ru" -> R.string.settings_language_russian
        "sr" -> R.string.settings_language_serbian
        "sk" -> R.string.settings_language_slovak
        "sl" -> R.string.settings_language_slovenian
        "es" -> R.string.settings_language_spanish
        "es-MX" -> R.string.settings_language_spanish_latam
        "sv" -> R.string.settings_language_swedish
        "th" -> R.string.settings_language_thai
        "tr" -> R.string.settings_language_turkish
        "uk" -> R.string.settings_language_ukrainian
        "vi" -> R.string.settings_language_vietnamese
        else -> R.string.settings_language_default
    },
)

/**
 * Settings → Metadata (TMDB). Phase M1 of the enrichment plan: the master toggle and the two advanced
 * access tiers (own TMDB key / self-host URL), plus a manual "look up title" test that proves the
 * configured tier reaches TMDB end-to-end. Enrichment of actual detail screens arrives in later phases.
 *
 * Precedence (plan §4): self-host URL > own key > the default caching Worker (zero setup).
 */
private fun metadataModeLabelRes(mode: tv.own.owntv.core.metadata.MetadataMode): Int = when (mode) {
    tv.own.owntv.core.metadata.MetadataMode.PROVIDER -> R.string.settings_metadata_provider_only
    tv.own.owntv.core.metadata.MetadataMode.PROVIDER_PLUS_TMDB -> R.string.settings_metadata_provider_plus_tmdb
    tv.own.owntv.core.metadata.MetadataMode.TMDB_ONLY -> R.string.settings_metadata_tmdb_only
}

private fun metadataTierLabelRes(tier: tv.own.owntv.core.metadata.MetadataConfig.Tier): Int = when (tier) {
    tv.own.owntv.core.metadata.MetadataConfig.Tier.DEFAULT_WORKER -> R.string.settings_tier_default
    tv.own.owntv.core.metadata.MetadataConfig.Tier.OWN_KEY -> R.string.settings_tier_key
    tv.own.owntv.core.metadata.MetadataConfig.Tier.SELF_HOST -> R.string.settings_tier_self_host
}

@Composable
fun MetadataSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    val vm: SettingsViewModel = koinViewModel()
    val mode by vm.metadataMode.collectAsStateWithLifecycle()
    val storedKey by vm.tmdbApiKey.collectAsStateWithLifecycle()
    val storedUrl by vm.metadataServerUrl.collectAsStateWithLifecycle()
    val tier by vm.metadataTier.collectAsStateWithLifecycle()
    val testState by vm.metadataTest.collectAsStateWithLifecycle()
    val language by vm.metadataLanguage.collectAsStateWithLifecycle()
    val budget by vm.metadataBudgetStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(tier) {
        if (tier == MetadataConfig.Tier.DEFAULT_WORKER) vm.refreshMetadataBudget()
    }

    var showLangPicker by remember { mutableStateOf(false) }
    var langPickerWasOpen by remember { mutableStateOf(false) }
    val langRowFocus = remember { FocusRequester() }

    // Seed the editable fields once; local edit → Save persists (same pattern as NetworkSettingsScreen).
    var seeded by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val defaultTestTitle = stringResource(R.string.settings_metadata_test_title)
    var testTitle by remember(defaultTestTitle) { mutableStateOf(defaultTestTitle) }
    // Advanced options are hidden by default. Auto-expand if the user already has a key/URL saved, so the
    // fields aren't silently hidden when they're actually in use.
    var showAdvanced by remember { mutableStateOf(false) }
    var advancedWasOpen by remember { mutableStateOf(false) }
    val advancedRowFocus = remember { FocusRequester() }
    var confirmClearAdvanced by remember { mutableStateOf(false) }
    var showModePicker by remember { mutableStateOf(false) }
    var showRemoteHandover by remember { mutableStateOf(false) }
    LaunchedEffect(storedKey, storedUrl) {
        if (!seeded) {
            key = storedKey; url = storedUrl
            seeded = true
        }
    }

    // A key handed over from the remote device lands straight in the field. Saving stays a deliberate act:
    // the user still presses Save, so an accidental send cannot silently replace a working key.
    val keyReceivedMessage = stringResource(R.string.settings_metadata_key_received)
    val toast = tv.own.owntv.ui.components.rememberInAppToast()
    LaunchedEffect(showRemoteHandover) {
        if (!showRemoteHandover) return@LaunchedEffect
        vm.remoteTmdbConfigs.collect { received ->
            key = received.apiKey
            url = received.serverUrl
            showAdvanced = true
            showRemoteHandover = false
            toast.show(keyReceivedMessage)
        }
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            // onEnter + focusGroup: a safety net for two dispose-on-collapse paths — (1) toggling
            // "Advanced options" off while focus is on a field inside it, and (2) switching Metadata
            // mode to PROVIDER (mode.enrich=false), which disposes the whole advanced block + the row
            // the user clicked. Either path leaves focus dangling; onEnter recaptures it onto the
            // always-composed first mode row whenever directional focus re-enters the group.
            .focusProperties { onEnter = { runCatching { firstFocus.requestFocus() } } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Header(stringResource(R.string.settings_metadata), onBack)
        Text(
            stringResource(R.string.settings_metadata_root_description),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp),
        )
        Spacer(Modifier.height(14.dp))

        if (mode.enrich) {
            val b = budget
            MetadataOverview(
                eyebrow = stringResource(R.string.settings_metadata_active_source),
                title = stringResource(metadataTierLabelRes(tier)),
                description = when (tier) {
                    MetadataConfig.Tier.DEFAULT_WORKER -> stringResource(R.string.settings_metadata_shared_worker_description)
                    MetadataConfig.Tier.OWN_KEY -> maskSecret(storedKey)
                    MetadataConfig.Tier.SELF_HOST -> storedUrl
                },
                minuteRemaining = b?.remainingMinute.takeIf { tier == MetadataConfig.Tier.DEFAULT_WORKER },
                minuteLimit = b?.limitMinute.takeIf { tier == MetadataConfig.Tier.DEFAULT_WORKER },
                hourRemaining = b?.remainingHour.takeIf { tier == MetadataConfig.Tier.DEFAULT_WORKER },
                hourLimit = b?.limitHour.takeIf { tier == MetadataConfig.Tier.DEFAULT_WORKER },
                dayRemaining = b?.remainingDay.takeIf { tier == MetadataConfig.Tier.DEFAULT_WORKER },
                dayLimit = b?.limitDay.takeIf { tier == MetadataConfig.Tier.DEFAULT_WORKER },
                refillTime = b?.let {
                    android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it.resetAtMs))
                }.takeIf { tier == MetadataConfig.Tier.DEFAULT_WORKER },
            )
            if (tier == MetadataConfig.Tier.DEFAULT_WORKER) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_metadata_fair_share),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
        }

        // Status panel, laid out exactly like the OpenSubtitles account screen: label left, value
        // right. Which source is active is always shown; the allowance rows only appear on the shared
        // default tier, because an own key or a self-hosted server is the user's own resource and is
        // never metered - showing a limit there would be a lie.
        if (false && mode.enrich) {
            ServiceSummaryCard(
                eyebrow = stringResource(R.string.settings_metadata_active_source),
                title = stringResource(metadataTierLabelRes(tier)),
                description = when (tier) {
                    MetadataConfig.Tier.DEFAULT_WORKER -> stringResource(R.string.settings_metadata_shared_worker_description)
                    MetadataConfig.Tier.OWN_KEY -> maskSecret(storedKey)
                    MetadataConfig.Tier.SELF_HOST -> storedUrl
                },
            )
            Spacer(Modifier.height(12.dp))
            when (tier) {
                // TMDB has no way to name the account behind a key - the key identifies the
                // application, not a person, so there is nothing to show but the key itself. Masked to
                // the last 4 characters: enough to tell two keys apart, useless to anyone reading it
                // over a shoulder or in a screenshot.
                MetadataConfig.Tier.OWN_KEY -> Unit
                MetadataConfig.Tier.SELF_HOST -> Unit
                MetadataConfig.Tier.DEFAULT_WORKER -> {
                    val budget by vm.metadataBudgetStatus.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { vm.refreshMetadataBudget() }
                    budget?.let { b ->
                        // All three windows on one line. Three separate rows pushed the actual
                        // settings off the first screen, and these numbers are only ever glanced at.
                    val context = LocalContext.current
                    AllowanceCard(
                        b.remainingMinute, b.limitMinute,
                        b.remainingHour, b.limitHour,
                        b.remainingDay, b.limitDay,
                        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(b.resetAtMs)),
                    )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_metadata_fair_share),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        // One row + a picker, matching Metadata language below. Stacked as three rows these read as
        // three separate settings rather than one choice, and they pushed everything else off screen.
        GroupLabel(stringResource(R.string.settings_metadata_library_details))
        ServiceSettingsRow(
            icon = OwnTVIcon.IMAGE,
            title = stringResource(R.string.settings_metadata_source),
            desc = stringResource(R.string.settings_metadata_source_description),
            chip = stringResource(metadataModeLabelRes(mode)), chevron = true,
            modifier = Modifier.focusRequester(firstFocus),
            onClick = { showModePicker = true },
        )

        // The advanced TMDB tier fields only make sense when TMDB is on (mode != Provider).
        if (mode.enrich) {
            ServiceSettingsRow(
                icon = OwnTVIcon.LANGUAGE,
            title = stringResource(R.string.settings_metadata_language),
            desc = stringResource(R.string.settings_metadata_language_description),
            chip = tmdbLangName(language), chevron = true,
            modifier = Modifier.focusRequester(langRowFocus),
            onClick = { showLangPicker = true },
        )

        Spacer(Modifier.height(4.dp))
        GroupLabel(stringResource(R.string.settings_metadata_connection))
        ServiceSettingsRow(
            icon = OwnTVIcon.GEAR,
            title = stringResource(R.string.settings_metadata_remote_advanced),
            desc = stringResource(R.string.settings_metadata_remote_advanced_description),
            chip = if (tier == MetadataConfig.Tier.DEFAULT_WORKER) stringResource(R.string.settings_shared)
                else stringResource(metadataTierLabelRes(tier)),
            primaryChip = tier != MetadataConfig.Tier.DEFAULT_WORKER,
            chevron = true,
            modifier = Modifier.focusRequester(advancedRowFocus),
            // Turning this OFF used to hide the fields while quietly leaving the saved key in force, so
            // the screen still reported "Your TMDB key" with nothing on screen to explain why. Off now
            // means what it says: confirm, then delete the key and URL and fall back to the shared
            // service. Confirmation because a key is real user data and a stray D-pad press must not
            // destroy it.
            onClick = { showAdvanced = true },
        )

        Spacer(Modifier.height(20.dp))
        GroupLabel(stringResource(R.string.settings_metadata_test_connection))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OwnTVTextField(
                value = testTitle,
                onValueChange = { testTitle = it },
                label = stringResource(R.string.settings_lookup_movie),
                placeholder = stringResource(R.string.settings_metadata_test_title),
                modifier = Modifier.weight(1f),
            )
            OwnTVButton(
                label = if (testState is SettingsViewModel.MetadataTestState.Testing) stringResource(R.string.settings_looking_up) else stringResource(R.string.settings_test_lookup),
                onClick = { vm.testMetadataLookup(testTitle) },
                style = OwnTVButtonStyle.SECONDARY,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetadataTestLabel(testState)
        }
        } // end if (mode.enrich)

        Spacer(Modifier.height(24.dp))
        // TMDB attribution (plan §8) — logo + line, required by TMDB's API terms.
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(tv.own.owntv.R.drawable.ic_tmdb_logo),
            contentDescription = stringResource(R.string.settings_metadata),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_tmdb_attribution),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }

    // Dialogs must come AFTER the scrolling Column: composition order is paint order, and
    // declaring this above it drew the whole settings list on top of the dialog.
    if (showAdvanced) {
        AdvancedMetadataPopup(
            key = key, url = url,
            onKeyChange = { key = it }, onUrlChange = { url = it },
            onRemote = { showAdvanced = false; showRemoteHandover = true },
            onRemove = {
                showAdvanced = false
                if (storedKey.isNotBlank() || storedUrl.isNotBlank()) confirmClearAdvanced = true
            },
            onSave = {
                vm.setTmdbApiKey(key); vm.setMetadataServerUrl(url); vm.resetMetadataTest()
                showAdvanced = false
            },
            onDismiss = { showAdvanced = false },
        )
    }
    LaunchedEffect(showAdvanced) {
        if (showAdvanced) advancedWasOpen = true
        else if (advancedWasOpen && !showRemoteHandover) {
            advancedWasOpen = false
            kotlinx.coroutines.delay(80)
            runCatching { advancedRowFocus.requestFocus() }
        }
    }

    if (confirmClearAdvanced) {
        ConfirmDialog(
            title = stringResource(R.string.settings_metadata_clear_advanced_title),
            message = stringResource(R.string.settings_metadata_clear_advanced_message),
            onConfirm = {
                key = ""
                url = ""
                vm.setTmdbApiKey("")
                vm.setMetadataServerUrl("")
                vm.resetMetadataTest()
                showAdvanced = false
                confirmClearAdvanced = false
            },
            onDismiss = { confirmClearAdvanced = false },
        )
    }

    tv.own.owntv.ui.components.InAppToast(toast)

    if (showRemoteHandover) {
        CompanionKeyDialog(
            titleRes = R.string.settings_metadata_remote_advanced,
            state = vm.remoteState.collectAsStateWithLifecycle().value,
            onStart = vm::startRemoteTmdbConfigListener,
            onStop = vm::stopRemoteListener,
            onDismiss = { showRemoteHandover = false },
        )
    }

    if (showModePicker) {
        PickerDialog(
            title = stringResource(R.string.settings_metadata_source),
            options = tv.own.owntv.core.metadata.MetadataMode.entries.map {
                it.name to stringResource(metadataModeLabelRes(it))
            },
            selected = mode.name,
            onSelect = { picked ->
                tv.own.owntv.core.metadata.MetadataMode.entries.firstOrNull { it.name == picked }?.let {
                    if (it != mode) {
                        vm.setMetadataMode(it)
                        vm.resetMetadataTest()
                    }
                }
                showModePicker = false
            },
            onDismiss = { showModePicker = false },
        )
    }

    if (showLangPicker) {
        // searchable: the list is long enough that D-pad scrolling to e.g. Ukrainian is tedious.
        PickerDialog(
            title = stringResource(R.string.settings_metadata_language),
            options = TMDB_LANGUAGE_CODES.map { it to tmdbLangName(it) },
            selected = language,
            searchable = true,
            onSelect = {
                if (it != language) vm.setMetadataLanguage(it)
                showLangPicker = false
            },
            onDismiss = { showLangPicker = false },
        )
    }
    // Return focus to the language row after the dialog closes, rather than letting it fall to the
    // screen's first mode row (same pattern as WeatherSettingsScreen's location dialog). Gated on
    // langPickerWasOpen so this doesn't fire on first composition and steal focus from firstFocus.
    LaunchedEffect(showLangPicker) {
        if (showLangPicker) {
            langPickerWasOpen = true
        } else if (langPickerWasOpen) {
            langPickerWasOpen = false
            kotlinx.coroutines.delay(80)
            runCatching { langRowFocus.requestFocus() }
        }
    }
}

@Composable
private fun MetadataTestLabel(state: SettingsViewModel.MetadataTestState) {
    val colors = OwnTVTheme.colors
    val (text, color) = when (state) {
        is SettingsViewModel.MetadataTestState.Ok -> stringResource(
            R.string.settings_metadata_match_result,
            state.title,
            state.year?.let { stringResource(R.string.settings_metadata_year, it) } ?: "",
            state.tmdbId,
        ) to colors.primary
        is SettingsViewModel.MetadataTestState.Fail -> when (val failure = state.failure) {
            SettingsViewModel.MetadataFailure.EmptyTitle -> stringResource(R.string.settings_metadata_empty_title)
            SettingsViewModel.MetadataFailure.ServerUnavailable -> stringResource(R.string.settings_metadata_server_unavailable)
            is SettingsViewModel.MetadataFailure.NoMatch -> stringResource(R.string.settings_metadata_no_match, failure.query)
            is SettingsViewModel.MetadataFailure.Unknown -> failure.rawMessage ?: stringResource(R.string.settings_metadata_lookup_failed)
        } to androidx.compose.ui.graphics.Color(0xFFEF4444)
        else -> null to colors.onSurfaceVariant
    }
    if (text != null) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Show only the tail of a secret: enough to tell two keys apart, useless to a shoulder-surfer. */
private fun maskSecret(secret: String): String {
    val trimmed = secret.trim()
    if (trimmed.length <= 4) return "\u2022".repeat(4)
    return "\u2022".repeat(8) + trimmed.takeLast(4)
}

@Composable
private fun AdvancedMetadataPopup(
    key: String,
    url: String,
    onKeyChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onRemote: () -> Unit,
    onRemove: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstFocus.requestFocus() } }
    BackHandler { onDismiss() }
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss, fontScale = .50f) {
      Box(Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(), contentAlignment = Alignment.Center) {
        Column(Modifier.dialogPanel(width = 560.dp, padding = 20.dp)) {
            Text(stringResource(R.string.settings_metadata_remote_advanced), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_metadata_server_description), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row2(
                icon = OwnTVIcon.SHARE,
                title = stringResource(R.string.settings_metadata_key_from_phone),
                desc = stringResource(R.string.settings_metadata_key_from_phone_desc),
                chevron = true,
                modifier = Modifier.focusRequester(firstFocus),
                onClick = onRemote,
            )
            Spacer(Modifier.height(8.dp))
            OwnTVTextField(value = key, onValueChange = onKeyChange, label = stringResource(R.string.settings_tmdb_api_key), placeholder = stringResource(R.string.settings_metadata_optional), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OwnTVTextField(value = url, onValueChange = onUrlChange, label = stringResource(R.string.settings_worker_server_url), placeholder = "https://your-worker.example.workers.dev", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OwnTVButton(stringResource(R.string.settings_metadata_clear_advanced_title), onRemove, style = OwnTVButtonStyle.SECONDARY)
                Spacer(Modifier.weight(1f))
                OwnTVButton(stringResource(R.string.common_cancel), onDismiss, style = OwnTVButtonStyle.SECONDARY)
                OwnTVButton(stringResource(R.string.common_save), onSave)
            }
        }
      }
    }
}

/**
 * QR + PIN panel for handing a TMDB key over from another device.
 *
 * Deliberately a dialog rather than a screen: it is a short-lived side trip from the field it fills,
 * and keeping it here avoids threading a new route through the settings navigation for something the
 * user sees once.
 *
 * The QR encodes the LAN URL only — never the PIN, which is shown on the TV and typed on the remote device.
 * A photographed QR on its own therefore cannot push a key. The listener starts when the dialog opens
 * and is stopped on dispose, so it never outlives the panel.
 */
@Composable
internal fun CompanionKeyDialog(
    titleRes: Int,
    state: tv.own.owntv.core.companion.CompanionServerState,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        onStart(tv.own.owntv.core.companion.CompanionLink.DEFAULT_PORT)
        kotlinx.coroutines.delay(80)
        runCatching { closeFocus.requestFocus() }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onStop() } }
    BackHandler { onDismiss() }

    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
      Box(Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.dialogPanel(width = 520.dp, padding = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
            stringResource(titleRes),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            when (state) {
                tv.own.owntv.core.companion.CompanionServerState.Idle,
                tv.own.owntv.core.companion.CompanionServerState.Starting,
                -> Text(
                    stringResource(R.string.settings_opening_server),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                is tv.own.owntv.core.companion.CompanionServerState.Listening -> {
                    Text(
                        stringResource(R.string.settings_enter_pin_browser),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.pin,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = colors.primary,
                        letterSpacing = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp),
                    )
                    Spacer(Modifier.height(12.dp))
                    state.qr?.let { qr ->
                        androidx.compose.foundation.Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = stringResource(R.string.settings_companion_qr),
                            modifier = Modifier
                                .size(176.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                .background(androidx.compose.ui.graphics.Color.White)
                                .padding(9.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        stringResource(R.string.settings_open_url),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    state.urls.forEach {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    }
                }
                is tv.own.owntv.core.companion.CompanionServerState.Failed -> Text(
                    state.failure.displayText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.favorite,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                tv.own.owntv.core.companion.CompanionServerState.Locked -> Text(
                    tv.own.owntv.ui.components.companionLockedText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.favorite,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.height(20.dp))
            OwnTVButton(
                stringResource(R.string.common_cancel),
                onClick = onDismiss,
                style = OwnTVButtonStyle.SECONDARY,
                modifier = Modifier.focusRequester(closeFocus),
            )
        }
      }
    }
}
