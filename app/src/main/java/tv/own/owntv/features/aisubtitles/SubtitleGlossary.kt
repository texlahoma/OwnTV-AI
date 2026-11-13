package tv.own.owntv.features.aisubtitles

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Built-in names-and-terms table for the three sports a live caption is watched with most, plus the
 * source-side rewrite and the misheard-token repair that go with it. It exists because two gaps sit
 * outside the models' reach — one in the translator, one in the recogniser.
 *
 * ## Why a table is needed at all
 *
 * The translator is handed SHORT fragments — two to five words — and only ever sees the *same
 * sentence's* earlier words as context (see [LiveTranslationAccumulator]). Stripping a fragment down
 * to that size removes the domain cues a model needs to disambiguate a word, and the worst case is
 * the FIRST fragment of a sentence, which has no context at all — which is exactly where a name is
 * usually introduced. Two failures follow, and both are 100% visible on screen:
 *
 *  - **Homographs.** `Judd Trump` comes back as 特朗普 (the politician), `deuce` as 平局, `pot` as 锅,
 *    `frame` as 框架, `love` as 爱, `Sinner` as 罪人.
 *  - **Drift.** Within one sentence [LiveTranslationAccumulator]'s `PREVIOUS` context keeps wording
 *    stable, but nothing carries across sentences, so the same player can be rendered two ways in two
 *    consecutive sentences.
 *
 * And the recogniser adds a third, one stage earlier: a name outside its vocabulary comes back as
 * the nearest word it does know — `Judd Trump` as `Trunk`, `Higgins` as `higgens` — which no amount
 * of translation-side rewriting can recover, because the wrong word is already on both lines. That is
 * what [correct] is for.
 *
 * ## Four mechanisms, one table
 *
 *  - **[recognitionHotwords]** biases the *decoder itself* toward one scene's vocabulary, so a
 *    name the model would otherwise flatten to its nearest word is decoded right the first time
 *    (`Salace` → `Salah`, `Osullivan` → `O'Sullivan`). This is the only mechanism that acts while
 *    the words are being recognised rather than after; it costs beam-search decode time, so only
 *    the English streaming engine uses it. It is also the only mechanism that can *hurt* ordinary
 *    television, so it is gated twice: the list is curated, not derived, and the pipeline sends one
 *    scene's list at most — chosen from the words already decoded ([SportSceneDetector]), never
 *    assumed. See [SPORT_HOTWORDS] for why the lists are per-sport.
 *  - **[correct]** repairs a *misheard* token before anything downstream sees the text
 *    (`Trunk` → `Trump`, `higgens` → `higgins`). ASR slips on names are near-misses — the model
 *    writes the nearest word it knows — so a known slip is rewritten to the entry's own spelling.
 *    This runs FIRST, on the recognised text itself, so *both* the English caption and the Chinese
 *    are built from the repaired words (a caption that keeps showing `Trunk` while the Chinese says
 *    特鲁姆普 would read as two different speakers).
 *  - **[disambiguate]** rewrites the *source* before the model ever sees it (`Trump` →
 *    `Judd Trump`). This is the cheap half: it costs no prompt tokens and removes the ambiguity
 *    instead of arguing with it. It is applied to the text handed to the translator only — never to
 *    the English caption, which must keep showing what was actually said.
 *  - **[promptSection]** pins the *target* rendering for the entries that actually occur
 *    (`Judd Trump` → 特鲁姆普), so the wording matches Chinese broadcast convention rather than the
 *    model's own choice. Only matched entries are sent, which keeps a request's cost at a handful of
 *    tokens instead of shipping the whole table on every round trip.
 *
 * ## The gate: why entries are not applied blindly
 *
 * Several entries are ordinary words outside their sport — `frame`, `pot`, `break`, `love`, `ace`,
 * `Sinner`, `Trump`. Applying them unconditionally would corrupt ordinary television: a news channel
 * mentioning Trump would be "corrected" to a snooker player, a film about frames to 局. So each entry
 * is either
 *
 *  - **[Kind.ANCHOR]** — unambiguous for its sport (`offside`, `deuce`, `snooker`, `O'Sullivan`).
 *    Matching one is proof enough, and it also *anchors* the sport for the request; or
 *  - **[Kind.GATED]** — applied only once its sport is anchored by an ANCHOR entry in the same
 *    window. `frame` becomes 局 when `O'Sullivan` is in the sentence, and is left alone otherwise.
 *
 * A fragment small enough to have been stripped of anchors is often a bare scoreline — `one nil`,
 * `three nil at half time` — whose proof sits in the numeral beside the word rather than in an
 * anchor word. A GATED entry flagged [Entry.provenByScoreline] therefore opens the gate on that
 * evidence too, which is what lets `nil` reach 零 in a scoreline with no football anchor anywhere in
 * the window. The flag is off by default: `love` would qualify the same way (`15 love`), but the
 * same rule would fire on a song title like `One Love`, so it stays anchor-only.
 *
 * A scoreline, though, arrives MANGLED: measured against the shipped English streaming model, `nil`
 * comes back as `Nill`/`Neil` and a score numeral as its homophone (`two` as `to`/`too`, `one` as
 * `won`), so "two nil" is heard as "to nil" and "nil nil" as "Neil Nill". [correct] therefore runs
 * [normaliseScoreline] over the recognised text whenever the window proves football
 * ([footballEvidenced]) — the club name is the evidence — which settles both the numeral and the
 * word before either caption is built. Only tokens inside a scoreline shape are rewritten, so an
 * ordinary `Neil`, `won` or `to` elsewhere is untouched, and the weaker homophone proof is only read
 * once football is evidenced some other way ("reduce to nil" is not a score).
 *
 * The same gate governs [correct]: a GATED entry's misheard forms (`trunk` → `trump`) are repaired
 * only when its sport is anchored, while an ANCHOR entry's slips are repaired on their own — which
 * is why those slips are chosen to be non-words (`selbi`, `higgens`) that cannot mean anything else.
 *
 * The gate is deliberately conservative: a sentence with no anchor gets no rewrite and no glossary,
 * so the feature can only ever fire where the domain is evidenced. The known limit is that an anchor
 * from a *previous* sentence is not remembered — the gate looks at one window.
 *
 * Only `zh` targets are carried, so [promptSection] stays empty for other target languages while
 * [disambiguate] (which is language-independent) still runs.
 *
 * Threading: pure and stateless, safe from any thread.
 */
internal object SubtitleGlossary {

    /**
     * How much proof an entry needs before it is applied.
     */
    private enum class Kind {
        /** Unambiguous for its sport; also anchors the sport for the request. */
        ANCHOR,

        /** Reads as an ordinary word elsewhere; applied only once its sport is anchored. */
        GATED,
    }

    /**
     * The sports the table knows. Used two ways: as the translation-gate anchor set an [Entry]
     * belongs to, and as the scene keys the decoder's hotwords are selected by (see
     * [recognitionHotwords]). The pipeline recognises one of these from the words it is decoding and
     * only then hands that sport's list to the decoder — see [SportSceneDetector].
     */
    enum class Sport { FOOTBALL, TENNIS, SNOOKER }

    /**
     * A football club whose squad the table carries: the unit [SportSceneDetector] recognises on the
     * pitch and [recognitionHotwords] hands to the decoder.
     *
     * The 20 clubs of a Premier League season and nothing else. Only clubs with a written
     * [TEAM_PLAYERS] block appear, and only the current season's are written down: a club whose squad
     * is missing could never be named anyway — its evidence IS its squad — so listing it would only
     * let a fixture be half-recognised and then mis-scoped. A European side, or a side since
     * relegated, therefore simply decodes unscoped, which is the intended failure direction: an
     * unscoped list is what the channel was biased with before scoping existed. Their club NAMES stay
     * in [FOOTBALL_CLUBS] regardless, so such a broadcast still opens a football scene and still
     * biases toward the club names — it just cannot be narrowed to two squads.
     *
     * [detect] is the club as a commentary actually names it, which is deliberately NOT the curated
     * long form the decoder is biased with. A commentary says `Brighton`, `Hull`, `Palace` and
     * `Spurs`; it rarely says `Brighton and Hove Albion` or `Crystal Palace` in full — so the short
     * forms have to be recognised, even though each of them is also an ordinary word (`Brighton` a
     * town, `Hull` a ship's body, `Palace` a building).
     *
     * A NICKNAME is admitted on the same terms as a short form: it is the club's own, a commentary
     * says it (not only the press writing it), and it is not a word another programme makes its own
     * subject. `Gunners`, `Spurs`, `Villans`, `Cherries`, `Seagulls`, `Toffees`, `Cottagers`,
     * `Magpies`, `Citizens`, `Black Cats`, `Red Devils`, `Tractor Boys` and `Sky Blues` qualify. The
     * ones WITHHELD are the ones that would be credited to the WRONG club: `Reds` (Liverpool's and
     * Nottingham Forest's), `Blues` (Chelsea's and Ipswich's), `Whites` (Leeds', and the press's for
     * several others), `Eagles`, `Bees` and `Tigers` (shared across the leagues). A withheld nickname
     * is not merely noise but a wrong club: resolved longest-first and by position, it would be
     * credited to whichever club the range happens to match rather than to the one meant.
     *
     * The ambiguity those readings would otherwise introduce is retired by the COUNT, not by the
     * roster: [SportSceneDetector] only asks about clubs once the football scene is already open,
     * and it scopes to the two clubs the commentary NAMES MOST ([teamMentionHits]). A bulletin or a
     * travel programme that says `Palace` once cannot out-count the two sides a match is actually
     * being commentated on. That is why [detect] may carry a short form where [SCENE_TERMS] — which
     * has to open a scene off a single word — deliberately does not.
     */
    enum class Team(val label: String, val detect: List<String>) {
        ARSENAL("Arsenal", listOf("Arsenal", "Gunners")),
        ASTON_VILLA("Aston Villa", listOf("Aston Villa", "Villa", "Villans")),
        BOURNEMOUTH("Bournemouth", listOf("Bournemouth", "Cherries")),
        BRENTFORD("Brentford", listOf("Brentford")),
        BRIGHTON("Brighton", listOf("Brighton and Hove Albion", "Brighton", "Seagulls")),
        CHELSEA("Chelsea", listOf("Chelsea")),
        COVENTRY("Coventry", listOf("Coventry City", "Coventry", "Sky Blues")),
        CRYSTAL_PALACE("Crystal Palace", listOf("Crystal Palace", "Palace")),
        EVERTON("Everton", listOf("Everton", "Toffees")),
        FULHAM("Fulham", listOf("Fulham", "Cottagers")),
        HULL("Hull", listOf("Hull City", "Hull")),
        IPSWICH("Ipswich", listOf("Ipswich Town", "Ipswich", "Tractor Boys")),
        LEEDS("Leeds", listOf("Leeds United", "Leeds")),
        LIVERPOOL("Liverpool", listOf("Liverpool")),
        MANCHESTER_CITY("Man City", listOf("Manchester City", "Man City", "City", "Citizens")),
        MANCHESTER_UNITED(
            "Man United",
            listOf("Manchester United", "Man United", "Man Utd", "United", "Red Devils"),
        ),
        NEWCASTLE("Newcastle", listOf("Newcastle United", "Newcastle", "Magpies")),
        NOTTINGHAM_FOREST("Forest", listOf("Nottingham Forest", "Forest")),
        SUNDERLAND("Sunderland", listOf("Sunderland", "Black Cats")),
        TOTTENHAM("Tottenham", listOf("Tottenham Hotspur", "Tottenham", "Spurs")),
    }

    /**
     * The most clubs whose squads are handed to the decoder at once: two, the sides of a fixture.
     *
     * The cap rather than a refusal, because the squads are what the decoder needs in order to
     * recognise the players AT ALL: a window that has named one side is scoped to one side and is
     * completed when the other is named, where holding out for both would leave a commentary's own
     * players unbiased for the minutes before the second side is ever mentioned.
     *
     * Two and not more because a broadcast naming three squads' worth of players is no longer a
     * fixture being commentated but the round-up after it, and the list has to stay short enough that
     * the scene terms which opened it remain audible (see [FOOTBALL_HOTWORDS]). A cap rather than a
     * refusal is what stops the context graph growing with every club a long window happens to
     * mention — and it is the RANK, not the cap, that decides which two ([teamMentionHits]).
     */
    internal const val TEAM_FIXTURE_SIZE = 2

    /**
     * How hard the decoder is pushed toward each tier of the SCOPED football list, written onto the
     * hotword as the ` :score` suffix sherpa-onnx's parser reads (see [scored]). The tiers are ranked
     * the way a commentary is: the two clubs on the pitch first (the words a football scene is
     * recognised from — get these wrong and nothing else is decodable), then the fixture's own
     * players (the names that go stale and that the general model flattens hardest), then the core
     * competition/terminology vocabulary.
     *
     * The player score is the recognizer's own global default (`KrokoStreamingRecognizer`'s fixed
     * `HOTWORDS_SCORE`) — the value the biasing was measured at, which fixed `Salace`/`Osullivan`
     * without disturbing ordinary speech — so only the clubs ride above it and only the terms sit
     * below. The unscoped baseline carries no per-word scores at all and uses that global score
     * throughout.
     */
    private const val HOTWORD_WEIGHT_CLUB = 4.0f
    private const val HOTWORD_WEIGHT_PLAYER = 3.0f
    private const val HOTWORD_WEIGHT_TERM = 2.5f

    /** A character a table token must not sit inside: a letter, digit, apostrophe or hyphen. */
    private const val EDGE = "[\\p{L}\\p{N}'’-]"

    /** The shortest surname [correctNames] matches a near-miss against, and why (see it). */
    private const val NAME_FIX_MIN_CHARS = 5

    /**
     * The bundled list of the commonest English words, one per line (`common-english-words.txt`).
     * Declared here because the file path is not user-visible text; the set itself is [COMMON_ENGLISH_WORDS].
     */
    private const val COMMON_ENGLISH_WORDS_RESOURCE = "common-english-words.txt"

    /**
     * Every word of [COMMON_ENGLISH_WORDS_RESOURCE], read once and lower-cased. This is the veto the
     * bare-surname repair ([correctNames]) consults before it rewrites a capitalised token.
     *
     * The token pass can only ever see a word in isolation, and on that evidence a one-substitution
     * near-miss is as likely to be ordinary English as a slip of a squad surname: `While` is one edit
     * from Arsenal's `White`, and rewriting it would corrupt perfectly good commentary. Requiring the
     * candidate to be a real word is the cheapest honest guard — a name the recogniser flattens
     * (`Sakho`, `Kovacik`) is not in this list, while the English word it collides with is. The
     * first-letter rule and the uniqueness rule still apply on top; this only removes the ordinary
     * words the collision was measured on.
     *
     * Read lazily and defensively: if the resource is ever missing from the classpath the set is
     * empty, which is exactly the pre-veto behaviour, so a packaging change can never make [correctNames]
     * throw.
     */
    private val COMMON_ENGLISH_WORDS: Set<String> by lazy {
        SubtitleGlossary::class.java.classLoader
            ?.getResourceAsStream(COMMON_ENGLISH_WORDS_RESOURCE)
            ?.bufferedReader()
            ?.useLines { lines ->
                lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
            }
            ?: emptySet()
    }

    /**
     * A capitalised word [correctNames] may repair: a leading capital and at least four more
     * lower-case letters — the shape of a surname. The lookaround keeps it off the tail of a word
     * that carries an apostrophe or hyphen (`O'Sullivan`'s `Sullivan`) and off a longer token.
     */
    private val NAME_TOKEN_PATTERN = Regex("(?<![\\p{L}'-])[A-Z][a-z]{4,}(?![\\p{L}])")

    /**
     * One word of a name as it is WRITTEN: a run of letters. An apostrophe or hyphen is not a letter,
     * so it reads as a separator and `O'Riley` and `Lewis-Potter` break into the same runs the table
     * stores them as. The capitalisation is kept, because a capital is the recogniser's own signal
     * that it heard a proper noun (see [correctWholeNames]).
     */
    private val NAME_WORD_PATTERN = Regex("[\\p{L}]+")

    /** How a name is split into its runs: on anything that is not a letter or a digit. */
    private val NAME_RUN_SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /**
     * How far the half of a name that is NOT heard right may sit from the squad's spelling when the
     * other half pins the man ([nameHalvesCorrectEachOther]). One edit would only repeat the rule
     * that already exists; two is what recovers the recogniser's common double slip (`Sakho` for
     * `Saka`) without letting an unrelated word through — the first letter is still required to match.
     */
    private const val HALF_MAX_EDITS = 2

    /** A score numeral as SPOKEN: a digit or a spelled number. */
    private const val SPOKEN_NUMBER =
        "\\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"

    /**
     * A score numeral as HEARD. The streaming recogniser's thin context cannot tell the homophones
     * apart, so on a bare scoreline `two` arrives as `to`/`too` and `one` as `won`. Only ever read
     * as part of a scoreline pair (see [normaliseScoreline]), so an ordinary `won` or `to`
     * elsewhere is never reinterpreted.
     */
    private val HEARD_NUMBER = "(?:$SPOKEN_NUMBER|to|too|won)"

    /** Every spelling a football scoreline's `nil` has been heard as (`Nill`, `Neil`, `Nile`). */
    private const val NIL_WORD = "(?:nil|nill|neil|neill|nile)"

    /**
     * @param match lower-case source token(s); matched whole-word and case-insensitively, so `pot`
     *   never matches inside `potential`.
     * @param source the unambiguous form handed to the translator; "" leaves the words as spoken.
     * @param target the pinned rendering, used by [promptSection]; "" means the entry only
     *   disambiguates the source.
     * @param misheard recognition slips repaired by [correct] — whole-word, case-insensitively, each
     *   rewritten to [match]. Keep them to forms that cannot be meant literally (`trunk` is gated
     *   behind snooker for that reason); an ANCHOR entry's slips are applied without an anchor, so a
     *   real word there would rewrite ordinary television.
     */
    private class Entry(
        val sport: Sport,
        val kind: Kind,
        val match: String,
        val source: String = "",
        val target: String = "",
        val misheard: List<String> = emptyList(),
        /** True when a score numeral directly beside [match] proves the sport on its own. */
        val provenByScoreline: Boolean = false,
    ) {
        val pattern: Regex = wordPattern(match)

        /**
         * [pattern] widened to consume a [source]'s given name when the window already carries one,
         * so [disambiguate] rewrites a name spoken in full to itself instead of expanding its
         * surname a second time. See [fullNamePattern].
         */
        val disambiguationPattern: Regex = fullNamePattern(match, source)

        /** Each slip paired with the entry's own spelling, for [correct]. */
        val misheardPatterns: List<Pair<Regex, String>> = misheard.map { wordPattern(it) to match }
    }

    private val ENTRIES = listOf(
        // --- Football -------------------------------------------------------------------------
        Entry(Sport.FOOTBALL, Kind.ANCHOR, "offside", target = "越位"),
        Entry(Sport.FOOTBALL, Kind.ANCHOR, "penalty", target = "点球"),
        Entry(Sport.FOOTBALL, Kind.ANCHOR, "penalties", target = "点球"),
        Entry(Sport.FOOTBALL, Kind.ANCHOR, "hat trick", target = "帽子戏法"),
        Entry(Sport.FOOTBALL, Kind.ANCHOR, "hat-trick", target = "帽子戏法"),
        Entry(Sport.FOOTBALL, Kind.ANCHOR, "derby", target = "德比"),
        Entry(Sport.FOOTBALL, Kind.ANCHOR, "salah", source = "Mohamed Salah", target = "萨拉赫"),
        Entry(Sport.FOOTBALL, Kind.ANCHOR, "mbappe", source = "Kylian Mbappé", target = "姆巴佩",
            misheard = listOf("mbape")),
        Entry(Sport.FOOTBALL, Kind.ANCHOR, "mbappé", source = "Kylian Mbappé", target = "姆巴佩"),
        Entry(Sport.FOOTBALL, Kind.ANCHOR, "haaland", source = "Erling Haaland", target = "哈兰德",
            misheard = listOf("haland")),
        // "two nil" is a scoreline, not the word "nothing": the numeral beside it proves football
        // even when the fragment carries no anchor word, so a bare scoreline is still pinned to 零.
        Entry(Sport.FOOTBALL, Kind.GATED, "nil", target = "零", provenByScoreline = true),

        // --- Tennis ---------------------------------------------------------------------------
        Entry(Sport.TENNIS, Kind.ANCHOR, "deuce", target = "平分"),
        Entry(Sport.TENNIS, Kind.ANCHOR, "tiebreak", target = "抢七"),
        Entry(Sport.TENNIS, Kind.ANCHOR, "tie-break", target = "抢七"),
        Entry(Sport.TENNIS, Kind.ANCHOR, "tie break", target = "抢七"),
        Entry(Sport.TENNIS, Kind.ANCHOR, "break point", target = "破发点"),
        Entry(Sport.TENNIS, Kind.ANCHOR, "grand slam", target = "大满贯"),
        Entry(Sport.TENNIS, Kind.ANCHOR, "djokovic", source = "Novak Djokovic", target = "德约科维奇",
            misheard = listOf("jokovic", "jokovich", "djokovich")),
        Entry(Sport.TENNIS, Kind.ANCHOR, "alcaraz", source = "Carlos Alcaraz", target = "阿尔卡拉斯",
            misheard = listOf("alcaras", "alcarez")),
        Entry(Sport.TENNIS, Kind.ANCHOR, "nadal", source = "Rafael Nadal", target = "纳达尔"),
        // "sinner" is an ordinary noun, so it needs the tennis anchor before it is read as a name.
        Entry(Sport.TENNIS, Kind.GATED, "sinner", source = "Jannik Sinner", target = "辛纳",
            misheard = listOf("sinna", "cinner")),
        // "15 love" is a score; "I love it" is not.
        Entry(Sport.TENNIS, Kind.GATED, "love", target = "零"),
        Entry(Sport.TENNIS, Kind.GATED, "ace", target = "ACE"),

        // --- Snooker --------------------------------------------------------------------------
        Entry(Sport.SNOOKER, Kind.ANCHOR, "snooker", target = "斯诺克"),
        Entry(Sport.SNOOKER, Kind.ANCHOR, "snookered", target = "做斯诺克"),
        Entry(Sport.SNOOKER, Kind.ANCHOR, "o'sullivan", source = "Ronnie O'Sullivan", target = "奥沙利文",
            // The recogniser writes the apostrophe as a word break far more often than it drops it.
            misheard = listOf("o sullivan")),
        Entry(Sport.SNOOKER, Kind.ANCHOR, "o’sullivan", source = "Ronnie O'Sullivan", target = "奥沙利文"),
        Entry(Sport.SNOOKER, Kind.ANCHOR, "osullivan", source = "Ronnie O'Sullivan", target = "奥沙利文"),
        Entry(Sport.SNOOKER, Kind.ANCHOR, "selby", source = "Mark Selby", target = "塞尔比",
            misheard = listOf("selbi")),
        Entry(Sport.SNOOKER, Kind.ANCHOR, "higgins", source = "John Higgins", target = "希金斯",
            misheard = listOf("higgens")),
        Entry(Sport.SNOOKER, Kind.ANCHOR, "century break", target = "单杆破百"),
        // The one that motivated the table: Judd Trump is 特鲁姆普, never the politician. GATED
        // because "trump" alone is also a name outside snooker — and so is its slip "trunk".
        Entry(Sport.SNOOKER, Kind.GATED, "trump", source = "Judd Trump", target = "特鲁姆普",
            misheard = listOf("trunk", "trunck")),
        Entry(Sport.SNOOKER, Kind.GATED, "frame", target = "局"),
        Entry(Sport.SNOOKER, Kind.GATED, "frames", target = "局"),
        Entry(Sport.SNOOKER, Kind.GATED, "pot", target = "打进"),
        Entry(Sport.SNOOKER, Kind.GATED, "cue ball", target = "母球"),
    )

    /**
     * The football vocabulary the decoder is additionally biased toward, ASCII and measured against
     * the shipped model. Coverage is the English top flight (plus the European clubs its sides
     * meet), split so that the ONE group a fixture can make irrelevant can be withheld without
     * disturbing the rest — see [SPORT_HOTWORDS] and [recognitionHotwords]:
     *
     *  - **[FOOTBALL_CLUBS]** — every club name the table knows. Proper nouns the model's general
     *    vocabulary does not carry at all (`Tottenham` came back as `Totten and Am`, and the club
     *    name is what makes a scoreline readable). A club with a common short form carries both
     *    spellings; [FOOTBALL_EVIDENCE] matches whole words, so `West Brom` still wins its own word
     *    without firing inside `West Bromwich Albion`.
     *  - **[FOOTBALL_COMMON]** — competitions, grounds and the terminology a commentary is made of
     *    (`offside`, `VAR`, `hat trick`), including this model's documented near-misses (`offside` →
     *    `off side`, `VAR` → `bar`). Every terminology entry here is also [FOOTBALL_EVIDENCE], so it
     *    is kept to phrases that *prove* football: the generic halves `half time` / `second half`
     *    were tried and left out, having dragged ordinary speech (`have gone` → `half gun`).
     *  - **[TEAM_PLAYERS]** — the squads, keyed by club, ASCII with the accents dropped (`Odegaard`,
     *    `Szoboszlai`). They are the ONLY group a broadcast can make relevant, and they are therefore
     *    NOT in the unscoped list at all: once [SportSceneDetector] has named the clubs on the pitch,
     *    those clubs' spoken surnames enter the decoder and no other squad does — see
     *    [footballHotwords] and [spokenHotwords] for why shipping the ~300 surnames unscoped would
     *    abort the very scene recognition the bias is gated on. A squad that goes stale with every
     *    transfer window is refreshed by [recognitionHotwords] rather than defended.
     *  - **`nil`** — the scoreline word itself, which turns a bare `nil nil` into a pair of near-nil
     *    tokens instead of `No, no` for [normaliseScoreline] to settle.
     *
     * A "marquee" group of names that ride every football list regardless of fixture used to sit
     * beside `nil`. It is gone: every one of its entries belonged to a club, so it was a second,
     * unscopable copy of what [TEAM_PLAYERS] already held — one that no broadcast could ever retire
     * and that therefore cost every decode its full length even in the one match it was written for.
     * A name that genuinely belongs to no club (a retired player in the studio, a foreign league the
     * table does not carry) is not worth a permanent entry; it is [correct] and [disambiguate]'s job
     * to repair, not the decoder's to be biased toward. See [TEAM_FIXTURE_SIZE] for how the named
     * clubs are capped now that belonging to a club is what makes a name loadable.
     *
     * Length is the price. Every entry joins the context graph the decoder walks each frame, and
     * the 3.0 score the biasing was measured at assumed a far shorter list, so growth is capped by
     * the on-screen `dec avg` (`KrokoStreamingRecognizer`'s diagnostics) — terminology is what to
     * trim first, clubs last, if a low-end box can no longer keep up. Spending the squads on the
     * named clubs alone is what keeps that length down without giving up a correction the broadcast
     * actually needed (see [recognitionHotwords]).
     *
     * The unscoped baseline is reassembled by [FOOTBALL_HOTWORDS] from this list and
     * [FOOTBALL_COMMON]. Once a fixture is proved, the SCOPED list ([footballHotwords]) narrows to
     * the two clubs that are actually playing — filtered out of this list by [clubHotwords] — so the
     * other clubs still carry the scene vocabulary but no longer ride a match they are not in.
     */
    private val FOOTBALL_CLUBS = listOf(
        // --- Premier League clubs, current and recent -------------------------------------------
        "Arsenal",
        "Aston Villa",
        "Bournemouth",
        "Brentford",
        "Brighton and Hove Albion",
        "Brighton",
        "Burnley",
        "Cardiff City",
        "Chelsea",
        "Coventry City",
        "Crystal Palace",
        "Everton",
        "Fulham",
        "Hull City",
        "Ipswich Town",
        "Ipswich",
        "Leeds United",
        "Leeds",
        "Leicester City",
        "Leicester",
        "Liverpool",
        "Luton Town",
        "Luton",
        "Manchester City",
        "Man City",
        "Manchester United",
        "Man United",
        "Man Utd",
        "Middlesbrough",
        "Newcastle United",
        "Newcastle",
        "Norwich City",
        "Norwich",
        "Nottingham Forest",
        "Sheffield United",
        "Southampton",
        "Stoke City",
        "Sunderland",
        "Swansea City",
        "Tottenham Hotspur",
        "Tottenham",
        "Watford",
        "West Bromwich Albion",
        "West Brom",
        "West Ham United",
        "West Ham",
        "Wolverhampton Wanderers",
        "Wolves",
        // --- Championship and lower-league clubs a cup tie or a round-up puts on screen ------------
        // ASCII and accent-free like the rest of the table; a surname the model flattens is not at
        // stake here, but the recogniser still writes these the way it writes the spoken form.
        "Sheffield Wednesday",
        "Queens Park Rangers",
        "Bristol City",
        "Blackburn Rovers",
        "Preston North End",
        "Derby County",
        "Stoke City",
        "Millwall",
        "Portsmouth",
        "Charlton Athletic",
        "Bolton Wanderers",
        "Blackpool",
        "Wigan Athletic",
        "Huddersfield Town",
        "Rotherham United",
        "Oxford United",
        "Peterborough United",
        "Wycombe Wanderers",
        // --- European clubs a Premier League side meets in Europe ---------------------------------
        "Real Madrid",
        "Atletico Madrid",
        "Barcelona",
        "Bayern Munich",
        "Borussia Dortmund",
        "Paris Saint Germain",
        "Juventus",
        "Inter Milan",
        "AC Milan",
        "Napoli",
        "Roma",
        "Ajax",
        "Porto",
        "Benfica",
        "Sporting Lisbon",
        // The rest of the continental sides that turn up in a European night — the annual
        // group-stage names rather than a full league table, which the ~200-entry cost of the
        // unscoped list (see [FOOTBALL_HOTWORDS]) does not allow.
        "Atalanta",
        "Lazio",
        "Fiorentina",
        "Sevilla",
        "Real Sociedad",
        "Villarreal",
        "Valencia",
        "Bayer Leverkusen",
        "RB Leipzig",
        "Eintracht Frankfurt",
        "Borussia Monchengladbach",
        "Wolfsburg",
        "Marseille",
        "Lyon",
        "Monaco",
        "Lille",
        "Feyenoord",
        "PSV Eindhoven",
        "Galatasaray",
        "Fenerbahce",
        "Besiktas",
        "Celtic",
        "Rangers",
        "Shakhtar Donetsk",
        "Red Star Belgrade",
        "Olympiacos",
        "Club Brugge",
        "Anderlecht",
        "Young Boys",
        "Salzburg",
        "Sparta Prague",
        "Copenhagen",
    )

    /**
     * The football vocabulary that is never scoped to a fixture: the competitions, the grounds and
     * the calls a commentary is made of. None of it names a squad, so none of it can be wrong for
     * the match on screen, and all of it stays on the UNscoped baseline whatever the two clubs turn
     * out to be. Once a fixture is proved the scoped list keeps only its [FOOTBALL_CORE] slice, so
     * the decoder carries the calls of the game rather than this whole table.
     */
    private val FOOTBALL_COMMON = listOf(
        // --- Competitions -------------------------------------------------------------------------
        "Premier League",
        "Champions League",
        "Europa League",
        "Conference League",
        "FA Cup",
        "League Cup",
        "Carabao Cup",
        "Community Shield",
        "World Cup",
        "European Championship",
        "Nations League",
        // --- Grounds a commentary names -----------------------------------------------------------
        "Anfield",
        "Old Trafford",
        "Emirates Stadium",
        "Etihad Stadium",
        "Stamford Bridge",
        "Goodison Park",
        "Villa Park",
        "St James Park",
        "Selhurst Park",
        "London Stadium",
        "Molineux",
        "Craven Cottage",
        "City Ground",
        "Elland Road",
        "Turf Moor",
        "Stadium of Light",
        "Vitality Stadium",
        // The rest of the English grounds a match or a round-up names. A commentary says the ground
        // far more often than it says the competition, and a long spell of play is often anchored by
        // the venue alone (`Anfield is rocking`), so the list is worth carrying beyond the seventeen
        // the decoder opened with. All ASCII and apostrophe-free, like the rest of the table.
        "Tottenham Hotspur Stadium",
        "Bramall Lane",
        "Hillsborough",
        "The Hawthorns",
        "Vicarage Road",
        "Portman Road",
        "Carrow Road",
        "King Power Stadium",
        "Kenilworth Road",
        "St Marys Stadium",
        "Riverside Stadium",
        "Deepdale",
        "Ewood Park",
        "Loftus Road",
        // --- Grounds a European night names -------------------------------------------------------
        // The venues a Premier League side meets on the continent; said as themselves, and often the
        // only football word in a window that names no other term.
        "Camp Nou",
        "Santiago Bernabeu",
        "Bernabeu",
        "Allianz Arena",
        "Signal Iduna Park",
        "Westfalenstadion",
        "Parc des Princes",
        "San Siro",
        "Giuseppe Meazza",
        "Stadio Olimpico",
        "Wanda Metropolitano",
        "Mestalla",
        "Estadio da Luz",
        "Jose Alvalade",
        "Johan Cruyff Arena",
        "De Kuip",
        "Celtic Park",
        "Ibrox",
        "Maracana",
        "La Bombonera",
        "Estadio Azteca",
        // --- Terminology --------------------------------------------------------------------------
        // The calls and states a commentary repeats. Kept to phrases that prove football, because
        // each is also FOOTBALL_EVIDENCE: the generic halves ("half time", "second half") were
        // measured to corrupt ordinary speech and stay out.
        "offside",
        "offside trap",
        "free kick",
        "penalty",
        "penalty area",
        "penalty spot",
        "penalty box",
        "penalty kick",
        "penalty shootout",
        "spot kick",
        "own goal",
        "hat trick",
        "VAR",
        "video assistant referee",
        "goal line",
        "goalkeeper",
        "goalmouth",
        "six yard box",
        "eighteen yard box",
        "centre circle",
        "halfway line",
        "set piece",
        "counter attack",
        "clean sheet",
        "injury time",
        "stoppage time",
        "added time",
        "extra time",
        "first leg",
        "second leg",
        "aggregate",
        "group stage",
        "knockout",
        "quarter final",
        "semi final",
        "relegation",
        "promotion",
        "title race",
        "transfer window",
        "deadline day",
        "matchday",
        "derby",
        "UEFA",
        "FIFA",
        "near post",
        "far post",
        "crossbar",
        "woodwork",
        "one on one",
        "through ball",
        "long ball",
        "high line",
        "back four",
        "wing back",
        "handball",
        "booking",
        "yellow card",
        "red card",
        "sending off",
        "straight red",
        "substitution",
        "volley",
        "tap in",
        "deflection",
        "super sub",
        "man of the match",
        "golden boot",
        "golden glove",
        "bicycle kick",
        "overhead kick",
        "equaliser",
        "throw in",
        "goal kick",
        "corner kick",
        "goal difference",
        "points deduction",
        "brace",
        "play-off",
    )

    /**
     * The SLICE of [FOOTBALL_COMMON] that rides the SCOPED list — the competition and terminology
     * vocabulary a fixture's commentary is actually made of, rather than the whole table.
     *
     * The scoped list is built from the match on screen ([footballHotwords]), and the user's brief is
     * explicit: ~50–120 core words from the two clubs, their squads and the calls of the game — not
     * the ~160-term global table plus both squads. The club names are the loudest tier and the two
     * squads the bulk, so this list is what keeps the total inside that budget without giving up the
     * biasing a live commentary leans on. Every entry is drawn from [FOOTBALL_COMMON] (and therefore
     * from [FOOTBALL_EVIDENCE]), so a scoped decode still hears the calls that keep the scene open.
     *
     * The rest of [FOOTBALL_COMMON] is not lost: it still forms the UNscoped baseline and the
     * [FOOTBALL_EVIDENCE] proof, which is what recognises a football scene before any club is named.
     * Only the list handed to the decoder once a fixture is proved is narrowed here.
     */
    private val FOOTBALL_CORE = listOf(
        "Premier League",
        "Champions League",
        "FA Cup",
        "Europa League",
        "offside",
        "offside trap",
        "free kick",
        "penalty",
        "goal kick",
        "corner kick",
        "goalkeeper",
        "penalty shootout",
        "spot kick",
        "own goal",
        "hat trick",
        "VAR",
        "set piece",
        "counter attack",
        "clean sheet",
        "injury time",
        "stoppage time",
        "extra time",
        "derby",
        "handball",
        "yellow card",
        "red card",
        "substitution",
        "equaliser",
    )

    /**
     * The manager of each club the table carries, as a commentary names him.
     *
     * A touchline name is spoken as often as a squad member's and is flattened by the recogniser the
     * same way, so it belongs on the decoder's list beside the squads. It is deliberately NOT a
     * [TEAM_PLAYERS] entry: a manager is not a squad member, so he must not become a surname the
     * whole-name or near-miss repair ([correctNames]) can rewrite a player to, nor a body
     * [squadSidesIn] can scope a club by. He rides the hotword lists and, through them,
     * [KNOWN_TOKENS] — which is bias and protection, nothing more.
     *
     * Maintained by hand and goes stale the same way the squads do: a sacking moves a name to another
     * club's row, and the fix is to edit it. Written ASCII, accent-free, exactly like the squads.
     */
    internal val FOOTBALL_MANAGERS: Map<Team, String> = mapOf(
        Team.ARSENAL to "Mikel Arteta",
        Team.ASTON_VILLA to "Unai Emery",
        Team.BOURNEMOUTH to "Marco Rose",
        Team.BRENTFORD to "Keith Andrews",
        Team.BRIGHTON to "Fabian Hurzeler",
        Team.CHELSEA to "Xabi Alonso",
        Team.COVENTRY to "Frank Lampard",
        Team.CRYSTAL_PALACE to "Pierre Sage",
        Team.EVERTON to "David Moyes",
        Team.FULHAM to "Alvaro Arbeloa",
        Team.HULL to "Sergej Jakirovic",
        Team.IPSWICH to "Gary O'Neil",
        Team.LEEDS to "Daniel Farke",
        Team.LIVERPOOL to "Andoni Iraola",
        Team.MANCHESTER_CITY to "Enzo Maresca",
        Team.MANCHESTER_UNITED to "Michael Carrick",
        Team.NEWCASTLE to "Matthias Jaissle",
        Team.NOTTINGHAM_FOREST to "Oliver Glasner",
        Team.SUNDERLAND to "Regis Le Bris",
        Team.TOTTENHAM to "Roberto De Zerbi",
    )

    /**
     * The match officials a commentary names, as they are spoken. Not tied to a fixture, so they ride
     * every football list the way [FOOTBALL_COMMON] does — a referee is named in a passage about a
     * decision, and the whole point of the entry is that the name is heard and, being in
     * [KNOWN_TOKENS], is not mistaken for a slip of a squad surname.
     *
     * A referee is named far less often than a player and the group turns over slowly, so the list is
     * short and hand-maintained: the current Select Group, minus the officials who only run the line
     * or the VAR room and so are almost never said by name.
     */
    private val FOOTBALL_REFEREES: List<String> = listOf(
        "Michael Oliver",
        "Chris Kavanagh",
        "Sam Barrott",
        "Darren England",
        "Jarred Gillett",
        "Paul Tierney",
        "Craig Pawson",
        "Simon Hooper",
        "Rob Jones",
        "Andy Madley",
        "Tim Robinson",
        "Michael Salisbury",
        "Lewis Smith",
        "Tom Kirk",
        "John Brooks",
        "Rebecca Welch",
    )

    /**
     * The managers and referees together, in the order the unscoped decoder list carries them — the
     * touchline and officialdom half of a football broadcast that [FOOTBALL_CLUBS] and
     * [FOOTBALL_COMMON] do not otherwise name.
     */
    private val FOOTBALL_OFFICIALS: List<String> = FOOTBALL_MANAGERS.values + FOOTBALL_REFEREES

    /**
     * Each club's home ground as a commentary names it, keyed by club — the venue form of the club's
     * name. A long spell of play is often anchored by the ground alone (`Anfield is rocking`), so a
     * proved fixture has to keep its own venue on the scoped decoder list: the unscoped baseline
     * carries every ground, and the scoped list replaces it, so without this a match could age back
     * to an empty scene on a passage that names only the stadium.
     *
     * The historic name rides beside the current one where a commentary still uses it (`Goodison
     * Park` for Everton, `Dean Court` for Bournemouth), for the same reason a club carries its short
     * forms. A club's ground the table does not know is simply absent, and the club's own name still
     * carries the bias.
     */
    internal val TEAM_GROUNDS: Map<Team, List<String>> = mapOf(
        Team.ARSENAL to listOf("Emirates Stadium"),
        Team.ASTON_VILLA to listOf("Villa Park"),
        Team.BOURNEMOUTH to listOf("Vitality Stadium", "Dean Court"),
        Team.BRENTFORD to listOf("Gtech Community Stadium", "Brentford Community Stadium"),
        Team.BRIGHTON to listOf("Amex Stadium", "Falmer Stadium"),
        Team.CHELSEA to listOf("Stamford Bridge"),
        Team.COVENTRY to listOf("Coventry Building Society Arena"),
        Team.CRYSTAL_PALACE to listOf("Selhurst Park"),
        Team.EVERTON to listOf("Hill Dickinson Stadium", "Goodison Park"),
        Team.FULHAM to listOf("Craven Cottage"),
        Team.HULL to listOf("MKM Stadium"),
        Team.IPSWICH to listOf("Portman Road"),
        Team.LEEDS to listOf("Elland Road"),
        Team.LIVERPOOL to listOf("Anfield"),
        Team.MANCHESTER_CITY to listOf("Etihad Stadium"),
        Team.MANCHESTER_UNITED to listOf("Old Trafford"),
        Team.NEWCASTLE to listOf("St James Park"),
        Team.NOTTINGHAM_FOREST to listOf("City Ground"),
        Team.SUNDERLAND to listOf("Stadium of Light"),
        Team.TOTTENHAM to listOf("Tottenham Hotspur Stadium"),
    )

    /**
     * The squads, keyed by the club the players play for, ASCII and accent-free. Player names are
     * what the recogniser flattens hardest, and this group is the one that goes stale — a transfer
     * window moves a name to another club's block, and the fix is to edit it, not to defend it.
     *
     * It is also the only group a broadcast can make relevant. A commentary in one fixture never
     * names the other squads, so the squads do not ride the unscoped decoder list at all — the words
     * that OPEN a football scene must be heard without them (see [FOOTBALL_HOTWORDS]) — and once
     * [SportSceneDetector] has named the clubs on the pitch, [recognitionHotwords] adds theirs: the
     * decoder walks a context graph of at most two squads where the unscoped list carried none. The
     * blocks are written in the order the scoped list reassembles them.
     *
     * Each block is the club's SENIOR core, not the full registered list — the matchday names a
     * commentary is made of, plus the squad players who spell worst. Teenagers carried only for
     * registration depth are left out: they cost every decode their length and a commentary does not
     * say them, and dropping one costs at most the bias on a name that was never going to be spoken.
     * When in doubt, keep the player; a withheld entry is invisible, a missing one is not.
     *
     * A club is listed here only when the table also carries its squad — a European side whose
     * players are not written down has no squad to hand over, so a fixture it plays in simply
     * decodes unscoped. That is the intended failure direction: an unscoped list is exactly what
     * the channel was biased with before scoping existed.
     */
    internal val TEAM_PLAYERS: Map<Team, List<String>> = mapOf(
        // --- Arsenal
        Team.ARSENAL to listOf(
            "David Raya",
            "William Saliba",
            "Gabriel Magalhaes",
            "Ben White",
            "Jurrien Timber",
            "Piero Hincapie",
            "Bukayo Saka",
            "Martin Odegaard",
            "Eberechi Eze",
            "Declan Rice",
            "Martin Zubimendi",
            "Bruno Guimaraes",
            "Mikel Merino",
            "Kai Havertz",
            "Viktor Gyokeres",
            "Riccardo Calafiori",
        ),

        // --- Aston Villa
        Team.ASTON_VILLA to listOf(
            "Zion Suzuki",
            "Matty Cash",
            "Victor Lindelof",
            "Tyrone Mings",
            "Taylor Harwood-Bellis",
            "Pau Torres",
            "Ian Maatsen",
            "Boubacar Kamara",
            "John McGinn",
            "Amadou Onana",
            "Leon Goretzka",
            "Emiliano Buendia",
            "Nicolas Jackson",
            "Alejandro Garnacho",
            "Tammy Abraham",
            "Joao Gomes",
        ),

        // --- Bournemouth
        Team.BOURNEMOUTH to listOf(
            "Djordje Petrovic",
            "Julian Araujo",
            "Adrien Truffert",
            "Lewis Cook",
            "James Hill",
            "David Brooks",
            "Alex Scott",
            "Evanilson",
            "Ryan Christie",
            "Tyler Adams",
            "Antonio Silva",
            "Adam Smith",
            "Marcus Tavernier",
            "Bafode Diakite",
            "Justin Kluivert",
            "Amine Adli",
        ),

        // --- Brentford
        Team.BRENTFORD to listOf(
            "Caoimhin Kelleher",
            "Aaron Hickey",
            "Rico Henry",
            "Sepp van den Berg",
            "Nathan Collins",
            "Kristoffer Ajer",
            "Yehor Yarmoliuk",
            "Mathias Jensen",
            "Mikkel Damsgaard",
            "Vitaly Janelt",
            "Kevin Schade",
            "Igor Thiago",
            "Dango Ouattara",
            "Callum Wilson",
            "Fabio Carvalho",
            "Keane Lewis-Potter",
        ),

        // --- Brighton
        Team.BRIGHTON to listOf(
            "Bart Verbruggen",
            "Jason Steele",
            "Lewis Dunk",
            "Pascal Struijk",
            "Jaouen Hadjam",
            "Ferdi Kadioglu",
            "Mats Wieffer",
            "Yasin Ayari",
            "Jack Hinshelwood",
            "Diego Gomez",
            "Kaoru Mitoma",
            "Georginio Rutter",
            "Yankuba Minteh",
            "Stefanos Tzimas",
            "Evan Ferguson",
            "Matt O'Riley",
        ),

        // --- Chelsea
        Team.CHELSEA to listOf(
            "Emiliano Martinez",
            "Reece James",
            "Levi Colwill",
            "Wesley Fofana",
            "Maxence Lacroix",
            "Malo Gusto",
            "Moises Caicedo",
            "Romeo Lavia",
            "Jordan Henderson",
            "Cole Palmer",
            "Pedro Neto",
            "Morgan Rogers",
            "Danny Welbeck",
            "Joao Pedro",
            "Jamie Gittens",
            "Estevao",
        ),

        // --- Coventry City
        Team.COVENTRY to listOf(
            "Ethan Pinnock",
            "Bobby Thomas",
            "Jay Dasilva",
            "Jake Bidwell",
            "Joel Latibeaudiere",
            "Milan van Ewijk",
            "Matt Grimes",
            "Josh Eccles",
            "Jack Rudoni",
            "Victor Torp",
            "Gustavo Hamer",
            "Tatsuhiro Sakamoto",
            "Ephron Mason-Clark",
            "Ellis Simms",
            "Haji Wright",
            "Brandon Thomas-Asante",
        ),

        // --- Crystal Palace
        Team.CRYSTAL_PALACE to listOf(
            "Dean Henderson",
            "Tyrick Mitchell",
            "Chadi Riad",
            "Axel Disasi",
            "Chris Richards",
            "Jaydee Canvot",
            "Ben Chilwell",
            "Adam Wharton",
            "Will Hughes",
            "Daichi Kamada",
            "Jefferson Lerma",
            "Ismaila Sarr",
            "Yeremy Pino",
            "Dwight McNeil",
            "Jean-Philippe Mateta",
            "Eddie Nketiah",
        ),

        // --- Everton
        Team.EVERTON to listOf(
            "Jordan Pickford",
            "Jarrad Branthwaite",
            "Michael Keane",
            "James Tarkowski",
            "Jake O'Brien",
            "Vitalii Mykolenko",
            "Ainsley Maitland-Niles",
            "Kiernan Dewsbury-Hall",
            "James Garner",
            "Christian Norgaard",
            "Charly Alcaraz",
            "Jack Grealish",
            "Thierno Barry",
            "Brennan Johnson",
            "Tyler Dibling",
            "Tyrique George",
        ),

        // --- Fulham
        Team.FULHAM to listOf(
            "Bernd Leno",
            "Kenny Tete",
            "Calvin Bassey",
            "Joachim Andersen",
            "Jorge Cuenca",
            "Timothy Castagne",
            "Antonee Robinson",
            "Sander Berge",
            "Harrison Reed",
            "Tom Cairney",
            "Alex Iwobi",
            "Emile Smith Rowe",
            "Oscar Bobb",
            "Rodrigo Muniz",
            "Hugo Larsson",
            "Ryan Sessegnon",
        ),

        // --- Hull City
        Team.HULL to listOf(
            "Jack Butland",
            "Lewie Coyle",
            "Ryan Giles",
            "Charlie Hughes",
            "Semi Ajayi",
            "John Egan",
            "Matt Targett",
            "Paddy McNair",
            "Hidemasa Morita",
            "Eliot Matazo",
            "Sorba Thomas",
            "Regan Slater",
            "Joe Gelhardt",
            "Oliver McBurnie",
            "Mohamed Belloumi",
            "Tim Iroegbunam",
        ),

        // --- Ipswich Town
        Team.IPSWICH to listOf(
            "Alex Palmer",
            "Darnell Furlong",
            "Leif Davis",
            "Cedric Kipre",
            "Issa Diop",
            "Jacob Greaves",
            "Dara O'Shea",
            "Sasa Lukic",
            "Azor Matusiwa",
            "Jack Taylor",
            "Julio Enciso",
            "Jaden Philogene",
            "Abdul Fatawu",
            "Daizen Maeda",
            "Chuba Akpom",
            "Zian Flemming",
        ),

        // --- Leeds United
        Team.LEEDS to listOf(
            "James Trafford",
            "Jayden Bogle",
            "Joe Rodon",
            "Gabriel Gudmundsson",
            "Jaka Bijol",
            "Nico Elvedi",
            "Ethan Ampadu",
            "Sean Longstaff",
            "Anton Stach",
            "Ilia Gruev",
            "Daniel James",
            "Brenden Aaronson",
            "Harry Wilson",
            "Dominic Calvert-Lewin",
            "Lukas Nmecha",
            "Noah Okafor",
        ),

        // --- Liverpool
        Team.LIVERPOOL to listOf(
            "Alisson Becker",
            "Giorgi Mamardashvili",
            "Joe Gomez",
            "Virgil van Dijk",
            "Milos Kerkez",
            "Conor Bradley",
            "Jeremie Frimpong",
            "Ronald Araujo",
            "Ryan Gravenberch",
            "Alexis Mac Allister",
            "Dominik Szoboszlai",
            "Florian Wirtz",
            "Cody Gakpo",
            "Federico Chiesa",
            "Alexander Isak",
            "Hugo Ekitike",
            "Bradley Barcola",
        ),

        // --- Manchester City
        Team.MANCHESTER_CITY to listOf(
            "Gianluigi Donnarumma",
            "Ruben Dias",
            "Josko Gvardiol",
            "Marc Guehi",
            "Abdukodir Khusanov",
            "Rayan Ait-Nouri",
            "Matheus Nunes",
            "Rico Lewis",
            "Mateo Kovacic",
            "Enzo Fernandez",
            "Elliot Anderson",
            "Rayan Cherki",
            "Phil Foden",
            "Jeremy Doku",
            "Erling Haaland",
            "Antoine Semenyo",
            "Iliman Ndiaye",
        ),

        // --- Manchester United
        Team.MANCHESTER_UNITED to listOf(
            "Senne Lammens",
            "Diogo Dalot",
            "Noussair Mazraoui",
            "Matthijs de Ligt",
            "Harry Maguire",
            "Lisandro Martinez",
            "Leny Yoro",
            "Luke Shaw",
            "Manuel Ugarte",
            "Bruno Fernandes",
            "Kobbie Mainoo",
            "Mason Mount",
            "Matheus Cunha",
            "Bryan Mbeumo",
            "Carlos Baleba",
            "Amad Diallo",
            "Benjamin Sesko",
        ),

        // --- Newcastle
        Team.NEWCASTLE to listOf(
            "Nick Pope",
            "Tino Livramento",
            "Lewis Hall",
            "Sven Botman",
            "Fabian Schar",
            "Malick Thiaw",
            "Dan Burn",
            "Nico Gonzalez",
            "Joelinton",
            "Joe Willock",
            "Harvey Barnes",
            "Anthony Elanga",
            "Jacob Murphy",
            "Yoane Wissa",
            "William Osula",
            "Jacob Ramsey",
        ),

        // --- Nottingham Forest
        Team.NOTTINGHAM_FOREST to listOf(
            "Matz Sels",
            "Neco Williams",
            "Ola Aina",
            "Murillo",
            "Nikola Milenkovic",
            "Ousmane Diomande",
            "Ryan Yates",
            "Ibrahim Sangare",
            "Nicolas Dominguez",
            "Morgan Gibbs-White",
            "Xaver Schlager",
            "James McAtee",
            "Callum Hudson-Odoi",
            "Dan Ndoye",
            "Chris Wood",
            "Liam Delap",
        ),

        // --- Sunderland
        Team.SUNDERLAND to listOf(
            "Robin Roefs",
            "Kevin Danso",
            "Dan Ballard",
            "Omar Alderete",
            "Reinildo Mandava",
            "Nordi Mukiele",
            "Trai Hume",
            "Luke O'Nien",
            "Granit Xhaka",
            "Habib Diarra",
            "Noah Sadiki",
            "Enzo Le Fee",
            "Chris Rigg",
            "Romaine Mundle",
            "Wilson Isidor",
            "Brian Brobbey",
            "Chemsdine Talbi",
        ),

        // --- Tottenham
        Team.TOTTENHAM to listOf(
            "Antonin Kinsky",
            "Micky van de Ven",
            "Jan Paul van Hecke",
            "Marcos Senesi",
            "Andy Robertson",
            "Pedro Porro",
            "Destiny Udogie",
            "Archie Gray",
            "Lucas Bergvall",
            "Sandro Tonali",
            "Conor Gallagher",
            "Xavi Simons",
            "James Maddison",
            "Mohammed Kudus",
            "Dejan Kulusevski",
            "Dominic Solanke",
            "Omar Marmoush",
        ),
    )

    /**
     * The Chinese broadcast rendering of each club the table can scope, keyed by [Team].
     *
     * A club name is the other half of the fixture's names the model has to render, and it is the
     * half with NO entry in [ENTRIES]: the club is only ever a hotword ([FOOTBALL_CLUBS]), so a
     * fragment that names it has nothing to pin the rendering and the model is free to drift
     * (`Brighton` 布莱顿 / 布赖顿, `Newcastle` 纽卡斯尔 / 纽卡斯尔联). Pinning it is the same fix
     * as the squads': the fixture is stable for the match, so the mapping rides every request and is
     * cache-friendly, and only the SCOPED clubs are ever sent (see [clubPromptSection]).
     *
     * The left-hand side is [Team.label] — the short form a commentary says — rather than the long
     * [Team.detect] name, because that is the word the request's own text carries.
     *
     * Coverage is the twenty clubs of [TEAM_PLAYERS]; a club with no squad block cannot be scoped,
     * so it never reaches this table.
     */
    private val CLUB_CHINESE: Map<Team, String> = mapOf(
        Team.ARSENAL to "阿森纳",
        Team.ASTON_VILLA to "阿斯顿维拉",
        Team.BOURNEMOUTH to "伯恩茅斯",
        Team.BRENTFORD to "布伦特福德",
        Team.BRIGHTON to "布莱顿",
        Team.CHELSEA to "切尔西",
        Team.COVENTRY to "考文垂",
        Team.CRYSTAL_PALACE to "水晶宫",
        Team.EVERTON to "埃弗顿",
        Team.FULHAM to "富勒姆",
        Team.HULL to "赫尔城",
        Team.IPSWICH to "伊普斯维奇",
        Team.LEEDS to "利兹联",
        Team.LIVERPOOL to "利物浦",
        Team.MANCHESTER_CITY to "曼城",
        Team.MANCHESTER_UNITED to "曼联",
        Team.NEWCASTLE to "纽卡斯尔联",
        Team.NOTTINGHAM_FOREST to "诺丁汉森林",
        Team.SUNDERLAND to "桑德兰",
        Team.TOTTENHAM to "托特纳姆热刺",
    )

    /**
     * The Chinese broadcast rendering of each squad member, keyed by the full name as [TEAM_PLAYERS]
     * spells it. Read only through [playerPromptSection], which expands the spoken SURNAME, because
     * that is the form a commentary says.
     *
     * A surname alone is often an ordinary English word (`Rice`, `White`, `Hall`, `Gray`, `Cook`,
     * `Mount`, `Wood`), so a fragment the model has to translate without context can render it as the
     * noun instead of the player. The prompt's general "use standard broadcast renderings" rule is not
     * enough on its own — it leaves the choice to the model — so the names the squads on the pitch
     * actually carry are pinned explicitly (see [playerPromptSection]).
     *
     * Coverage is the current squads and the renderings Chinese broadcast uses; a name whose accepted
     * rendering is not known is simply absent, and the model decides it as before rather than being
     * given a guess. Maintained by hand beside [TEAM_PLAYERS]: a transfer moves a name, a rendering
     * does not change.
     */
    internal val PLAYER_CHINESE: Map<String, String> = mapOf(
        // --- Arsenal
        "David Raya" to "拉亚",
        "William Saliba" to "萨利巴",
        "Gabriel Magalhaes" to "马加良斯",
        "Ben White" to "怀特",
        "Jurrien Timber" to "廷伯",
        "Piero Hincapie" to "因卡皮耶",
        "Bukayo Saka" to "萨卡",
        "Martin Odegaard" to "厄德高",
        "Eberechi Eze" to "埃泽",
        "Declan Rice" to "赖斯",
        "Martin Zubimendi" to "苏比门迪",
        "Bruno Guimaraes" to "吉马良斯",
        "Mikel Merino" to "梅里诺",
        "Kai Havertz" to "哈弗茨",
        "Viktor Gyokeres" to "约克雷斯",
        "Riccardo Calafiori" to "卡拉菲奥里",
        // --- Aston Villa
        "Zion Suzuki" to "铃木",
        "Matty Cash" to "卡什",
        "Victor Lindelof" to "林德洛夫",
        "Tyrone Mings" to "明斯",
        "Taylor Harwood-Bellis" to "哈伍德-贝利斯",
        "Pau Torres" to "托雷斯",
        "Ian Maatsen" to "马特森",
        "Boubacar Kamara" to "卡马拉",
        "John McGinn" to "麦金",
        "Amadou Onana" to "奥纳纳",
        "Leon Goretzka" to "格雷茨卡",
        "Emiliano Buendia" to "布恩迪亚",
        "Nicolas Jackson" to "杰克逊",
        "Alejandro Garnacho" to "加纳乔",
        "Tammy Abraham" to "亚伯拉罕",
        "Joao Gomes" to "戈麦斯",
        // --- Bournemouth
        "Djordje Petrovic" to "彼得罗维奇",
        "Julian Araujo" to "阿劳霍",
        "Adrien Truffert" to "特吕费尔",
        "Lewis Cook" to "库克",
        "James Hill" to "希尔",
        "David Brooks" to "布鲁克斯",
        "Alex Scott" to "斯科特",
        "Evanilson" to "埃瓦尼尔森",
        "Ryan Christie" to "克里斯蒂",
        "Tyler Adams" to "亚当斯",
        "Antonio Silva" to "席尔瓦",
        "Adam Smith" to "史密斯",
        "Marcus Tavernier" to "塔弗尼耶",
        "Bafode Diakite" to "迪亚基特",
        "Justin Kluivert" to "克鲁伊维特",
        "Amine Adli" to "阿德利",
        // --- Brentford
        "Caoimhin Kelleher" to "凯莱赫",
        "Aaron Hickey" to "希基",
        "Rico Henry" to "亨利",
        "Sepp van den Berg" to "范登贝尔赫",
        "Nathan Collins" to "柯林斯",
        "Kristoffer Ajer" to "阿耶尔",
        "Yehor Yarmoliuk" to "亚尔莫柳克",
        "Mathias Jensen" to "延森",
        "Mikkel Damsgaard" to "达姆斯高",
        "Vitaly Janelt" to "亚内尔特",
        "Kevin Schade" to "沙德",
        "Igor Thiago" to "蒂亚戈",
        "Dango Ouattara" to "瓦塔拉",
        "Callum Wilson" to "威尔逊",
        "Fabio Carvalho" to "卡瓦略",
        "Keane Lewis-Potter" to "刘易斯-波特",
        // --- Brighton
        "Jason Steele" to "斯蒂尔",
        "Lewis Dunk" to "邓克",
        "Ferdi Kadioglu" to "卡迪奥卢",
        "Mats Wieffer" to "维费尔",
        "Yasin Ayari" to "阿亚里",
        "Jack Hinshelwood" to "欣谢尔伍德",
        "Diego Gomez" to "戈麦斯",
        "Kaoru Mitoma" to "三笘薰",
        "Georginio Rutter" to "鲁特",
        "Stefanos Tzimas" to "齐马斯",
        "Evan Ferguson" to "弗格森",
        "Matt O'Riley" to "奥赖利",
        "Bart Verbruggen" to "费布鲁亨",
        "Pascal Struijk" to "斯特鲁伊克",
        "Jaouen Hadjam" to "哈贾姆",
        "Yankuba Minteh" to "明特",
        // --- Chelsea
        "Emiliano Martinez" to "马丁内斯",
        "Reece James" to "詹姆斯",
        "Levi Colwill" to "科尔威尔",
        "Wesley Fofana" to "福法纳",
        "Maxence Lacroix" to "拉克鲁瓦",
        "Malo Gusto" to "居斯托",
        "Moises Caicedo" to "凯塞多",
        "Romeo Lavia" to "拉维亚",
        "Jordan Henderson" to "亨德森",
        "Cole Palmer" to "帕尔默",
        "Pedro Neto" to "内托",
        "Morgan Rogers" to "罗杰斯",
        "Danny Welbeck" to "维尔贝克",
        "Joao Pedro" to "佩德罗",
        "Jamie Gittens" to "吉滕斯",
        "Estevao" to "埃斯特旺",
        // --- Coventry City
        "Ethan Pinnock" to "平诺克",
        "Bobby Thomas" to "托马斯",
        "Jay Dasilva" to "达席尔瓦",
        "Jake Bidwell" to "比德韦尔",
        "Milan van Ewijk" to "范埃韦克",
        "Matt Grimes" to "格兰姆斯",
        "Josh Eccles" to "埃克尔斯",
        "Jack Rudoni" to "鲁多尼",
        "Victor Torp" to "托普",
        "Gustavo Hamer" to "哈默",
        "Tatsuhiro Sakamoto" to "坂本",
        "Ephron Mason-Clark" to "梅森-克拉克",
        "Ellis Simms" to "西姆斯",
        "Haji Wright" to "赖特",
        "Brandon Thomas-Asante" to "托马斯-阿桑特",
        "Joel Latibeaudiere" to "拉蒂博迭尔",
        // --- Crystal Palace
        "Dean Henderson" to "亨德森",
        "Tyrick Mitchell" to "米切尔",
        "Chadi Riad" to "里亚德",
        "Axel Disasi" to "迪萨西",
        "Chris Richards" to "理查兹",
        "Ben Chilwell" to "奇尔韦尔",
        "Adam Wharton" to "沃顿",
        "Will Hughes" to "休斯",
        "Daichi Kamada" to "镰田",
        "Jefferson Lerma" to "莱尔马",
        "Ismaila Sarr" to "萨尔",
        "Yeremy Pino" to "皮诺",
        "Dwight McNeil" to "麦克尼尔",
        "Jean-Philippe Mateta" to "马特塔",
        "Eddie Nketiah" to "恩凯提亚",
        "Jaydee Canvot" to "坎沃特",
        // --- Everton
        "Jordan Pickford" to "皮克福德",
        "Jarrad Branthwaite" to "布兰斯韦特",
        "Michael Keane" to "基恩",
        "James Tarkowski" to "塔尔科夫斯基",
        "Jake O'Brien" to "奥布莱恩",
        "Vitalii Mykolenko" to "米科连科",
        "Ainsley Maitland-Niles" to "梅特兰-奈尔斯",
        "Kiernan Dewsbury-Hall" to "杜斯伯里-霍尔",
        "James Garner" to "加纳",
        "Christian Norgaard" to "诺尔高",
        "Charly Alcaraz" to "阿尔卡拉斯",
        "Jack Grealish" to "格拉利什",
        "Thierno Barry" to "巴里",
        "Brennan Johnson" to "约翰逊",
        "Tyler Dibling" to "迪布林",
        "Tyrique George" to "乔治",
        // --- Fulham
        "Bernd Leno" to "莱诺",
        "Kenny Tete" to "特特",
        "Calvin Bassey" to "巴西",
        "Joachim Andersen" to "安德森",
        "Jorge Cuenca" to "昆卡",
        "Timothy Castagne" to "卡斯塔涅",
        "Antonee Robinson" to "罗宾逊",
        "Sander Berge" to "贝格",
        "Harrison Reed" to "里德",
        "Tom Cairney" to "凯尔尼",
        "Alex Iwobi" to "伊沃比",
        "Emile Smith Rowe" to "史密斯·罗",
        "Oscar Bobb" to "博布",
        "Rodrigo Muniz" to "穆尼斯",
        "Hugo Larsson" to "拉尔松",
        "Ryan Sessegnon" to "塞塞尼翁",
        // --- Hull City
        "Jack Butland" to "巴特兰",
        "Lewie Coyle" to "科伊尔",
        "Ryan Giles" to "贾尔斯",
        "Charlie Hughes" to "休斯",
        "Semi Ajayi" to "阿贾伊",
        "John Egan" to "伊根",
        "Matt Targett" to "塔吉特",
        "Paddy McNair" to "麦克奈尔",
        "Hidemasa Morita" to "守田",
        "Eliot Matazo" to "马塔佐",
        "Sorba Thomas" to "托马斯",
        "Regan Slater" to "斯莱特",
        "Joe Gelhardt" to "格尔哈特",
        "Oliver McBurnie" to "麦克伯尼",
        "Mohamed Belloumi" to "贝卢米",
        "Tim Iroegbunam" to "伊罗格布纳姆",
        // --- Ipswich Town
        "Alex Palmer" to "帕尔默",
        "Darnell Furlong" to "弗隆",
        "Leif Davis" to "戴维斯",
        "Cedric Kipre" to "基普雷",
        "Issa Diop" to "迪奥普",
        "Jacob Greaves" to "格里夫斯",
        "Dara O'Shea" to "奥谢",
        "Sasa Lukic" to "卢基奇",
        "Azor Matusiwa" to "马图西瓦",
        "Jack Taylor" to "泰勒",
        "Julio Enciso" to "恩西索",
        "Jaden Philogene" to "菲洛吉尼",
        "Abdul Fatawu" to "法塔乌",
        "Daizen Maeda" to "前田",
        "Chuba Akpom" to "阿克波姆",
        "Zian Flemming" to "弗莱明",
        // --- Leeds United
        "James Trafford" to "特拉福德",
        "Jayden Bogle" to "博格尔",
        "Joe Rodon" to "罗登",
        "Gabriel Gudmundsson" to "古德蒙松",
        "Jaka Bijol" to "比约尔",
        "Nico Elvedi" to "埃尔维迪",
        "Ethan Ampadu" to "安帕杜",
        "Sean Longstaff" to "朗斯塔夫",
        "Anton Stach" to "施塔赫",
        "Ilia Gruev" to "格鲁耶夫",
        "Daniel James" to "詹姆斯",
        "Brenden Aaronson" to "阿伦森",
        "Harry Wilson" to "威尔逊",
        "Dominic Calvert-Lewin" to "卡尔弗特-勒温",
        "Lukas Nmecha" to "恩梅查",
        "Noah Okafor" to "奥卡福",
        // --- Liverpool
        "Alisson Becker" to "贝克尔",
        "Giorgi Mamardashvili" to "马马尔达什维利",
        "Joe Gomez" to "戈麦斯",
        "Virgil van Dijk" to "范戴克",
        "Milos Kerkez" to "克尔凯兹",
        "Conor Bradley" to "布拉德利",
        "Jeremie Frimpong" to "弗林蓬",
        "Ronald Araujo" to "阿劳霍",
        "Ryan Gravenberch" to "赫拉芬贝赫",
        "Alexis Mac Allister" to "麦卡利斯特",
        "Dominik Szoboszlai" to "索博斯洛伊",
        "Florian Wirtz" to "维尔茨",
        "Cody Gakpo" to "加克波",
        "Federico Chiesa" to "基耶萨",
        "Alexander Isak" to "伊萨克",
        "Hugo Ekitike" to "埃基蒂克",
        "Bradley Barcola" to "巴尔科拉",
        // --- Manchester City
        "Gianluigi Donnarumma" to "多纳鲁马",
        "Ruben Dias" to "迪亚斯",
        "Josko Gvardiol" to "格瓦迪奥尔",
        "Marc Guehi" to "格伊",
        "Abdukodir Khusanov" to "胡萨诺夫",
        "Rayan Ait-Nouri" to "艾特-努里",
        "Matheus Nunes" to "努内斯",
        "Rico Lewis" to "刘易斯",
        "Mateo Kovacic" to "科瓦契奇",
        "Enzo Fernandez" to "费尔南德斯",
        "Elliot Anderson" to "安德森",
        "Rayan Cherki" to "谢尔基",
        "Phil Foden" to "福登",
        "Jeremy Doku" to "多库",
        "Erling Haaland" to "哈兰德",
        "Antoine Semenyo" to "塞梅尼奥",
        "Iliman Ndiaye" to "恩迪亚耶",
        // --- Manchester United
        "Senne Lammens" to "拉门斯",
        "Diogo Dalot" to "达洛特",
        "Noussair Mazraoui" to "马兹拉维",
        "Matthijs de Ligt" to "德利赫特",
        "Harry Maguire" to "马奎尔",
        "Lisandro Martinez" to "马丁内斯",
        "Leny Yoro" to "约罗",
        "Luke Shaw" to "肖",
        "Manuel Ugarte" to "乌加特",
        "Bruno Fernandes" to "费尔南德斯",
        "Kobbie Mainoo" to "梅努",
        "Mason Mount" to "芒特",
        "Matheus Cunha" to "库尼亚",
        "Bryan Mbeumo" to "姆贝乌莫",
        "Carlos Baleba" to "巴莱巴",
        "Amad Diallo" to "迪亚洛",
        "Benjamin Sesko" to "塞斯科",
        // --- Newcastle
        "Nick Pope" to "波普",
        "Tino Livramento" to "利夫拉门托",
        "Lewis Hall" to "霍尔",
        "Sven Botman" to "博特曼",
        "Fabian Schar" to "舍尔",
        "Malick Thiaw" to "蒂奥",
        "Dan Burn" to "伯恩",
        "Nico Gonzalez" to "冈萨雷斯",
        "Joelinton" to "乔林顿",
        "Joe Willock" to "威洛克",
        "Harvey Barnes" to "巴恩斯",
        "Anthony Elanga" to "埃兰加",
        "Jacob Murphy" to "墨菲",
        "Yoane Wissa" to "维萨",
        "William Osula" to "奥苏拉",
        "Jacob Ramsey" to "拉姆齐",
        // --- Nottingham Forest
        "Matz Sels" to "塞尔斯",
        "Neco Williams" to "威廉姆斯",
        "Ola Aina" to "艾纳",
        "Murillo" to "穆里略",
        "Nikola Milenkovic" to "米伦科维奇",
        "Ousmane Diomande" to "迪奥曼德",
        "Ryan Yates" to "耶茨",
        "Ibrahim Sangare" to "桑加雷",
        "Nicolas Dominguez" to "多明格斯",
        "Morgan Gibbs-White" to "吉布斯-怀特",
        "Xaver Schlager" to "施拉格",
        "James McAtee" to "麦卡蒂",
        "Callum Hudson-Odoi" to "哈德森-奥多伊",
        "Dan Ndoye" to "恩多耶",
        "Chris Wood" to "伍德",
        "Liam Delap" to "德拉普",
        // --- Sunderland
        "Kevin Danso" to "丹索",
        "Dan Ballard" to "巴拉德",
        "Omar Alderete" to "阿尔德雷特",
        "Reinildo Mandava" to "曼达瓦",
        "Nordi Mukiele" to "穆基莱",
        "Trai Hume" to "休姆",
        "Luke O'Nien" to "奥尼恩",
        "Granit Xhaka" to "扎卡",
        "Habib Diarra" to "迪亚拉",
        "Noah Sadiki" to "萨迪基",
        "Enzo Le Fee" to "勒费",
        "Chris Rigg" to "里格",
        "Romaine Mundle" to "芒德尔",
        "Wilson Isidor" to "伊西多尔",
        "Brian Brobbey" to "布罗贝",
        "Chemsdine Talbi" to "塔尔比",
        "Robin Roefs" to "罗夫斯",
        // --- Tottenham
        "Antonin Kinsky" to "金斯基",
        "Micky van de Ven" to "范德芬",
        "Jan Paul van Hecke" to "范赫克",
        "Marcos Senesi" to "塞内西",
        "Andy Robertson" to "罗伯逊",
        "Pedro Porro" to "波罗",
        "Destiny Udogie" to "乌多吉",
        "Archie Gray" to "格雷",
        "Lucas Bergvall" to "贝里瓦尔",
        "Sandro Tonali" to "托纳利",
        "Conor Gallagher" to "加拉格尔",
        "Xavi Simons" to "西蒙斯",
        "James Maddison" to "麦迪逊",
        "Mohammed Kudus" to "库杜斯",
        "Dejan Kulusevski" to "库卢塞夫斯基",
        "Dominic Solanke" to "索兰克",
        "Omar Marmoush" to "马尔穆什",
    )

    /**
     * The Chinese rendering of a player's GIVEN name, keyed by the given name as [TEAM_PLAYERS]
     * spells it. Read only by [playerPromptSection], and only for the players whose surname
     * rendering collides with a team-mate's or an opponent's inside one fixture.
     *
     * [PLAYER_CHINESE] pins a surname, which is what a commentary usually says — but 13 of the 190
     * fixtures carry two men whose surnames render to the SAME Chinese word (`Fernandez`/`Fernandes`
     * both 费尔南德斯, `Gomes`/`Gomez` both 戈麦斯, and the shared `Wilson`, `Henderson`, `Palmer`
     * …). A `Surname → rendering` line cannot tell those two apart, so the whole name is pinned too,
     * and pinning the whole name needs the given name's rendering: `Bruno Fernandes → 布鲁诺·费尔南
     * 德斯` against `Enzo Fernandez → 恩佐·费尔南德斯`. Chinese broadcast routinely qualifies the
     * given name for exactly this reason.
     *
     * Only the colliding fixtures read this, so a given name absent here costs nothing but the extra
     * line; the plain surname line is always sent. Maintained by hand beside [PLAYER_CHINESE].
     */
    internal val GIVEN_CHINESE: Map<String, String> = mapOf(
        "Alex" to "亚历克斯",
        "Bobby" to "博比",
        "Bruno" to "布鲁诺",
        "Callum" to "卡勒姆",
        "Charlie" to "查理",
        "Cole" to "科尔",
        "Daniel" to "丹尼尔",
        "Dean" to "迪恩",
        "Diego" to "迭戈",
        "Elliot" to "埃利奥特",
        "Emiliano" to "埃米利亚诺",
        "Enzo" to "恩佐",
        "Harry" to "哈里",
        "Joachim" to "约阿希姆",
        "Joao" to "若昂",
        "Joe" to "乔",
        "Jordan" to "乔丹",
        "Julian" to "朱利安",
        "Lisandro" to "利桑德罗",
        "Reece" to "里斯",
        "Ronald" to "罗纳德",
        "Sorba" to "索尔巴",
        "Will" to "威尔",
    )

    /** Each club's [Team.detect] spellings as whole-word matchers, built once from the enum. */
    private val TEAM_NAMES: Map<Team, List<Regex>> = Team.entries.associateWith { team ->
        team.detect.map { wordPattern(it) }
    }

    /**
     * The club names the table knows ([FOOTBALL_CLUBS]) but [Team] cannot scope, as whole-word
     * matchers — every `… United` / `… City` whose club carries no squad block. A commentary names
     * them by the same generic half as Manchester's two (`Leicester City`, `West Ham United`), and
     * because no [Team] form is longer, nothing competes for the range: without this the bare half
     * would be credited to Manchester — a club the commentary never named.
     *
     * Derived from [FOOTBALL_CLUBS] rather than declared again, so a club added there is guarded
     * here too. The covered ones (`Manchester City`, `Hull City`, `Newcastle United`) are left out —
     * their own longer [Team.detect] form already wins the range.
     */
    private val UNSCOPED_CLUB_PATTERNS: List<Regex> = run {
        val scoped = Team.entries.flatMap { it.detect }.toSet()
        FOOTBALL_CLUBS
            .filter { it.endsWith(" United") || it.endsWith(" City") }
            .filter { it !in scoped }
            .map { wordPattern(it) }
    }

    /**
     * The one word a commentary uses in place of a club's whole name (`United`, `City`). A match of
     * one of these is credited to Manchester by [isStandaloneGenericHalf] only when it stands as its
     * OWN name — capitalised, and either at the head of the fresh slice or following a word that is
     * not the proper noun of a longer club name.
     *
     * [UNSCOPED_CLUB_PATTERNS] can only guard the clubs [FOOTBALL_CLUBS] happens to carry, and the
     * names it does not — `Bristol City`, `Oxford United`, a foreign league's `Atlanta United` — are
     * exactly the ones a cup broadcast adds. The guard is what keeps their generic half from being
     * credited to Manchester, without the table having to list every club in the world. The
     * lower-case check is the same signal [squadSidesIn] reads from a surname: the recogniser
     * capitalises a proper noun, so an ordinary `the city council` is not a club.
     */
    private val GENERIC_CLUB_HALVES = setOf("united", "city")

    /**
     * Capitalised words that open a clause rather than name a club: `And United press`, `However
     * City hold on`. The recogniser capitalises the first word of a sentence, so without this
     * exemption a sentence whose subject is the club itself would read as the tail of a club name.
     */
    private val CLAUSE_OPENERS = setOf(
        "and", "but", "or", "so", "then", "now", "yet", "still", "also", "plus", "when", "after",
        "before", "as", "with", "from", "for", "however", "meanwhile", "both", "here", "there",
    )

    /**
     * Tokens that belong to the SURNAME rather than the given name, so [spokenSurname] keeps them.
     * The Dutch / Portuguese / Spanish / French particles the squads actually carry: `van den Berg`,
     * `van Ewijk`, `van Dijk`, `van de Ven`, `van Hecke`, `de Ligt`.
     *
     * Matched lower-case because that is how the table stores them; a capitalised token is a given
     * name or a surname's own first word and ends the walk, which is what keeps `Jan Paul van Hecke`
     * from swallowing `Paul`.
     *
     * Declared before [TEAM_SURNAMES] because that table's initializer reads it — object properties
     * initialize top to bottom, so a particle set placed below it would still be null when the
     * surnames are built.
     */
    private val SURNAME_PARTICLES = setOf(
        "van", "von", "de", "den", "der", "del", "della", "di", "da", "das", "dos", "do", "du",
        "le", "la", "les", "ten", "ter",
    )

    /**
     * Each club's squad reduced to the form a commentary actually says: the surname, matched
     * whole-word. Built once from [TEAM_PLAYERS] so the mention pass compiles nothing per call.
     *
     * The surname is the last whitespace-separated token AND the particles that belong to it
     * ([SURNAME_PARTICLES]) — `van Dijk`, `van de Ven`, `de Ligt`. A bare last token is wrong for
     * every name that carries one: a commentary says `Van Dijk`, not `Dijk`, and the stored
     * `Micky van de Ven` reduces to a bare `Ven` if the particle is dropped. Apostrophes and hyphens
     * read as optional joins so a commentary's spelling variation still matches
     * (`Alexander-Arnold` / `Alexander Arnold`, `O'Nien` / `Onien`). Matching the surname rather than
     * the full name is what a commentary affords: it says `Salah`, not `Mohamed Salah`.
     */
    private val TEAM_SURNAMES: Map<Team, List<SurnameMatcher>> = TEAM_PLAYERS.mapValues { (_, players) ->
        players.map { SurnameMatcher(spokenSurname(it), surnamePattern(it)) }
    }

    /** One squad surname as it is spoken, with the whole-word matcher that finds it in a window. */
    private class SurnameMatcher(val surname: String, val pattern: Regex)

    /**
     * One squad member's FULL name as the runs [correctWholeNames] matches: the spelling the table
     * stores, that spelling split on its separators ([nameRuns]) so the recogniser's flattened form
     * reads the same (`Lewis-Potter` / `Lewis Potter`, `O'Riley` / `O Riley`), and where the GIVEN
     * name ends ([givenRuns]) so the two halves can be compared — and correct each other — separately.
     */
    private class WholeName(val fullName: String, val runs: List<String>, val givenRuns: Int)

    /**
     * Each club's squad as [WholeName]s, built once from [TEAM_PLAYERS] so the pass compiles nothing
     * per call. This is the candidate set [correctWholeNames] repairs against: where [TEAM_SURNAMES]
     * reduces a man to the surname a commentary says, this keeps his whole name, which is the extra
     * context that makes a SHORT or COMPOUND surname repairable at all.
     */
    private val TEAM_WHOLE_NAMES: Map<Team, List<WholeName>> = TEAM_PLAYERS.mapValues { (_, players) ->
        players.mapNotNull { fullName ->
            val runs = nameRuns(fullName)
            if (runs.isEmpty()) null
            else WholeName(fullName, runs, nameRuns(spokenGivenName(fullName)).size)
        }
    }

    /** [name] split into its letter/digit runs, lower-cased — the form both sides compare in. */
    private fun nameRuns(name: String): List<String> =
        NAME_RUN_SPLIT.split(name).filter { it.isNotEmpty() }.map { it.lowercase() }

    /**
     * The surname as a commentary says it: the last whitespace-separated token and every
     * [SURNAME_PARTICLES] token immediately before it. `Virgil van Dijk` → `van Dijk`,
     * `Micky van de Ven` → `van de Ven`, `Mohamed Salah` → `Salah`, `Evanilson` → `Evanilson`.
     */
    internal fun spokenSurname(fullName: String): String {
        val tokens = fullName.trim().split(Regex("\\s+"))
        var start = tokens.lastIndex
        while (start > 0 && tokens[start - 1].lowercase() in SURNAME_PARTICLES) start--
        return tokens.subList(start, tokens.size).joinToString(" ")
    }

    /**
     * One player's spoken surname as a whole-word, separator-tolerant matcher. The surname is split
     * into its letter runs and re-joined by `[\s'’-]*`, so the commentary's spelling of a compound
     * name still matches — `Alexander-Arnold`, `Alexander Arnold` and `alexanderarnold` are one
     * pattern, and so are `van Dijk`, `Van Dijk` and `vandijk` — while a plain-word boundary keeps
     * `Dijk` out of `Dijkstra` and `Wood` out of `woodwork`.
     */
    private fun surnamePattern(fullName: String): Regex = Regex(
        "(?<![\\p{L}\\p{N}])" + surnameRuns(fullName) + "(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    /** [spokenSurname] as a separator-tolerant run join, shared by the surname and whole-name matchers. */
    private fun surnameRuns(fullName: String): String =
        spokenSurname(fullName)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .joinToString("[\\s'’-]*") { Regex.escape(it) }

    /**
     * The whole name as a matcher that also CONSUMES a given name already spoken, so expanding a bare
     * surname to the whole name never doubles it (`Diego Gomez`, never `Diego Diego Gomez`). The given
     * part is optional and separator-tolerant for the same reason the surname is (`Van Dijk` /
     * `van Dijk`), and it is built from [spokenGivenName] rather than [fullName] so a particle stays
     * with the surname (`Virgil van Dijk` → prefix `Virgil`, surname `van Dijk`).
     */
    private fun wholeNamePattern(fullName: String): Regex {
        val given = spokenGivenName(fullName)
        val prefix = if (given.isEmpty()) {
            ""
        } else {
            "(?:" + given.split(Regex("\\s+")).joinToString("[\\s'’-]*") { Regex.escape(it) } +
                "[\\s'’-]*)?"
        }
        return Regex(
            "(?<![\\p{L}\\p{N}])" + prefix + surnameRuns(fullName) + "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )
    }

    /** The given name(s) of [fullName] — every token before the [spokenSurname]. */
    private fun spokenGivenName(fullName: String): String {
        val tokens = fullName.trim().split(Regex("\\s+"))
        var start = tokens.lastIndex
        while (start > 0 && tokens[start - 1].lowercase() in SURNAME_PARTICLES) start--
        return tokens.subList(0, start).joinToString(" ")
    }

    /**
     * The squad surnames that belong to exactly ONE club, each paired with that club — the evidence
     * [squadSidesIn] matches.
     *
     * A surname carried by two clubs (`Silva`, `Long`) names no single side, so it is left out: a
     * commentary saying it cannot be used to say WHICH club is on the pitch. What remains is the
     * surname that identifies its club on its own, which is what lets a side be recognised through a
     * player when its name has not been said yet.
     */
    private val UNIQUE_SURNAMES: List<Pair<Team, SurnameMatcher>> = run {
        val perClub = TEAM_SURNAMES.values.flatten()
        val counts = perClub.groupingBy { it.surname.lowercase() }.eachCount()
        TEAM_SURNAMES.entries.flatMap { (team, squad) ->
            squad.filter { counts[it.surname.lowercase()] == 1 }.map { team to it }
        }
    }

    /**
     * One regex matching ANY of [UNIQUE_SURNAMES], used as a cheap reject before the per-club pass:
     * the common slice carries no squad surname at all, and this answers that with a single scan.
     */
    private val UNIQUE_SURNAME_PATTERN: Regex = Regex(
        UNIQUE_SURNAMES.joinToString("|") { "(?:${it.second.pattern.pattern})" },
        RegexOption.IGNORE_CASE,
    )

    /**
     * The clubs a commentary is naming through their PLAYERS in [slice], for a caller that does not
     * yet know the fixture — [SportSceneDetector]'s way of finding the second side of a match from a
     * squad member alone, before the club itself is named.
     *
     * Only [UNIQUE_SURNAMES] are matched, so a surname shared by two clubs never votes. A match must
     * also carry a capital — the recogniser capitalises a name, while an ordinary word that happens to
     * be a surname lower-case (`a long ball`) is left alone. Both rules exist so this pass cannot put
     * a club on the pitch that the commentary never described; a wrong side would cost the one already
     * scoped its bias.
     */
    internal fun squadSidesIn(text: String): Set<Team> {
        if (text.isBlank()) return emptySet()
        if (!UNIQUE_SURNAME_PATTERN.containsMatchIn(text)) return emptySet()
        val found = mutableSetOf<Team>()
        for ((team, matcher) in UNIQUE_SURNAMES) {
            if (team in found) continue
            val hit = matcher.pattern.find(text) ?: continue
            if (hit.value.none { it.isUpperCase() }) continue
            found += team
        }
        return found
    }

    /**
     * How many times a slice of commentary NAMES each club, whole-word against [Team.detect]. This is
     * the evidence the fixture decision is made of (see [SportSceneDetector]): a commentary says
     * `City`, `United`, `Liverpool` dozens of times an hour, and the two sides being commentated
     * out-count any club mentioned in passing, so the COUNT — not a surname found beside the name —
     * is what ranks them.
     *
     * Counting names rather than surnames is what lets the decision be made AT ALL: a surname is the
     * word the recogniser flattens hardest with no bias toward it, so a rule that needed one before
     * scoping could scope was asking the surname pass to work before it was allowed to help (see
     * [teamMentionHits]'s caller for the ranking, [TEAM_FIXTURE_SIZE] for the cap). The club name, by
     * contrast, is already in [FOOTBALL_CLUBS] and decodes without help.
     *
     * Matches are resolved longest-first and any that OVERLAP an accepted one are dropped, so a
     * nested short form cannot name a club the text never named: `Hull City` counts Hull once and
     * Manchester City not at all, and `Newcastle United` counts Newcastle, not United.
     */
    internal fun teamMentionHits(text: String): Map<Team, Int> =
        teamMentionSpans(text).groupingBy { it.team }.eachCount()

    /**
     * The club names [text] carries, as the character ranges they occupy, resolved longest-first and
     * never overlapping. [teamMentionHits] is the count of these; [SportSceneDetector] reads the
     * RANGES so a name the recogniser splits across incremental slices can still be resolved as one
     * name, and so the same mention is never counted twice as the hypothesis is re-emitted (see
     * `SportSceneDetector.countMentions`).
     *
     * Ranges rather than a bare count are what give a mention an identity to compare against: a count
     * alone cannot tell "the same `United` seen again" from "a second `United`".
     *
     * @param freshFrom the index in [text] at which the words just recognised begin; everything
     *   before it is the tail carried from earlier slices (see `SportSceneDetector.countMentions`).
     *   Only the raw [teamMentionHits] caller leaves it at 0, which is right for a whole window
     *   matched in one piece. It is what lets the generic-half guard tell the tail of an unknown
     *   club name (`Bristol City`) from a fresh mention that merely follows one (`… Foden`, then a
     *   new slice's `United`).
     */
    internal fun teamMentionSpans(text: String, freshFrom: Int = 0): List<TeamSpan> {
        if (text.isBlank()) return emptyList()
        // Ranges taken by a club the table knows but cannot scope (`Leicester City`, `West Ham
        // United`). Its generic half must not be credited to Manchester (see
        // [UNSCOPED_CLUB_PATTERNS]); the whole name is skipped, not rewritten — it still names SOME
        // club, just not one this list can rank.
        val blocked = UNSCOPED_CLUB_PATTERNS.flatMap { pattern ->
            pattern.findAll(text).map { it.range }
        }
        val spans = mutableListOf<TeamSpan>()
        for (team in Team.entries) {
            for (pattern in TEAM_NAMES[team].orEmpty()) {
                for (match in pattern.findAll(text)) {
                    if (blocked.any { it.first <= match.range.last && match.range.first <= it.last }) {
                        continue
                    }
                    if (!isStandaloneGenericHalf(text, match.range, freshFrom)) continue
                    spans += TeamSpan(team, match.range.first, match.range.last)
                }
            }
        }
        val taken = mutableListOf<TeamSpan>()
        spans
            .sortedWith(compareByDescending<TeamSpan> { it.last - it.first }.thenBy { it.first })
            .forEach { span ->
                if (taken.none { it.overlaps(span) }) taken += span
            }
        return taken.sortedBy { it.first }
    }

    /**
     * Whether the club-name match at [range] in [text] is the bare generic half of a club
     * ([GENERIC_CLUB_HALVES] — `United`, `City`) standing as its OWN name rather than the tail of a
     * club the table does not carry (`Bristol City`, `Oxford United`, `Atlanta United`). Anything
     * else is not a generic half and passes untouched.
     *
     * A club the table carries never reaches this: its longer form (`Manchester City`, `Hull City`)
     * wins the range by the longest-first rule, and a table club the table cannot scope is skipped
     * by [UNSCOPED_CLUB_PATTERNS] before this runs. What is left is every club the table has never
     * heard of, whose generic half would otherwise be credited to Manchester — a club the
     * commentary never named.
     *
     * Two signals, both the recogniser's own. A proper noun is capitalised, so a lower-case `city`
     * is the ordinary word and no club (the same signal [squadSidesIn] reads from a surname). And a
     * generic half that FOLLOWS a capitalised word is that word's tail — `Bristol City` — unless
     * the word merely OPENS a clause ([CLAUSE_OPENERS]), which the recogniser capitalises at the
     * head of a sentence (`However City hold on`). The preceding word is read only when it lies in
     * the SAME fresh slice ([freshFrom]): a slice is a hypothesis boundary, not a sentence, so
     * `… Foden` followed by a new slice's `United` is two mentions, not a club called `Foden
     * United`.
     */
    private fun isStandaloneGenericHalf(text: String, range: IntRange, freshFrom: Int): Boolean {
        val word = text.substring(range.first, range.last + 1)
        if (word.lowercase() !in GENERIC_CLUB_HALVES) return true
        if (word.none { it.isUpperCase() }) return false
        val before = text.substring(0, range.first).trimEnd()
        val prev = before.takeLastWhile { it.isLetterOrDigit() || it == '\'' || it == '’' || it == '-' }
        if (prev.isEmpty()) return true
        if (before.length - prev.length < freshFrom) return true
        if (!prev.first().isUpperCase()) return true
        return prev.lowercase() in CLAUSE_OPENERS
    }

    /** One club-name occurrence in a slice, as the character range it occupies. */
    internal class TeamSpan(val team: Team, val first: Int, val last: Int) {
        fun overlaps(other: TeamSpan): Boolean = first <= other.last && other.first <= last
    }

    /**
     * The players of [teams] whose spoken surnames ([TEAM_SURNAMES]) the window [text] carries, in
     * roster order — the diagnostic that says whether the squads handed to the decoder are actually
     * being heard. [SportSceneDetector.teams] reports the verdict alone, so from it a scoped squad
     * whose surnames never match and one whose surnames are simply not being said look identical;
     * this reading is what tells them apart (see [SportSceneDetector.scopeSummary]).
     *
     * Only the SCOPED clubs are scanned — at most [TEAM_FIXTURE_SIZE] squads — because the full
     * twenty are what the unbounded pass cost, and this runs on the same worker that has to keep
     * draining the live PCM queue.
     */
    internal fun squadEvidence(teams: Collection<Team>, text: String): List<TeamEvidence> {
        if (text.isBlank()) return emptyList()
        return teams.map { team ->
            TeamEvidence(
                team,
                TEAM_SURNAMES[team].orEmpty()
                    .filter { it.pattern.containsMatchIn(text) }
                    .map { it.surname },
            )
        }
    }

    /**
     * One club's evidence in a window: the spoken surnames ([TEAM_SURNAMES]) the window [text]
     * carries for it, in roster order. Read by the scoping diagnostics ([squadEvidence]) to show
     * whether the squads handed to the decoder are being heard at all.
     */
    internal data class TeamEvidence(val team: Team, val surnames: List<String>)

    /**
     * The clubs whose squads are handed to the decoder, given the clubs the detector ranked: at most
     * [TEAM_FIXTURE_SIZE] of them — see that constant for why the rule is a cap rather than the
     * exactness it once was. A caller that hands over more than a fixture's worth gets nothing, so an
     * over-full set can never bias the decoder toward a match that is not on.
     *
     * Callers compare and ship THIS rather than the raw ranking, so the set that decides a rebuild is
     * the same set that decides the list. Idempotent, so applying it at both the detector and the
     * hotword list costs nothing.
     */
    internal fun scopingTeams(named: Set<Team>): Set<Team> =
        if (named.size <= TEAM_FIXTURE_SIZE) named else emptySet()

    /**
     * The scoreline word itself. Biasing toward it is what turns a bare "nil nil" into a pair of
     * near-nil tokens (`Neil Nill`) instead of `No, no`, which [normaliseScoreline] then settles.
     * Kept out of [FOOTBALL_COMMON] only so [FOOTBALL_HOTWORDS] reassembles in its original order.
     */
    private val FOOTBALL_NIL = "nil"

    /**
     * One NAMED squad as the decoder is biased toward it: every player's FULL NAME and his spoken
     * SURNAME, one entry each. Only the SCOPED list uses this (see [footballHotwords]) — the unscoped
     * list carries no player names at all, because ~300 single-token surnames abort the commentary's
     * decode when they precede the very scene terms that gate the bias (see [FOOTBALL_HOTWORDS]).
     *
     * The surname is the form a commentary says most, and sherpa-onnx's context graph matches a
     * hotword from its FIRST token: while a player rode the list only as his full name, a bare
     * `Saka` was not a prefix of `Bukayo Saka` and received none of the bias, so the name was
     * corrected only on the minority of mentions that say the whole name. The club names in
     * [FOOTBALL_CLUBS] never had this problem for the simple reason that `Arsenal` IS the spoken
     * form; the surname entry gives the players the same treatment.
     *
     * The full name rides beside the surname for the mirror-image gap: a surname entry matches from
     * `Saka` and so cannot help the decoder reach `Bukayo`, whose tokens carry no bias of their own
     * — and a mention of the whole name is exactly where the recogniser flattens hardest. Only a
     * NAMED squad's full names are ever listed, so this is at most ~34 extra multi-token entries for
     * the two sides of a fixture, not the ~300 that made the unscoped list unaffordable.
     *
     * Deduplicated because a mononym (`Evanilson`) is its own surname, and two clubs can share a
     * surname — one entry per spelling is all the context graph needs.
     */
    private fun playerHotwords(players: List<String>): List<String> =
        players.flatMap { listOf(it, spokenSurname(it)) }.distinct()

    /**
     * The vocabulary a football channel is biased with BEFORE any club is named: club names, the
     * fixture-independent terminology, the managers and match officials, and the scoreline word —
     * and deliberately NO player names.
     *
     * Those are exactly what [SportSceneDetector] recognises a football scene from, and the
     * scene may only be opened by a decoder that can still hear them. A player entry is a
     * single-token hotword that scores on every frame the context graph can reach it, so carrying
     * ~300 of them (the squads) turned stadium commentary into a string of surnames, the
     * `offside` / `free kick` anchors stopped appearing, and the scene aged straight back out — the
     * list defeated the very recognition that gates it. The stored FULL NAMES did not fire enough to
     * break the scene, but they cost the context graph ~300 entries per decode for mentions a
     * commentary almost never makes, and never corrected a surname either (the context graph matches
     * from a hotword's first token, so a bare `Saka` is no prefix of `Bukayo Saka`).
     *
     * The touchline and officialdom names ([FOOTBALL_OFFICIALS]) DO ride here: a commentary names a
     * manager far more often than it names most squad members, they are few enough not to break the
     * scene, and biasing toward them is what keeps a flattened `Arteta` / `Kavanagh` from being read
     * as a slip of a squad surname. They are bias and protection only, never EVIDENCE: a person's
     * name is not proof of football, so [FOOTBALL_EVIDENCE] leaves them out.
     *
     * The squads therefore enter the decoder ONLY through the SCOPED list ([footballHotwords]), and
     * only as the NAMED clubs' players ([playerHotwords]) — each carried by full name and by surname
     * — the group a broadcast makes relevant, spent on at most two squads rather than twenty (see
     * [TEAM_FIXTURE_SIZE]). Until a
     * club is named this list is the baseline, which is where a channel whose clubs the table cannot
     * name sits. Once a fixture is proved the scoped list is NARROWER than this one — only the two
     * clubs, their grounds, their squads and managers, and [FOOTBALL_CORE] — so the baseline is what
     * opens a scene and the scoped list is what decodes it, and the two are the same `nil` on both
     * sides.
     */
    private val FOOTBALL_HOTWORDS: List<String> =
        FOOTBALL_CLUBS + FOOTBALL_COMMON + FOOTBALL_OFFICIALS + listOf(FOOTBALL_NIL)

    /**
     * The tennis vocabulary, ASCII and curated the same way as [FOOTBALL_HOTWORDS] (see
     * [SPORT_HOTWORDS] for the BPE/`<unk>` rule). Two groups: the calls a match is made of, then the
     * current tour names.
     *
     * Nothing here is *proven* by this list the way football's are — a scene is only ever opened by
     * [SportSceneDetector]'s own conservative score, so these entries are free to be broad: once a
     * tennis scene is recognised, biasing toward `advantage`/`serve`/`rally` is exactly right, and
     * they never reach a channel that is not already proving tennis.
     */
    private val TENNIS_HOTWORDS = listOf(
        // --- Terminology ---
        "deuce",
        "advantage",
        "tiebreak",
        "tie break",
        "tie-break",
        "break point",
        "match point",
        "set point",
        "game point",
        "championship point",
        "double fault",
        "foot fault",
        "service game",
        "hold serve",
        "breaks serve",
        "serve",
        "volley",
        "smash",
        "drop shot",
        "topspin",
        "slice",
        "rally",
        "passing shot",
        "net cord",
        "baseline",
        "forehand",
        "backhand",
        "return of serve",
        "straight sets",
        "fifth set",
        "centre court",
        "grand slam",
        "Wimbledon",
        "Roland Garros",
        "Australian Open",
        "US Open",
        "ATP",
        "WTA",
        "wildcard",
        "walkover",
        // --- Players ---
        "Novak Djokovic",
        "Carlos Alcaraz",
        "Jannik Sinner",
        "Daniil Medvedev",
        "Alexander Zverev",
        "Andrey Rublev",
        "Stefanos Tsitsipas",
        "Holger Rune",
        "Casper Ruud",
        "Taylor Fritz",
        "Hubert Hurkacz",
        "Grigor Dimitrov",
        "Alex de Minaur",
        "Ben Shelton",
        "Frances Tiafoe",
        "Tommy Paul",
        "Karen Khachanov",
        "Felix Auger-Aliassime",
        "Rafael Nadal",
        "Andy Murray",
        "Stan Wawrinka",
        "Marin Cilic",
        "Borna Coric",
        "Nick Kyrgios",
        "Denis Shapovalov",
        "Iga Swiatek",
        "Aryna Sabalenka",
        "Elena Rybakina",
        "Coco Gauff",
        "Jessica Pegula",
        "Ons Jabeur",
        "Maria Sakkari",
        "Caroline Wozniacki",
        "Simona Halep",
        "Petra Kvitova",
        "Barbora Krejcikova",
        "Marketa Vondrousova",
        "Qinwen Zheng",
        "Daria Kasatkina",
        "Jelena Ostapenko",
        "Madison Keys",
        "Emma Raducanu",
        "Leylah Fernandez",
        "Mirra Andreeva",
        "Diana Shnaider",
    )

    /**
     * The snooker vocabulary, ASCII and curated the same way as [FOOTBALL_HOTWORDS] (see
     * [SPORT_HOTWORDS] for the BPE/`<unk>` rule). Two groups: the calls a frame is made of, then the
     * current tour names.
     *
     * `Trump` rides in the names on purpose — inside a recognised snooker scene it is the player,
     * which is the whole reason the table exists; [SportSceneDetector] is what keeps it away from a
     * news channel, where the same word is the politician.
     */
    private val SNOOKER_HOTWORDS = listOf(
        // --- Terminology ---
        "snooker",
        "snookered",
        "snookers required",
        "cue ball",
        "century break",
        "century",
        "maximum break",
        "one four seven",
        "break off",
        "clearance",
        "respotted black",
        "re-rack",
        "free ball",
        "safety shot",
        "baulk",
        "cushion",
        "pocket",
        "plant",
        "screw shot",
        "topspin",
        "side spin",
        "rest",
        "spider",
        "chalk",
        "frame",
        "frames",
        "pot",
        "potting",
        "break",
        "decider",
        "match ball",
        "Crucible",
        "the Masters",
        "UK Championship",
        "ranking event",
        "world championship",
        // --- Players ---
        "Ronnie O'Sullivan",
        "Judd Trump",
        "Mark Selby",
        "John Higgins",
        "Mark Williams",
        "Neil Robertson",
        "Kyren Wilson",
        "Mark Allen",
        "Shaun Murphy",
        "Luca Brecel",
        "Ding Junhui",
        "Zhao Xintong",
        "Yan Bingtao",
        "Si Jiahui",
        "Ali Carter",
        "Barry Hawkins",
        "Stuart Bingham",
        "Stephen Maguire",
        "David Gilbert",
        "Tom Ford",
        "Gary Wilson",
        "Zhou Yuelong",
        "Xiao Guodong",
        "Pang Junxu",
        "Hossein Vafaei",
        "Robert Milkins",
        "Ryan Day",
        "Anthony McGill",
        "Jack Lisowski",
        "Matthew Selt",
        "Ricky Walden",
        "Noppon Saengkham",
        "Thepchaiya Un-Nooh",
        "Wu Yize",
        "Yuan Sijun",
    )

    /**
     * The decoder's contextual-bias vocabulary, keyed by the scene it belongs to. The whole reason
     * the sport lists are kept apart: biasing is a *cost and a risk* (it pushes the decoder toward
     * these words and away from whatever it would otherwise have heard), so it must only ever be
     * paid on a channel that is actually the sport.
     *
     * Curated, not derived from [Entry.source]. The encoder pushes every character of a hotword
     * through the model's own BPE table, and one character it cannot encode becomes `<unk>` — which
     * does not just spoil its own entry but measurably drags down the *whole* list (a properly
     * accented `Ødegaard` cost every other name its correction). So accents are dropped (`Odegaard`)
     * and the apostrophe capitalised to match the model's tokens (`O'Sullivan`, never `O'sullivan`);
     * both were verified against the model, not assumed. Keep every list ASCII.
     *
     * The pipeline ships ONE of these, chosen by [SportSceneDetector] from the words it is already
     * decoding: no scene recognised → no list at all, so a news bulletin is never biased toward
     * `Tottenham` and a football match never toward `Wimbledon`. `nil` rides in the football list
     * alone for the same reason.
     */
    private val SPORT_HOTWORDS: Map<Sport, List<String>> = mapOf(
        Sport.FOOTBALL to FOOTBALL_HOTWORDS,
        Sport.TENNIS to TENNIS_HOTWORDS,
        Sport.SNOOKER to SNOOKER_HOTWORDS,
    )

    /**
     * True when [word] is a name or term the table already carries — an entry's spelling or a slip it
     * repairs, a club, a squad member, or a hotword. Case-insensitive and single-token.
     *
     * This is the membership test behind the capitalised-unknown count
     * ([AiSubtitleDiagnostics.noteWord]): the names worth adding next are exactly the ones the table
     * does NOT know, and asking the table itself is the only honest way to find them.
     */
    fun knows(word: String): Boolean {
        val w = word.trim().lowercase()
        return w.isNotEmpty() && w in KNOWN_TOKENS
    }

    /**
     * Every lower-case token the table covers, flattened from the same declarations it acts on. Built
     * once, lazily: the count reads it on every recognised partial, on the ASR worker that also has to
     * keep draining the live PCM queue, so it must not be rebuilt per call.
     */
    private val KNOWN_TOKENS: Set<String> by lazy {
        val tokens = mutableSetOf<String>()
        fun add(text: String) {
            text.split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotEmpty() }
                .forEach { tokens += it.lowercase() }
        }
        ENTRIES.forEach { entry ->
            add(entry.match)
            add(entry.source)
            entry.misheard.forEach { add(it) }
        }
        Team.entries.forEach { team -> team.detect.forEach { add(it) } }
        TEAM_PLAYERS.values.flatten().forEach { add(it) }
        SPORT_HOTWORDS.values.flatten().forEach { add(it) }
        tokens
    }

    /**
     * The scene terms [SportSceneDetector] scores a channel by, keyed by sport. Kept deliberately
     * separate from [SPORT_HOTWORDS] and much narrower: this list decides WHETHER a sport is on
     * air, so it holds only words that are heard in that sport and ~nowhere else (`deuce`, `baulk`,
     * `offside`). An ordinary-English word (`frame`, `break`, `pot`, `serve`, `advantage`) would
     * detect the sport in a cooking or news show and open the gate exactly where it must not open.
     *
     * Ordinary English words the sport also owns are not DROPPED for that reason — they live in
     * [SCENE_WEAK_TERMS], where they count only beside a term from this list. See there for why the
     * combination, not the bare word, is what the sport makes unique.
     */
    private val SCENE_TERMS: Map<Sport, Regex> = mapOf(
        Sport.FOOTBALL to scenePattern(
            // The word "penalty" alone is not proof: it is ordinary English ("a penalty for late
            // payment", a court case), and opening the bias on it dragged a news bulletin toward the
            // football vocabulary. Only the COMBINATIONS a commentary actually says are counted, so
            // the gate needs the pitch context the phrase carries with it.
            "offside", "offside trap", "free kick", "penalty kick", "penalty spot", "penalty area",
            "penalty box", "penalty shootout", "spot kick", "hat trick", "hat-trick", "own goal",
            "clean sheet", "goal line",
            // The rest of a commentary's set pieces and clock. Football-only and said constantly,
            // so they both open the scene sooner and keep it open through spells of play that name
            // none of the rarer anchors above — the mid-match lull the hold hysteresis covers. The
            // bare ordinary words the sport also owns (`goal`, `corner`, `keeper`) are NOT here but
            // in [SCENE_WEAK_TERMS]: alone they would open the gate on a talk show, so they count
            // only once the window already carries one of these terms — the COMBINATION is the proof.
            "goal kick", "corner kick", "goalkeeper", "goalmouth", "stoppage time", "injury time",
            "added time",
            "VAR", "video assistant referee", "premier league", "champions league",
            "europa league", "conference league", "fa cup", "league cup", "carabao cup",
            "world cup", "nil",
            // Club names count as evidence: a long spell of play is often named by the two sides
            // ("Arsenal pressing", "Chelsea on the break") with no other football term in the
            // window, and without these the scene flickered off mid-match. Kept to names that are
            // distinctive to the club — the multi-word ones and the unique single words — and away
            // from ones an ordinary programme can say as a place or a noun (Brighton, Roma, Wolves).
            "Arsenal", "Aston Villa", "Bournemouth", "Brentford", "Burnley", "Chelsea",
            "Coventry City", "Crystal Palace", "Everton", "Fulham", "Hull City", "Liverpool",
            "Luton Town", "Middlesbrough",
            "Newcastle United", "Norwich City", "Nottingham Forest", "Sheffield United",
            "Southampton", "Sunderland", "Tottenham Hotspur", "Watford", "West Ham United",
            "Leeds United", "Leicester City", "Ipswich Town", "Manchester City", "Manchester United",
            "Real Madrid", "Atletico Madrid", "Barcelona", "Bayern Munich", "Borussia Dortmund",
            "Paris Saint Germain", "Juventus", "Inter Milan", "AC Milan", "Benfica", "Porto",
            "Anfield", "Old Trafford", "Goodison Park", "Stamford Bridge", "Molineux",
            // The rest of the grounds, beyond the five the gate opened with. A ground is the
            // strongest anchor a lull has: a commentary can spend a whole passage naming only the
            // venue (`Anfield is rocking`, `under way at the Camp Nou`), and without these the scene
            // ages back to empty while the match is still on. Kept to names an ordinary programme
            // does not say — the grounds that double as a place or a noun (`Villa Park`, `St James
            // Park`, `Elland Road`, `Turf Moor`, `City Ground`) stay decoder-only in
            // [FOOTBALL_COMMON] rather than opening the gate on their own.
            "Emirates Stadium", "Etihad Stadium", "Selhurst Park", "Craven Cottage",
            "Stadium of Light", "Vitality Stadium", "Tottenham Hotspur Stadium", "Bramall Lane",
            "Hillsborough", "The Hawthorns", "Vicarage Road", "Portman Road", "Carrow Road",
            "King Power Stadium", "Kenilworth Road", "St Marys Stadium", "Riverside Stadium",
            "Deepdale", "Ewood Park", "Loftus Road",
            "Camp Nou", "Santiago Bernabeu", "Bernabeu", "Allianz Arena", "Signal Iduna Park",
            "Westfalenstadion", "Parc des Princes", "San Siro", "Giuseppe Meazza",
            "Stadio Olimpico", "Wanda Metropolitano", "Mestalla", "Estadio da Luz",
            "Jose Alvalade", "Johan Cruyff Arena", "De Kuip", "Celtic Park", "Ibrox",
            "Maracana", "La Bombonera", "Estadio Azteca",
        ),
        Sport.TENNIS to scenePattern(
            "deuce", "tiebreak", "tie break", "tie-break", "break point", "match point",
            "set point", "championship point", "double fault", "foot fault", "grand slam",
            "wimbledon", "roland garros", "australian open", "us open", "atp", "wta",
            "net cord", "passing shot",
        ),
        Sport.SNOOKER to scenePattern(
            "snooker", "snookered", "snookers required", "cue ball", "century break",
            "maximum break", "one four seven", "respotted black", "free ball", "re-rack",
            "baulk", "crucible",
        ),
    )

    /**
     * The scene terms that are ALSO ordinary English, keyed by sport — counted only beside a strong
     * term from [SCENE_TERMS], never on their own.
     *
     * A football commentary is made of `goal`, `corner`, `keeper`, `foul`, `half`, `defender` as much
     * as of `free kick`, and those bare words are what a long slow spell of play is actually called
     * through. Dropping them outright was wrong: the sport's UNIQUENESS is in the COMBINATION, not in
     * each word alone — `goal` and `corner` beside each other are football, but "your goal in life"
     * and "round the corner" on a talk show are not. So they are scored here, and [sceneHits] adds
     * them only when [SCENE_TERMS] already hit: a window has to NAME the sport before its ordinary
     * words are read as its vocabulary. That keeps two bare `corner`s worth zero while letting
     * "free kick … corner … keeper" hold the scene open instead of aging out between the rarer
     * anchors — which is exactly the football-to-empty flip this tier exists to stop.
     *
     * Deliberately NOT in [SPORT_HOTWORDS] / [FOOTBALL_EVIDENCE]: those are the decoder's bias and the
     * scoreline proof, and there a bare `goal` or `half` would fire on ordinary speech. This list only
     * decides WHETHER the sport is on air, beside proof that it is.
     */
    private val SCENE_WEAK_TERMS: Map<Sport, Regex> = mapOf(
        Sport.FOOTBALL to scenePattern(
            "goal", "goals", "corner", "corners", "keeper", "foul", "fouls",
            "header", "headers", "shot", "shots", "save", "saves", "tackle", "tackles",
            "cross", "crosses", "pass", "passes", "possession",
            "half", "first half", "second half", "half time", "half-time",
            "full time", "full-time", "kick off", "kick-off",
            "defender", "defenders", "midfielder", "midfielders",
            "striker", "strikers", "winger", "wingers", "midfield",
            "goalless", "pitch", "touchline",
        ),
    )

    /**
     * How many of a sport's scene terms [text] carries, keyed by sport: its strong [SCENE_TERMS] plus,
     * for a sport that has any, the [SCENE_WEAK_TERMS] those strong hits vouch for. Whole-word and
     * case-insensitive, so `nil` never fires inside `vanilla` and `atp` never inside `at par`. Fed one
     * incremental slice of recognised words at a time by [SportSceneDetector].
     *
     * The weak tier is consulted ONLY when the strong tier already hit (see [SCENE_WEAK_TERMS]): two
     * bare `corner`s on a property show still score zero, while a `free kick` beside a `corner` counts
     * as the combination football is. A sport with no weak list (tennis, snooker) scores exactly as
     * before, and a sport with no strong hit pays no second regex pass at all.
     *
     * The weak pass runs over the text with the strong matches BLANKED, so a word a strong term
     * already contains (`goal` inside `goal kick`, `corner` inside `corner kick`) is not counted
     * twice — otherwise one set piece would clear the two-hit gate alone.
     */
    internal fun sceneHits(text: String): Map<Sport, Int> =
        if (text.isBlank()) emptyMap()
        else SCENE_TERMS.mapValues { (sport, pattern) ->
            val strong = pattern.findAll(text).count()
            val weak = if (strong == 0) 0
            else SCENE_WEAK_TERMS[sport]?.findAll(pattern.replace(text, " "))?.count() ?: 0
            strong + weak
        }.filterValues { it > 0 }

    /** One whole-word alternation over [terms], case-insensitive — the [SCENE_TERMS] shape. */
    private fun scenePattern(vararg terms: String): Regex =
        Regex(terms.joinToString("|") { "(?<!$EDGE)${Regex.escape(it)}(?!$EDGE)" }, RegexOption.IGNORE_CASE)

    /**
     * A club or competition name, as a whole-word matcher. Read by [footballEvidenced] — a
     * commentary that names a club is football whatever else its window holds. The bare `nil` is
     * deliberately NOT part of this: on its own it is not proof of football, only of a word.
     *
     * Built from the clubs and terminology alone, NOT from the whole of [FOOTBALL_HOTWORDS]: the
     * managers and officials ride the decoder as bias and protection, but a person's name
     * ([FOOTBALL_OFFICIALS]) is not proof of football and must not open the gate.
     */
    private val FOOTBALL_EVIDENCE: Regex = Regex(
        (FOOTBALL_CLUBS + FOOTBALL_COMMON)
            .joinToString("|") { "(?<!$EDGE)${Regex.escape(it)}(?!$EDGE)" },
        RegexOption.IGNORE_CASE,
    )

    /**
     * The names the streaming recogniser should be biased toward *while it decodes*, one per `/` —
     * the separator sherpa-onnx's hotword JNI splits on.
     *
     * The decoder is free to prefer them over the nearest word its acoustic model would otherwise
     * settle for (`Salace` → `Salah`, `Osullivan` → `O'Sullivan`), which no post-hoc rewrite can
     * generalise to: [correct] only repairs slips it was told about, one spelling at a time.
     *
     * The list is the scene's vocabulary ([SPORT_HOTWORDS]) — measured to fix a mangled club name
     * and a mangled `nil` without disturbing ordinary speech, which is what the curated, ASCII-only
     * lists are for. Only ONE scene is ever sent, and a null scene sends none.
     *
     * A FOOTBALL scene is additionally scoped by [teams]: once the window has RANKED the clubs on the
     * pitch, the decoder's list is REBUILT from that fixture alone — the two clubs' spoken names, the
     * two squads by full name and surname ([playerHotwords]), and the core calls of the game
     * ([FOOTBALL_CORE]) — rather than the whole ~160-term global table plus both squads (~230
     * entries). Each tier carries its own score ([HOTWORD_WEIGHT_CLUB]); the clubs outrank the squads
     * and the squads outrank the terminology. A window that has named no club yet yields the unscoped
     * baseline, which is what the scene is recognised from — see [teamMentionHits] for how the two
     * clubs are ranked, and [TEAM_FIXTURE_SIZE] for the cap.
     *
     * The squads are what makes the scoped list worth its length: a surname is one of the words the
     * recogniser flattens hardest, so it has to be on the graph before it can be heard at all. Only
     * the fixture's own clubs and players ride it, which is how the list stays inside the ~50–120
     * core-word budget the narrow bias is specified as.
     *
     * @param sport the scene [SportSceneDetector] has recognised on this channel, or null while it
     *   has none. Null yields "" on purpose: with no scene there is no domain, so the honest thing to
     *   do is decode with no bias at all rather than guess a sport and drag ordinary television
     *   toward it.
     * @param teams the clubs [SportSceneDetector] ranked — [scopingTeams]' verdict, which is all the
     *   same after the cap is applied here. Only read for [Sport.FOOTBALL]; anything past a
     *   fixture's worth scopes nothing, and the unscoped list is the baseline rather than a
     *   degradation.
     *
     * Only the built-in English streaming engine consumes this — the list is written for that
     * model's token table, and biasing is only supported by its beam-search decoder.
     */
    fun recognitionHotwords(sport: Sport?, teams: Set<Team> = emptySet()): String = when (sport) {
        null -> ""
        Sport.FOOTBALL -> footballHotwords(teams).joinToString("/")
        else -> SPORT_HOTWORDS[sport].orEmpty().joinToString("/")
    }

    /**
     * The football list, in one of its two shapes. With no club named it is the unscoped baseline
     * ([FOOTBALL_HOTWORDS] — clubs, terminology, officials, `nil`), the vocabulary a scene is
     * recognised from. With a fixture RANKED it is rebuilt from that match alone — the two clubs and
     * their grounds, their squads and managers by full name and surname, and [FOOTBALL_CORE] — every
     * entry scored by its tier, so the list is narrow and ranked instead of the whole table plus a
     * pair of squads. See [recognitionHotwords] for the scoring and [teamMentionHits] for how the
     * two clubs are chosen. Sorted by enum order so one window always produces the same string,
     * whatever order the clubs were named in.
     */
    private fun footballHotwords(teams: Set<Team>): List<String> {
        // [scopingTeams] is applied HERE rather than trusted from the caller, so an over-full set can
        // never scope the list however [recognitionHotwords] was called.
        val scoped = scopingTeams(teams)
        // No club named yet: the baseline that OPENS a scene. It cannot be narrowed to a fixture —
        // there is no fixture — and every entry is what the scene is recognised from, so it stays.
        if (scoped.isEmpty()) return FOOTBALL_HOTWORDS
        val ranked = scoped.sortedBy { it.ordinal }
        // A fixture is on screen, so the list is rebuilt from THAT match alone: the two clubs' spoken
        // spellings, their squads, and the core calls of the game — ~95 entries instead of the ~230
        // the global club-and-terminology table plus a full pair of squads would cost. The other
        // clubs and the rest of the terminology are dropped from the decoder's list, which is the
        // narrow-but-deep bias the fixture is worth; they still carry the unscoped baseline above.
        //
        // Each tier rides its own score (see [HOTWORD_WEIGHT_CLUB]): the parser reads the ` :score`
        // suffix, so the two clubs outrank the squads and the squads outrank the terminology, and a
        // crowded graph cannot let a term outbid the club the commentary is about.
        val players = ranked.flatMap { TEAM_PLAYERS[it].orEmpty() }
        // The club tier is the two spoken club names AND the two grounds: the scoped list REPLACES
        // the unscoped baseline, so a proved fixture has to keep its own venues (see [TEAM_GROUNDS])
        // or a passage that names only the stadium (`Anfield is rocking`) would age the scene back to
        // empty while the match is still on.
        val clubs = ranked
            .flatMap { clubHotwords(it) + TEAM_GROUNDS[it].orEmpty() }
            .distinct()
        // The player tier is the two squads AND the two managers, given the same treatment: a
        // touchline name is spoken as often as a squad member's and is flattened the same way, so it
        // rides by full name and spoken surname (see [FOOTBALL_MANAGERS] and [playerHotwords]).
        val names = playerHotwords(players + ranked.mapNotNull { FOOTBALL_MANAGERS[it] })
        return scored(HOTWORD_WEIGHT_CLUB, clubs) +
            scored(HOTWORD_WEIGHT_PLAYER, names) +
            scored(HOTWORD_WEIGHT_TERM, FOOTBALL_CORE) +
            // Unscored on purpose: [FOOTBALL_NIL] is not one of the three tiers and takes the
            // recognizer's global score, exactly as it does on the unscoped baseline.
            listOf(FOOTBALL_NIL)
    }

    /**
     * The scoped [team]'s spoken club names: the [FOOTBALL_CLUBS] spellings the club is known by,
     * matched through its [Team.detect] forms so a club with a long and a short name (`Brighton and
     * Hove Albion` / `Brighton`) carries both.
     *
     * The match is against [FOOTBALL_CLUBS], not against [Team.detect] directly, because the two
     * lists answer different questions: [Team.detect] deliberately holds bare `City` / `United` so
     * the DETECTOR can rank a mention, while the decoder's list must not be biased toward an
     * ordinary word — [FOOTBALL_CLUBS] already curates those out (`Manchester City`, never `City`).
     */
    private fun clubHotwords(team: Team): List<String> =
        FOOTBALL_CLUBS.filter { club -> team.detect.any { it.equals(club, ignoreCase = true) } }

    /**
     * [entries] each carrying [score] as the ` :score` suffix sherpa-onnx's hotword parser reads
     * (`Arsenal :4.0`). One decimal, because the parser takes the token after `:` as the score and a
     * bare integer would be indistinguishable from part of a club name (`League 1`).
     *
     * [Locale.ROOT] and not the device locale: the suffix is protocol, not display text, and in a
     * comma-decimal locale (`de`, `fr`) the default formatter would write `3,0` — which the parser
     * cannot read as the score it was meant to be.
     *
     * Applied only to the SCOPED list: the unscoped baseline and the other sports keep the single
     * global score they were measured with, so this is the one place a tier's importance can be said.
     */
    private fun scored(score: Float, entries: List<String>): List<String> =
        entries.map { "$it :${"%.1f".format(Locale.ROOT, score)}" }

    /**
     * One scene's curated hotword list, exposed so the static validation test can assert every entry
     * is encodable by the shipped model's BPE table (see [SPORT_HOTWORDS]).
     */
    internal fun hotwordsFor(sport: Sport): List<String> = SPORT_HOTWORDS[sport].orEmpty()

    /**
     * A human-readable name of [sport] for the translator prompt, or "" when no scene is known. Left
     * in English: it is an instruction to the model ("the audio is football commentary"), not a
     * caption, so it is not localised.
     */
    fun sceneLabel(sport: Sport?): String = when (sport) {
        Sport.FOOTBALL -> "association football (soccer)"
        Sport.TENNIS -> "tennis"
        Sport.SNOOKER -> "snooker"
        null -> ""
    }

    /**
     * The window a request is answered from: the sentence's already-translated words followed by the
     * fragment being asked for. Both the gate and the glossary look at the whole of it, so an anchor
     * in the earlier words still governs a name that arrives in the fragment.
     */
    internal fun window(context: String, text: String): String =
        if (context.isBlank()) text else "$context $text"

    /**
     * [text] with recognition slips on the table's names repaired to the entry's own spelling
     * (`Trunk` → `Trump`, `higgens` → `higgins`).
     *
     * This is the only mechanism that rewrites the text *before* it is displayed, because a slip left
     * in place is reproduced on BOTH lines — the English caption and the Chinese built from it — and
     * no translation-side rewrite can undo it. The slip's own capitalisation is kept, so a repaired
     * sentence-initial name is not lowercased.
     *
     * A football window additionally gets its scoreline set right ([normaliseScoreline]): the
     * numerals and `nil` a commentary is made of, which the recogniser swaps for homophones and
     * near-names. That repair needs the window to prove football first ([footballEvidenced]), so an
     * ordinary sentence is never read as a score.
     *
     * @param anchoredBy the window the gate reads; a [Kind.GATED] entry's slips are repaired only
     *   when an anchor for its sport occurs in it. Defaults to [text] itself.
     */
    fun correct(text: String, anchoredBy: String = text): String {
        if (text.isBlank()) return text
        val anchored = anchoredSports(anchoredBy)
        var out = text
        for (entry in ENTRIES) {
            if (entry.misheardPatterns.isEmpty()) continue
            if (entry.kind == Kind.GATED && entry.sport !in anchored) continue
            for ((pattern, canonical) in entry.misheardPatterns) {
                out = pattern.replace(out) { m -> matchCase(canonical, m.value) }
            }
        }
        if (footballEvidenced(anchoredBy, anchored)) out = normaliseScoreline(out)
        return out
    }

    /** [canonical] re-cased to look like the [heard] token it replaces, so a name keeps its case. */
    private fun matchCase(canonical: String, heard: String): String = when {
        heard.all { !it.isLetter() || it.isUpperCase() } -> canonical.uppercase()
        heard.firstOrNull()?.isUpperCase() == true -> canonical.replaceFirstChar { it.uppercaseChar() }
        else -> canonical
    }

    /**
     * [text] with a mis-heard squad surname repaired to the spelling of the [teams] in play
     * (`Sakho` → `Saka` is too short to qualify, but `Salo` → `Salah`, `Rodry` → `Rodri`).
     *
     * [correct] repairs only the slips the table has written down by hand, which is right for the
     * marquee names it carries and useless for the other ~320 squad members: a newcomer the
     * recogniser has never been biased with is the likeliest to be mis-heard, and a slip left in
     * place survives on BOTH lines because the Chinese is built from the same words.
     *
     * Four things keep it from rewriting ordinary television, and they are why it runs at all:
     * the candidate set is the fixture's own ~32 surnames, so a word has to be a near-miss of one of
     * THEM; the miss is a single substitution, insertion or deletion that also keeps the first
     * letter (the recogniser almost always gets the onset); exactly ONE squad member may match,
     * so `Fernandez`/`Fernandes` — one edit apart, and both in a Manchester fixture — are left as
     * heard rather than swapped for each other; and the token itself must not be an ordinary English
     * word ([COMMON_ENGLISH_WORDS]), because in isolation a one-edit near-miss of a surname is as
     * readily the recogniser's own reading (`While` is one edit from `White`).
     *
     * Only surnames of five letters or more are candidates. Below that the collision with ordinary
     * words is not a tail risk but the common case (`Word`→`Wood`, `Hall`/`Ball`/`Fall`), and the
     * first-letter rule cannot separate them. A short surname is left to the hand-written table —
     * unless the window names the man in FULL, which is what [correctWholeNames] covers: the given
     * name supplies the context the bare surname lacks, so `Bukayo Sakho` is repaired there even
     * though a bare `Sakho` is not.
     *
     * @param teams the clubs the pipeline has scoped the decoder to; empty (no fixture) is a no-op.
     */
    internal fun correctNames(text: String, teams: Set<Team>): String {
        val scoped = scopingTeams(teams)
        if (text.isBlank() || scoped.isEmpty()) return text
        // The fixture's repair index — the surname candidates and the whole-name halves — is built
        // once per scoped club set and reused ([nameFixIndex]): this pass runs on every recognised
        // partial, on the same worker that also has to keep draining the live PCM queue, and
        // rebuilding ~64 candidates and re-counting the whole-name halves per call was arithmetic
        // and allocation spent for nothing.
        val index = nameFixIndex(scoped)
        // The whole name first: a full-name mention is repaired against the squads directly (short
        // and compound surnames included), and what it fixes is then already spelled right for the
        // bare-surname pass below.
        val wholeNamed = correctWholeNames(text, index)
        if (index.surnames.isEmpty()) return wholeNamed
        return NAME_TOKEN_PATTERN.replace(wholeNamed) { match ->
            val heard = match.value
            val lower = heard.lowercase()
            // A surname already spelled right, a club, a term — the table knows it, so it is not a
            // slip. `knows` is the same membership test the diagnostics count against.
            if (knows(heard)) return@replace heard
            // An ordinary English word is never rewritten, however close it sits to a surname. The
            // isolated token offers no other evidence, and the recogniser reads `While` as readily
            // as it flattens `White` (see [COMMON_ENGLISH_WORDS]).
            if (lower in COMMON_ENGLISH_WORDS) return@replace heard
            val near = index.surnames.filter {
                it != lower && it.first() == lower.first() && oneEditApart(lower, it)
            }
            if (near.size != 1) return@replace heard
            matchCase(near.single(), heard)
        }
    }

    /**
     * One scoped fixture's repair index: the surnames [correctNames] measures a near-miss against,
     * and the whole names [correctWholeNames] matches together with the two half-handle counts its
     * 姓↔名 互纠 rule reads ([nameHalvesCorrectEachOther]).
     *
     * Built once per scoped club set ([nameFixIndex]) because both repairs run on every recognised
     * partial — see [correctNames]. Everything is derived from the immutable squad tables, so a
     * cached index can never go stale while the process lives.
     */
    private class NameFixIndex(
        val surnames: List<String>,
        val wholeNames: List<WholeName>,
        val byLength: Map<Int, List<WholeName>>,
        val lengths: List<Int>,
        val givenHandles: Map<List<String>, Int>,
        val surnameHandles: Map<List<String>, Int>,
    )

    /**
     * [NameFixIndex]'s cache, keyed by the scoped club set. Bounded in practice by [scopingTeams]: a
     * key is one club, a pair of them, or the empty set, so the map tops out at the number of club
     * pairs rather than growing with the broadcast.
     */
    private val nameFixIndexes = java.util.concurrent.ConcurrentHashMap<Set<Team>, NameFixIndex>()

    /** The cached repair index for [scoped], built on first sight (see [NameFixIndex]). */
    private fun nameFixIndex(scoped: Set<Team>): NameFixIndex =
        nameFixIndexes.getOrPut(scoped) { buildNameFixIndex(scoped) }

    /** Builds one [NameFixIndex] from the squad tables — the expensive half [nameFixIndex] caches. */
    private fun buildNameFixIndex(scoped: Set<Team>): NameFixIndex {
        val surnames = scoped.flatMap { TEAM_SURNAMES[it].orEmpty() }
            .map { it.surname }
            .filter { it.length >= NAME_FIX_MIN_CHARS && it.all { c -> c.isLetter() } }
            .map { it.lowercase() }
            .distinct()
        val wholeNames = scoped.sortedBy { it.ordinal }.flatMap { TEAM_WHOLE_NAMES[it].orEmpty() }
        val byLength = wholeNames.groupBy { it.runs.size }
        return NameFixIndex(
            surnames = surnames,
            wholeNames = wholeNames,
            byLength = byLength,
            lengths = byLength.keys.sortedDescending(),
            givenHandles = halfHandles(wholeNames) { it.runs.subList(0, it.givenRuns) },
            surnameHandles = halfHandles(wholeNames) { it.runs.subList(it.givenRuns, it.runs.size) },
        )
    }

    /**
     * [text] with a mis-heard player's FULL NAME repaired to the squad's own spelling
     * (`Bukayo Sakho` → `Bukayo Saka`, `Lewis Potex` → `Lewis-Potter`, `Matt O'Rilex` → `Matt
     * O'Riley`).
     *
     * [correctNames]'s token pass repairs a BARE surname, and it can only afford to: with no context
     * it must demand a long, all-letter, unique surname. The full name carries that context itself —
     * the given name pins WHICH man — so the surname no longer has to be long or plain, and this pass
     * is what covers the names the token pass deliberately leaves alone: a short surname (`Saka`,
     * `Eze`, `Neto`) and a compound one (`van Dijk`, `Lewis-Potter`, `O'Riley`).
     *
     * The window is compared RUN BY RUN ([nameRuns]), so the separators the recogniser flattens
     * (`Lewis-Potter` / `Lewis Potter`, `O'Riley` / `O Riley`) read the same on both sides, and at
     * most ONE run may be a single-edit near-miss that keeps its first letter. A window is rewritten
     * only when exactly ONE scoped name explains it, so `Fernandez`/`Fernandes` stays as heard.
     *
     * Where that rule gives up — a half too far to be a single edit — the two halves are allowed to
     * correct each other ([nameHalvesCorrectEachOther]): the half heard RIGHT pins the man, and the
     * other is rewritten to his, however far it slipped. That is what recovers `Bukayo Sakho` (the
     * given name carries it) and `Bukaiyo Saka` (the surname does), and it stays safe because the
     * intact half must name exactly ONE scoped player.
     *
     * @param index the fixture's cached repair index (see [NameFixIndex]); no scoped whole names is
     *   a no-op.
     */
    private fun correctWholeNames(text: String, index: NameFixIndex): String {
        if (text.isBlank() || index.wholeNames.isEmpty()) return text
        val tokens = NAME_WORD_PATTERN.findAll(text).toList()
        if (tokens.isEmpty()) return text
        val out = StringBuilder(text.length)
        var copied = 0
        var i = 0
        while (i < tokens.size) {
            // A name only ever starts on a capital: a lower-case run is an ordinary word the
            // recogniser did not treat as a proper noun (the same signal [squadSidesIn] reads).
            if (!tokens[i].value.first().isUpperCase()) {
                i++
                continue
            }
            val hit = wholeNameHit(
                tokens, i, index.byLength, index.lengths, index.givenHandles, index.surnameHandles,
            )
            if (hit == null) {
                i++
                continue
            }
            out.append(text, copied, tokens[i].range.first)
            out.append(hit.canonical)
            copied = tokens[i + hit.length - 1].range.last + 1
            i += hit.length
        }
        out.append(text, copied, text.length)
        return out.toString()
    }

    /** One repaired span: the canonical [full name][WholeName.fullName] and the tokens it consumed. */
    private class WholeNameHit(val canonical: String, val length: Int)

    /** Each half as both sides compare it (its runs), counted, so `handle == 1` means one man owns it. */
    private fun halfHandles(
        candidates: List<WholeName>,
        half: (WholeName) -> List<String>,
    ): Map<List<String>, Int> = candidates
        .map { half(it) }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()

    /**
     * The full name the window at [start] is a near-miss of, or null. Lengths are tried LONGEST
     * first and the first length with exactly one explaining candidate wins, so a longer name is never
     * dropped for a shorter one that happens to fit its opening runs.
     */
    private fun wholeNameHit(
        tokens: List<MatchResult>,
        start: Int,
        byLength: Map<Int, List<WholeName>>,
        lengths: List<Int>,
        givenHandles: Map<List<String>, Int>,
        surnameHandles: Map<List<String>, Int>,
    ): WholeNameHit? {
        for (length in lengths) {
            if (start + length > tokens.size) continue
            val heard = (0 until length).map { tokens[start + it].value.lowercase() }
            var canonical: String? = null
            var matches = 0
            for (candidate in byLength.getValue(length)) {
                val hit = nameWindowMatches(heard, candidate) ||
                    nameHalvesCorrectEachOther(heard, candidate, givenHandles, surnameHandles)
                if (!hit) continue
                canonical = candidate.fullName
                if (++matches > 1) break
            }
            if (matches == 1) return WholeNameHit(canonical!!, length)
        }
        return null
    }

    /** True when [heard] is [runs] with at most one run a single-edit near-miss of its counterpart. */
    private fun nameWindowMatches(heard: List<String>, candidate: WholeName): Boolean {
        var edits = 0
        for (j in candidate.runs.indices) {
            val h = heard[j]
            val c = candidate.runs[j]
            if (h == c) continue
            if (h.firstOrNull() != c.firstOrNull() || !oneEditApart(h, c) || ++edits > 1) return false
        }
        return true
    }

    /**
     * The 姓↔名 互纠 rule: a name's two halves are compared apart, and whichever half is heard
     * EXACTLY — and names exactly ONE scoped player — repairs the other, however far the other
     * slipped. `Bukayo Sakho` is recovered because `Bukayo` alone is [Bukayo Saka]; `Bukaiyo Saka`
     * because `Saka` alone is him. The intact half is the context a BARE surname never has, which is
     * why the token pass refuses short surnames and this does not.
     *
     * The half that pins must be unique ([halfHandles] == 1), so a fixture with two `Wilson`s or two
     * `Bruno`s repairs neither, and no rewrite ever invents a player the fixture does not field.
     */
    private fun nameHalvesCorrectEachOther(
        heard: List<String>,
        candidate: WholeName,
        givenHandles: Map<List<String>, Int>,
        surnameHandles: Map<List<String>, Int>,
    ): Boolean {
        val split = candidate.givenRuns
        val runs = candidate.runs
        if (split <= 0 || split >= runs.size) return false
        val heardGiven = heard.subList(0, split)
        val heardSurname = heard.subList(split, runs.size)
        val canonGiven = runs.subList(0, split)
        val canonSurname = runs.subList(split, runs.size)
        // The given name, heard right, pins the man; the surname is rewritten to his...
        if (heardGiven == canonGiven &&
            givenHandles[canonGiven] == 1 &&
            garbled(heardSurname, canonSurname)
        ) return true
        // ...and the other way round: the surname, heard right, pins the man.
        return heardSurname == canonSurname &&
            surnameHandles[canonSurname] == 1 &&
            garbled(heardGiven, canonGiven)
    }

    /**
     * True when [heard] keeps every run's first letter and is within [HALF_MAX_EDITS] of [canon] —
     * the recogniser's onset signal survives a slip, so the first letter is the cheap, honest guard
     * that keeps this from firing on an ordinary word.
     */
    private fun garbled(heard: List<String>, canon: List<String>): Boolean {
        for (j in canon.indices) {
            val h = heard[j]
            val c = canon[j]
            if (h == c) continue
            if (h.firstOrNull() != c.firstOrNull()) return false
            if (h.length - c.length !in -HALF_MAX_EDITS..HALF_MAX_EDITS) return false
            if (!withinEditDistance(h, c, HALF_MAX_EDITS)) return false
        }
        return true
    }

    /** True when [a] and [b] are within [max] single-character edits (bounded Levenshtein). */
    private fun withinEditDistance(a: String, b: String, max: Int): Boolean {
        if (a == b) return true
        if (a.length - b.length !in -max..max) return false
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowBest = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                if (curr[j] < rowBest) rowBest = curr[j]
            }
            // Every later row can only grow, so a row already past the budget can never come back.
            if (rowBest > max) return false
            val swap = prev
            prev = curr
            curr = swap
        }
        return prev[b.length] <= max
    }

    /**
     * [text] with a bare squad SURNAME expanded to the whole name when the session supplies which of
     * the fixture's players it is — the in-sentence and cross-sentence half of the player
     * disambiguation, run on the ASR side so the English caption and the Chinese built from it agree.
     *
     * A surname two of the fixture's players share (`Wilson` on Brentford and Leeds, `Henderson` on
     * Chelsea and Palace) is not resolvable from the surname alone, and [correctNames] deliberately
     * refuses to guess one. Three kinds of evidence are consulted, strongest first:
     *
     *  1. **This sentence names exactly one of the surname's clubs** (and that club fields exactly one
     *     player of it). A fresh mention is the best evidence there is.
     *  2. **A full name for the surname was heard recently** ([rememberedNames], surname →
     *     whole name, session memory). Commentary introduces a player in full and then says only his
     *     surname for minutes; the earlier full name is what tells the two Wilsons apart. The caller
     *     only supplies a surname whose recent full names resolve to exactly ONE man — two recently
     *     named Wilsons prove nothing.
     *  3. **Exactly one of the surname's clubs was named in the recent commentary window**
     *     ([recentClubs], not necessarily this sentence). A match is discussed club by club; the side
     *     last on the lips is the tiebreaker when no full name was heard.
     *
     * Deliberately conservative: nothing is expanded unless one of those three proves a single
     * player. A window naming BOTH sides resolves nothing, because a shared surname there is exactly
     * the ambiguity that must not be guessed — and a stale memory must never outrank a club the
     * sentence actually names (evidence 1 wins over 2 and 3).
     *
     * @param teams the clubs scoping the decoder; empty (no fixture) is a no-op.
     * @param recentClubs clubs mentioned in the recent commentary window, as context for [text].
     * @param rememberedNames recently heard whole names by spoken-surname key (lower-case), already
     *   reduced by the caller to surnames that resolve to exactly one player. Empty = no memory.
     */
    internal fun resolveSurnames(
        text: String,
        teams: Set<Team>,
        recentClubs: Set<Team> = emptySet(),
        rememberedNames: Map<String, String> = emptyMap(),
    ): String {
        if (text.isBlank() || teams.isEmpty()) return text
        val scoped = scopingTeams(teams)
        if (scoped.isEmpty()) return text
        // The surname -> {club, whole name} candidates of the fixture, but only for surnames MORE THAN
        // ONE scoped player carries: a lone surname needs no resolution and is left to [correctNames]
        // and the whole-name pinning.
        val candidates = mutableMapOf<String, MutableList<Pair<Team, String>>>()
        for (team in scoped.sortedBy { it.ordinal }) {
            for (fullName in TEAM_PLAYERS[team].orEmpty()) {
                candidates.getOrPut(spokenSurname(fullName).lowercase()) { mutableListOf() } += team to fullName
            }
        }
        if (candidates.values.none { it.size > 1 }) return text
        val named = teamMentionHits(text).keys
        var out = text
        for ((surname, players) in candidates) {
            if (players.size < 2) continue
            // Exactly one of the surname's clubs must be the one the sentence named...
            val proved = players.map { it.first }.distinct().filter { it in named }
            val fullName = when {
                proved.size == 1 -> players.filter { it.first == proved.single() }.singleOrNull()?.second
                // No club evidence in this sentence: a recently heard whole name is the next best
                // proof, and the caller has already guaranteed it names one of these players.
                proved.isEmpty() -> rememberedNames[surname]?.takeIf { name -> players.any { it.second == name } }
                    ?: run {
                        // ...and failing that, exactly one club named in the recent window.
                        val fromRecent = players.map { it.first }.distinct().filter { it in recentClubs }
                        if (fromRecent.size == 1) {
                            players.filter { it.first == fromRecent.single() }.singleOrNull()?.second
                        } else {
                            null
                        }
                    }
                // Both clubs named: the ambiguity is real and nothing may be guessed.
                else -> null
            } ?: continue
            val pattern = wholeNamePattern(fullName)
            if (!pattern.containsMatchIn(out)) continue
            out = pattern.replace(out) { Regex.escapeReplacement(fullName) }
        }
        return out
    }

    /**
     * Every squad member of [teams] whose WHOLE name occurs in [text], case-insensitively. This is
     * how the ASR worker builds its session name memory: a full name spoken now is evidence that a
     * later bare surname refers to that man (see [resolveSurnames]).
     *
     * The match is a plain case-insensitive substring, not a word-boundary regex: the recogniser adds
     * punctuation and possessives at the edges (`Saka's`, `Salah.`), and a full name spanning two
     * words (`van Dijk`) cannot be found by a token comparison without reassembling spans.
     */
    internal fun fullNamesIn(text: String, teams: Set<Team>): List<String> {
        if (text.isBlank() || teams.isEmpty()) return emptyList()
        val lower = text.lowercase()
        return scopingTeams(teams)
            .flatMap { TEAM_PLAYERS[it].orEmpty() }
            .filter { lower.contains(it.lowercase()) }
            .distinct()
    }

    /** True when [a] and [b] differ by exactly one substitution, insertion or deletion. */
    private fun oneEditApart(a: String, b: String): Boolean {
        if (a == b) return false
        if (a.length == b.length) {
            var seen = 0
            for (i in a.indices) {
                if (a[i] != b[i]) {
                    if (++seen > 1) return false
                }
            }
            return seen == 1
        }
        return when {
            a.length + 1 == b.length -> oneDeletionApart(a, b)
            b.length + 1 == a.length -> oneDeletionApart(b, a)
            else -> false
        }
    }

    /** True when [longer] is [shorter] with exactly one character inserted. */
    private fun oneDeletionApart(shorter: String, longer: String): Boolean {
        var i = 0
        var skipped = false
        for (j in longer.indices) {
            if (i < shorter.length && shorter[i] == longer[j]) {
                i++
            } else {
                if (skipped) return false
                skipped = true
            }
        }
        return i == shorter.length && skipped
    }

    /**
     * [text] with every applicable ambiguous word replaced by its unambiguous form.
     *
     * A name is expanded only as far as it is not already spoken: [Entry.disambiguationPattern]
     * consumes a given name the window already carries, so a bare surname becomes its full form
     * (`Salah` → `Mohamed Salah`) while a full name is left as it is rather than doubled
     * (`Mohamed Salah`, never `Mohamed Mohamed Salah`). The same pattern serves the echo check in
     * `LiveTranslationAccumulator`, so both callers keep the repair.
     *
     * @param anchoredBy the whole window the sentence is showing; a [Kind.GATED] entry applies only
     *   when an anchor for its sport occurs in it. Defaults to [text] itself for a caller that has
     *   nothing else — the entry then has to prove itself within the text alone.
     */
    fun disambiguate(text: String, anchoredBy: String = text): String {
        if (text.isBlank()) return text
        val anchored = anchoredSports(anchoredBy)
        var out = text
        for (entry in ENTRIES) {
            if (entry.source.isEmpty()) continue
            if (entry.kind == Kind.GATED && entry.sport !in anchored) continue
            out = entry.disambiguationPattern.replace(out, Regex.escapeReplacement(entry.source))
        }
        return out
    }

    /**
     * A compact "use this exact rendering" list for the entries that occur in [anchoredBy], or "" when
     * none do (or the target is not Chinese, since every target here is a Chinese rendering). Sent as
     * part of the system prompt so the wording follows Chinese broadcast convention instead of the
     * model's own choice; bounded by the number of matches rather than by the size of the table.
     */
    fun promptSection(anchoredBy: String, targetLanguage: String): String {
        if (!targetLanguage.startsWith("zh")) return ""
        if (anchoredBy.isBlank()) return ""
        val anchored = anchoredSports(anchoredBy)
        val hits = ENTRIES.filter { entry ->
            entry.target.isNotEmpty() &&
                (entry.kind == Kind.ANCHOR || entry.sport in anchored) &&
                entry.pattern.containsMatchIn(anchoredBy)
        }
        if (hits.isEmpty()) return ""
        return hits.joinToString("\n") { "${it.source.ifEmpty { it.match }} → ${it.target}" }
    }

    /**
     * The "use this exact rendering" list for the CLUBS of [teams] — the sides the pipeline has
     * scoped the decoder to — or "" when none is scoped (or the target is not Chinese).
     *
     * The missing half of the fixture's names: a squad member is pinned by [playerPromptSection], but
     * a club has no [ENTRIES] row of its own (it is only a hotword), so nothing else fixes its Chinese
     * spelling and the model is free to render `Brighton` two ways in two sentences. The fixture is
     * stable for the match, so — like the squad list — this rides every request and reuses a gateway's
     * prefix cache.
     *
     * Every spelling the club is recognised by ([Team.detect]) is pinned to the one rendering, not
     * just [Team.label]: a fragment that says only `City`, `United`, `Gunners` or `Magpies` carries
     * no other cue, and the model would otherwise render the nickname by its literal sense
     * (`Magpies` as 喜鹊). The spellings all name the same club, so they all map to its rendering.
     * Only the scoped clubs are sent, so an unscoped channel adds nothing.
     */
    fun clubPromptSection(teams: Set<Team>, targetLanguage: String): String {
        if (!targetLanguage.startsWith("zh")) return ""
        if (teams.isEmpty()) return ""
        return teams.sortedBy { it.ordinal }
            .mapNotNull { team ->
                val chinese = CLUB_CHINESE[team] ?: return@mapNotNull null
                (team.detect + team.label).distinct().joinToString("\n") { "$it → $chinese" }
            }
            .joinToString("\n")
    }

    /**
     * The fixture's names as one prompt block — the scoped CLUBS ([clubPromptSection]) and then their
     * SQUADS ([playerPromptSection]) — or "" when no club is scoped (or the target is not Chinese).
     *
     * Handed to the model as the stable floor of the system prompt: a club and its players are the
     * fixture's names, they do not change for the match, and each has to render the same way in every
     * sentence. The two lists are labelled, so the model can tell `Arsenal → 阿森纳` (a club) from
     * `Saka → 萨卡` (a player) rather than reading one flat table.
     */
    fun fixturePromptSection(teams: Set<Team>, targetLanguage: String): String {
        if (!targetLanguage.startsWith("zh")) return ""
        if (teams.isEmpty()) return ""
        val clubs = clubPromptSection(teams, targetLanguage)
        val squads = playerPromptSection(teams, targetLanguage)
        return listOfNotNull(
            clubs.takeIf { it.isNotBlank() }?.let { "Clubs:\n$it" },
            squads.takeIf { it.isNotBlank() }?.let { "Squads:\n$it" },
        ).joinToString("\n")
    }

    /**
     * The "use this exact rendering" list for the SQUADS of [teams] — the clubs the pipeline has
     * scoped the decoder to — or "" when none is scoped (or the target is not Chinese).
     *
     * Separate from [promptSection], which pins the entries the window actually contains: this is sent
     * for the whole fixture and does not depend on the sentence, because a surname that doubles as an
     * ordinary word (`Rice`, `Mount`, `Wood`) is exactly the case where a fragment translated without
     * context can be rendered as the noun. The floor is stable for the match and sits near the front of
     * the system prompt, so an incremental request reuses it under a gateway's prefix cache.
     *
     * The spoken SURNAME is the left-hand side because that is the form a commentary says (see
     * [spokenSurname]). A name the table already pins through [promptSection] — the marquee players of
     * [ENTRIES] — is left out here so the two lists never disagree; a name whose accepted rendering is
     * not recorded in [PLAYER_CHINESE] is left to the model, not guessed.
     *
     * Where TWO of the fixture's squad members render to the same Chinese word, the plain surname
     * line is ambiguous — `Wilson → 威尔逊` cannot say WHICH Wilson — so each of that pair gets its
     * whole name pinned as well, the given name qualified ([GIVEN_CHINESE]) exactly as Chinese
     * broadcast does: `Bruno Fernandes → 布鲁诺·费尔南德斯` beside `Enzo Fernandez → 恩佐·费尔南
     * 德斯`. The surname line still rides for a bare-surname mention, but a commentary that says the
     * whole name now has the means to tell the two apart. Only a colliding pair costs the extra line.
     */
    fun playerPromptSection(teams: Set<Team>, targetLanguage: String): String {
        if (!targetLanguage.startsWith("zh")) return ""
        if (teams.isEmpty()) return ""
        val pinned = ENTRIES.filter { it.target.isNotEmpty() }
        // (full name, spoken surname, Chinese rendering), already narrowed to the scoped squads and
        // the names the table can pin.
        val squad = teams.sortedBy { it.ordinal }
            .flatMap { TEAM_PLAYERS[it].orEmpty() }
            .distinct()
            .mapNotNull { fullName ->
                val chinese = PLAYER_CHINESE[fullName] ?: return@mapNotNull null
                val surname = spokenSurname(fullName)
                if (pinned.any { it.pattern.containsMatchIn(surname) }) return@mapNotNull null
                Triple(fullName, surname, chinese)
            }
        // The renderings more than one of those players shares: the surname line cannot tell them
        // apart, so each of their whole names is pinned too.
        val colliding = squad.groupBy { it.third }
            .filterValues { it.size > 1 }
            .values.flatten()
            .map { it.first }
            .toSet()
        return squad.flatMap { (fullName, surname, chinese) ->
            val plain = "$surname → $chinese"
            val given = GIVEN_CHINESE[fullName.substringBefore(' ')]
            if (fullName !in colliding || given == null) {
                listOf(plain)
            } else {
                listOf(plain, "$fullName → $given·$chinese")
            }
        }
            .distinct()
            .joinToString("\n")
    }

    /**
     * The sports proven by an [Kind.ANCHOR] entry — or by a score numeral beside a
     * [Entry.provenByScoreline] entry — in [text]; nothing is applied without one.
     */
    private fun anchoredSports(text: String): Set<Sport> {
        val sports = mutableSetOf<Sport>()
        for (entry in ENTRIES) {
            if (entry.kind == Kind.ANCHOR && entry.pattern.containsMatchIn(text)) {
                sports += entry.sport
            } else if (entry.provenByScoreline && isScoreline(text, entry.match)) {
                sports += entry.sport
            }
        }
        return sports
    }

    /**
     * [word] sitting directly beside a score numeral ("one nil", "nil to two"), or repeated next to
     * itself ("nil nil") — the proof a bare scoreline carries when it holds no anchor word. Spelled
     * numbers and digits both count, so "three nil at half time" reads the same as a bare "one
     * nil", and a hyphen is accepted so "two-nil" still matches. Only the SPOKEN numerals are read
     * here: a homophone beside `word` ("to nil") is too weak to prove football by itself (see
     * [footballEvidenced]).
     */
    private fun isScoreline(text: String, word: String): Boolean {
        // The three shapes are compiled ONCE per proven word and reused. This runs from
        // [anchoredSports], which [correct] performs on every recognised partial on the same worker
        // that has to keep draining the live PCM queue — compiling the three patterns inside the call
        // made an unchanging pattern a fresh allocation on every partial. The table's proven word is
        // fixed, so the map holds one entry after the first call.
        val patterns = scorelinePatterns.getOrPut(word) {
            val token = Regex.escape(word)
            listOf(
                Regex("(?<!$EDGE)(?:$SPOKEN_NUMBER)[-\\s]+$token(?!$EDGE)", RegexOption.IGNORE_CASE),
                Regex("(?<!$EDGE)$token[-\\s]+(?:$SPOKEN_NUMBER)(?!$EDGE)", RegexOption.IGNORE_CASE),
                Regex("(?<!$EDGE)$token[-\\s]+$token(?!$EDGE)", RegexOption.IGNORE_CASE),
            )
        }
        for (pattern in patterns) if (pattern.containsMatchIn(text)) return true
        return false
    }

    /** Compiled [isScoreline] shapes per proven word; see there for why they are cached. */
    private val scorelinePatterns = java.util.concurrent.ConcurrentHashMap<String, List<Regex>>()

    /**
     * True when [text] carries evidence strong enough to read a scoreline in it: a football anchor
     * (or an already-spoken scoreline) per [anchoredSports], a club or competition name, or two
     * nil-forms together. A lone homophone pair ("to nil") is deliberately NOT evidence — "reduce
     * to nil" is not a score — so that weaker proof is only read once football is evidenced some
     * other way, and ordinary television is left exactly as it was.
     *
     * [anchored] is the [anchoredSports] set the caller already computed for this same window:
     * [correct] scans the whole table once to anchor the gate, and re-deriving it here repeated that
     * scan — a per-entry regex pass over the growing hypothesis — on every recognised partial, on
     * the same worker that has to keep draining the live PCM queue.
     */
    private fun footballEvidenced(text: String, anchored: Set<Sport>): Boolean =
        Sport.FOOTBALL in anchored ||
            FOOTBALL_EVIDENCE.containsMatchIn(text) ||
            DOUBLED_NIL.containsMatchIn(text)

    /**
     * [text] with a scoreline's misheard tokens set right: the recogniser writes `nil` as
     * `Nill`/`Neil`, and a score numeral as its homophone (`two` as `to`/`too`, `one` as `won`), so
     * "two nil" can arrive as "to nil" or "won Nill". Only tokens inside a scoreline shape — a
     * numeral beside a nil-form, or two nil-forms together — are touched, so an ordinary
     * `Neil`/`won`/`to` elsewhere is left alone. The caller decides whether the text is football at
     * all (see [footballEvidenced]).
     */
    private fun normaliseScoreline(text: String): String {
        var out = SCORELINE_PAIR.replace(text) { m ->
            numeral(m.groupValues[1]) + m.groupValues[2] + "nil"
        }
        out = SCORELINE_PAIR_REVERSED.replace(out) { m ->
            "nil" + m.groupValues[2] + numeral(m.groupValues[3])
        }
        return DOUBLED_NIL.replace(out) { m -> "nil" + m.groupValues[2] + "nil" }
    }

    /**
     * [word] as the score numeral it is: a homophone is mapped to the numeral it stands for
     * (`too` → `two`), anything else — a digit or a spelled number — is left exactly as spoken.
     */
    private fun numeral(word: String): String =
        NUMERAL_HOMOPHONES[word.lowercase()]?.let { matchCase(it, word) } ?: word

    /** A `<numeral> <nil-form>` pair — the shape a scoreline has when the score is spoken first. */
    private val SCORELINE_PAIR = Regex(
        "(?<!$EDGE)($HEARD_NUMBER)([-\\s]+)($NIL_WORD)(?!$EDGE)",
        RegexOption.IGNORE_CASE,
    )

    /** The same pair with the score spoken after the word: `nil` too. */
    private val SCORELINE_PAIR_REVERSED = Regex(
        "(?<!$EDGE)($NIL_WORD)([-\\s]+)($HEARD_NUMBER)(?!$EDGE)",
        RegexOption.IGNORE_CASE,
    )

    /** A bare "nil nil": a scoreline with no numeral in it at all. */
    private val DOUBLED_NIL = Regex(
        "(?<!$EDGE)($NIL_WORD)([-\\s]+)($NIL_WORD)(?!$EDGE)",
        RegexOption.IGNORE_CASE,
    )

    /** The ordinary words the recogniser swaps in for a score numeral, and what each one means. */
    private val NUMERAL_HOMOPHONES = mapOf("to" to "two", "too" to "two", "won" to "one")
}

/** Whole-word, case-insensitive match of [word]: `pot` never matches inside `potential`. */
private fun wordPattern(word: String): Regex = Regex(
    "(?<![\\p{L}\\p{N}'’-])" + Regex.escape(word) + "(?![\\p{L}\\p{N}'’-])",
    RegexOption.IGNORE_CASE,
)

/**
 * The pattern `disambiguate` rewrites a player's spoken form to: the surname ([match]'s spelling)
 * with the given [source] carries optionally consumed ahead of it.
 *
 * The optional given name is what keeps the expansion from doubling a name the commentary already
 * spoke in full: a bare surname is expanded (`Salah` → `Mohamed Salah`), but `Mohamed Salah` is
 * rewritten to itself — never `Mohamed Mohamed Salah` — and the same holds when the recogniser has
 * split the given name from the surname by a hyphen or an apostrophe. Without it the doubled name
 * was handed to both the translator's text and its context, and to the Google fallback.
 *
 * The surname is rebuilt from [match] rather than read out of [source] because an entry spells the
 * recogniser's form (`mbappe`) and the canonical one (`Mbappé`) differently, and it is the
 * recogniser's form that has to be found in the text. A mononym (a [source] with no given name) has
 * nothing to consume and is just [wordPattern].
 */
private fun fullNamePattern(match: String, source: String): Regex {
    val tokens = source.trim().split(Regex("\\s+"))
    if (tokens.size <= 1) return wordPattern(match)
    val given = tokens.dropLast(1).joinToString(" ")
    val surname = match.split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() }
        .joinToString("[\\s'’-]*") { Regex.escape(it) }
    return Regex(
        "(?<![\\p{L}\\p{N}'’-])(?:${Regex.escape(given)}[\\s'’-]+)?$surname(?![\\p{L}\\p{N}'’-])",
        RegexOption.IGNORE_CASE,
    )
}