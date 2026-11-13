package tv.own.owntv.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import android.view.View
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Root-level touch adapter that makes the D-pad-first UI usable with a touch screen (phones / tablets).
 *
 * The app is built for remotes: its *own* interactive components use Compose's touch-aware
 * `selectable` / `combinedClickable` / `clickable`, which already answer taps, move focus and consume the
 * tap so nothing below double-fires. That path is left untouched. This adapter only steps in for the
 * **unconsumed** tap — i.e. a tap on a surface that announces an `OnClick` semantics action but does not
 * handle pointer input itself (notably `androidx.tv.material3.FocusableSurface` semantics, popup scrims
 * and any custom onKeyEvent-only target). For those it moves focus to the tapped node and fires its
 * `OnClick`, so tap and D-pad coexist instead of one being dead.
 *
 * Safety: it never consumes a pointer event, never acts on a tap a descendant consumed
 * (`waitForUpOrCancellation` returns null as soon as a child consumes), and every lookup is guarded, so
 * a failure is a silent no-op — it cannot block scrolling, sliders, text input, seek gestures or crash.
 *
 * Apply once at the root of the composed content (see `Modifier.rootTouchSupport()`).
 */
class TouchTapDispatcher(private val owner: SemanticsOwner) {

    /** Called from the pointer gesture when an unconsumed tap lands at [position] (root coordinates). */
    fun onTap(position: Offset) {
        runCatching { dispatchTap(position) }
    }

    private fun dispatchTap(position: Offset) {
        val target = hitTestClickable(position) ?: return
        // Move focus there first so follow-up D-pad / keyboard navigation stays coherent after the tap.
        target.config.getOrNull(SemanticsActions.RequestFocus)?.action?.let { runCatching { it.invoke() } }
        target.config.getOrNull(SemanticsActions.OnClick)?.action?.let { runCatching { it.invoke() } }
    }

    /**
     * Deepest merged semantics node whose touch bounds contain [position] and which exposes an `OnClick`.
     * Deepest wins (most specific); ties resolved by smallest area. Null when nothing qualifies.
     */
    private fun hitTestClickable(position: Offset): SemanticsNode? {
        var best: SemanticsNode? = null
        var bestDepth = -1
        var bestArea = Float.MAX_VALUE

        fun visit(node: SemanticsNode, depth: Int) {
            val bounds = node.touchBoundsInRoot
            if (!bounds.isEmpty && bounds.inflate(TOUCH_INFLATE_PX).contains(position)) {
                if (node.config.getOrNull(SemanticsActions.OnClick)?.action != null) {
                    val area = bounds.width * bounds.height
                    if (best == null || depth > bestDepth || (depth == bestDepth && area < bestArea)) {
                        best = node
                        bestDepth = depth
                        bestArea = area
                    }
                }
            }
            for (child in node.children) visit(child, depth + 1)
        }

        runCatching { visit(owner.rootSemanticsNode, 0) }
        return best
    }
}

/**
 * Reads the composed view's semantics owner so [TouchTapDispatcher] can be built from the actual UI root.
 * Returns null when the local view is not (yet) a Compose view — callers then simply skip touch support.
 */
@Composable
fun rememberTouchTapDispatcher(): TouchTapDispatcher? {
    val view = LocalView.current
    val owner = remember(view) { semanticsOwnerOf(view) }
    return remember(owner) { owner?.let { TouchTapDispatcher(it) } }
}

/**
 * `androidx.compose.ui.platform.AndroidComposeView` exposes a `semanticsOwner` property, but the class is
 * `internal` in recent Compose UI (BOM 2026.x), so it cannot be cast at compile time. Read the property via
 * reflection instead — the production class name is stable and the accessor survives Kotlin 2.x.
 */
private fun semanticsOwnerOf(view: View?): SemanticsOwner? {
    if (view == null) return null
    // Walk up the type hierarchy until we land on the Compose view so the lookup also works for subclasses.
    var clazz: Class<*>? = view.javaClass
    while (clazz != null && clazz.name != "androidx.compose.ui.platform.AndroidComposeView") {
        clazz = clazz.superclass
    }
    val composeClass = clazz ?: return null
    return runCatching {
        val getter = composeClass.getDeclaredMethod("getSemanticsOwner")
        getter.isAccessible = true // internal still compiles to a plain JVM method; just not accessible by name.
        getter.invoke(view) as? SemanticsOwner
    }.getOrNull() ?: runCatching {
        val field = composeClass.getDeclaredField("semanticsOwner")
        field.isAccessible = true
        field.get(view) as? SemanticsOwner
    }.getOrNull()
}

/**
 * Add to the root container. Listens for taps no descendant consumed and routes them into the semantics
 * tree (focus + `OnClick`), leaving every touch-aware component and each drag gesture alone.
 */
fun Modifier.rootTouchSupport(dispatcher: TouchTapDispatcher?): Modifier {
    if (dispatcher == null) return this
    return pointerInput(dispatcher) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // A non-null `up` means the pointer went down and straight back up with nothing consuming it —
            // a genuine unconsumed tap that belongs to this adapter (movement/cancellation/child-consumption
            // all make this return null — and a null here bails before any dispatch). Route it to the
            // semantics tree without holding any events.
            withTimeoutOrNull(TAP_TIMEOUT_MS) { waitForUpOrCancellation() } ?: return@awaitEachGesture
            dispatcher.onTap(down.position)
        }
    }
}

// Touch hit-testing tolerance in px added around a node's declared touch bounds (finger jitter on a phone).
private const val TOUCH_INFLATE_PX = 18f
private const val TAP_TIMEOUT_MS = 300L