package tv.own.owntv.features.multiview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.live.OpenStreamRegistry
import tv.own.owntv.core.live.StreamGrant
import tv.own.owntv.core.live.StreamPurpose
import tv.own.owntv.core.live.connectionBudget
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.player.LiveEnginePool

/**
 * How many tiles a grid opens with: two, or the ceiling when the user set it lower.
 *
 * Two because Multiview is for watching more than one thing — opening at one would be
 * indistinguishable from full screen — and because the second tile is the invitation: it says
 * "+ Add channel" and is one press away. Everything beyond the second is asked for.
 */
private fun openingTileCount(maxTiles: Int): Int = minOf(2, maxTiles).coerceAtLeast(1)

/** One tile of the grid: empty, playing a channel, or explaining why it could not start. */
data class MultiviewTile(
    val channel: ChannelEntity? = null,
    /** Set when the playlist had no connection to spare — the tile shows this instead of a picture. */
    val refusal: StreamGrant.Refused? = null,
    /**
     * Set when the *device* could not carry this tile: the provider had already allowed it, and the
     * picture stopped anyway. Four simultaneous 4K streams is the real-world case.
     */
    val deviceLimit: Boolean = false,
) {
    val isEmpty: Boolean get() = channel == null && refusal == null && !deviceLimit
}

/**
 * What the Multiview grid is currently showing, and the rules for changing it.
 *
 * Held by the shell for as long as the grid is open, and thrown away with it. Everything that
 * decides *whether* a tile may tune is core's ([connectionBudget] over [OpenStreamRegistry]); this
 * class only asks, and remembers the answer so the tile can say it.
 */
@UnstableApi
class MultiviewState(
    val pool: LiveEnginePool,
    private val registry: OpenStreamRegistry,
    private val live: LiveViewModel,
    /**
     * The **most** tiles this grid may have, from Settings — not how many it opens with.
     *
     * The setting used to be the literal size, so choosing four meant every grid was four, and
     * watching two channels was impossible without going back into Settings to change a number. The
     * number the user picks is the ceiling their television and their account can stand; how many to
     * actually watch is a decision made in front of the grid, every time.
     */
    private val maxTiles: Int,
) {
    /**
     * The tiles on screen now. Grows and shrinks from the end only, which is what keeps a tile's
     * index — and therefore its engine in [LiveEnginePool] — the same for as long as it exists.
     */
    val tiles = mutableStateListOf<MultiviewTile>().apply {
        repeat(openingTileCount(maxTiles)) { add(MultiviewTile()) }
    }

    /** Room for one more? */
    val canAddTile: Boolean get() = tiles.size < maxTiles

    /** One more empty tile for the user to fill. */
    fun addTile() {
        if (canAddTile) tiles.add(MultiviewTile())
    }

    /** Which tile the D-pad is on. */
    var focused by mutableIntStateOf(0)
        private set

    /** Which tile has the sound. Kept here as well as in the pool so the badge can be drawn. */
    var audible by mutableIntStateOf(0)
        private set

    private val claims = HashMap<Int, OpenStreamRegistry.Claim>()

    /**
     * Tiles playing sound with no picture — the background-audio case: watch one
     * channel while another's commentary plays behind it.
     *
     * It costs a provider connection exactly as a picture does, because dropping the video track does
     * not close the stream, so the tile says so instead of looking free.
     */
    val soundOnly = mutableStateListOf<Int>()

    fun setSoundOnly(tile: Int, value: Boolean) {
        if (tiles.getOrNull(tile)?.channel == null) return
        pool.setSoundOnly(tile, value)
        if (value) {
            if (tile !in soundOnly) soundOnly.add(tile)
            audible = tile
        } else {
            soundOnly.remove(tile)
        }
    }

    fun focus(tile: Int) {
        if (tile in tiles.indices) focused = tile
    }

    /**
     * A tile whose engine has failed keeps its audio track playing — the picture is what failed, not
     * the stream — so a tile reading "Couldn't play this channel" was still making a noise. Silence
     * it, and move the sound to a tile that is actually showing something.
     */
    fun silenceFailed(tile: Int) {
        pool.peek(tile)?.setMuted(true)
        if (audible != tile) return
        val next = tiles.indices.firstOrNull { it != tile && tiles[it].channel != null && !failed(it) }
        if (next != null) giveSoundTo(next) else pool.giveSoundTo(null)
    }

    private fun failed(tile: Int): Boolean =
        pool.peek(tile)?.state?.value == tv.own.owntv.player.LivePreviewEngine.State.ERROR

    /** Give the sound to a filled tile. An empty one keeps whatever had it — there is nothing to hear. */
    fun giveSoundTo(tile: Int) {
        if (tiles.getOrNull(tile)?.channel == null) return
        // A failed tile has no sound to give, so handing it the sound would only un-mute the audio of
        // a channel that is not on screen.
        if (failed(tile)) return
        audible = tile
        pool.giveSoundTo(tile)
    }

    /**
     * Put [channel] in [tile], if that playlist has a connection to spare.
     *
     * Tiles and recordings count against one budget, per playlist, and a tile
     * that loses is told why instead of being left to fail into a spinner. Refilling a tile releases
     * what it held first, so swapping a channel never counts as two.
     */
    fun fill(tile: Int, channel: ChannelEntity) {
        if (tile !in tiles.indices) return
        releaseClaim(tile)
        val source = live.sourceOf(channel)
        when (val grant = connectionBudget(source, registry.openOn(channel.sourceId), StreamPurpose.WATCHING)) {
            is StreamGrant.Refused -> {
                tiles[tile] = MultiviewTile(refusal = grant)
                return
            }
            StreamGrant.Allowed -> Unit
        }
        claims[tile] = registry.claim(channel.sourceId, StreamPurpose.WATCHING)
        tiles[tile] = MultiviewTile(channel = channel)
        // The first channel to arrive takes the sound; after that the user decides.
        val takesSound = tiles.none { it.channel != null && it != tiles[tile] }
        live.tuneTile(pool.engineFor(tile), channel, muted = !takesSound && audible != tile)
        if (takesSound) giveSoundTo(tile) else if (audible == tile) pool.giveSoundTo(tile)
    }

    /**
     * A tile that was playing has stopped, while other tiles from the same playlist carry on.
     *
     * **It does not blame the provider, and it used to.** That version was built on the belief that
     * this portal silently cut the oldest stream; measuring the account directly disproved it — four
     * of its streams run side by side for as long as you like. Every time a stricter rule fired it was
     * wrong, most visibly when it told the user their provider allowed three streams while three of
     * its streams were playing in front of them.
     *
     * What is left is the honest reading. The connection budget had already established the provider
     * allows this many, so a picture that stops after that is the television reaching its own limit —
     * decoders, bandwidth or video planes — and that is what the tile says.
     */
    fun refuseDeviceLimit(tile: Int) {
        if (tiles.getOrNull(tile)?.channel == null) return
        releaseClaim(tile)
        pool.release(tile)
        soundOnly.remove(tile)
        tiles[tile] = MultiviewTile(deviceLimit = true)
        // The sound cannot stay on a tile that has none. Hand it to a tile that still plays.
        if (audible == tile) {
            val next = tiles.indexOfFirst { it.channel != null }
            if (next >= 0) giveSoundTo(next) else pool.giveSoundTo(null)
        }
    }

    /**
     * Empty a tile: stop its engine, hand the connection back, and forget the refusal it was showing.
     *
     * Emptying the *last* tile also removes it, so "Remove tile" shrinks a grid the user has finished
     * with instead of leaving a permanent hole. Only ever from the end, so no surviving tile changes
     * index and no engine is silently handed to a different channel.
     */
    fun clear(tile: Int) {
        if (tile !in tiles.indices) return
        releaseClaim(tile)
        pool.release(tile)
        soundOnly.remove(tile)
        tiles[tile] = MultiviewTile()
        while (tiles.size > openingTileCount(maxTiles) && tiles.last().isEmpty) {
            pool.release(tiles.lastIndex)
            tiles.removeAt(tiles.lastIndex)
        }
        if (focused > tiles.lastIndex) focused = tiles.lastIndex
        if (audible == tile) audible = tiles.indexOfFirst { it.channel != null }.coerceAtLeast(0)
    }

    /** Leaving the grid. Every engine and every claim goes, or the next tile is refused for a ghost. */
    fun releaseAll() {
        claims.values.forEach { registry.release(it) }
        claims.clear()
        pool.releaseAll()
    }

    private fun releaseClaim(tile: Int) {
        claims.remove(tile)?.let { registry.release(it) }
    }
}
