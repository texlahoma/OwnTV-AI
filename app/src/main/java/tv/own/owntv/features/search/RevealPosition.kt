package tv.own.owntv.features.search

import androidx.paging.PagingSource

/**
 * Search's "Go to": where [id] sits in a list's own query, read in large pages off the main thread so
 * the screen can scroll straight there instead of paging through the list on screen. This is the raw
 * position, before the list's hide/move filters: the row is at or above it, so scrolling there loads
 * the right part of the list and the screen then finds the row by id. Null when it is not in the list.
 */
suspend fun <T : Any> PagingSource<Int, T>.positionOf(id: Long, idOf: (T) -> Long, maxRows: Int = 60_000): Int? {
    var offset = 0
    while (offset < maxRows) {
        val page = load(PagingSource.LoadParams.Append(offset, REVEAL_PAGE, false)) as? PagingSource.LoadResult.Page ?: return null
        val i = page.data.indexOfFirst { idOf(it) == id }
        if (i >= 0) return offset + i
        if (page.data.isEmpty() || page.nextKey == null) return null
        offset += page.data.size
    }
    return null
}

private const val REVEAL_PAGE = 1000

/** A pending "Go to": the category to show, the item, and its raw position in that category's list. */
data class Reveal(val key: tv.own.owntv.core.live.LiveKey, val id: Long, val position: Int)
