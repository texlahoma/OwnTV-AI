package tv.own.owntv.features.aisubtitles.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.aisubtitles.AiSubtitleConfig
import tv.own.owntv.features.aisubtitles.translate.GoogleFreeTranslator.Companion.windowOf

/**
 * The degradation contract when the configured gateway is unusable: the caption must keep reading as
 * ONE translation. Two shapes are acceptable — the gateway's own partial line if it managed to write
 * anything, or the free endpoint's whole-window answer — and the one shape that must never ship is a
 * standalone machine rendering of the tail glued onto the gateway's wording, because the seam sits
 * mid-sentence and the viewer reads it as a broken translation.
 */
class FallbackTranslatorTest {

    private val config = AiSubtitleConfig(apiKey = "configured-key", targetLanguage = "zh")

    /**
     * A backend that answers a fragment (what a prompt-capable gateway does): [body] sees the request
     * and reports [deltas] as the line is written, then returns [answer].
     */
    private fun fragmentBackend(
        deltas: List<String> = emptyList(),
        answer: String = "网关翻译",
        body: (() -> Unit)? = null,
    ) = object : SpeechTranslator {
        override suspend fun translate(text: String, config: AiSubtitleConfig) = answer
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
            deltas.forEach(onDelta)
            body?.invoke()
            return TranslationResult(answer)
        }
    }

    /**
     * A backend that answers a replacement line (what the key-free endpoint does): [seenText] captures
     * the text it was handed, which must be the whole window because it takes no prompt.
     */
    private fun wholeLineBackend(answer: String, seenText: (String) -> Unit = {}) =
        object : SpeechTranslator {
            override suspend fun translate(text: String, config: AiSubtitleConfig) = answer
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
                seenText(windowOf(context, text))
                return TranslationResult(answer, wholeLine = true)
            }
        }

    /** The context has to be folded into the text: a prompt-less endpoint can be told nothing else. */
    @Test
    fun `the prompt-less endpoint is handed the whole window`() {
        assertEquals("now tied", windowOf("", "now tied"))
        assertEquals("the game is now tied", windowOf("the game is", "now tied"))
    }

    @Test
    fun `no gateway configured goes straight to the free endpoint`() = runBlocking {
        var primaryCalls = 0
        var handed = ""
        val translator = FallbackTranslator(
            primary = fragmentBackend(body = { primaryCalls++ }),
            fallback = wholeLineBackend("自由翻译") { handed = it },
        )

        val result = translator.translateStreaming(
            text = "now tied",
            context = "the game is",
            config = AiSubtitleConfig(targetLanguage = "zh"),
        ) { }

        assertEquals(0, primaryCalls)
        assertEquals("the game is now tied", handed)
        assertTrue(result.wholeLine)
        assertEquals("自由翻译", result.text)
    }

    @Test
    fun `a gateway that fails outright yields the free endpoint's replacement line`() = runBlocking {
        val translator = FallbackTranslator(
            primary = fragmentBackend(body = { throw java.io.IOException("gateway down") }),
            fallback = wholeLineBackend("整句翻译"),
        )

        val result = translator.translateStreaming("now tied", "the game is", config) { }

        assertTrue(
            "a whole-window answer must be flagged so the accumulator replaces the line",
            result.wholeLine,
        )
        assertEquals("整句翻译", result.text)
    }

    @Test
    fun `a gateway line that broke mid-sentence is kept instead of being re-rendered`() = runBlocking {
        var fallbackCalls = 0
        val translator = FallbackTranslator(
            primary = fragmentBackend(
                deltas = listOf("比赛现在"),
                body = { throw java.io.IOException("connection reset") },
            ),
            fallback = wholeLineBackend("另一种措辞") { fallbackCalls++ },
        )

        val result = translator.translateStreaming("now tied", "the game is", config) { }

        // The fallback would have worded the sentence again, discarding wording already on screen;
        // stopping on the gateway's own partial keeps the line the viewer is reading.
        assertEquals(0, fallbackCalls)
        assertEquals("比赛现在", result.text)
        assertFalse(
            "the kept partial is a fragment of the gateway line, not a whole-window replacement",
            result.wholeLine,
        )
    }

    @Test
    fun `a healthy gateway answer is passed through as a fragment`() = runBlocking {
        val translator = FallbackTranslator(
            primary = fragmentBackend(deltas = listOf("现在", "现在平手"), answer = "现在平手"),
            fallback = wholeLineBackend("不该用到"),
        )

        val result = translator.translateStreaming("now tied", "the game is", config) { }

        assertEquals("现在平手", result.text)
        assertFalse(result.wholeLine)
    }
}