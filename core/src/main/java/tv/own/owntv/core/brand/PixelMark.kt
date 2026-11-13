package tv.own.owntv.core.brand

/**
 * "Pixel": today's TV-set silhouette rebuilt as a dot matrix, on the same
 * 100-unit grid as `owntv_mark_pixel`. Both apps draw it in code so the play triangle can take the
 * accent: each [Dot] is a circle of radius [DOT_RADIUS], plus one power light.
 */
object PixelMark {
    enum class Kind { BODY, TRIANGLE, SIDE }

    data class Dot(val x: Float, val y: Float, val kind: Kind)

    const val DOT_RADIUS = 2.1f
    const val POWER_X = 76f
    const val POWER_Y = 24f
    const val POWER_RADIUS = 4.2f

    const val BODY = 0xFFF5EDDAL
    const val SIDE = 0xFFCBB795L
    const val POWER = 0xFFE24B36L

    val dots: List<Dot> by lazy {
        fun inRoundRect(x: Float, y: Float): Boolean {
            if (x < 10 || x > 90 || y < 12 || y > 88) return false
            val cx = x.coerceIn(28f, 72f)
            val cy = y.coerceIn(30f, 70f)
            return (x - cx) * (x - cx) + (y - cy) * (y - cy) <= 18f * 18f
        }
        fun inTriangle(x: Float, y: Float): Boolean {
            fun s(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) = (px - rx) * (qy - ry) - (qx - rx) * (py - ry)
            val d1 = s(x, y, 38f, 30f, 38f, 70f)
            val d2 = s(x, y, 38f, 70f, 72f, 50f)
            val d3 = s(x, y, 72f, 50f, 38f, 30f)
            return !((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))
        }
        val out = mutableListOf<Dot>()
        var y = 14f
        while (y <= 86f) {
            var x = 14f
            while (x <= 86f) {
                if (inRoundRect(x, y) && (x - 76) * (x - 76) + (y - 24) * (y - 24) >= 40) {
                    out += Dot(x, y, if (inTriangle(x, y)) Kind.TRIANGLE else Kind.BODY)
                }
                x += 4.5f
            }
            y += 4.5f
        }
        for (x in listOf(5f, 95f)) for (sy in listOf(45.5f, 50f, 54.5f)) out += Dot(x, sy, Kind.SIDE)
        out
    }
}
