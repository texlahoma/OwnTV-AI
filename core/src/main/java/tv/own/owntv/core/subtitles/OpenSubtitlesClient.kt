package tv.own.owntv.core.subtitles

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * OpenSubtitles REST client. All calls go through the OwnTV
 * OpenSubtitles Worker (worker/opensub/), which injects the application consumer key — the app
 * never holds an Api-Key. The Worker forwards to api.opensubtitles.com or
 * vip-api.opensubtitles.com per request via the X-OS-Host header, so the login-returned
 * `base_url` is honoured through the proxy.
 *
 * Logging rule: never log usernames, passwords, tokens, or full auth responses.
 */
class OpenSubtitlesClient(
    private val okHttpClient: OkHttpClient,
    private val clientId: tv.own.owntv.core.metadata.OwnTVClientId,
    private val settings: tv.own.owntv.core.settings.SettingsRepository,
) {

    /** Non-2xx from OpenSubtitles. 401 drives the one-shot silent re-login upstream. */
    class ApiException(val code: Int, message: String) : IOException(message)

    /**
     * Same pool/dispatcher as the shared client, but with connection-failure recovery ON.
     *
     * The shared client sets `retryOnConnectionFailure(false)` so SyncManager owns stream retries —
     * and that flag ALSO disables OkHttp's fall-back to the next resolved address. The Worker host
     * resolves to both IPv4 and IPv6 records, so on a network that advertises IPv6 but cannot route
     * it, the first connect fails and sign-in dies instantly while the rest of the TV looks online.
     * Subtitle calls are small one-shot requests; retrying the other address costs nothing here.
     */
    private val http: OkHttpClient = okHttpClient.newBuilder().retryOnConnectionFailure(true).build()

    /** Login result: the bearer token plus the account snapshot OpenSubtitles returns with it. */
    data class LoginResult(
        val token: String,
        /** Upstream API host from the login `base_url` (e.g. vip-api.opensubtitles.com). */
        val apiHost: String,
        val user: UserInfo,
    )

    /** Account status — provider-reported values only, never assumed totals. */
    data class UserInfo(
        val username: String?,
        val level: String?,
        val vip: Boolean,
        /** Downloads remaining today, or null if the provider didn't report it. */
        val remainingDownloads: Int?,
        /** Provider-reported daily allowance, or null (remaining-only display then). */
        val allowedDownloads: Int?,
        /** Provider-reported reset time text (e.g. "23 hours and 57 minutes"), or null. */
        val resetTime: String?,
    )

    /** POST /login. Throws [ApiException] (401 = wrong credentials) or IOException (network). */
    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("username", username).put("password", password)
        val json = call("POST", "/login", host = DEFAULT_HOST, token = null, body = body)
        val token = json.optString("token").takeIf { it.isNotBlank() }
            ?: throw ApiException(0, "Login response had no token")
        val host = json.optString("base_url").takeIf { it.isNotBlank() } ?: DEFAULT_HOST
        LoginResult(token = token, apiHost = host, user = parseUser(json.optJSONObject("user"), username))
    }

    /** DELETE /logout — best-effort server-side session invalidation; local erase always happens anyway. */
    suspend fun logout(token: String, apiHost: String) = withContext(Dispatchers.IO) {
        runCatching { call("DELETE", "/logout", host = apiHost, token = token) }
            .onFailure { Log.w(TAG, "logout call failed (ignored): ${it.message}") }
        Unit
    }

    /** GET /infos/user — refreshes the allowance display. Throws [ApiException] 401 on expired token. */
    suspend fun userInfo(token: String, apiHost: String, username: String?): UserInfo =
        withContext(Dispatchers.IO) {
            val json = call("GET", "/infos/user", host = apiHost, token = token)
            parseUser(json.optJSONObject("data"), username)
        }

    /** GET /subtitles search (unauthenticated — Api-Key only, injected by the Worker). */
    suspend fun search(query: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val qs = query.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        call("GET", "/subtitles?$qs", host = DEFAULT_HOST, token = null)
    }

    /**
     * POST /download — mints the single-use, time-limited download link for [fileId] and consumes one
     * quota unit. Callers MUST check the cache by file_id first. Requests
     * UTF-8 normalization. Returns the raw response: `link`, `remaining`, `reset_time`.
     */
    suspend fun requestDownload(token: String, apiHost: String, fileId: Long): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("file_id", fileId).put("force_download", false)
                .put("sub_format", "srt")
            call("POST", "/download", host = apiHost, token = token, body = body)
        }

    private fun call(
        method: String,
        pathAndQuery: String,
        host: String,
        token: String?,
        body: JSONObject? = null,
    ): JSONObject {
        val customServer = kotlinx.coroutines.runBlocking { settings.currentOpenSubtitlesServerUrl() }.trim().trimEnd('/')
        val ownKey = kotlinx.coroutines.runBlocking { settings.currentOpenSubtitlesApiKey() }.trim()
        val direct = customServer.isBlank() && ownKey.isNotBlank()
        val base = when {
            customServer.isNotBlank() -> customServer
            direct -> "https://$host"
            else -> WORKER_BASE
        }
        val builder = Request.Builder()
            .url("$base/api/v1$pathAndQuery")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        if (!direct) builder.header("X-OS-Host", host)
        if (direct) builder.header("Api-Key", ownKey)
        if (token != null) builder.header("Authorization", "Bearer $token")
        // Same identity the metadata Worker requires. This Worker sits on *.workers.dev with no WAF
        // in front of it, so the shared secret it checks internally is its only protection — without
        // these headers every subtitle call would come back 403 once the Worker is deployed.
        // Blank on a build with no key (fork/fresh clone); the Worker degrades open for those.
        if (!direct && customServer.isBlank() && tv.own.owntv.core.CoreBuildInfo.edgeKey.isNotBlank()) {
            builder.header("x-owntv-key", tv.own.owntv.core.CoreBuildInfo.edgeKey)
            // The Worker validates the key per app version, so it needs the version alongside it.
            builder.header("x-owntv-version", tv.own.owntv.core.CoreBuildInfo.versionName)
            edgeClientId()?.let { builder.header("x-owntv-client", it) }
        }
        val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
        builder.method(method, requestBody)

        val response = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            // A transport failure used to leave NOTHING in logcat, so "couldn't reach OpenSubtitles"
            // was unfalsifiable from a bug report. Cause + host only: never the query (the query
            // carries the movie title and moviehash) and never the body (credentials).
            Log.w(TAG, "$method ${pathAndQuery.substringBefore('?')} via $base -> ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        response.use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) {
                // The body may echo credentials on auth endpoints — log only code + path. The QUERY is
                // dropped too: on a search it carries the movie title and the file's moviehash, which is
                // the user's viewing history written into logcat.
                Log.w(TAG, "$method ${pathAndQuery.substringBefore('?')} via $base -> HTTP ${resp.code}")
                throw ApiException(resp.code, "OpenSubtitles HTTP ${resp.code}")
            }
            return runCatching { JSONObject(text) }.getOrElse {
                throw IOException("OpenSubtitles returned malformed JSON for $pathAndQuery")
            }
        }
    }

    /** Blocking read of the per-install id; [call] already runs on the IO dispatcher. */
    private fun edgeClientId(): String? = runCatching {
        kotlinx.coroutines.runBlocking { clientId.get() }
    }.getOrNull()

    private fun parseUser(user: JSONObject?, fallbackUsername: String?): UserInfo = UserInfo(
        username = user?.optString("username")?.takeIf { it.isNotBlank() } ?: fallbackUsername,
        level = user?.optString("level")?.takeIf { it.isNotBlank() },
        vip = user?.optBoolean("vip") ?: false,
        remainingDownloads = user?.optInt("remaining_downloads", -1)?.takeIf { it >= 0 },
        allowedDownloads = user?.optInt("allowed_downloads", -1)?.takeIf { it > 0 },
        resetTime = user?.optString("reset_time")?.takeIf { it.isNotBlank() }
            ?: user?.optString("reset_time_utc")?.takeIf { it.isNotBlank() },
    )

    companion object {
        private const val TAG = "OpenSubtitles"

        /** The maintainer's deployment of worker/opensub/ — holds the consumer key server-side. */
        private const val WORKER_BASE = "https://my-owntv-opensub.xiannero.workers.dev"

        /** Standard upstream; login's base_url may switch a VIP account to vip-api. */
        const val DEFAULT_HOST = "api.opensubtitles.com"

        /** OpenSubtitles requires an identifying UA; must match what the Worker sends upstream. */
        private const val USER_AGENT = "OwnTV"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
