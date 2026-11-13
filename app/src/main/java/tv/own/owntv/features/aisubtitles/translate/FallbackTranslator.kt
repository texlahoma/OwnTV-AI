package tv.own.owntv.features.aisubtitles.translate

import android.util.Log
import tv.own.owntv.features.aisubtitles.AiSubtitleConfig

/**
 * Tries [primary] (the user-configured OpenAI-compatible gateway) and, on any failure — no gateway
 * configured, unreachable, or a blank reply — falls back to [fallback] (the key-free Google web
 * translator) so the caption is never lost to the configured backend being down.
 *
 * **Zero-config fast path:** when the user has not configured an API key, [primary] is skipped
 * entirely and [fallback] is used directly. This is what makes the AI-subtitle feature work
 * out-of-the-box — with no gateway configured the caption goes straight to the free Google
 * translator instead of burning a (5s+) socket timeout against a default endpoint only to fall back.
 *
 * Only if both backends fail does the error propagate to the caller, which then shows the source
 * line verbatim rather than dropping the caption.
 */
class FallbackTranslator(
    private val primary: SpeechTranslator,
    private val fallback: SpeechTranslator,
) : SpeechTranslator {

    private val tag = "AiSubtitle"

    /**
     * Round trips the free [fallback] actually served, and of those, the ones where [primary] was
     * configured but failed first.
     *
     * The pipeline's own `fail` counter can never show this: this class SWALLOWS a primary failure
     * and answers from the free endpoint, so the round trip still counts as a success. A gateway with
     * a dead key, an exhausted quota or a wrong model name therefore reports a perfectly healthy
     * `req`/`n` while every line on screen is Google's — which is exactly how "中文质量差" and "网关
     * 其实早就挂了" look the same from the outside. [fellBackAfterPrimary] is the number that separates
     * them: it says the configured gateway is being BYPASSED, whereas `fallbackTrips −` it is only the
     * zero-config path (no key set, which the config itself already tells the reader).
     */
    @Volatile var fallbackTrips: Long = 0L
        private set
    @Volatile var fellBackAfterPrimary: Long = 0L
        private set

    override suspend fun translate(text: String, config: AiSubtitleConfig): String {
        // No API key configured: don't probe the gateway (it would fail against a default/blank
        // endpoint and cost a network timeout) — go straight to the free, key-free Google path.
        if (config.apiKey.isBlank()) {
            Log.d(tag, "no gateway configured; using google free translate directly")
            fallbackTrips++
            return fallback.translate(text, config)
        }
        val viaPrimary = attempt("primary") { primary.translate(text, config) }
        if (!viaPrimary.isNullOrBlank()) return viaPrimary
        Log.d(tag, "primary translation unavailable; falling back to google free translate")
        fellBackAfterPrimary++
        fallbackTrips++
        return fallback.translate(text, config)
    }

    /** [translate], but streamed: the gateway's line is reported as it is written. */
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
        if (config.apiKey.isBlank()) {
            Log.d(tag, "no gateway configured; using google free translate directly")
            fallbackTrips++
            return fallback.translateStreaming(text, context, config, translated, scene, recent, players, lookahead, onDelta)
        }
        // Keep the last partial the gateway managed to write: if the connection breaks mid-line the
        // caption must keep what it already shows rather than switching to the free endpoint's
        // differently-worded full sentence (or blanking outright) after the fact.
        var lastPartial = ""
        val viaPrimary = attempt("primary") {
            primary.translateStreaming(text, context, config, translated, scene, recent, players, lookahead) { partial ->
                lastPartial = partial
                onDelta(partial)
            }
        }
        if (viaPrimary != null && viaPrimary.text.isNotBlank()) return viaPrimary
        // A line the gateway had already started writing is kept as-is rather than re-rendered by the
        // free endpoint — that is a DEGRADED primary, not a fallback, so it is deliberately not
        // counted here: the gateway demonstrably answered.
        if (lastPartial.isNotBlank()) return TranslationResult(lastPartial)
        Log.d(tag, "primary translation unavailable; falling back to google free translate")
        // The free endpoint cannot be told the context — it takes no prompt — so it answers with the
        // whole window instead of the fragment (see [GoogleFreeTranslator.translateStreaming]):
        // because it is handed everything already on screen, its answer replaces the line rather than
        // being appended to the words the gateway had produced. The honest degradation is a line that
        // reads as ONE translation: the alternative — a standalone machine rendering of the tail
        // glued onto the gateway's wording — is the "the Chinese reads like machine output" report,
        // and it is the more visible defect of the two, because the seam sits mid-sentence.
        fellBackAfterPrimary++
        fallbackTrips++
        return fallback.translateStreaming(text, context, config, translated, scene, recent, players, lookahead, onDelta)
    }

    /**
     * Run one backend, treating *any* failure as "try the next one" — except coroutine cancellation,
     * which must propagate. `runCatching` would swallow it, and then a round trip the worker had
     * already superseded (see [AiSubtitleManager.startTranslationWorker]) would run on to its own
     * timeout, falling through to the free endpoint and publishing a line nobody is waiting for —
     * the cancelled request would effectively become the slowest one.
     */
    private inline fun <T> attempt(backend: String, block: () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(tag, "$backend translation failed (${e.message})")
        null
    }
}