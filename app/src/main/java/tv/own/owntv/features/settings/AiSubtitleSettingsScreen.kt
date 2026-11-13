package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.settings.SubtitleStyle
import tv.own.owntv.features.aisubtitles.AiSubtitleConfigStore
import tv.own.owntv.features.aisubtitles.AiSubtitleDefaults
import tv.own.owntv.features.aisubtitles.AiSubtitleLanguages
import tv.own.owntv.features.aisubtitles.AiSubtitleManager
import tv.own.owntv.features.aisubtitles.streaming.StreamingModelCatalog
import tv.own.owntv.features.aisubtitles.streaming.StreamingModelInfo
import tv.own.owntv.features.aisubtitles.streaming.StreamingModelStore
import tv.own.owntv.features.aisubtitles.translate.OpenAiCompatibleTranslator
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.OwnTVTheme
import kotlin.math.roundToInt

/**
 * Languages offered for both speech recognition (source) and translation (target), as BCP-47 codes.
 * Kept deliberately modest — Whisper and most cloud gateways cover these well.
 *
 * The list shares [AiSubtitleLanguages] with the in-player AI-subtitle dialog so both offer the
 * same localized options.
 */

@Composable
private fun aiLanguages(): List<Pair<String, String>> {
    val displayLocale = LocalConfiguration.current.locales[0]
    return remember(displayLocale) { AiSubtitleLanguages.localized(displayLocale) }
}

private fun languageName(code: String, options: List<Pair<String, String>>): String? =
    options.firstOrNull { it.first == code }?.second

/**
 * Settings → Content → AI subtitles. Controls the speech-recognition → cloud-translate pipeline:
 * the recognition (source) language, the translate (target) language, and the OpenAI-compatible
 * gateway (base URL, API key, model). There is deliberately NO master switch here — AI subtitles
 * are switched on/off per channel from the playback HUD (default off). The live recognition status
 * (engine:language:mode + chunk/blank/words debug) is shown at the bottom.
 */
@Composable
fun AiSubtitleSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    val manager = koinInject<AiSubtitleManager>()
    val configStore = koinInject<AiSubtitleConfigStore>()
    val modelStore = koinInject<StreamingModelStore>()
    val config by manager.config.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    val downloadedModels by modelStore.downloaded.collectAsStateWithLifecycle()
    val downloadProgress by modelStore.progress.collectAsStateWithLifecycle()
    val modelExtracting by modelStore.extracting.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val languages = aiLanguages()
    val sourceName = languageName(config.sourceLanguage, languages)
        ?: config.sourceLanguage.ifBlank { stringResource(R.string.ai_subtitles_auto) }
    val targetName = languageName(config.targetLanguage, languages)
        ?: config.targetLanguage.ifBlank { stringResource(R.string.ai_subtitles_original) }

    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var showApi by remember { mutableStateOf(false) }
    var showDisplay by remember { mutableStateOf(false) }
    var showLineSpacing by remember { mutableStateOf(false) }
    var showPosition by remember { mutableStateOf(false) }
    var showLiveDelay by remember { mutableStateOf(false) }
    var modelToDelete by remember { mutableStateOf<StreamingModelInfo?>(null) }
    var modelBusy by remember { mutableStateOf<String?>(null) } // language code tapped while its model is downloading
    var modelFailed by remember { mutableStateOf<String?>(null) } // language code whose download errored
    val firstFocus = remember { FocusRequester() }
    val apiRowFocus = remember { FocusRequester() }
    val apiWasOpen = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstFocus.requestFocus() } }
    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            .focusProperties { onEnter = { runCatching { firstFocus.requestFocus() } } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Header(stringResource(R.string.settings_ai_subtitles), onBack)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_ai_subtitles_description),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))

        // Recognition — the master switch is deliberately NOT here: AI subtitles are turned on/off
        // per channel from the playback HUD (default off), so the global Settings page only offers
        // the recognition language, translation target/gateway, display and streaming models.

        GroupLabel(stringResource(R.string.ai_subtitles_recognition))
        ServiceSettingsRow(
            icon = OwnTVIcon.LANGUAGE,
            title = stringResource(R.string.ai_subtitles_source_language),
            desc = stringResource(R.string.ai_subtitles_source_language_description),
            chip = sourceName, chevron = true,
            modifier = Modifier.focusRequester(firstFocus),
            onClick = { showSourcePicker = true },
        )
        ServiceSettingsRow(
            icon = OwnTVIcon.GEAR,
            title = stringResource(R.string.ai_subtitles_live_delay),
            desc = stringResource(R.string.ai_subtitles_live_delay_description),
            chip = if (config.liveDelaySeconds == AiSubtitleDefaults.LIVE_DELAY_OFF) {
                stringResource(R.string.ai_subtitles_live_delay_off)
            } else {
                stringResource(R.string.ai_subtitles_live_delay_seconds, config.liveDelaySeconds)
            },
            chevron = true,
            onClick = { showLiveDelay = true },
        )

        Spacer(Modifier.height(14.dp))
        GroupLabel(stringResource(R.string.ai_subtitles_translation))
        ServiceSettingsRow(
            icon = OwnTVIcon.LANGUAGE,
            title = stringResource(R.string.ai_subtitles_target_language),
            desc = stringResource(R.string.ai_subtitles_target_language_description),
            chip = targetName, chevron = true,
            onClick = { showTargetPicker = true },
        )
        ServiceSettingsRow(
            icon = OwnTVIcon.GEAR,
            title = stringResource(R.string.ai_subtitles_api),
            desc = stringResource(R.string.ai_subtitles_api_description),
            chip = if (config.apiKey.isNotBlank()) stringResource(R.string.common_on) else stringResource(R.string.settings_shared),
            primaryChip = config.apiKey.isNotBlank(),
            chevron = true,
            modifier = Modifier.focusRequester(apiRowFocus),
            onClick = { showApi = true },
        )

        Spacer(Modifier.height(14.dp))
        GroupLabel(stringResource(R.string.ai_subtitles_display))
        ServiceSettingsRow(
            icon = OwnTVIcon.SUBTITLE,
            title = stringResource(R.string.ai_subtitles_font_size),
            desc = stringResource(R.string.ai_subtitles_font_size_description),
            chip = stringResource(R.string.ai_subtitles_font_size_percent, (config.scale * 100).roundToInt()), chevron = true,
            onClick = { showDisplay = true },
        )
        ServiceSettingsRow(
            icon = OwnTVIcon.SUBTITLE,
            title = stringResource(R.string.ai_subtitles_line_height),
            desc = stringResource(R.string.ai_subtitles_line_height_description),
            chip = stringResource(R.string.common_percent, (config.lineHeight * 100).roundToInt()), chevron = true,
            onClick = { showLineSpacing = true },
        )
        ServiceSettingsRow(
            icon = OwnTVIcon.SUBTITLE,
            title = stringResource(R.string.ai_subtitles_position),
            desc = stringResource(R.string.ai_subtitles_position_description),
            chip = subtitlePositionName(SubtitleStyle.Position.fromKey(config.position)), chevron = true,
            onClick = { showPosition = true },
        )

        Spacer(Modifier.height(14.dp))
        GroupLabel(stringResource(R.string.ai_subtitles_streaming_models))
        // English ships inside the APK as assets, so it is always ready — nothing to download/delete.
        val englishName = languageName(StreamingModelCatalog.BUILT_IN_ENGLISH_LANGUAGE, languages)
            ?: StreamingModelCatalog.BUILT_IN_ENGLISH_LANGUAGE
        ServiceSettingsRow(
            icon = OwnTVIcon.SPARKLE,
            title = englishName,
            desc = stringResource(R.string.ai_subtitles_model_builtin_description),
            chip = stringResource(R.string.ai_subtitles_model_builtin),
            primaryChip = true,
            onClick = {},
        )
        StreamingModelCatalog.models.forEach { model ->
            val name = languageName(model.language, languages) ?: model.language
            val downloaded = model.language in downloadedModels
            val progress = downloadProgress?.takeIf { it.first == model.language }?.second
            val extractingNow = modelExtracting?.first == model.language
            // Resolved in composition (not via context.getString in the click) so the string tracks
            // Configuration changes, and readable from the click lambda below.
            val downloadStarted = stringResource(R.string.ai_subtitles_model_download_started, name)
            val chip = when {
                progress != null && !extractingNow ->
                    stringResource(R.string.ai_subtitles_model_downloading, (progress * 100).toInt())
                extractingNow ->
                    stringResource(R.string.ai_subtitles_model_extracting, ((modelExtracting?.second ?: 0L) / 1_000_000L).toInt())
                downloaded -> stringResource(R.string.ai_subtitles_model_downloaded)
                else -> stringResource(R.string.ai_subtitles_model_size_mb, (model.sizeBytes / 1_000_000).toInt())
            }
            ServiceSettingsRow(
                icon = OwnTVIcon.DOWNLOADS,
                title = name,
                desc = stringResource(R.string.ai_subtitles_model_description),
                chip = chip,
                primaryChip = downloaded,
                chevron = progress == null,
                onClick = {
                    when {
                        downloaded -> modelToDelete = model
                        progress != null -> modelBusy = model.language
                        else -> {
                    // Give instant feedback before the (possibly slow) network connect, so a
                    // tap never looks idle even while the connection is still hanging.
                    android.widget.Toast.makeText(
                        context,
                        downloadStarted,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    modelBusy = model.language
                    scope.launch {
                        val ok = modelStore.ensureDownloaded(model)
                        modelBusy = null
                        if (!ok) modelFailed = model.language
                    }
                }
                    }
                },
            )
        }

        // The Whisper tiny multilingual fallback recognizer ships inside the APK as assets
        // (no download, no delete) — only the on-demand streaming models appear in this list.

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.ai_subtitles_status, status),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    if (showLiveDelay) {
        PickerDialog(
            title = stringResource(R.string.ai_subtitles_live_delay),
            options = AiSubtitleDefaults.LIVE_DELAY_CHOICES.map { seconds ->
                seconds.toString() to if (seconds == AiSubtitleDefaults.LIVE_DELAY_OFF) {
                    stringResource(R.string.ai_subtitles_live_delay_off)
                } else {
                    stringResource(R.string.ai_subtitles_live_delay_seconds, seconds)
                }
            },
            selected = config.liveDelaySeconds.toString(),
            searchable = false,
            onSelect = {
                scope.launch { configStore.setLiveDelaySeconds(it.toIntOrNull() ?: AiSubtitleDefaults.LIVE_DELAY_DEFAULT) }
                showLiveDelay = false
            },
            onDismiss = { showLiveDelay = false },
        )
    }
    if (showSourcePicker) {
        PickerDialog(
            title = stringResource(R.string.ai_subtitles_source_language),
            options = listOf("" to stringResource(R.string.ai_subtitles_auto)) + languages,
            selected = config.sourceLanguage,
            searchable = languages.size > 8,
            onSelect = {
                scope.launch { configStore.setSourceLanguage(it) }
                showSourcePicker = false
            },
            onDismiss = { showSourcePicker = false },
        )
    }
    if (showTargetPicker) {
        PickerDialog(
            title = stringResource(R.string.ai_subtitles_target_language),
            options = listOf("" to stringResource(R.string.ai_subtitles_original)) + languages,
            selected = config.targetLanguage,
            searchable = languages.size > 8,
            onSelect = {
                scope.launch { configStore.setTargetLanguage(it) }
                showTargetPicker = false
            },
            onDismiss = { showTargetPicker = false },
        )
    }

    if (showApi) {
        AiSubtitleApiPopup(
            baseUrl = config.apiBaseUrl,
            apiKey = config.apiKey,
            model = config.model,
            targetLanguage = config.targetLanguage,
            onBaseUrlChange = { scope.launch { configStore.setApiBaseUrl(it) } },
            onApiKeyChange = { scope.launch { configStore.setApiKey(it) } },
            onModelChange = { scope.launch { configStore.setModel(it) } },
            onReset = {
                scope.launch {
                    configStore.setApiBaseUrl(AiSubtitleDefaults.DEFAULT_BASE_URL)
                    configStore.setApiKey("")
                    configStore.setModel(AiSubtitleDefaults.DEFAULT_MODEL)
                }
                showApi = false
            },
            onDismiss = { showApi = false },
        )
    }
    if (showDisplay) {
        StepperDialog(
            title = stringResource(R.string.ai_subtitles_font_size),
            value = (config.scale * 100).roundToInt(),
            step = 10, min = 50, max = 200,
            format = { it -> stringResource(R.string.ai_subtitles_font_size_percent, it) },
            onSet = { scope.launch { configStore.setScale(it / 100f) } },
            onReset = { scope.launch { configStore.setScale(AiSubtitleDefaults.SCALE_DEFAULT) } },
            onDismiss = { showDisplay = false },
        )
    }
    if (showLineSpacing) {
        StepperDialog(
            title = stringResource(R.string.ai_subtitles_line_height),
            value = (config.lineHeight * 100).roundToInt(),
            step = AiSubtitleDefaults.LOOK_STEP_PERCENT,
            min = AiSubtitleDefaults.LINE_HEIGHT_MIN_PERCENT,
            max = AiSubtitleDefaults.LINE_HEIGHT_MAX_PERCENT,
            format = { it -> stringResource(R.string.common_percent, it) },
            onSet = { scope.launch { configStore.setLineHeight(it / 100f) } },
            onReset = { scope.launch { configStore.setLineHeight(AiSubtitleDefaults.LINE_HEIGHT_DEFAULT) } },
            onDismiss = { showLineSpacing = false },
        )
    }
    if (showPosition) {
        SubtitlePositionDialog(
            position = SubtitleStyle.Position.fromKey(config.position),
            onSelect = { pos ->
                scope.launch { configStore.setPosition(pos.key) }
                showPosition = false
            },
            onDismiss = { showPosition = false },
        )
    }
    modelToDelete?.let { model ->
        ConfirmDialog(
            title = languageName(model.language, languages) ?: model.language,
            message = stringResource(R.string.ai_subtitles_model_delete_prompt),
            onConfirm = {
                scope.launch { modelStore.delete(model) }
                modelToDelete = null
            },
            onDismiss = { modelToDelete = null },
        )
    }
    modelBusy?.let { lang ->
        val name = languageName(lang, languages) ?: lang
        val pct = ((downloadProgress?.takeIf { it.first == lang }?.second ?: 0f) * 100).toInt()
        AiSubtitleInfoDialog(
            body = stringResource(R.string.ai_subtitles_model_busy_message, name, pct),
            title = name,
            onDismiss = { modelBusy = null },
        )
    }
    modelFailed?.let { lang ->
        val name = languageName(lang, languages) ?: lang
        AiSubtitleInfoDialog(
            body = stringResource(R.string.ai_subtitles_model_failed_message, name),
            title = stringResource(R.string.ai_subtitles_model_failed_title),
            onDismiss = { modelFailed = null },
        )
    }
    LaunchedEffect(showApi) {
        if (showApi) {
            apiWasOpen.value = true
        } else if (apiWasOpen.value) {
            apiWasOpen.value = false
            kotlinx.coroutines.delay(80)
            runCatching { apiRowFocus.requestFocus() }
        }
    }
}

/**
 * Edits the OpenAI-compatible gateway. Saving writes each field through the config store; Cancelling
 * (Back) discards uncommitted edits — each keystroke persists, so the Save/Reset are mostly conveniences.
 */

/**
 * Minimal single-button notice used to surface model download state (in-progress / failed) so a tap
 * on a model row always gives deterministic feedback even when a download is slow or fails instantly.
 * Mirrors [ConfirmDialog]'s popup chrome without a destructive confirm affordance.
 */
@Composable
private fun AiSubtitleInfoDialog(
    body: String,
    onDismiss: () -> Unit,
    title: String,
) {
    val colors = OwnTVTheme.colors
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(80)
            runCatching { focus.requestFocus() }
        }
        BackHandler { onDismiss() }
        Box(
            Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.dialogPanel(width = 460.dp, padding = 28.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Spacer(Modifier.height(10.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.weight(1f))
                    OwnTVButton(stringResource(R.string.common_ok), onDismiss, modifier = Modifier.focusRequester(focus))
                }
            }
        }
    }
}

@Composable
private fun AiSubtitleApiPopup(
    baseUrl: String,
    apiKey: String,
    model: String,
    targetLanguage: String,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    // null = not tested yet; Boolean is whether the last probe succeeded (drives the result colour).
    var testing by remember { mutableStateOf(false) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    var testText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onDismiss() }
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss, fontScale = .50f) {
        Box(
            Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.dialogPanel(width = 560.dp, padding = 20.dp)) {
                Text(stringResource(R.string.ai_subtitles_api), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.ai_subtitles_api_description), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OwnTVTextField(
                    value = baseUrl, onValueChange = onBaseUrlChange,
                    label = stringResource(R.string.ai_subtitles_api_base_url), modifier = Modifier.fillMaxWidth(), focusRequester = firstFocus,
                )
                Spacer(Modifier.height(8.dp))
                OwnTVTextField(
                    value = apiKey, onValueChange = onApiKeyChange,
                    label = stringResource(R.string.ai_subtitles_api_key), isPassword = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OwnTVTextField(
                    value = model, onValueChange = onModelChange,
                    label = stringResource(R.string.ai_subtitles_model), modifier = Modifier.fillMaxWidth(),
                )
                // One-tap connectivity check against the current fields. Success is shown with the
                // model's own translated reply so the user sees the actual text that will be used;
                // failure maps the common causes to a fixable hint instead of a raw HTTP line.
                testText?.let { text ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (testOk) {
                            true -> Color(0xFF81C784)
                            false -> Color(0xFFEF9A9A)
                            else -> colors.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OwnTVButton(
                        label = if (testing) stringResource(R.string.ai_subtitles_api_testing)
                        else stringResource(R.string.ai_subtitles_api_test),
                        enabled = !testing,
                        onClick = {
                            testing = true
                            testOk = null
                            testText = null
                            scope.launch {
                                val translator = OpenAiCompatibleTranslator(OpenAiCompatibleTranslator.httpClient())
                                val result: Result<String> = runCatching {
                                    translator.testConnection(baseUrl, apiKey, model, targetLanguage)
                                }
                                testing = false
                                result.onSuccess { reply ->
                                    testOk = true
                                    testText = context.getString(R.string.ai_subtitles_api_test_success, reply)
                                }.onFailure { e ->
                                    testOk = false
                                    // The translator's IOExceptions carry the HTTP status in the message
                                    // ("translation 401: …"); the network failures are their own subtypes.
                                    testText = when {
                                        e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
                                            context.getString(R.string.ai_subtitles_api_test_fail_network)
                                        e is java.io.IOException && (e.message?.contains(" 401") == true || e.message?.contains(" 403") == true) ->
                                            context.getString(R.string.ai_subtitles_api_test_fail_auth)
                                        e is java.io.IOException && e.message?.contains(" 404") == true ->
                                            context.getString(R.string.ai_subtitles_api_test_fail_not_found)
                                        e is java.io.IOException && e.message?.contains(" 400") == true ->
                                            context.getString(R.string.ai_subtitles_api_test_fail_bad_request)
                                        else ->
                                            context.getString(R.string.ai_subtitles_api_test_fail, e.message ?: e.javaClass.simpleName)
                                    }
                                }
                            }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    OwnTVButton(stringResource(R.string.ai_subtitles_api_reset), onReset, style = OwnTVButtonStyle.SECONDARY)
                    OwnTVButton(stringResource(R.string.common_done), onDismiss)
                }
            }
        }
    }
}