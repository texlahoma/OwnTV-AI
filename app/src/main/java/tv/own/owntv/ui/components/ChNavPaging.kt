package tv.own.owntv.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.own.owntv.core.settings.RemoteShortcutAction
import tv.own.owntv.core.settings.RemoteShortcutPress

/**
 * Configurable remote paging for a browse panel (category rail OR item list/grid).
 * Factory bindings preserve the original CH+/CH− and rewind/fast-forward short/long-press behavior;
 * the Remote shortcuts screen can replace those buttons or assign paging to another delivered key.
 *
 * Everything only fires when [enabled] AND [isFocused] (the pane this modifier is attached to must
 * currently hold focus). All jumps use the caller-supplied [onJumpToIndex] which is expected to call
 * `scrollToItem` (instant — never `animateScrollToItem`, which janks on low-end TVs over big skips)
 * and then request focus on the target row.
 *
 * A non-paging shortcut pressed while this pane has focus is forwarded to the shell dispatcher. This
 * lets a custom Guide/Search/etc. binding override a former paging key without two actions firing.
 */
fun Modifier.chNavPaging(
    enabled: Boolean,
    upSkip: Int,
    downSkip: Int,
    isFocused: () -> Boolean,
    lastIndex: () -> Int,
    currentTargetIndex: () -> Int,
    onJumpToIndex: (Int) -> Unit,
    // When false, long-press (jump-to-first / jump-to-last) is ignored — short-press skipping still
    // works. Used to disable the end-jump on the huge "All" list (a jump to the last of 170k items is
    // pointless and just janks), while keeping it on real categories/folders.
    longPressEnabled: () -> Boolean = { true },
): Modifier = composed {
    val remote = LocalRemoteShortcuts.current
    if (!enabled || !remote.enabled) return@composed this

    // Handle the key directly here instead of nesting remoteShortcutHandler (another composed
    // modifier) around the category/content focus groups. The nested modifier invalidated focus on
    // the two-pane Live TV, Movies and Series screens.
    var activeKeyCode by remember { mutableIntStateOf(android.view.KeyEvent.KEYCODE_UNKNOWN) }
    var pressedAt by remember { mutableStateOf(0L) }

    onPreviewKeyEvent { event ->
        val keyCode = event.nativeKeyEvent.keyCode
        val keyBindings = remote.bindings.filter { it.keyCode == keyCode }
        if (!isFocused() || keyBindings.isEmpty()) return@onPreviewKeyEvent false

        when (event.type) {
            KeyEventType.KeyDown -> {
                if (activeKeyCode != keyCode) {
                    activeKeyCode = keyCode
                    pressedAt = event.nativeKeyEvent.eventTime
                }
                true
            }
            KeyEventType.KeyUp -> {
                if (activeKeyCode != keyCode) return@onPreviewKeyEvent false
                val heldLong = event.nativeKeyEvent.eventTime - pressedAt >= PAGING_LONG_PRESS_MS
                val binding = if (heldLong) {
                    keyBindings.firstOrNull { it.press == RemoteShortcutPress.LONG }
                        ?: keyBindings.firstOrNull { it.press == RemoteShortcutPress.SHORT }
                } else {
                    keyBindings.firstOrNull { it.press == RemoteShortcutPress.SHORT }
                }
                activeKeyCode = android.view.KeyEvent.KEYCODE_UNKNOWN
                binding?.let { selected ->
                    val current = currentTargetIndex()
                    val last = lastIndex()
                    val target = when (selected.action) {
                        RemoteShortcutAction.PAGE_TOWARD_FIRST -> current - upSkip
                        RemoteShortcutAction.PAGE_TOWARD_LAST -> current + downSkip
                        RemoteShortcutAction.JUMP_TO_FIRST -> if (longPressEnabled()) 0 else current
                        RemoteShortcutAction.JUMP_TO_LAST -> if (longPressEnabled()) last else current
                        else -> {
                            remote.dispatch(selected.action)
                            return@let
                        }
                    }.coerceIn(0, last.coerceAtLeast(0))
                    if (last >= 0 && target != current) onJumpToIndex(target)
                }
                true
            }
            else -> activeKeyCode == keyCode
        }
    }
}

private const val PAGING_LONG_PRESS_MS = 600L

/**
 * Helper for screens that hold a [LazyListState]: scroll instantly to [index] and (optionally) request
 * focus after a one-frame wait so the target row is composed. Mirrors the restore pattern already used
 * for player-return focus (e.g. LiveScreen.restoreToContextRow).
 */
fun CoroutineScope.jumpLazyListTo(
    listState: LazyListState,
    index: Int,
    focus: (suspend () -> Unit)? = null,
) = launch {
    runCatching { listState.scrollToItem(index) }
    if (focus != null) {
        withFrameNanos { } // wait one frame so the row is laid out
        runCatching { focus() }
    }
}
