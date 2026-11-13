package tv.own.owntv.ui.components

import java.text.NumberFormat

/** Locale-aware grouped count for presentation helpers that cannot call Compose directly. */
fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)
