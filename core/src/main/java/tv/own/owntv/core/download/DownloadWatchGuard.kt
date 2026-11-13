package tv.own.owntv.core.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.live.WatchSession
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SettingsRepository.DownloadWatchChoice

/**
 * Watching and downloading on a playlist that allows one stream at a time: they cannot both have it.
 *
 * The moment playback opens on such a playlist ([WatchSession]) while one of its downloads runs, the
 * download is paused, so the picture gets the stream at once. Then, unless the user has already
 * answered for good, [prompt] asks: keep watching (the download stays paused, and the user resumes
 * it from Downloads) or keep downloading (the app closes the player, and the download carries on).
 *
 * Lives in core because both apps play from the same playlists and keep the same downloads; each app
 * only draws the question.
 */
class DownloadWatchGuard(
    private val watchSession: WatchSession,
    private val downloadDao: DownloadDao,
    private val sourceDao: SourceDao,
    private val engine: DownloadEngine,
    private val manager: DownloadManager,
    private val settings: SettingsRepository,
) {
    /** The question to show: which playlist, and the downloads paused for it. */
    data class Prompt(val sourceId: Long, val playlistName: String, internal val downloadIds: List<Long>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    init {
        scope.launch {
            var before = emptySet<Long>()
            watchSession.watching.collect { now ->
                (now - before).forEach { onWatchStarted(it) }
                before = now
            }
        }
    }

    /** The television's preview pane would need the stream a download holds on [sourceId]. */
    fun holdsOnlyStream(sourceId: Long): Boolean = sourceId in engine.singleStreamBusy.value

    /**
     * The user's answer. [watch] keeps the downloads paused; otherwise they resume and the app must
     * close the player. [remember] is "Don't ask again".
     */
    fun answer(watch: Boolean, remember: Boolean) {
        val p = _prompt.value ?: return
        _prompt.value = null
        scope.launch {
            if (remember) settings.setDownloadWatchChoice(if (watch) DownloadWatchChoice.PAUSE else DownloadWatchChoice.KEEP)
            if (!watch) p.downloadIds.forEach { manager.resumeNow(it) }
        }
    }

    private suspend fun onWatchStarted(sourceId: Long) {
        if (sourceId !in engine.singleStreamBusy.value) return
        val source = sourceDao.getById(sourceId) ?: return
        if (source.maxConnections != 1) return
        val choice = settings.downloadWatchChoice.first()
        if (choice == DownloadWatchChoice.KEEP) return
        val running = downloadDao.byStatus(DownloadStatus.RUNNING).filter { engine.sourceIdOf(it) == sourceId }
        if (running.isEmpty()) return
        running.forEach { manager.pauseForWatching(it.id) }
        if (choice == DownloadWatchChoice.ASK) _prompt.value = Prompt(sourceId, source.name, running.map { it.id })
    }
}
