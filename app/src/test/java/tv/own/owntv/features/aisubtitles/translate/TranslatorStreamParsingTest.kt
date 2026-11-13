package tv.own.owntv.features.aisubtitles.translate

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.aisubtitles.translate.OpenAiCompatibleTranslator.Companion.buildUserMessage
import tv.own.owntv.features.aisubtitles.translate.OpenAiCompatibleTranslator.Companion.handleSseFrame
import tv.own.owntv.features.aisubtitles.translate.OpenAiCompatibleTranslator.Companion.isDeepSeek
import tv.own.owntv.features.aisubtitles.translate.OpenAiCompatibleTranslator.Companion.isEventStream
import tv.own.owntv.features.aisubtitles.translate.OpenAiCompatibleTranslator.Companion.streamDelta

/**
 * Parsing of the two response shapes an "OpenAI compatible" gateway can return, against the payloads
 * the real ones actually send. Streaming is what makes the Chinese caption appear while the model is
 * still writing (see [SpeechTranslator.translateStreaming]); a frame misread here either drops a word
 * from the line or splices reasoning text into the caption, so the shapes are pinned down.
 */
class TranslatorStreamParsingTest {

    private val translator = OpenAiCompatibleTranslator(OkHttpClient())

    /** DeepSeek/OpenAI open a stream with a role-only frame carrying an empty content string. */
    @Test
    fun `role frame carries no text`() {
        val frame = """{"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}"""
        assertNull(streamDelta(frame))
    }

    @Test
    fun `content frame carries its fragment`() {
        assertEquals("你好", streamDelta("""{"choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}"""))
        assertEquals("，世界", streamDelta("""{"choices":[{"delta":{"content":"，世界"}}]}"""))
    }

    /** DeepSeek's reasoning models put the chain of thought in `reasoning_content` — never captioned. */
    @Test
    fun `reasoning content is not caption text`() {
        val frame = """{"choices":[{"delta":{"reasoning_content":"The sentence is about a game."}}]}"""
        assertNull(streamDelta(frame))
    }

    @Test
    fun `a null or empty content field carries no text`() {
        assertNull(streamDelta("""{"choices":[{"delta":{"content":null}}]}"""))
        assertNull(streamDelta("""{"choices":[{"delta":{}}]}"""))
    }

    @Test
    fun `frames without a usable choice are ignored`() {
        assertNull(streamDelta("""{"choices":[]}"""))
        assertNull(streamDelta("""{"error":{"message":"rate limited"}}"""))
        // A stream with no payload at all must not throw into the caption pipeline.
        assertNull(streamDelta(OpenAiCompatibleTranslator.STREAM_DONE))
        assertNull(streamDelta("not json"))
    }

    /**
     * The stream's final frame. OpenAI/DeepSeek close with an empty delta and `finish_reason` set,
     * which carries no text; a gateway that instead folds the last fragment into that frame must
     * still have it delivered, or the caption silently loses its tail.
     */
    @Test
    fun `a finish frame carries text only when it also carries content`() {
        assertNull(streamDelta("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
        assertEquals("x", streamDelta("""{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}"""))
    }

    /** A gateway that ignored `stream` answers with one ordinary completion body. */
    @Test
    fun `whole completion body is parsed`() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant","content":" 你好，世界。 "},"finish_reason":"stop"}]}"""
        assertEquals("你好，世界。", translator.parseCompletion(body))
    }

    /**
     * Which shape the body is. The header is the gateway's declaration; the body's own `data:` framing
     * has to decide when the header does not say, or a streaming proxy that labels the body
     * `application/json` is read as a single completion and the caption waits for the whole line.
     */
    @Test
    fun `the response shape is decided by the framing as well as the header`() {
        assertTrue(isEventStream("text/event-stream; charset=utf-8", null))
        assertTrue(isEventStream("application/json", """data: {"choices":[]}"""))
        assertFalse(isEventStream("application/json", """{"choices":[]}"""))
        assertFalse(isEventStream(null, null))
        // An ordinary completion body is JSON, so it can never start with the SSE prefix.
        assertFalse(isEventStream("application/json", """  {"choices":[]}"""))
    }

    /** One frame's fragment is appended and the accumulated line reported; `[DONE]` ends the stream. */
    @Test
    fun `sse frames accumulate and stop at the terminator`() {
        val line = StringBuilder()
        val seen = mutableListOf<String>()
        assertFalse(handleSseFrame(""": keep-alive""", line) { seen += it })
        assertFalse(handleSseFrame("""data: {"choices":[{"delta":{"content":"你"}}]}""", line) { seen += it })
        assertFalse(handleSseFrame("""data: {"choices":[{"delta":{"content":"好"}}]}""", line) { seen += it })
        assertTrue(handleSseFrame("data: [DONE]", line) { seen += it })
        assertEquals(listOf("你", "你好"), seen)
    }

    @Test
    fun `unusable completion bodies yield null instead of a blank line`() {
        assertNull(translator.parseCompletion("""{"choices":[]}"""))
        assertNull(translator.parseCompletion("""{"choices":[{"message":{"content":"   "}}]}"""))
        assertNull(translator.parseCompletion("""{"error":"nope"}"""))
        assertNull(translator.parseCompletion(""))
    }

    /**
     * The thinking-mode switch is DeepSeek-only: sent to any other OpenAI-compatible gateway the unknown
     * field fails the whole request, so the match must be tight enough to be safe and wide enough to
     * still catch a DeepSeek model reached through a third-party relay.
     */
    @Test
    fun `only deepseek is asked to turn thinking off`() {
        assertTrue(isDeepSeek("deepseek-chat", "https://api.deepseek.com"))
        assertTrue(isDeepSeek("deepseek-reasoner", "https://api.deepseek.com/v1"))
        assertTrue(isDeepSeek("deepseek-chat", "https://relay.example.com/v1"))
        assertTrue(isDeepSeek("gpt-4o-mini", "https://api.deepseek.com"))
        assertFalse(isDeepSeek("gpt-4o-mini", "https://api.openai.com/v1"))
        assertFalse(isDeepSeek("qwen2.5", "https://relay.example.com/v1"))
    }

    /**
     * The user turn hands the model both halves of what is already on screen — the source words and the
     * Chinese they produced — so a fragment is translated as the continuation of that line; the first
     * fragment of a line carries neither.
     */
    @Test
    fun `the user turn carries the source and target of the translated prefix`() {
        assertEquals("NEW: now tied", buildUserMessage("now tied", "", ""))
        assertEquals(
            "CONTEXT: the game is\nPREVIOUS: 比赛现在\nNEW: now tied",
            buildUserMessage("now tied", "the game is", "比赛现在"),
        )
        // A rewind with no unchanged source prefix still keeps the produced line's wording.
        assertEquals("PREVIOUS: 比赛现在\nNEW: tied", buildUserMessage("tied", "", "比赛现在"))
    }
}