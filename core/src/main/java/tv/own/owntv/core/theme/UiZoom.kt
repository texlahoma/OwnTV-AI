package tv.own.owntv.core.theme

/**
 * Global UI scale as a percentage, applied by overriding [androidx.compose.ui.platform.LocalDensity]
 * so every dp and sp grows/shrinks uniformly. Users fine-tune the 10-foot layout anywhere in
 * [MIN]..[MAX] in [STEP] increments. Persisted via DataStore and adjusted from Settings.
 */
object UiZoom {
    const val MIN = 50
    const val MAX = 150
    const val DEFAULT = 90
    const val STEP = 5

    /** Below this, low-RAM devices (2 GB sticks) can run out of memory from the extra on-screen
     *  items — the zoom dialog shows an accept-the-risk warning before stepping under it. */
    const val LOW_RAM_WARN = 85

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)
    fun factor(percent: Int): Float = percent / 100f
}
