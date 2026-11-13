package tv.own.owntv.features.home

/**
 * The Trending pager under the hero buttons: ◀ ▶ on it go to the previous / next title. ▶ on the last
 * title wraps to the first, as the automatic advance does. ◀ on the first returns null: the key is not
 * the pager's, so focus moves left and the rail opens, as ◀ does from any left-most control.
 */
fun trendingPagerTarget(index: Int, count: Int, delta: Int): Int? = when {
    count < 2 -> null
    delta < 0 && index <= 0 -> null
    delta < 0 -> index - 1
    else -> (index + 1) % count
}
