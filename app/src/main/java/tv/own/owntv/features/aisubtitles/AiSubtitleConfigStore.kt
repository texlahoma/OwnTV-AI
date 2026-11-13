package tv.own.owntv.features.aisubtitles

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.settings.SubtitleStyle

/**
 * Configuration for the AI (speech-recognition → cloud-translate) subtitle feature.
 *
 * Stored in the app's own Preferences DataStore rather than the shared core SettingsRepository,
 * because this feature is app-only (the TV app) and the shared repo lives in the core binary. Kept
 * deliberately independent so toggling it cannot disturb core settings or backup/restore formats.
 *
 * @param enabled master switch; when on, playback audio is fed to local Whisper ASR.
 * @param sourceLanguage language of the audio (BCP-47), fed to Whisper so it decodes in the right
 *   language; leave empty for auto-detect/whisper to guess.
 * @param targetLanguage language to translate the recognised text into (BCP-47, e.g. "zh", "en").
 *   Empty means no translation — ASR text shown as-is.
 * @param apiBaseUrl cloud gateway base URL (no trailing slash), e.g. https://api.openai.com.
 * @param apiKey cloud gateway bearer token.
 * @param model cloud model id, e.g. gpt-4o-mini (chat/completions) or an OpenAI-compatible gateway.
 * @param scale relative font size of the AI subtitle lines (1.0 = default).
 * @param lineHeight multiplier on the built-in line height (1.0 = default), so the two lines of a
 *   bilingual block can be pulled apart or tightened without touching the glyph size.
 * @param position screen anchor for the AI subtitle block, as a [SubtitleStyle.Position] key.
 * @param positionOffset fine-grained nudge (dp) applied on top of [position], letting the player
 *   move the block with the on-screen arrows without touching the chosen anchor.
 * @param syncOffsetMs shift of the caption timeline against the audio, ms; positive shows the
 *   subtitles LATER, negative EARLIER. It exists because the one thing no Android API can report is
 *   how long the sound takes after the platform's own mixer — HDMI/ARC, a soundbar, a TV's audio DSP
 *   all add their own, device-specific delay. Every player exposes this trim for that reason; on a
 *   healthy sink the right value is 0.
 * @param liveDelaySeconds how long live audio is held back so recognition and translation can run
 *   ahead of the speaker (5–10 s, 0 = off). This is what makes the two caption lines able to reach
 *   the screen *with* the audio instead of seconds after it; see `LiveAudioDelay`. A stream with no
 *   past data to draw on (raw live TV) pays for it with a longer tune: playback waits that many
 *   seconds before it starts, which is exactly what places it behind the live edge.
 */
data class AiSubtitleConfig(
    val enabled: Boolean = false,
    val sourceLanguage: String = "en",
    val targetLanguage: String = "zh",
    val apiBaseUrl: String = "https://api.openai.com",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val scale: Float = AI_SUBTITLE_SCALE_DEFAULT,
    val lineHeight: Float = AI_SUBTITLE_LINE_HEIGHT_DEFAULT,
    val position: String = AI_SUBTITLE_POSITION_DEFAULT,
    val positionOffset: Offset = Offset.Zero,
    val syncOffsetMs: Int = AI_SUBTITLE_SYNC_OFFSET_DEFAULT,
    val liveDelaySeconds: Int = AI_SUBTITLE_LIVE_DELAY_DEFAULT,
)

private val Context.aiSubtitleDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_subtitles")

/** Convenience names reused by the settings screen and the manager. */
object AiSubtitleDefaults {
    const val DEFAULT_BASE_URL = "https://api.openai.com"
    const val DEFAULT_MODEL = "gpt-4o-mini"

    /** Default display look for the AI subtitle block. */
    const val SCALE_DEFAULT = 1.0f
    const val LINE_HEIGHT_DEFAULT = 1.0f
    const val POSITION_DEFAULT = "bottom_center"

    /** Bounds/step of the adjustable caption look, shared by the player panel and Settings. */
    const val SCALE_MIN_PERCENT = 50
    const val SCALE_MAX_PERCENT = 200
    const val LINE_HEIGHT_MIN_PERCENT = 80
    const val LINE_HEIGHT_MAX_PERCENT = 200
    const val LOOK_STEP_PERCENT = 10

    /** On-screen nudge step (dp) for the player's arrow buttons. */
    const val POSITION_NUDGE_DP = 10f

    /**
     * Caption sync trim: range/step of the manual shift, ms. 50 ms is far below the point where a
     * viewer can see a change and small enough that a dozen presses is still quick; +/- 1 s covers
     * every real output chain (HDMI/ARC/soundbar/DSP).
     */
    const val SYNC_OFFSET_DEFAULT = 0
    const val SYNC_OFFSET_MIN = -1000
    const val SYNC_OFFSET_MAX = 1000
    const val SYNC_OFFSET_STEP = 50

    /**
     * Live hold-back so captions can be ready when their audio plays, in seconds. 8 s is the middle
     * of the useful 5–10 s band: long enough for a decode plus one cloud round trip, short enough
     * that zapping still feels live. 0 = off.
     */
    const val LIVE_DELAY_DEFAULT = 12
    const val LIVE_DELAY_MIN = 5
    /**
     * Longest hold-back the picker offers, s. Whole-sentence captions need the budget to cover the
     * sentence's own length plus one decode and one translation round trip; a commentary sentence can
     * run 10-12 s, so 20 s is the ceiling that keeps a long one complete AND on time. The cost is a
     * longer timeshift, which is the trade the viewer chose by asking for movie-style lines.
     */
    const val LIVE_DELAY_MAX = 20
    const val LIVE_DELAY_OFF = 0

    /** Choices the stepper offers; off stays first so turning the hold-back off is one press. */
    val LIVE_DELAY_CHOICES = listOf(0, 5, 6, 7, 8, 9, 10, 12, 14, 16, 18, 20)

    /** [seconds] as a stored value: 0 (off) or the 5–10 s band. */
    fun clampLiveDelay(seconds: Int): Int =
        if (seconds <= 0) LIVE_DELAY_OFF else seconds.coerceIn(LIVE_DELAY_MIN, LIVE_DELAY_MAX)
}

private const val AI_SUBTITLE_LIVE_DELAY_DEFAULT = AiSubtitleDefaults.LIVE_DELAY_DEFAULT
private const val AI_SUBTITLE_SYNC_OFFSET_DEFAULT = AiSubtitleDefaults.SYNC_OFFSET_DEFAULT

private const val AI_SUBTITLE_SCALE_DEFAULT = AiSubtitleDefaults.SCALE_DEFAULT
private const val AI_SUBTITLE_LINE_HEIGHT_DEFAULT = AiSubtitleDefaults.LINE_HEIGHT_DEFAULT
private const val AI_SUBTITLE_POSITION_DEFAULT = AiSubtitleDefaults.POSITION_DEFAULT

class AiSubtitleConfigStore(private val context: Context) {

    private val keyEnabled = booleanPreferencesKey("enabled")
    private val keySource = stringPreferencesKey("source_language")
    private val keyTarget = stringPreferencesKey("target_language")
    private val keyBaseUrl = stringPreferencesKey("api_base_url")
    private val keyApiKey = stringPreferencesKey("api_key")
    private val keyModel = stringPreferencesKey("model")
    private val keyScale = floatPreferencesKey("scale")
    private val keyLineHeight = floatPreferencesKey("line_height")
    private val keyPosition = stringPreferencesKey("position")
    private val keyPositionOffsetX = floatPreferencesKey("position_offset_x")
    private val keyPositionOffsetY = floatPreferencesKey("position_offset_y")
    private val keySyncOffset = intPreferencesKey("sync_offset_ms")
    private val keyLiveDelay = intPreferencesKey("live_delay_seconds")
    private val keyStreamingReady = stringPreferencesKey("streaming_models_ready")

    private val enabled: Flow<Boolean> = context.aiSubtitleDataStore.data.map { it[keyEnabled] ?: AiSubtitleConfig().enabled }
    private val source: Flow<String> = context.aiSubtitleDataStore.data.map { it[keySource] ?: AiSubtitleConfig().sourceLanguage }
    private val target: Flow<String> = context.aiSubtitleDataStore.data.map { it[keyTarget] ?: AiSubtitleConfig().targetLanguage }
    private val baseUrl: Flow<String> =
        context.aiSubtitleDataStore.data.map { it[keyBaseUrl] ?: AiSubtitleDefaults.DEFAULT_BASE_URL }
    private val apiKey: Flow<String> = context.aiSubtitleDataStore.data.map { it[keyApiKey] ?: "" }
    private val model: Flow<String> =
        context.aiSubtitleDataStore.data.map { it[keyModel] ?: AiSubtitleDefaults.DEFAULT_MODEL }
    private val scale: Flow<Float> =
        context.aiSubtitleDataStore.data.map { it[keyScale] ?: AiSubtitleDefaults.SCALE_DEFAULT }
    private val lineHeight: Flow<Float> =
        context.aiSubtitleDataStore.data.map { it[keyLineHeight] ?: AiSubtitleDefaults.LINE_HEIGHT_DEFAULT }
    private val position: Flow<String> =
        context.aiSubtitleDataStore.data.map { it[keyPosition] ?: AiSubtitleDefaults.POSITION_DEFAULT }
    private val positionOffset: Flow<Offset> = context.aiSubtitleDataStore.data.map {
        Offset(it[keyPositionOffsetX] ?: 0f, it[keyPositionOffsetY] ?: 0f)
    }
    private val syncOffset: Flow<Int> = context.aiSubtitleDataStore.data.map {
        (it[keySyncOffset] ?: AiSubtitleDefaults.SYNC_OFFSET_DEFAULT)
            .coerceIn(AiSubtitleDefaults.SYNC_OFFSET_MIN, AiSubtitleDefaults.SYNC_OFFSET_MAX)
    }
    private val liveDelay: Flow<Int> = context.aiSubtitleDataStore.data.map {
        AiSubtitleDefaults.clampLiveDelay(it[keyLiveDelay] ?: AiSubtitleDefaults.LIVE_DELAY_DEFAULT)
    }

    private val baseConfig: Flow<AiSubtitleConfig> = combine(
        combine(enabled, source, target) { e, s, t -> Triple(e, s, t) },
        combine(baseUrl, apiKey, model) { b, k, m -> Triple(b, k, m) },
        combine(scale, lineHeight, position) { s, l, p -> Triple(s, l, p) },
        positionOffset,
    ) { first, second, third, offset ->
        AiSubtitleConfig(
            enabled = first.first,
            sourceLanguage = first.second,
            targetLanguage = first.third,
            apiBaseUrl = second.first,
            apiKey = second.second,
            model = second.third,
            scale = third.first,
            lineHeight = third.second,
            position = third.third,
            positionOffset = offset,
        )
    }

    val config: Flow<AiSubtitleConfig> = combine(baseConfig, liveDelay, syncOffset) { cfg, seconds, sync ->
        cfg.copy(liveDelaySeconds = seconds, syncOffsetMs = sync)
    }

    suspend fun setEnabled(value: Boolean) = context.aiSubtitleDataStore.edit { it[keyEnabled] = value }
    suspend fun setSourceLanguage(value: String) = context.aiSubtitleDataStore.edit { it[keySource] = value }
    suspend fun setTargetLanguage(value: String) = context.aiSubtitleDataStore.edit { it[keyTarget] = value }
    suspend fun setApiBaseUrl(value: String) = context.aiSubtitleDataStore.edit { it[keyBaseUrl] = value }
    suspend fun setApiKey(value: String) = context.aiSubtitleDataStore.edit { it[keyApiKey] = value }
    suspend fun setModel(value: String) = context.aiSubtitleDataStore.edit { it[keyModel] = value }
    suspend fun setScale(value: Float) = context.aiSubtitleDataStore.edit { it[keyScale] = value }
    suspend fun setLineHeight(value: Float) = context.aiSubtitleDataStore.edit { it[keyLineHeight] = value }
    suspend fun setPosition(value: String) = context.aiSubtitleDataStore.edit { it[keyPosition] = value }
    suspend fun setPositionOffset(offset: Offset) = context.aiSubtitleDataStore.edit {
        it[keyPositionOffsetX] = offset.x
        it[keyPositionOffsetY] = offset.y
    }
    suspend fun setSyncOffsetMs(value: Int) = context.aiSubtitleDataStore.edit {
        it[keySyncOffset] = value.coerceIn(AiSubtitleDefaults.SYNC_OFFSET_MIN, AiSubtitleDefaults.SYNC_OFFSET_MAX)
    }
    suspend fun setLiveDelaySeconds(value: Int) = context.aiSubtitleDataStore.edit {
        it[keyLiveDelay] = AiSubtitleDefaults.clampLiveDelay(value)
    }

    // --- Model-readiness snapshot ("last saved correct config") ---
    //
    // Once a streaming model has been downloaded and validated OK, it is remembered here so a later
    // launch can trust it and skip the per-launch model-state detection (disk scan + possible
    // multi-minute download attempt). The manager never re-detects a model the snapshot says is
    // ready; it only falls back to a real download when the recognizer actually fails to build
    // (files removed behind our back), which clears the stale snapshot. The bundled Whisper
    // fallback is an APK asset and always ready, so it needs no snapshot.

    /** Streaming languages whose model was validated OK. */
    val streamingModelsReady: Flow<Set<String>> =
        context.aiSubtitleDataStore.data.map { decodeLanguages(it[keyStreamingReady]) }

    suspend fun setStreamingModelsReady(languages: Set<String>) =
        context.aiSubtitleDataStore.edit {
            if (languages.isEmpty()) {
                it.remove(keyStreamingReady)
            } else {
                it[keyStreamingReady] = languages.sorted().joinToString(",")
            }
        }

    /** One-shot read of the whole readiness snapshot, as a fast-path gate for the pipeline start. */
    suspend fun readinessSnapshot(): Set<String> = try {
        decodeLanguages(context.aiSubtitleDataStore.data.first()[keyStreamingReady])
    } catch (e: java.io.IOException) {
        // DataStore not readable yet (first boot, interrupted write): behave as "nothing ready" and
        // let the normal detection/download path take over.
        emptySet()
    }

    private fun decodeLanguages(encoded: String?): Set<String> =
        encoded?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
}