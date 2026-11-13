package tv.own.owntv.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.core.subtitles.OpenSubtitlesAccountManager
import tv.own.owntv.core.subtitles.OpenSubtitlesAuthStore
import tv.own.owntv.core.subtitles.OpenSubtitlesClient
import tv.own.owntv.core.settings.SettingsRepository

/** Backs the OpenSubtitles account screen: active profile's session + sign-in/out. */
class OpenSubtitlesViewModel(
    private val settings: SettingsRepository,
    private val accounts: OpenSubtitlesAccountManager,
) : ViewModel() {

    sealed interface UiState {
        data object SignedOut : UiState
        data object Busy : UiState
        data class SignedIn(val session: OpenSubtitlesAuthStore.Session) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.SignedOut)
    val state: StateFlow<UiState> = _state.asStateFlow()

    enum class ErrorKind { EMPTY_CREDENTIALS, INVALID_CREDENTIALS, SERVER_ERROR, NETWORK, REFRESH_NETWORK }

    /**
     * One-shot error for the error dialogs; wording is resolved by the Compose boundary.
     * [httpCode] is set only for [ErrorKind.SERVER_ERROR] — a reachable server that refused the
     * request (403 from the proxy, 429 rate limit, 5xx). Telling that apart from a real transport
     * failure matters: both used to say "check your internet connection", which sent users with a
     * perfectly good network chasing the wrong thing.
     */
    data class Error(val kind: ErrorKind, val httpCode: Int = 0)

    private val _error = MutableStateFlow<Error?>(null)
    val error: StateFlow<Error?> = _error.asStateFlow()

    init {
        // Show the stored session immediately, then refresh the allowance from the provider.
        viewModelScope.launch {
            val pid = activeProfile() ?: return@launch
            val stored = accounts.session(pid)
            _state.value = stored?.let { UiState.SignedIn(it) } ?: UiState.SignedOut
            if (stored != null) {
                runCatching { accounts.refreshUserInfo(pid) }
                    .onSuccess { updated ->
                        _state.value = updated?.let { UiState.SignedIn(it) } ?: UiState.SignedOut
                    }
                    // Network failure: keep showing the stored snapshot — but say why in the log,
                    // since a silent failure here just looks like a missing Downloads row.
                    .onFailure { android.util.Log.w("OpenSubtitles", "user-info refresh failed: ${it.message}") }
            }
        }
    }

    fun signIn(username: String, password: String, staySignedIn: Boolean) {
        if (username.isBlank() || password.isEmpty()) {
            _error.value = Error(ErrorKind.EMPTY_CREDENTIALS)
            return
        }
        viewModelScope.launch {
            val pid = activeProfile() ?: return@launch
            _state.value = UiState.Busy
            runCatching { accounts.signIn(pid, username, password, staySignedIn) }
                .onSuccess { _state.value = UiState.SignedIn(it) }
                .onFailure { e ->
                    _state.value = UiState.SignedOut
                    // Cause only — never the credentials that were submitted.
                    android.util.Log.w("OpenSubtitles", "sign-in failed: ${e.javaClass.simpleName}: ${e.message}")
                    val api = e as? OpenSubtitlesClient.ApiException
                    _error.value = when {
                        api != null && api.code == 401 -> Error(ErrorKind.INVALID_CREDENTIALS)
                        // code 0 = a 2xx we couldn't make sense of; that's not a server refusal.
                        api != null && api.code > 0 -> Error(ErrorKind.SERVER_ERROR, api.code)
                        else -> Error(ErrorKind.NETWORK)
                    }
                }
        }
    }

    /** Manual "Refresh" on the account screen — re-pulls the allowance from /infos/user. */
    fun refresh() {
        viewModelScope.launch {
            val pid = activeProfile() ?: return@launch
            if (state.value !is UiState.SignedIn) return@launch
            runCatching { accounts.refreshUserInfo(pid) }
                .onSuccess { updated ->
                    _state.value = updated?.let { UiState.SignedIn(it) } ?: UiState.SignedOut
                }
                .onFailure {
                    android.util.Log.w("OpenSubtitles", "manual refresh failed: ${it.javaClass.simpleName}: ${it.message}")
                    _error.value = Error(ErrorKind.REFRESH_NETWORK)
                }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val pid = activeProfile() ?: return@launch
            _state.value = UiState.Busy
            runCatching { accounts.signOut(pid) } // local erase happens even if the server call fails
            _state.value = UiState.SignedOut
        }
    }

    fun dismissError() {
        _error.value = null
    }

    private suspend fun activeProfile(): Long? = settings.activeProfileId.first().takeIf { it >= 0 }
}
