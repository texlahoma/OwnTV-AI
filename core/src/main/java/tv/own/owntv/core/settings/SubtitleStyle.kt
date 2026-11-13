package tv.own.owntv.core.settings

/**
 * Shared contract for the custom subtitle look — the single place the three very different
 * subtitle renderers agree on values, defaults and geometry:
 *
 *  - mpv's own OSD (`sub-scale` / `sub-color` / `sub-back-color` / `sub-pos` / `sub-align-x`),
 *  - Media3's [androidx.media3.ui.SubtitleView] (Live TV and the VOD image-subtitle handoff),
 *  - the app-drawn Compose overlay used on mpv's direct-render path.
 *
 * **Every option has its own "Default" value** ([SCALE_DEFAULT], [COLOR_DEFAULT],
 * [Position.DEFAULT], [OPACITY_DEFAULT]) meaning *leave this alone*. Turning the master toggle on
 * therefore changes nothing on its own: only the options the user actually picks are pushed to a
 * renderer, and the rest keep their stock behaviour — including the styling broadcasters embed in
 * Live TV (CEA-608/teletext) cues.
 */
object SubtitleStyle {

    /** Size: 1.0 = the renderer's own text size. */
    const val SCALE_DEFAULT = 1.0f

    /** Color: blank = the renderer's own color (and, for [SubtitleView], embedded broadcaster colors). */
    const val COLOR_DEFAULT = ""

    /** Background transparency: negative = untouched. Otherwise 0 (no box) … 100 (solid). */
    const val OPACITY_DEFAULT = -1
    const val OPACITY_MIN = 0
    const val OPACITY_MAX = 100
    const val OPACITY_STEP = 10

    /** Where the stepper starts when transparency is moved off "Default". */
    const val OPACITY_START = 50

    /** One of six fixed screen anchors, or [DEFAULT] to leave placement to the stream/renderer. */
    enum class Position(val key: String) {
        DEFAULT("default"),
        TOP_LEFT("top_left"),
        TOP_CENTER("top_center"),
        TOP_RIGHT("top_right"),
        BOTTOM_LEFT("bottom_left"),
        BOTTOM_CENTER("bottom_center"),
        BOTTOM_RIGHT("bottom_right");

        val isTop: Boolean get() = this == TOP_LEFT || this == TOP_CENTER || this == TOP_RIGHT
        val isLeft: Boolean get() = this == TOP_LEFT || this == BOTTOM_LEFT
        val isRight: Boolean get() = this == TOP_RIGHT || this == BOTTOM_RIGHT

        companion object {
            fun fromKey(key: String?): Position = entries.firstOrNull { it.key == key } ?: DEFAULT

            /** The six real anchors, in reading order — the 3×2 grid the picker draws. */
            val ANCHORS: List<Position> = listOf(TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT)
        }
    }

    fun hasScale(scale: Float): Boolean = scale != SCALE_DEFAULT

    fun hasColor(hex: String): Boolean = hex.isNotBlank()

    fun hasOpacity(pct: Int): Boolean = pct >= OPACITY_MIN

    fun clampOpacity(pct: Int): Int = pct.coerceIn(OPACITY_MIN, OPACITY_MAX)

    /** Parse "#RRGGBB"/"#AARRGGBB" into an ARGB int, falling back to opaque white. */
    fun colorArgb(hex: String): Int {
        val s = hex.trim().removePrefix("#")
        return runCatching {
            when (s.length) {
                6 -> (0xFF000000L or s.toLong(16)).toInt()
                8 -> s.toLong(16).toInt()
                else -> WHITE
            }
        }.getOrDefault(WHITE)
    }

    /** Black background box at the requested opacity, as an ARGB int. */
    fun backgroundArgb(opacityPct: Int): Int = (clampOpacity(opacityPct) * 255 / 100) shl 24

    /** mpv wants colors as "#AARRGGBB". */
    fun mpvColor(hex: String): String = "#%08X".format(colorArgb(hex))

    fun mpvBackColor(opacityPct: Int): String = "#%08X".format(backgroundArgb(opacityPct))

    // --- Geometry ---------------------------------------------------------------------------
    // mpv `sub-pos` is a percentage of screen height: 100 = bottom (its default), 0 = top.

    const val MPV_POS_BOTTOM = 100
    const val MPV_POS_TOP = 5
    const val MPV_ALIGN_X_DEFAULT = "center"

    fun mpvSubPos(position: Position): Int = if (position.isTop) MPV_POS_TOP else MPV_POS_BOTTOM

    fun mpvAlignX(position: Position): String = when {
        position.isLeft -> "left"
        position.isRight -> "right"
        else -> MPV_ALIGN_X_DEFAULT
    }

    /** Vertical placement as a fraction of the video height, measured from the top edge. */
    fun lineFraction(position: Position): Float = if (position.isTop) 0.05f else 0.95f

    /** Horizontal placement as a fraction of the video width, measured from the left edge. */
    fun positionFraction(position: Position): Float = when {
        position.isLeft -> 0.05f
        position.isRight -> 0.95f
        else -> 0.5f
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
}
