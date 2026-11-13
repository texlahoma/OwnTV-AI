package tv.own.owntv.core.theme

/**
 * The one place OwnTV's colour values are written down, shared by the TV app and the mobile app.
 *
 * Values are ARGB longs rather than `androidx.compose.ui.graphics.Color` because core carries the
 * Compose *runtime* only — no `compose-ui` — and because the two apps build different colour
 * schemes from them (`androidx.tv.material3` on the TV, `androidx.compose.material3` here). Each
 * app wraps a value once: `Color(OwnTVPalette.DarkBackground)`.
 *
 * Dark uses a near-black background (#040e0b) so the panel colours pop against the deep dark
 * surface while keeping a subtle green undertone. NEUTRAL and the secondary/tertiary roles are
 * theme-only; the `primary` roles are seeded per [AccentColor] (default teal).
 *
 * The three `*RailPanel` / `*ContentPanel` / `*PreviewPanel` values are not part of the M3 ladder:
 * they are the shell's per-region colour identity, keeping the navigation, the content and the
 * detail pane distinct in both themes instead of collapsing into the greyer generic elevation
 * steps. Both apps draw those same three regions, so they belong here rather than in either one.
 */
object OwnTVPalette {

    /** Brand mark colour (the OwnTV play logo) — constant, and the default teal accent. */
    const val AccentCyan = 0xFF52DBC8L

    // ---------------- DARK (M3 dark over near-black #040e0b) ----------------
    const val DarkBackground = 0xFF040E0BL
    const val DarkSurface = 0xFF0E1513L
    const val DarkSurfaceContainerLowest = 0xFF090F0EL
    const val DarkSurfaceContainerLow = 0xFF161D1BL
    const val DarkSurfaceContainer = 0xFF1B211FL
    const val DarkSurfaceContainerHigh = 0xFF252B29L
    const val DarkSurfaceContainerHighest = 0xFF303634L
    const val DarkContentPanel = 0xFF0A1512L
    const val DarkPreviewPanel = 0xFF15201DL
    const val DarkOnSurface = 0xFFDEE4E1L
    const val DarkOnSurfaceVariant = 0xFFBFC9C4L
    const val DarkOutline = 0xFF89938FL
    const val DarkOutlineVariant = 0xFF3F4945L
    const val DarkSecondary = 0xFFB1CCC3L
    const val DarkOnSecondary = 0xFF1C352EL
    const val DarkSecondaryContainer = 0xFF334B44L
    const val DarkOnSecondaryContainer = 0xFFCDE8DFL
    const val DarkTertiary = 0xFFA9CBE4L
    const val DarkOnTertiary = 0xFF0B3445L
    const val DarkTertiaryContainer = 0xFF294B5DL
    const val DarkOnTertiaryContainer = 0xFFC5E7FFL
    const val DarkError = 0xFFFFB4ABL

    // ---------------- LIGHT (M3 light) ----------------
    const val LightBackground = 0xFFF5FBF8L
    const val LightSurface = 0xFFF5FBF8L
    const val LightSurfaceContainerLowest = 0xFFFFFFFFL
    const val LightSurfaceContainerLow = 0xFFEFF5F2L
    const val LightSurfaceContainer = 0xFFE9EFECL
    const val LightSurfaceContainerHigh = 0xFFE3EAE6L
    const val LightSurfaceContainerHighest = 0xFFDEE4E1L
    const val LightContentPanel = 0xFFF2F7F4L
    const val LightPreviewPanel = 0xFFDEE9E3L
    const val LightOnSurface = 0xFF171D1BL
    const val LightOnSurfaceVariant = 0xFF3F4945L
    const val LightOutline = 0xFF6F7975L
    const val LightOutlineVariant = 0xFFBFC9C4L
    const val LightSecondary = 0xFF4B635CL
    const val LightOnSecondary = 0xFFFFFFFFL
    const val LightSecondaryContainer = 0xFFCDE8DFL
    const val LightOnSecondaryContainer = 0xFF07201AL
    const val LightTertiary = 0xFF416278L
    const val LightOnTertiary = 0xFFFFFFFFL
    const val LightTertiaryContainer = 0xFFC5E7FFL
    const val LightOnTertiaryContainer = 0xFF001E2FL
    const val LightError = 0xFFBA1A1AL
}

/**
 * The four M3 primary roles for one accent on one theme, as ARGB longs.
 *
 * They come either from a preset's table ([roles]) or are generated from the user's custom hex
 * seed ([accentRolesFromSeed]).
 */
data class AccentRoleValues(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
)

/**
 * The tonal palette each [AccentColor] seeds the M3 colour scheme with, for both themes (M3 uses
 * lighter tones on dark surfaces, darker tones on light).
 *
 * The display label belongs to whichever app is drawing the settings screen, so it is not here.
 */
private class AccentPalette(val dark: AccentRoleValues, val light: AccentRoleValues)

private val TealPalette = AccentPalette(
    dark = AccentRoleValues(0xFF52DBC8L, 0xFF003730L, 0xFF004F46L, 0xFF6FF8E4L),
    light = AccentRoleValues(0xFF006B5EL, 0xFFFFFFFFL, 0xFF6FF8E4L, 0xFF00201BL),
)

private val BluePalette = AccentPalette(
    dark = AccentRoleValues(0xFF6FB0FFL, 0xFF00315CL, 0xFF134A7CL, 0xFFD3E4FFL),
    light = AccentRoleValues(0xFF1565C0L, 0xFFFFFFFFL, 0xFFD6E3FFL, 0xFF001C3AL),
)

private val VioletPalette = AccentPalette(
    dark = AccentRoleValues(0xFFCBBEFFL, 0xFF312170L, 0xFF483A88L, 0xFFE7DEFFL),
    light = AccentRoleValues(0xFF5B45C9L, 0xFFFFFFFFL, 0xFFE5DEFFL, 0xFF190066L),
)

private val GreenPalette = AccentPalette(
    dark = AccentRoleValues(0xFF6FDB94L, 0xFF00391CL, 0xFF1F5135L, 0xFF8BF8AFL),
    light = AccentRoleValues(0xFF1B6B3FL, 0xFFFFFFFFL, 0xFFA6F2C0L, 0xFF00210FL),
)

private val AmberPalette = AccentPalette(
    dark = AccentRoleValues(0xFFFFB95CL, 0xFF452B00L, 0xFF624000L, 0xFFFFDDB3L),
    light = AccentRoleValues(0xFF8A5100L, 0xFFFFFFFFL, 0xFFFFDDB3L, 0xFF2C1600L),
)

/** The preset's four primary-role values for the given theme. */
fun AccentColor.roles(isDark: Boolean): AccentRoleValues {
    val palette = when (this) {
        AccentColor.TEAL -> TealPalette
        AccentColor.BLUE -> BluePalette
        AccentColor.VIOLET -> VioletPalette
        AccentColor.GREEN -> GreenPalette
        AccentColor.AMBER -> AmberPalette
    }
    return if (isDark) palette.dark else palette.light
}

/** Parses "#RRGGBB" / "RRGGBB" (also 8-digit AARRGGBB) into an ARGB long; null when invalid. */
fun parseAccentHex(hex: String): Long? {
    val s = hex.trim().removePrefix("#")
    return runCatching {
        when (s.length) {
            6 -> 0xFF000000L or s.toLong(16)
            8 -> s.toLong(16)
            else -> null
        }
    }.getOrNull()
}

/**
 * Generate tonal primary roles from an arbitrary seed colour (the custom hex accent).
 * The seed is used EXACTLY as `primary` so the user's hex renders true; only the supporting
 * contrast roles (onPrimary / containers) are derived by nudging the seed's lightness.
 */
fun accentRolesFromSeed(seed: Long, isDark: Boolean): AccentRoleValues {
    val argb = seed.toInt()
    // Choose a readable foreground for text/icons drawn on top of the exact seed colour.
    val onPrimary = if (androidx.core.graphics.ColorUtils.calculateLuminance(argb) > 0.5) {
        0xFF000000L
    } else {
        0xFFFFFFFFL
    }
    return if (isDark) {
        AccentRoleValues(seed, onPrimary, argb.withLightness(0.26f), argb.withLightness(0.90f))
    } else {
        AccentRoleValues(seed, onPrimary, argb.withLightness(0.88f), argb.withLightness(0.10f))
    }
}

/** Keep the seed's hue/saturation but pin the HSL lightness — a cheap stand-in for M3 tones. */
private fun Int.withLightness(l: Float): Long {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(this, hsl)
    hsl[2] = l
    return androidx.core.graphics.ColorUtils.HSLToColor(hsl).toLong() and 0xFFFFFFFFL
}
