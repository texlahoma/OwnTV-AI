package tv.own.owntv.core.player

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.PlaybackPrefsDao
import tv.own.owntv.core.database.entity.PlaybackPrefsEntity
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Remembers the zoom/aspect mode, the volume and the A/V-sync offset the user last chose for one
 * specific item, so a film that needs 130% volume, a 4:3 channel the user prefers cropped, or a
 * channel whose audio runs 200 ms early, comes back that way next time.
 *
 * Keyed exactly like the engine pins ([VodEngineStore], [ForceMpvStore]) — [enginePinKey] when the
 * row carries a provider id, the stream URL when it doesn't — which is why a re-sync doesn't lose
 * these: the same item computes the same key again. Scoped to the active profile, so the kids
 * profile's choices never reach the main one.
 *
 * Nothing is stored until the user actually changes something in the player. A missing row, or a
 * null column in an existing one, means "follow the global default in Settings".
 */
class PlaybackPrefsStore(
    private val dao: PlaybackPrefsDao,
    private val settings: SettingsRepository,
) {
    /** The remembered values for [contentKey], or null when this item has none. */
    suspend fun prefsFor(contentKey: String): PlaybackPrefsEntity? =
        runCatching { dao.get(settings.activeProfileId.first(), contentKey) }.getOrNull()

    /** Remember a deliberate zoom choice ([tv.own.owntv.player.ZoomMode] name). */
    suspend fun rememberZoom(contentKey: String, zoomMode: String) {
        runCatching { dao.setZoom(settings.activeProfileId.first(), contentKey, zoomMode) }
    }

    /** Remember a deliberate volume change (percent, 0–150). */
    suspend fun rememberVolume(contentKey: String, volume: Int) {
        runCatching { dao.setVolume(settings.activeProfileId.first(), contentKey, volume) }
    }

    /** Remember a deliberate A/V-sync offset in ms; null forgets it and returns to the global value. */
    suspend fun rememberAudioDelay(contentKey: String, audioDelayMs: Int?) {
        runCatching { dao.setAudioDelay(settings.activeProfileId.first(), contentKey, audioDelayMs) }
    }

    /** Remember the AI-subtitle master switch for this item; null forgets it and returns to the global switch. */
    suspend fun rememberAiSubtitleEnabled(contentKey: String, enabled: Boolean?) {
        runCatching { dao.setAiSubtitleEnabled(settings.activeProfileId.first(), contentKey, enabled) }
    }

    /** Remember the AI-subtitle recognition language for this item; null forgets it and returns to the global language. */
    suspend fun rememberAiSubtitleSourceLanguage(contentKey: String, language: String?) {
        runCatching { dao.setAiSubtitleSourceLanguage(settings.activeProfileId.first(), contentKey, language) }
    }

    /** Settings escape hatch: forget every item's zoom, on every profile. Volumes are kept. */
    suspend fun clearZoom() {
        runCatching { dao.clearZoom() }
    }

    /** Settings escape hatch: forget every item's volume, on every profile. Zoom modes are kept. */
    suspend fun clearVolume() {
        runCatching { dao.clearVolume() }
    }

    /** How many items currently remember a zoom / a volume — the two Settings rows' chips. */
    fun observeZoomCount(): kotlinx.coroutines.flow.Flow<Int> = dao.observeZoomCount()

    fun observeVolumeCount(): kotlinx.coroutines.flow.Flow<Int> = dao.observeVolumeCount()

    /** Settings escape hatch: forget every item's audio delay, on every profile. */
    suspend fun clearAudioDelay() {
        runCatching { dao.clearAudioDelay() }
    }

    fun observeAudioDelayCount(): kotlinx.coroutines.flow.Flow<Int> = dao.observeAudioDelayCount()
}
