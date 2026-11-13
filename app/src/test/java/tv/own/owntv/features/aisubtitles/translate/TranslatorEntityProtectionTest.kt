package tv.own.owntv.features.aisubtitles.translate

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.aisubtitles.SubtitleGlossary

/**
 * Pins the entity-protection half of the system prompt. Once the pipeline has scoped a fixture, its
 * clubs and squad members are handed to the model as pin
 * ([tv.own.owntv.features.aisubtitles.SubtitleGlossary.fixturePromptSection]) and the prompt has to
 * declare them CONFIRMED — to be used exactly as given and never re-translated, paraphrased or
 * replaced. The earlier wording ("the pin wins where it applies") only implied that, which left a
 * chatty model free to render `Arsenal` two ways in two sentences: the drift the fixture block
 * exists to remove.
 */
class TranslatorEntityProtectionTest {

    private val translator = OpenAiCompatibleTranslator(OkHttpClient())

    @Test
    fun `a scoped fixture is declared confirmed and locked against re-translation`() {
        val players = SubtitleGlossary.fixturePromptSection(
            setOf(SubtitleGlossary.Team.ARSENAL),
            "zh-CN",
        )
        val prompt = translator.systemPrompt(
            target = "zh",
            source = "en",
            hasContext = false,
            players = players,
        )
        // The pins themselves have to be in the prompt...
        assertTrue(prompt, prompt.contains("Arsenal → 阿森纳"))
        assertTrue(prompt, prompt.contains("Saka → 萨卡"))
        // ...and the rule that makes them binding has to say so in as many words, on both the
        // fixture block and the standing naming rule.
        assertTrue(prompt, prompt.contains("CONFIRMED for the whole match"))
        assertTrue(prompt, prompt.contains("never be re-translated"))
        assertTrue(prompt, prompt.contains("MUST be used exactly as given"))
    }

    @Test
    fun `no fixture means no fixture block and no pins`() {
        val prompt = translator.systemPrompt(
            target = "zh",
            source = "en",
            hasContext = false,
        )
        // Ordinary television is not told about a fixture it does not have: no club is pinned and
        // the fixture block does not ride, so nothing is claimed to be confirmed.
        assertFalse(prompt, prompt.contains("fixture on the pitch"))
        assertFalse(prompt, prompt.contains("Arsenal → 阿森纳"))
    }

    @Test
    fun `look-ahead is offered as evidence and fenced off from the caption`() {
        val prompt = translator.systemPrompt(
            target = "zh",
            source = "en",
            hasContext = false,
            lookahead = "and Salah turns away from the defender",
        )
        // The following words are evidence for an ambiguous name...
        assertTrue(prompt, prompt.contains("and Salah turns away from the defender"))
        assertTrue(prompt, prompt.contains("After this fragment"))
        // ...and the model must not translate, quote or mention them.
        assertTrue(prompt, prompt.contains("never translate, quote or mention it"))
    }

    @Test
    fun `no look-ahead leaves the prompt as it was`() {
        val prompt = translator.systemPrompt(target = "zh", source = "en", hasContext = false)
        assertFalse(prompt, prompt.contains("After this fragment"))
    }
}