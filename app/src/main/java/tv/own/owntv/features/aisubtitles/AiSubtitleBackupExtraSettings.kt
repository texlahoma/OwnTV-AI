package tv.own.owntv.features.aisubtitles

import kotlinx.coroutines.flow.first
import org.json.JSONObject
import tv.own.owntv.core.backup.BackupExtraSettings

/**
 * Carries the AI-subtitle / cloud-translation (LLM) config inside a backup's SETTINGS section.
 *
 * The translation setup — a hand-typed API base URL, API key, model id and language pair — is the
 * kind of thing that is painful to re-enter after a device wipe or a fresh install, so it rides
 * along with the rest of the settings on backup/restore. Read straight from [AiSubtitleConfigStore]
 * and written straight back through the same setters, so applying it cannot drift from the live UI.
 */
class AiSubtitleBackupExtraSettings(
    private val configStore: AiSubtitleConfigStore,
) : BackupExtraSettings {

    override suspend fun exportJson(sealed: Boolean): JSONObject? {
        val c = configStore.config.first()
        return JSONObject().apply {
            put("enabled", c.enabled)
            put("source", c.sourceLanguage)
            put("target", c.targetLanguage)
            put("baseUrl", c.apiBaseUrl)
            // Secret field — mirrors the rest of the backup: an API key only rides along when the
            // backup is protected by a passphrase (whole container sealed). A passwordless backup
            // omits it, so the key is not spilled as plaintext; the user re-enters it on restore.
            if (sealed) put("apiKey", c.apiKey)
            put("model", c.model)
            put("scale", c.scale.toString())
            put("lineHeight", c.lineHeight.toString())
            put("position", c.position)
            put("offsetX", c.positionOffset.x.toString())
            put("offsetY", c.positionOffset.y.toString())
            put("liveDelay", c.liveDelaySeconds)
        }
    }

    override suspend fun importJson(json: JSONObject?) {
        json ?: return
        val c = configStore.config.first()
        if (json.has("enabled")) configStore.setEnabled(json.optBoolean("enabled", c.enabled))
        if (json.has("source")) configStore.setSourceLanguage(json.optString("source", c.sourceLanguage))
        if (json.has("target")) configStore.setTargetLanguage(json.optString("target", c.targetLanguage))
        if (json.has("baseUrl")) configStore.setApiBaseUrl(json.optString("baseUrl", c.apiBaseUrl))
        if (json.has("apiKey")) configStore.setApiKey(json.optString("apiKey", c.apiKey))
        if (json.has("model")) configStore.setModel(json.optString("model", c.model))
        if (json.has("scale")) configStore.setScale(json.optString("scale", c.scale.toString()).toFloatOrNull() ?: c.scale)
        if (json.has("lineHeight")) {
            configStore.setLineHeight(
                json.optString("lineHeight", c.lineHeight.toString()).toFloatOrNull() ?: c.lineHeight,
            )
        }
        if (json.has("position")) configStore.setPosition(json.optString("position", c.position))
        if (json.has("offsetX") && json.has("offsetY")) {
            val x = json.optString("offsetX", c.positionOffset.x.toString()).toFloatOrNull() ?: c.positionOffset.x
            val y = json.optString("offsetY", c.positionOffset.y.toString()).toFloatOrNull() ?: c.positionOffset.y
            configStore.setPositionOffset(androidx.compose.ui.geometry.Offset(x, y))
        }
        if (json.has("liveDelay")) {
            configStore.setLiveDelaySeconds(json.optInt("liveDelay", c.liveDelaySeconds))
        }
    }
}