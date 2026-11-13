package tv.own.owntv.features.aisubtitles.translate

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import tv.own.owntv.features.aisubtitles.AiSubtitleConfig
import tv.own.owntv.features.aisubtitles.AiSubtitleDefaults
import tv.own.owntv.features.aisubtitles.SubtitleGlossary
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * One round trip's answer.
 *
 * [wholeLine] tells the two shapes an answer can have apart. Normally it is a *fragment*: the
 * translation of the new words only, which the accumulator appends to the line already on screen. A
 * backend that has no way to be given context — the key-free Google endpoint takes no prompt, so it
 * can be told nothing about the words already translated — answers the other way: it is handed the
 * WHOLE source window and returns one coherent translation of the sentence so far. That answer must
 * REPLACE the line; appending it would duplicate every word the window already covered.
 * See [tv.own.owntv.features.aisubtitles.LiveTranslationAccumulator.accept].
 */
data class TranslationResult(val text: String, val wholeLine: Boolean = false)

/**
 * A backend answered with a non-2xx HTTP status; [code] is that status.
 *
 * Its own type, rather than an [IOException] whose message happens to contain the code, because the
 * diagnostics line splits translation failures into "the request timed out" and "the gateway refused
 * it" — and that split decides whether the next step is a network fix or a quota/key one. Reading it
 * back out of a message string would make the meter depend on each backend's wording.
 */
class TranslationHttpException(val code: Int, message: String) : IOException(message)

/** Turns a recognised phrase into the target-language subtitle line. */
fun interface SpeechTranslator {
    suspend fun translate(text: String, config: AiSubtitleConfig): String

    /**
     * [translate] with the line reported while it is still being written: [onDelta] is called with
     * the accumulated translation every time the backend has produced more of it.
     *
     * This is what keeps the Chinese caption close behind the English one. A subtitle line is short,
     * but a whole non-streaming round trip is not: measured against a configured DeepSeek gateway the
     * answer took a couple of seconds, during which the live English line kept moving and the Chinese
     * one showed the *previous* segment — the "Chinese lags English by seconds" complaint. Reporting
     * the line as it is generated lets it appear as soon as the model starts writing instead of only
     * when it stops.
     *
     * [text] is only the fragment that has no translation yet, [context] is the source words of the
     * same sentence that are already translated, and [translated] is the target-language line those
     * words already produced (see [tv.own.owntv.features.aisubtitles.LiveTranslationAccumulator]).
     * Passing the produced line back is what keeps names, terminology and register stable across the
     * fragments of one sentence; a backend that cannot use either still gets a correct translation of
     * the fragment.
     *
     * [scene] is the sport the pipeline recognised the channel to be showing (see
     * [tv.own.owntv.features.aisubtitles.SportSceneDetector]), or "" when it recognised none. It is
     * a domain cue for the model: the same word means different things in different sports, so a
     * fragment given its sport translates more reliably than one the model has to place itself.
     * [recent] is the last one or two finished sentences as `source → translation` lines, which is
     * what carries a name or a term across sentence boundaries — the same-sentence context cannot,
     * because it is cleared at every sentence.
     *
     * [players] is the "name → Chinese rendering" floor for the fixture the pipeline scoped the
     * decoder to — its clubs and their squads
     * (see [tv.own.owntv.features.aisubtitles.SubtitleGlossary.fixturePromptSection]) — or "" when no
     * club is scoped. Unlike [scene] it rides EVERY request: it is stable for the match, and a fragment
     * naming a player the model would otherwise render as an ordinary word (`Rice`, `Mount`) — or a
     * club it would spell two ways — needs it whether or not it is the sentence's first request.
     *
     * [lookahead] is the commentary that FOLLOWS the fragment, known only when the pipeline runs
     * ahead of the speaker (live audio held back — see `LiveAudioDelay`), or "". It is evidence for
     * resolving a name, a pronoun or a term that the fragment alone leaves ambiguous; it must never
     * be translated or echoed into the caption.
     *
     * Default: no incremental reporting — the result is delivered once. That is the right behaviour
     * for backends that cannot stream (the free Google endpoint) and for test doubles.
     *
     * The answer says whether it applies to the fragment just asked for or to the whole window (see
     * [TranslationResult.wholeLine]); a whole-line answer is normally reported once, as the return
     * value, because there is no fragment-by-fragment progress to publish.
     */
    suspend fun translateStreaming(
        text: String,
        context: String = "",
        config: AiSubtitleConfig,
        translated: String = "",
        scene: String = "",
        recent: String = "",
        players: String = "",
        lookahead: String = "",
        onDelta: (String) -> Unit,
    ): TranslationResult = TranslationResult(translate(text, config).also(onDelta))
}

/**
 * OpenAI-compatible chat-completions client used as the cloud translation backend.
 *
 * Any OpenAI Chat Completions compatible endpoint works (OpenAI itself, DeepSeek, an Azure-style
 * gateway, a self-hosted vLLM/Ollama OpenAI shim,…): the request is a plain
 * `POST {baseUrl}/v1/chat/completions` with a Bearer token, answered by only the translated text.
 *
 * The line is requested with `stream: true` so the caller can show it while the model writes it (see
 * [translateStreaming]); a gateway that ignores the flag and answers with one whole completion body is
 * handled transparently, and [translate] keeps the plain single-round-trip form.
 *
 * The temperature and the system instruction are deliberately opinionated so the reply is only the
 * translation, ready to display as a subtitle. Temperature is 0 (not the usual small value) because
 * the same sentence is re-translated as it grows: with any sampling the model reworded the shared
 * prefix on every round trip and the Chinese line visibly churned, where a greedy decode keeps the
 * early words stable and only appends the new ones.
 */
class OpenAiCompatibleTranslator(
    private val client: OkHttpClient,
) : SpeechTranslator {

    private val json = "application/json; charset=utf-8".toMediaType()

    override suspend fun translate(text: String, config: AiSubtitleConfig): String {
        val target = config.targetLanguage.ifBlank { return text }
        val request = buildRequest(text, "", "", "", "", "", "", target, config, stream = false)
        return client.awaitCall(request) { resp ->
            if (!resp.isSuccessful) throw TranslationHttpException(resp.code, "translation ${resp.code}: ${resp.body?.string()?.take(200)}")
            parse(resp) ?: throw IOException("no translation in response")
        }
    }

    /**
     * Streaming form: reads the server-sent events the gateway writes as it generates, feeding the
     * accumulated line to [onDelta] as it grows, and returns the finished line.
     *
     * Two shapes are accepted, because "OpenAI compatible" is a spectrum: an `text/event-stream` body
     * of `data: {...}` frames, and — for gateways/proxies that ignore `stream` — one ordinary
     * completion body, which is simply reported in a single [onDelta]. The body's own framing decides
     * when the header does not say (see [isEventStream]), so a proxy that streams under a JSON
     * Content-Type still reaches the caption as it is written. A response that never produces
     * any content (an error frame, a content-less stream, a pure reasoning reply) throws, so the
     * caller falls back exactly as it does for a failed non-streaming round trip.
     */
    override suspend fun translateStreaming(
        text: String,
        context: String,
        config: AiSubtitleConfig,
        translated: String,
        scene: String,
        recent: String,
        players: String,
        lookahead: String,
        onDelta: (String) -> Unit,
    ): TranslationResult {
        val target = config.targetLanguage.ifBlank { return TranslationResult(text).also { onDelta(text) } }
        val request = buildRequest(text, context, translated, scene, recent, players, lookahead, target, config, stream = true)
        return client.awaitCall(request) { resp ->
            if (!resp.isSuccessful) throw TranslationHttpException(resp.code, "translation ${resp.code}: ${resp.body?.string()?.take(200)}")
            val body = resp.body ?: throw IOException("no translation in response")
            val source = body.source()
            // Decide the response shape from the body's own framing, not only the header. A gateway or
            // proxy that streams but labels the body `application/json` used to be read as a single
            // completion: nothing reached the caption until the whole line had been generated, which is
            // exactly the "Chinese lags a whole generation behind the English" behaviour streaming is
            // there to remove. The first line is read up front so it can be handed to whichever path is
            // chosen — `data:` is unambiguous (an ordinary completion body is JSON and never starts
            // with it).
            val first = source.readUtf8Line()
            if (!isEventStream(resp.header("Content-Type"), first)) {
                val rest = source.readUtf8()
                val whole = parseCompletion(listOfNotNull(first, rest).joinToString("\n").trim())
                    ?: throw IOException("no translation in response")
                onDelta(whole)
                return@awaitCall TranslationResult(whole)
            }
            val line = StringBuilder()
            var done = first?.let { handleSseFrame(it, line, onDelta) } ?: false
            while (!done) {
                val frame = source.readUtf8Line() ?: break
                done = handleSseFrame(frame, line, onDelta)
            }
            val whole = line.toString().trim()
            if (whole.isEmpty()) throw IOException("no translation in response")
            TranslationResult(whole)
        }
    }

    /**
     * Minimal connectivity check for the settings page's "Test connection" button. Runs the exact
     * same request path as [translate] against the gateway fields passed in (which may still be
     * mid-edit, so they are taken verbatim rather than from the saved config), so a passing check
     * means subtitles will flow. Returns the translated probe sentence on success, throws with a
     * human-readable cause on failure. The probe is deliberately tiny to keep the round-trip cheap.
     */
    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
        targetLanguage: String,
    ): String {
        val config = AiSubtitleConfig(
            targetLanguage = targetLanguage.ifBlank { "zh" },
            apiBaseUrl = baseUrl.ifBlank { AiSubtitleDefaults.DEFAULT_BASE_URL },
            apiKey = apiKey,
            model = model.ifBlank { AiSubtitleDefaults.DEFAULT_MODEL },
        )
        return translate(SAMPLE_PROBE, config)
    }

    private fun buildRequest(
        text: String,
        context: String,
        translated: String,
        scene: String,
        recent: String,
        players: String,
        lookahead: String,
        target: String,
        config: AiSubtitleConfig,
        stream: Boolean,
    ): Request {
        val hasContext = context.isNotBlank()
        // The names-and-terms table acts on the SOURCE side: ambiguous words are rewritten before the
        // model sees them (see [SubtitleGlossary.disambiguate]) and the entries that occur are pinned
        // in the prompt. A two-to-five-word fragment carries too little context to disambiguate on its
        // own, which is where "Judd Trump" came back as the politician.
        val anchoredBy = SubtitleGlossary.window(context, text)
        val user = buildUserMessage(
            text = SubtitleGlossary.disambiguate(text, anchoredBy),
            context = SubtitleGlossary.disambiguate(context, anchoredBy),
            translated = translated,
        )
        val glossary = SubtitleGlossary.promptSection(anchoredBy, target)
        // DeepSeek's thinking mode is what makes a live caption unusable: the model writes its chain of
        // thought before the first translated word, so the English line runs on for seconds with nothing
        // under it, and only `reasoning_content` (which is discarded, see [streamDelta]) arrives. Turning
        // it off is a DeepSeek-specific request field — sending it to an OpenAI-compatible gateway that
        // does not know it is a 400 — so it is added only when the configured model/gateway is DeepSeek.
        val thinking = if (isDeepSeek(config.model, config.apiBaseUrl)) THINKING_DISABLED else ""
        val body = """
            |{"model":${config.model.json()},
            |"messages":[
            |  {"role":"system","content":${systemPrompt(target, config.sourceLanguage, hasContext, glossary, scene, recent, players, lookahead).json()}},
            |  {"role":"user","content":${user.json()}}
            |],
            |"temperature":0,
            |"max_tokens":$MAX_TOKENS,
            |"stream":$stream$thinking}
        """.trimMargin()
        val url = config.apiBaseUrl.trimEnd('/') + "/v1/chat/completions"
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Accept", if (stream) "text/event-stream" else "application/json")
            .post(body.toRequestBody(json))
            .build()
    }

    /**
     * The instruction the gateway is asked to follow. Written for a *live* subtitle line rather than
     * for a document: the fragment handed over is part of a sentence still being spoken, so it can be
     * an unfinished clause, and the reply must be exactly the next piece of one readable subtitle line.
     *
     * The context framing is what makes incremental translation as good as translating whole
     * sentences: a three-word tail translated on its own loses its subject and reads as machine
     * output, while the same tail translated as the continuation of the words already on screen keeps
     * the sentence's register, names and terminology. The prompt therefore has to be explicit that
     * CONTEXT is *not* to be translated again — a model left to interpret the two parts on its own
     * answers with the whole sentence, which would duplicate the line on screen.
     *
     * The source language is named when the pipeline knows it — "auto-detect this fragment" made a
     * short line's translation drift into the wrong language, and a wrong-language Chinese caption
     * reads as a broken translation rather than as a detection error.
     *
     * The forbid-list is what keeps the caption clean under a chatty model: an unconstrained
     * instruction-following model answers a subtitle request with the original, a vocabulary note or
     * an explanation often enough that the overlay shows a paragraph instead of a caption. Asking for
     * "natural spoken wording" (rather than a faithful literal rendering) is the counterweight for the
     * other half of the quality problem — a word-for-word rendering of unfinished speech reads as
     * machine output.
     *
     * [glossary] carries the names-and-terms table's entries for the words actually present. It has to
     * override the general "keep names as they are" rule above it, or a model told both would keep the
     * English name and the pinned Chinese rendering would be ignored.
     */
    internal fun systemPrompt(
        target: String,
        source: String,
        hasContext: Boolean,
        glossary: String = "",
        scene: String = "",
        recent: String = "",
        players: String = "",
        lookahead: String = "",
    ): String {
        val from = source.takeIf { it.isNotBlank() }?.let { " from ${it.bcp47Label()}" } ?: ""
        val to = target.bcp47Label()
        val framing = if (hasContext) {
            "It arrives in parts: CONTEXT is the source words already translated and already shown " +
                "on screen — never translate them again; PREVIOUS, when present, is the translation " +
                "already displayed for CONTEXT — continue its wording, names and terminology; and NEW " +
                "is the fragment you must translate. Translate only NEW, continuing naturally from " +
                "PREVIOUS so the whole reads as one sentence. "
        } else {
            "It is the first fragment of a new line. Translate all of it. "
        }
        // The sport the pipeline recognised, when it recognised one: a domain cue that disambiguates
        // the words that mean different things in different sports (`pot`, `frame`, `ace`). Absent
        // when no scene was proved, so ordinary television pays nothing for it.
        val domain = if (scene.isNotBlank()) {
            "The programme is $scene coverage, so expect its vocabulary, its scorelines and its " +
                "players' names. "
        } else {
            ""
        }
        // The squads the fixture is made of, pinned for the WHOLE match rather than for the sentence:
        // a surname that is also an ordinary word (`Rice`, `Mount`, `Wood`) is exactly what a fragment
        // translated with no context gets wrong, and the block is identical on every request, so it
        // sits near the front where a gateway's prefix cache can hold it. Empty until a club is scoped,
        // so a channel with no fixture pays nothing. The block carries one rule that is NOT part of
        // the list: a name is rendered in the form the text used (whole name in, whole name out), so
        // one man's name cannot grow or shrink between sentences — and where two of the listed players
        // share a surname, the given name is what tells them apart.
        val squad = if (players.isNotBlank()) {
            "The fixture on the pitch is the clubs and squads below, and their renderings are " +
                "CONFIRMED for the whole match: every name listed there must be rendered with the " +
                "given $to wording exactly as given, and must never be re-translated, paraphrased " +
                "or replaced by any other wording — not even a synonym or a literal reading of a " +
                "nickname. A player is named the way the text names him: keep the whole name when " +
                "it is spoken in full and only the surname when only the surname is said — do not " +
                "let one man's name grow or shrink between sentences. Where two of the listed " +
                "players share a surname, the given name is what tells them apart, so render it as " +
                "well.\n$players\n"
        } else {
            ""
        }
        // The last finished sentences, so a name or a term chosen in one sentence is carried into the
        // next instead of being re-chosen — the same-sentence context cannot do this, because it is
        // cleared at every sentence boundary. Only sent when there is history.
        val history = if (recent.isNotBlank()) {
            "Earlier lines already shown (for continuity of names and terminology — do NOT translate " +
                "them again):\n$recent\n"
        } else {
            ""
        }
        // The words that FOLLOW the fragment, available only while the audio is held back so the
        // recognizer runs ahead of the speaker. They settle exactly the ambiguities a live fragment
        // cannot — a bare surname, `City` versus `United`, a pronoun — and must never leak into the
        // caption, so the rule against translating them is as explicit as the one for CONTEXT.
        val ahead = if (lookahead.isNotBlank()) {
            "After this fragment the commentary continues with: \"$lookahead\". That is later speech, " +
                "not part of what you translate — use it only to settle an ambiguous name, pronoun or " +
                "term, and never translate, quote or mention it.\n"
        } else {
            ""
        }
        // Only present when the table matched something, so an ordinary broadcast pays no tokens for
        // it and gets exactly the prompt it had before.
        val terms = if (glossary.isNotBlank()) {
            "These are live sports captions, so use these exact $to renderings wherever the listed " +
                "word appears — they are the accepted ones, and the word may well mean something else " +
                "outside this context:\n$glossary\n"
        } else {
            ""
        }
        return "You translate live TV subtitles$from into $to. What you receive is speech captured " +
            "while it is still being spoken, so it may be an unfinished clause and it may begin " +
            "mid-sentence. $squad$framing$domain$history$ahead$terms" +
            "Answer with the $to translation of NEW only — no CONTEXT repeated, no original text, no " +
            "quotes, no labels, no notes, no romanisation. $namingAndRepair" +
            "Use natural spoken wording rather than a literal word-for-word rendering."
    }

    /**
     * The two standing quality rules that are not about framing:
     *
     *  - **Conventional renderings.** A live caption is read, not studied, so it has to use the
     *    wording Chinese broadcast already uses for a name, a club or a technical term — the same
     *    person must not be rendered two ways in two sentences. A pinned club or squad member is
     *    LOCKED: its rendering is confirmed for the match and this rule forbids the model from
     *    re-translating or substituting it, which is what the earlier "the pin wins where it applies"
     *    only implied. Everything the table does not pin is still the model's own choice.
     *  - **Evident recognition errors.** The source is automatic speech recognition, so it contains
     *    slips. A word that cannot fit its sentence ("he needs the queue ball") should be translated
     *    as what was evidently meant, not literally. Phrased as "a word that clearly does not fit",
     *    so the model corrects the obvious slip rather than rewriting lines it merely finds odd.
     */
    private val namingAndRepair =
        "Use the standard Chinese broadcast rendering for names, teams, competitions and technical " +
            "terms. Where a name is pinned above (a club or a squad member), that rendering is the " +
            "CONFIRMED one and MUST be used exactly as given — never re-translate, paraphrase or " +
            "substitute another wording for it, and never render it two ways in two sentences. The " +
            "text comes from automatic speech recognition and may contain a mis-heard word; where a " +
            "word clearly does not fit the sentence, translate what was evidently meant rather than " +
            "the word as written. Keep numbers as they are. "

    /** The completed text of an ordinary (non-streaming) completion body, or null if unusable. */
    internal fun parseCompletion(raw: String): String? = kotlin.runCatching {
        val arr = org.json.JSONObject(raw).getJSONArray("choices")
        if (arr.length() == 0) null
        else arr.getJSONObject(0).getJSONObject("message").getString("content").trim()
    }.getOrNull().takeIf { it?.isNotEmpty() == true }

    private fun parse(resp: Response): String? {
        val raw = resp.body?.string() ?: return null
        return parseCompletion(raw)
    }

    private fun String.bcp47Label(): String = if (length == 2) when (this) {
        "zh" -> "Simplified Chinese"
        "en" -> "English"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "fr" -> "French"
        "es" -> "Spanish"
        "de" -> "German"
        "ru" -> "Russian"
        "ar" -> "Arabic"
        else -> this
    } else this

    private fun String.json(): String {
        val sb = StringBuilder("\"")
        for (ch in this) when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    /**
     * Run one round trip whose socket dies with the coroutine. [block] receives the response and must
     * consume it (it is closed on the way out). Without this, cancellation — a newer caption
     * superseding this round trip, or the pipeline stopping — left the request running to its own
     * timeout on an IO thread while the caption waited behind it; `Call.cancel()` interrupts the
     * blocking read instead, so a superseded translation frees its connection immediately.
     */
    private suspend inline fun <T> OkHttpClient.awaitCall(
        request: Request,
        crossinline block: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val call = newCall(request)
        val cancellation = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { block(it) }
        } finally {
            cancellation?.dispose()
        }
    }

    companion object {
        /** One cheap, inoffensive sentence; only its translation matters to the connectivity test. */
        private const val SAMPLE_PROBE = "Hello, this is a connectivity test."

        /**
         * Ceiling on the reply. A subtitle line for the ≤18-word clause the pipeline hands over is a
         * few dozen tokens; a gateway left unbounded can spin far past it (a reasoning model writing
         * a plan, a model echoing the instruction back) and every extra token is time the caption
         * spends showing the *previous* line. One short line of Chinese is ~40 tokens, so 96 leaves
         * room for a longer clause and a stray leading token while bounding the generation time.
         */
        private const val MAX_TOKENS = 96

        /** The SSE terminator every OpenAI-compatible stream ends with. */
        internal const val STREAM_DONE = "[DONE]"

        /**
         * DeepSeek's OpenAI-format switch for its thinking mode. Live subtitles need it off: the model
         * writes the whole chain of thought before the first translated word, so the English line runs
         * on with nothing under it and only `reasoning_content` (discarded, see [streamDelta]) arrives.
         * Only DeepSeek understands this field — see [isDeepSeek].
         */
        private const val THINKING_DISABLED = ""","thinking":{"type":"disabled"}"""

        /**
         * Whether the configured backend is DeepSeek, the only gateway that accepts
         * [THINKING_DISABLED]. Matched on the model *or* the host so a DeepSeek model reached through a
         * third-party relay is still recognised; sending the unknown field to any other
         * OpenAI-compatible gateway is a 400, so it must not go there.
         */
        internal fun isDeepSeek(model: String, baseUrl: String): Boolean =
            model.contains("deepseek", ignoreCase = true) || baseUrl.contains("deepseek", ignoreCase = true)

        /**
         * The user turn: the source words already on screen ([context]), the translation they already
         * produced ([translated]) and the fragment to translate now. Kept as one string because the
         * model is asked to translate only `NEW` while staying consistent with the other two. When the
         * line is just starting there is neither, and the fragment stands alone.
         */
        internal fun buildUserMessage(text: String, context: String, translated: String): String {
            if (context.isBlank() && translated.isBlank()) return "NEW: $text"
            return buildString {
                if (context.isNotBlank()) append("CONTEXT: ").append(context).append('\n')
                if (translated.isNotBlank()) append("PREVIOUS: ").append(translated).append('\n')
                append("NEW: ").append(text)
            }
        }

        /**
         * Whether a response is a server-sent-event stream rather than one whole completion body.
         * The Content-Type is the gateway's declaration and is trusted when present; `data:` framing is
         * the body's own, used when the header does not say (a proxy that strips or rewrites it). Pure
         * so the shapes are pinned by tests without a server.
         */
        internal fun isEventStream(contentType: String?, firstLine: String?): Boolean =
            contentType.orEmpty().contains("event-stream", ignoreCase = true) ||
                firstLine?.trimStart()?.startsWith("data:") == true

        /**
         * Consume one SSE line: append its content fragment to [line] and report the accumulated
         * translation. Returns true when the stream terminator arrived, so the reader stops.
         * Lines that carry no text (comments, keep-alives, the opening role frame, a
         * reasoning model's `reasoning_content`) are skipped without touching the caption. Pure, so it
         * can be driven from real gateway frames in tests; see [streamDelta] for the frame shapes.
         */
        internal fun handleSseFrame(
            frame: String,
            line: StringBuilder,
            onDelta: (String) -> Unit,
        ): Boolean {
            if (!frame.startsWith("data:")) return false
            val payload = frame.removePrefix("data:").trim()
            if (payload.isEmpty()) return false
            if (payload == STREAM_DONE) return true
            val delta = streamDelta(payload) ?: return false
            line.append(delta)
            onDelta(line.toString().trim())
            return false
        }

        /**
         * The text fragment carried by one streamed `data:` frame, or null for a frame that carries
         * none: the opening role frame, a keep-alive, a final `finish_reason` frame, or a reasoning
         * model's `reasoning_content` (which must never be spliced into the caption). Pure so it can
         * be tested against real gateway payloads without a server; `optJSONObject` on `choices[0]`
         * also covers a short or empty `choices` array.
         */
        internal fun streamDelta(payload: String): String? {
            val frame = kotlin.runCatching { org.json.JSONObject(payload) }.getOrNull() ?: return null
            val choice = frame.optJSONArray("choices")?.optJSONObject(0) ?: return null
            val delta = choice.optJSONObject("delta") ?: return null
            if (delta.isNull("content")) return null
            return delta.optString("content").takeIf { it.isNotEmpty() }
        }

        /**
         * Shared builder used by the manager.
         *
         * [callTimeout] and [readTimeout] split the two ways a round trip can misbehave, and the split
         * is what keeps the caption moving:
         *
         *  - A connection that has gone quiet is a connection that is not coming back. Because the
         *    line is streamed, [readTimeout] is what detects that: it is the gap *between* writes, so a
         *    model that keeps producing text is never cut off, while one that stalls is cut after a few
         *    seconds and the worker moves on to the newest sentence. (This matters more than a total
         *    deadline: with SSE keep-alives or a dribbling gateway, a per-read timeout can be reset
         *    forever, so a stalled round trip used to hold the caption for as long as the far end felt
         *    like taking.)
         *  - [callTimeout] bounds the whole round trip, and is deliberately *generous* rather than
         *    tight. Cutting a line that is still being written is worse than waiting for it: the
         *    caller falls back to the free endpoint, which answers with a differently-worded
         *    translation (a visibly inconsistent caption) plus a second round trip of its own. A
         *    subtitle line is short, so a call that needs more than this is stalled, not slow.
         *
         * [connectTimeout] is tight for the opposite reason: an unreachable gateway must fail over to
         * the free endpoint quickly instead of spending its whole budget failing to open a socket
         * while the Chinese line sits on the previous sentence.
         */
        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}