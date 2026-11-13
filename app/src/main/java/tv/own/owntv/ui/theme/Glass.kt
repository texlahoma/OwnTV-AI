package tv.own.owntv.ui.theme

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import tv.own.owntv.core.theme.GlassConfig

/*
 * What Glass needs besides [stageGlass][tv.own.owntv.ui.stage.stageGlass]: the user's Glass settings,
 * the blurred copy of the background picture that glass panels frost, and each panel's on-screen
 * position so it can draw the part of that picture behind it.
 */

/** The user's Glass settings, provided by MainActivity. Default is "off". */
val LocalGlass = compositionLocalOf { GlassConfig() }

/**
 * The single shared blurred copy of the background picture. Provided by `MainActivity` while a
 * picture is shown; null otherwise.
 *
 * [rootSizePx] is the full app viewport size in pixels (the area the bitmap stands in for, since the
 * blurred bitmap is a downscaled stand-in). Each glass surface maps its on-screen rect from root px
 * → bitmap coords using [rootSizePx], then draws the matching slice translated to its own position so
 * the frost lines up with the photo behind it.
 */
@Stable
data class BlurredBackdrop(
    /** Ten geometric mip levels, least → most diffuse. Empty on Tier-1-only devices. */
    val frostLevels: List<ImageBitmap>,
    val rootSizePx: Size,
) {
    /** Exact 10% steps use one texture; preset values between steps cross-fade adjacent mips. */
    fun frostFor(strength: Float): FrostSelection? {
        if (frostLevels.isEmpty() || strength <= 0f) return null
        val position = (strength.coerceIn(0f, 1f) * frostLevels.size - 1f)
            .coerceIn(0f, (frostLevels.size - 1).toFloat())
        val lowerIndex = kotlin.math.floor(position).toInt()
        val upperIndex = kotlin.math.ceil(position).toInt()
        return FrostSelection(
            lower = frostLevels[lowerIndex],
            upper = frostLevels[upperIndex].takeIf { upperIndex != lowerIndex },
            upperWeight = position - lowerIndex,
        )
    }
}

@Stable
data class FrostSelection(
    val lower: ImageBitmap,
    val upper: ImageBitmap?,
    val upperWeight: Float,
)

/** Ambient blurred backdrop, or null when blur isn't active. See [BlurredBackdrop]. */
val LocalBlurredBackdrop = compositionLocalOf<BlurredBackdrop?> { null }

/** The resolved Glass & background setting (what sits behind every screen), provided by MainActivity. */
val LocalBackground = compositionLocalOf { tv.own.owntv.core.theme.BackgroundConfig() }

/**
 * Draw the part of the blurred picture that lies behind [bounds] (root px), into this node's own
 * coordinates, so a panel shows a frosted copy of exactly what is behind it. The node must already be
 * clipped to its shape. Float translate/scale on purpose:
 * the integer src/dst overload of drawImage misaligns subpixels under bilinear filtering.
 */
fun DrawScope.drawBackdropSlice(blurred: BlurredBackdrop, selection: FrostSelection, bounds: Rect) {
    val rootW = blurred.rootSizePx.width
    val rootH = blurred.rootSizePx.height
    val lower = selection.lower
    if (rootW <= 0f || rootH <= 0f || bounds.width <= 0f || bounds.height <= 0f || lower.width <= 0) return
    val sx = size.width / bounds.width
    val sy = size.height / bounds.height
    translate(-bounds.left * sx, -bounds.top * sy) {
        scale(rootW / lower.width * sx, rootH / lower.height * sy, pivot = Offset.Zero) {
            drawImage(lower, topLeft = Offset.Zero, alpha = 1f - selection.upperWeight)
        }
        selection.upper?.let { upper ->
            scale(rootW / upper.width * sx, rootH / upper.height * sy, pivot = Offset.Zero) {
                drawImage(upper, topLeft = Offset.Zero, alpha = selection.upperWeight)
            }
        }
    }
}

/**
 * Position holder deliberately outside Compose snapshot state. A scrolling frost surface only needs
 * a redraw when its root-space position changes; recomposition and cache rebuilding would be wasted.
 */
class GlassPositionState(var bounds: Rect = Rect.Zero)

data class GlassPositionElement(
    val position: GlassPositionState,
) : ModifierNodeElement<GlassPositionNode>() {
    override fun create(): GlassPositionNode = GlassPositionNode(position)

    override fun update(node: GlassPositionNode) {
        node.position = position
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassPosition"
    }
}

class GlassPositionNode(
    var position: GlassPositionState,
) : Modifier.Node(), GlobalPositionAwareModifierNode, DrawModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val next = coordinates.boundsInRoot()
        if (next != position.bounds) {
            position.bounds = next
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
    }
}
