package tv.own.owntv.features.aisubtitles.translate

import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.features.aisubtitles.AiSubtitleConfig
import tv.own.owntv.features.aisubtitles.SubtitleGlossary
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * Ad-hoc Google Translate **free** web endpoint (`translate.googleapis.com`, `client=gtx`) — no
 * API key, no quota account, nothing to configure. Used as the last-resort fallback so a subtitle
 * line still gets translated even when no OpenAI-compatible gateway is configured or reachable.
 *
 * The response shape of `translate_a/single` is a nested JSONArray whose first element holds one
 * entry per translated sentence: `[0][n][0]` is the translated text of sentence *n*. We splice all
 * sentences back into a single subtitle line.
 */
class GoogleFreeTranslator(
    private val client: OkHttpClient,
) : SpeechTranslator {

    override suspend fun translate(text: String, config: AiSubtitleConfig): String {
        // No target language configured: there is nothing to translate into — keep the source.
        val target = config.targetLanguage.ifBlank { return text }
        // Pin the source language when the pipeline knows it. A live unit is one unfinished clause
        // (sometimes only two or three words), and on a fragment that short Google's auto-detect
        // regularly guesses a neighbouring language — the caption then comes back in the wrong
        // language, which the viewer reads as a bad translation rather than as a detection miss.
        val source = config.sourceLanguage.ifBlank { "auto" }
        val url = "https://translate.googleapis.com/translate_a/single?" +
            "client=gtx&sl=$source&tl=$target&dt=t&q=${URLEncoder.encode(text, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()
        return withContext(Dispatchers.IO) {
            // Cancellation must kill the socket: this endpoint is the fallback the caption lands on
            // when the gateway is down, and a round trip left running to its timeout would hold the
            // Chinese line even after a newer one has superseded it.
            val call = client.newCall(request)
            val cancellation = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) call.cancel()
            }
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw TranslationHttpException(resp.code, "google free translate ${resp.code}")
                    parse(resp) ?: throw IOException("no text in google free translate response")
                }
            } finally {
                cancellation?.dispose()
            }
        }
    }

    /**
     * Translate the WHOLE source window and answer with a replacement line, not a fragment.
     *
     * This endpoint takes no prompt, so it cannot be told what is already on screen: there is no
     * `PREVIOUS` for it to continue and no glossary for it to respect. Translating only the new
     * fragment — what a prompt-capable gateway is asked for — therefore produced a standalone machine
     * rendering glued next to the gateway's wording, the most visible form of the "the Chinese reads
     * like machine output" report. Handing it [context] and [text] together instead yields one
     * coherent translation of the sentence so far, at the cost of re-rendering the whole line on each
     * round trip (marked [TranslationResult.wholeLine] so the accumulator replaces rather than
     * appends). Google's output is deterministic, so the shared prefix normally survives the
     * re-render unchanged.
     *
     * No [onDelta] is reported: the answer is the whole line, and publishing it as a fragment delta
     * would momentarily show it *appended* to the line it replaces.
     */
    override suspend fun translateStreaming(
        text: String,
        context: String,
        config: AiSubtitleConfig,
        translated: String,
        // This endpoint takes no prompt, so the scene cue and the recent-line pairs have nowhere to
        // go: the source-side rewrite below is the only lever it has. Accepted and ignored so the
        // fallback keeps the same shape as the gateway path.
        scene: String,
        recent: String,
        // Same reason as [scene] and [recent]: no prompt, so the scoped squads' Chinese renderings
        // have nowhere to go. Accepted and ignored so the fallback keeps the gateway's shape.
        players: String,
        // Same again: a look-ahead tail is prompt material, and this endpoint takes no prompt.
        lookahead: String,
        onDelta: (String) -> Unit,
    ): TranslationResult {
        val window = SubtitleGlossary.window(context, text)
        // Same source-side rewrite the gateway path gets ([SubtitleGlossary.disambiguate]); there is no
        // prompt here to pin a target rendering, but "Trump" still reaches the endpoint as "Judd
        // Trump", which is what stops it coming back as the politician.
        return TranslationResult(
            translate(SubtitleGlossary.disambiguate(window, window), config),
            wholeLine = true,
        )
    }

    private fun parse(resp: okhttp3.Response): String? {
        val raw = resp.body?.string() ?: return null
        return kotlin.runCatching {
            val sentences = org.json.JSONArray(raw).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val sentence = sentences.getJSONArray(i)
                val part = sentence.optString(0)
                if (part.isBlank()) continue
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(part)
            }
            sb.toString().trim().ifEmpty { null }
        }.getOrNull()
    }

    companion object {
        /**
         * The text handed to the endpoint: the already-translated source words plus the new fragment,
         * i.e. the whole stable window the caption is showing in English. This endpoint takes no
         * prompt, so folding the context into the text is the only way to get one coherent rendering of
         * the sentence instead of a standalone one of the fragment (see [translateStreaming]).
         */
        internal fun windowOf(context: String, text: String): String =
            SubtitleGlossary.window(context, text)

        /**
         * A short-socket-timeout client, same shape as [OpenAiCompatibleTranslator.httpClient].
         * [OkHttpClient.Builder.callTimeout] bounds the whole fallback round trip, so a stalled
         * endpoint cannot hold the caption past it — this is the endpoint the caption lands on when
         * the configured gateway is down, so it must fail fast rather than inherit the gateway's more
         * generous budget. The manager shares one instance between both backends, so these values are
         * the ones a standalone construction gets.
         */
        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}