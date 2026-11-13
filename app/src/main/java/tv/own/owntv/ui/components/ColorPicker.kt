package tv.own.owntv.ui.components

/** Convert an HSV triple (h 0..360, s/v 0..1) into an uppercase "#RRGGBB" string. */
fun hsvToHex(h: Float, s: Float, v: Float): String {
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    return "#%06X".format(java.util.Locale.ROOT, argb and 0xFFFFFF)
}
