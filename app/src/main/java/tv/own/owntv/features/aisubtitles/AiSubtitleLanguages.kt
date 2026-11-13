package tv.own.owntv.features.aisubtitles

import java.util.Locale

/**
 * Languages offered for AI speech recognition (source) and translation (target), as BCP-47 codes.
 * Shared by the global Settings screen and the in-player AI-subtitle dialog so both offer the same
 * list. Kept deliberately modest — Whisper, the Kroko streaming engines and most cloud gateways
 * cover these well.
 */
object AiSubtitleLanguages {

    val CODES: List<String> = listOf(
        "en", "zh", "ja", "ko", "fr", "de", "es", "ru", "ar", "pt", "it",
        "nl", "hi", "id", "tr", "th", "vi", "pl", "uk", "sv",
    )

    /** "en" → the language's own, user-localized display name, or [code] when Locale is stumped. */
    fun displayName(code: String, displayLocale: Locale): String =
        runCatching { Locale.forLanguageTag(code).getDisplayName(displayLocale) }
            .getOrElse { code }
            .ifBlank { code }

    /** [CODES] as (code, localized display name) pairs for pickers. */
    fun localized(displayLocale: Locale): List<Pair<String, String>> =
        CODES.map { code -> code to displayName(code, displayLocale) }

    /** The display name of [code], or null when it is not one of [CODES]. */
    fun nameOf(code: String, displayLocale: Locale): String? =
        CODES.firstOrNull { it == code }?.let { displayName(it, displayLocale) }
}