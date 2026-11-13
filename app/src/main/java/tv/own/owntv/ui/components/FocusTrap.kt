package tv.own.owntv.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Keeps D-pad focus INSIDE this focus group for vertical moves: when a held Up/Down outruns a lazy
 * list's composition, Compose's focus search finds nothing above/below within the pane and would
 * escape to the nearest focusable outside it (e.g. the top bar). Cancelling the vertical exit pins
 * focus to the pane's edge row instead. Left/Right/Back still leave the pane normally, and moves
 * BETWEEN children inside the group are unaffected (onExit only fires when leaving the group).
 */
fun Modifier.trapVerticalFocusExit(): Modifier = focusProperties {
    onExit = {
        if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) {
            cancelFocusChange()
        }
    }
}

/**
 * Traps D-pad focus inside this group for ALL directions (Up/Down/Left/Right). Apply to a full-screen
 * modal scrim so a directional press can never escape into the UI behind it. Back is NOT affected — it
 * must still be handled by a BackHandler above (onExit only blocks directional exits).
 *
 * Use this (not [trapVerticalFocusExit]) on modals/dialogs/overlays where every direction must stay
 * inside the dialog. Inside the group, moves between children are unaffected (onExit only fires when
 * leaving the group).
 */
fun Modifier.trapAllFocusExit(): Modifier =
    // No imePadding here: OwnTVPopup already lays a popup out in the room above the keyboard, and
    // padding by the keyboard again left a 74 px strip — only the popup's heading — on the TCL.
    focusProperties { onExit = { cancelFocusChange() } }

/**
 * Put focus back on the control that opened a dialog, and hold the list still while it lands.
 *
 * Restoring focus after a modal closes is two jobs, not one, and doing them in sequence is what made
 * the highlight visibly *travel* across the screen on the way back:
 *
 *  1. **Focus.** The dialog's own window still owns focus for a frame or two after the flag flips, so
 *     a single early request is silently dropped. Hence the retry per frame until one is accepted.
 *  2. **Scroll.** Tearing down a scrim dialog makes Compose re-search focus through the scrollable
 *     that is suddenly exposed again; it lands on the first row, resets the offset and
 *     bringIntoView-animates. The previous fix snapped the offset back **once** and then waited 80 ms
 *     before asking for focus — which works only while the dialog tears down inside that one frame. A
 *     heavier dialog (Glass & background, About) tore down *after* the snap, so the re-search scrolled the
 *     list anyway and the restore then animated all the way back down: the "focus arriving from
 *     another setting" the user sees. Re-asserting the offset every frame leaves the re-search nothing
 *     to animate.
 *
 * Stops as soon as focus has landed and the offset has been steady for a moment, so it never fights a
 * user who is already pressing a direction key.
 */
suspend fun restoreAfterDialogClose(
    opener: FocusRequester?,
    scrollState: ScrollState? = null,
    scrollOffset: Int = 0,
) {
    var landed = opener == null
    var settled = 0
    repeat(RESTORE_MAX_FRAMES) {
        withFrameNanos { }
        if (scrollState != null && scrollState.value != scrollOffset) {
            // scrollTo takes the scroll mutex, so it also cancels a bringIntoView animation in flight.
            runCatching { scrollState.scrollTo(scrollOffset) }
        }
        if (!landed) landed = opener != null && runCatching { opener.requestFocus() }.getOrDefault(false)
        if (landed && ++settled > RESTORE_SETTLE_FRAMES) return
    }
}

/**
 * [restoreAfterDialogClose] for a lazy list, where a position is an item index plus an offset into it.
 *
 * Same two jobs, one extra consequence: an opener row that is scrolled out of view is not composed at
 * all, so its [FocusRequester] is unattached and the request is dropped. Re-asserting the position
 * every frame therefore both holds the list still AND is what puts the row back on screen for the
 * focus request to land on.
 */
suspend fun restoreAfterDialogClose(
    opener: FocusRequester?,
    listState: LazyListState,
    index: Int,
    offset: Int,
) {
    var landed = opener == null
    var settled = 0
    repeat(RESTORE_MAX_FRAMES) {
        withFrameNanos { }
        if (listState.firstVisibleItemIndex != index || listState.firstVisibleItemScrollOffset != offset) {
            runCatching { listState.scrollToItem(index, offset) }
        }
        if (!landed) landed = opener != null && runCatching { opener.requestFocus() }.getOrDefault(false)
        if (landed && ++settled > RESTORE_SETTLE_FRAMES) return
    }
}

/** ~10 frames: long enough for a slow TV to finish tearing the dialog down, short enough not to fight
 *  a user who has already pressed a key. */
private const val RESTORE_MAX_FRAMES = 10

/** Frames to keep correcting the offset after focus lands, covering a late teardown. */
private const val RESTORE_SETTLE_FRAMES = 3

/**
 * Remembers which control opened a dialog, and puts focus back on it once every dialog is closed.
 *
 * Every screen that opens a modal had its own copy of this: a nullable [FocusRequester] state, plus a
 * `LaunchedEffect` that waits for the dialog to leave the composition and then re-requests focus. On a
 * TV that restore is not cosmetic — a dialog dismissed with no focus target leaves the D-pad dead, or
 * drops the user at the top of a list they had scrolled deep into.
 *
 * Set the returned state when opening a dialog:
 * ```
 * onClick = { dialogFocus.value = sortRowFocus; showSortPicker = true }
 * ```
 *
 * Pass the screen's [scrollState] whenever the rows live in a scrollable column — which is nearly
 * always — and the offset is held still across the restore too; see [restoreAfterDialogClose]. It is
 * captured here, when the dialog opens, so no caller has to thread a saved offset of its own.
 *
 * Screens that arbitrate against a second focus source (a `focusProperties.onEnter` that reads the
 * same state) keep their own effect and call [restoreAfterDialogClose] directly.
 */
@Composable
fun rememberDialogFocusRestore(
    anyDialogOpen: Boolean,
    scrollState: ScrollState? = null,
): MutableState<FocusRequester?> {
    val target = remember { mutableStateOf<FocusRequester?>(null) }
    val savedScroll = remember { mutableIntStateOf(0) }
    LaunchedEffect(anyDialogOpen) {
        if (anyDialogOpen) {
            savedScroll.intValue = scrollState?.value ?: 0
            return@LaunchedEffect
        }
        if (target.value != null) restoreAfterDialogClose(target.value, scrollState, savedScroll.intValue)
        target.value = null
    }
    return target
}

/** The focus requesters for a −/+ stepper pair, kept usable across the ends of the range. */
class StepperFocus(val minus: FocusRequester, val plus: FocusRequester)

/**
 * Keeps the D-pad alive on a −/+ stepper pair when one side disables at the end of its range.
 *
 * A disabled button cannot take focus. Inside a dialog that traps focus, the button holding focus going
 * disabled therefore left nothing focused and the D-pad dead with only Back working — the reported
 * "+/− unreachable at the top of the range". So: land on whichever side is usable, and hand focus to
 * the other side the moment the one holding it goes disabled.
 *
 * ```
 * val steppers = rememberStepperFocus(plusEnabled = value < max, minusEnabled = value > min)
 * StepButton("–", enabled = value > min, modifier = Modifier.focusRequester(steppers.minus)) { … }
 * StepButton("+", enabled = value < max, modifier = Modifier.focusRequester(steppers.plus)) { … }
 * ```
 */
@Composable
fun rememberStepperFocus(plusEnabled: Boolean, minusEnabled: Boolean): StepperFocus {
    val focus = remember { StepperFocus(FocusRequester(), FocusRequester()) }
    // "+" is the natural landing spot, but it can't take focus while disabled.
    LaunchedEffect(Unit) { runCatching { (if (plusEnabled) focus.plus else focus.minus).requestFocus() } }
    LaunchedEffect(plusEnabled) { if (!plusEnabled && minusEnabled) runCatching { focus.minus.requestFocus() } }
    LaunchedEffect(minusEnabled) { if (!minusEnabled && plusEnabled) runCatching { focus.plus.requestFocus() } }
    return focus
}
