package tv.own.owntv.features.subtitles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.own.owntv.core.subtitles.OpenSubtitlesClient
import tv.own.owntv.core.subtitles.SubtitleController

/**
 * Backs the OpenSubtitles search overlay opened from the player's ADD SUBTITLES menu.
 * Reads the current item from [SubtitleController], runs the search, and on selection downloads
 * + attaches + remembers the subtitle. All OpenSubtitles specifics stay in the controller/repository.
 */
class SubtitleSearchViewModel(
    private val controller: SubtitleController,
) : ViewModel() {

    /** One OpenSubtitles result, distilled to what a TV user needs to choose a release. */
    data class Result(
        val fileId: Long,
        val language: String?,
        val languageName: String?,
        val releaseName: String?,
        val hearingImpaired: Boolean,
        val aiTranslated: Boolean,
        val fromTrusted: Boolean,
        val downloads: Int,
    )

    sealed interface UiState {
        /** No usable session — fresh signed-out, or the token expired and silent re-login failed. */
        data class SignedOut(val sessionExpired: Boolean = false) : UiState
        data object Loading : UiState
        data class Results(val results: List<Result>, val showingAllLanguages: Boolean) : UiState
        /** [showingAllLanguages] false means a language filter narrowed the search. */
        data class Empty(val showingAllLanguages: Boolean) : UiState
        enum class ErrorKind { LIMIT_REACHED, NETWORK }
        data class Error(val kind: ErrorKind) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Prefilled title for the "Edit search" field (the cleaned title the search itself uses). */
    val initialQuery: String get() = controller.searchTitle

    /** Non-null while a chosen subtitle is downloading (disables the list, shows progress). */
    private val _applying = MutableStateFlow<Long?>(null)
    val applying: StateFlow<Long?> = _applying.asStateFlow()

    /** One-shot "subtitle applied" event so the overlay closes. A SharedFlow (not a sticky flag) so
     *  re-opening the overlay — this ViewModel is reused across opens — never re-fires an old event. */
    private val _applied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val applied: SharedFlow<Unit> = _applied.asSharedFlow()

    data class QuotaNote(val remaining: Int, val reset: String?)

    /** Transient allowance data from the last download, or null. */
    private val _quotaNote = MutableStateFlow<QuotaNote?>(null)
    val quotaNote: StateFlow<QuotaNote?> = _quotaNote.asStateFlow()

    private var preferred: List<String> = emptyList()

    /**
     * (Re)start for the item currently playing — called each time the overlay opens (the ViewModel
     * instance is reused, so init-time work can't be relied on). Resets transient state and searches.
     */
    fun start() {
        _applying.value = null
        _quotaNote.value = null
        if (!controller.isSignedIn()) {
            _state.value = UiState.SignedOut()
            return
        }
        viewModelScope.launch {
            preferred = controller.preferredLanguages()
            runSearch(languages = preferred, editedQuery = null, showingAll = preferred.isEmpty())
        }
    }

    /** "Edit search" — free-text query overriding the metadata match. */
    fun editSearch(query: String) {
        viewModelScope.launch {
            runSearch(languages = if (showingAll()) emptyList() else preferred, editedQuery = query, showingAll = showingAll())
        }
    }

    /** "Show all languages" — drops the preferred-language filter for this search. */
    fun showAllLanguages() {
        viewModelScope.launch { runSearch(languages = emptyList(), editedQuery = null, showingAll = true) }
    }

    fun retry() {
        viewModelScope.launch { runSearch(languages = if (showingAll()) emptyList() else preferred, editedQuery = null, showingAll = showingAll()) }
    }

    fun select(result: Result) {
        if (_applying.value != null) return
        viewModelScope.launch {
            _applying.value = result.fileId
            runCatching {
                controller.apply(
                    fileId = result.fileId,
                    language = result.language,
                    languageName = result.languageName,
                    releaseName = result.releaseName,
                    hearingImpaired = result.hearingImpaired,
                    onQuota = { remaining, reset ->
                        _quotaNote.value = remaining?.let { QuotaNote(it, reset) }
                    },
                )
            }.onSuccess {
                _applying.value = null
                _applied.tryEmit(Unit)
            }.onFailure { e ->
                _applying.value = null
                _state.value = errorState(e)
            }
        }
    }

    private fun showingAll(): Boolean = (_state.value as? UiState.Results)?.showingAllLanguages ?: preferred.isEmpty()

    private suspend fun runSearch(languages: List<String>, editedQuery: String?, showingAll: Boolean) {
        _state.value = UiState.Loading
        runCatching { controller.search(languages, editedQuery) }
            .onSuccess { json ->
                val results = parse(json)
                _state.value =
                    if (results.isEmpty()) UiState.Empty(showingAll) else UiState.Results(results, showingAll)
            }
            .onFailure { e -> _state.value = errorState(e) }
    }

    private fun parse(json: JSONObject): List<Result> {
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Result>(data.length())
        for (i in 0 until data.length()) {
            val attrs = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val files = attrs.optJSONArray("files") ?: continue
            val fileId = (0 until files.length())
                .firstNotNullOfOrNull { files.optJSONObject(it)?.optLong("file_id")?.takeIf { id -> id > 0 } }
                ?: continue
            val lang = attrs.optString("language").takeIf { it.isNotBlank() }
            out.add(
                Result(
                    fileId = fileId,
                    language = lang,
                    languageName = lang?.let(::languageDisplayName),
                    releaseName = attrs.optString("release").takeIf { it.isNotBlank() }
                        ?: attrs.optJSONObject("feature_details")?.optString("movie_name").orEmpty()
                            .ifBlank { null },
                    hearingImpaired = attrs.optBoolean("hearing_impaired"),
                    aiTranslated = attrs.optBoolean("ai_translated") || attrs.optBoolean("machine_translated"),
                    fromTrusted = attrs.optBoolean("from_trusted"),
                    downloads = attrs.optInt("download_count"),
                ),
            )
        }
        return out
    }

    private fun languageDisplayName(code: String): String =
        runCatching { java.util.Locale.forLanguageTag(code).displayLanguage.ifBlank { code } }.getOrDefault(code)

    /** Error mapping. 401 here means the one-shot silent re-login already failed → sign in via Settings. */
    private fun errorState(e: Throwable): UiState = when {
        e is OpenSubtitlesClient.ApiException && e.code == 406 -> UiState.Error(UiState.ErrorKind.LIMIT_REACHED)
        e is OpenSubtitlesClient.ApiException && e.code == 401 -> UiState.SignedOut(sessionExpired = true)
        else -> UiState.Error(UiState.ErrorKind.NETWORK)
    }
}
