package tv.own.owntv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.LocalGlass
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.glass

/**
 * Inline search field for a section, TV-style: the pill itself takes D-pad focus like any other
 * control — the keyboard only opens when the user presses OK on it (the inner text field is not
 * focusable until then), so focus can pass through / land on search without an IME popup.
 *
 * Glass effect: when [surface] is in scope the pill frosts (translucent fill + specular highlight)
 * and the focus ring becomes a bright white glass rim; otherwise it keeps its solid tonal fill +
 * accent/outline border. Defaults to [GlassSurface.CARDS] (most search bars live on a content panel);
 * pass [GlassSurface.DIALOGS] for one inside a popup.
 *
 * @param surface glass surface to frost with, or null to stay flat.
 */
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    surface: GlassSurface? = GlassSurface.CARDS,
) {
    val colors = OwnTVTheme.colors
    val resolvedPlaceholder = placeholder ?: stringResource(R.string.common_search_hint)
    val interaction = remember { MutableInteractionSource() }
    val pillFocused by interaction.collectIsFocusedAsState()
    var editing by remember { mutableStateOf(false) }
    val pillFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val tvImeWatcher = LocalTvImeWatcher.current
    val tvImeMetrics = LocalTvImeMetrics.current
    val shape = RoundedCornerShape(50)
    val focused = pillFocused || editing
    // Glassy only when a surface is given and it's in the active glass scope (matches FocusableSurface).
    val glassy = surface != null && LocalGlass.current.isGlassy(surface)
    // The material reads as glass because a bright hairline lenses the whole edge at all times, not
    // just on focus. So when glassy: a faint white rim always, brightening on focus.
    // A focused pill is a cursor position, so it follows the user's focus highlight (#121) in both
    // materials — glass keeps its white idle hairline, but the focused rim is the chosen color.
    val borderColor = when {
        focused -> colors.focusBorder
        glassy -> Color.White.copy(alpha = 0.22f)
        else -> colors.outlineVariant
    }

    // Enter edit mode after recomposition has made the field focusable (canFocus = editing).
    LaunchedEffect(editing) {
        if (editing) {
            tvImeWatcher?.onImeRequested()
            // The field's node only becomes focusable this frame; inside a popup window its window
            // focus may not have arrived yet, so a one-shot request can be silently dropped. Retry
            // until the requester is attached and the request lands (same pattern as the player dialogs).
            // requestFocus() returns Boolean: a dropped request is NOT an exception, so the retry must
            // test the return value — runCatching{}.isSuccess would always be true and the loop would
            // bail on the first frame, leaving the keyboard up with no focused field (typing goes nowhere).
            for (attempt in 0 until 10) {
                withFrameNanos { }
                if (fieldFocus.requestFocus()) break
                kotlinx.coroutines.delay(50)
            }
            keyboard?.show()
            kotlinx.coroutines.delay(120)
            runCatching { bringIntoView.bringIntoView() }
        } else {
            tvImeWatcher?.onImeDismissed()
        }
    }
    LaunchedEffect(editing, tvImeMetrics.keyboardTopPx, tvImeMetrics.visible) {
        if (editing && tvImeMetrics.visible) {
            kotlinx.coroutines.delay(32)
            runCatching { bringIntoView.bringIntoView() }
        }
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .then(
                // Frosted glass pill when this surface is glassy; plain tonal fill otherwise. glass()
                // early-returns on a transparent fill, but our fill is always opaque here.
                if (surface != null) Modifier.glass(surface = surface, baseFill = colors.surfaceContainerHigh, shape = shape, frostScale = 0.6f)
                else Modifier.background(colors.surfaceContainerHigh)
            )
            .border(
                width = if (focused) tv.own.owntv.ui.theme.LocalFocusBorderWidth.current else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .focusRequester(pillFocus)
            // clickable is the two-stage flow's battle-tested trigger (D-pad OK + touch tap both
            // fire its onClick); it also restores focusability and the semantics OnClick. The
            // pointerInput and onPreviewKeyEvent below stay for their own strengths: the former arms
            // edit on finger-DOWN (a tap the inner field swallows on UP while canFocus=false), the
            // latter consumes the remote OK during the preview phase before clickable's main phase.
            .clickable(interactionSource = interaction, indication = null) { editing = true }
            // Enter edit mode on finger-down (see OwnTVTextField): the field's own tap consumes the up
            // while canFocus is still false, swallowing short taps with no focus/IME. Down-arming flips
            // canFocus first; the field keeps its native tap. Harmless for this single-line field.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (!editing) editing = true
                }
            }
            // Remote "OK": D-pad center/Enter arms edit; only while not editing so Enter still reaches
            // the IME onSearch once editing.
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && !editing &&
                    (it.key == Key.DirectionCenter || it.key == Key.Enter)
                ) {
                    editing = true
                    true
                } else false
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnTVIcon(
                icon = OwnTVIcon.SEARCH,
                tint = if (focused) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(resolvedPlaceholder, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(bringIntoView)
                        .focusRequester(fieldFocus)
                        .focusProperties { canFocus = editing }
                        .onFocusChanged { if (editing && !it.isFocused) editing = false }
                        .onPreviewKeyEvent {
                            // After the IME closed itself, Back hands focus back to the pill
                            // instead of bubbling to the screen's BackHandler.
                            if (it.key == Key.Back) {
                                if (it.type == KeyEventType.KeyUp) {
                                editing = false
                                keyboard?.hide()
                                runCatching { pillFocus.requestFocus() }
                                }
                                true
                            } else {
                                false
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    singleLine = true,
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        editing = false
                        keyboard?.hide()
                        runCatching { pillFocus.requestFocus() }
                    }),
                )
            }
        }
    }
}
