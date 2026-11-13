package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.hsvToHex
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageProgress
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageTag
import tv.own.owntv.ui.stage.drawOuterRing
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.labelRes
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.parseAccentHex
import tv.own.owntv.ui.theme.primary
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Settings › Appearance › Accent color: presets, a hex code and an HSV picker, with a preview of
 * what the accent tints. The whole app behind re-tints while the picker moves; Cancel puts the colour
 * that was there back, "Use this color" keeps the new one.
 */
@Composable
fun AccentPopup(
    accent: AccentColor,
    customAccent: String,
    onPickPreset: (AccentColor) -> Unit,
    onPickCustom: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // What to put back on Cancel.
    val startPreset = remember { accent }
    val startCustom = remember { customAccent }
    StageColorPopup(
        eyebrow = stringResource(R.string.settings_group_appearance),
        title = stringResource(R.string.settings_accent),
        presets = AccentColor.entries.map { ac ->
            ColorChoice(ac.primary(true), stringResource(ac.labelRes)) { onPickCustom(""); onPickPreset(ac) }
        },
        start = parseAccentHex(customAccent) ?: accent.primary(true),
        current = stageAccent.accent,
        onLive = onPickCustom,
        onCancel = { if (startCustom.isBlank()) { onPickCustom(""); onPickPreset(startPreset) } else onPickCustom(startCustom) },
        onDone = { hex -> if (hex != null) onPickCustom(hex) },
        onDismiss = onDismiss,
    ) {
        // What the accent tints, drawn rather than focusable: the primary button, a progress bar, a tag.
        Row(horizontalArrangement = Arrangement.spacedBy(14.mpx), verticalAlignment = Alignment.CenterVertically) {
            val a = stageAccent
            Row(
                Modifier
                    .height(54.mpx)
                    .drawBehind { drawOuterRing(a.focus, 3.mpx.toPx(), 22.mpx.toPx()) }
                    .background(a.accent, RoundedCornerShape(22.mpx))
                    .padding(horizontal = 26.mpx),
                horizontalArrangement = Arrangement.spacedBy(10.mpx),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OwnTVIcon(OwnTVIcon.PLAY, a.onAccent, Modifier.size(19.mpx), filled = true)
                Text(stringResource(R.string.home_trending_play), style = stageText(19, 700), color = a.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StageProgress(0.6f, Modifier.width(190.mpx))
            StageTag(stringResource(R.string.player_live), tint = a.accent)
        }
    }
}

/** One preset swatch of [StageColorPopup]: its colour, its name and what picking it stores. */
class ColorChoice(val color: Color, val label: String, val pick: () -> Unit)

/**
 * The Stage colour picker, shared by Accent color and the clock colours: presets, the
 * colour in use ("Yours"), a hex code and the HSV field, with [preview] under them. [onLive] receives the
 * picked hex a beat after the picker moves; Cancel / Back call [onCancel]; "Use this color" calls
 * [onDone] with the picked hex, or null when a preset was the last choice (already stored).
 */
@Composable
fun StageColorPopup(
    eyebrow: String,
    title: String,
    presets: List<ColorChoice>,
    start: Color,
    current: Color,
    onLive: (String) -> Unit,
    onCancel: () -> Unit,
    onDone: (String?) -> Unit,
    onDismiss: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val seed = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(start.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(seed[0]) }
    var sat by remember { mutableFloatStateOf(seed[1]) }
    var value by remember { mutableFloatStateOf(seed[2]) }
    var moved by remember { mutableStateOf(false) }
    val picked = hsvToHex(hue, sat, value)
    var hexInput by remember { mutableStateOf(picked.removePrefix("#")) }
    // The picker applies as it moves, a beat behind so a held key does not write every step.
    LaunchedEffect(hue, sat, value) {
        if (!moved) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        onLive(picked)
        hexInput = picked.removePrefix("#")
    }
    val cancel = { onCancel(); onDismiss() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    OwnTVPopup(onDismissRequest = cancel, stageLayout = true) {
        BackHandler { cancel() }
        Box(
            Modifier.fillMaxSize().background(StageColors.Scrim).trapAllFocusExit().focusGroup(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                Modifier
                    .padding(top = 210.mpx)
                    .width(1100.mpx)
                    .stageGlass(30.mpx, overContent = true)
                    .verticalScroll(rememberScrollState())
                    .padding(30.mpx),
                horizontalArrangement = Arrangement.spacedBy(34.mpx),
            ) {
                Column(Modifier.width(520.mpx)) {
                    Text(eyebrow.uppercase(), style = stageText(15, 800, 0.12.em), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(title, style = stageText(38, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.mpx, bottom = 22.mpx))
                    SectionLabel(stringResource(R.string.settings_presets))
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.padding(bottom = 26.mpx),
                        horizontalArrangement = Arrangement.spacedBy(16.mpx),
                        verticalArrangement = Arrangement.spacedBy(14.mpx),
                    ) {
                        presets.forEachIndexed { i, choice ->
                            Swatch(
                                color = choice.color,
                                label = choice.label,
                                chosen = false,
                                onClick = {
                                    moved = false
                                    choice.pick()
                                    val hsv = FloatArray(3)
                                    android.graphics.Color.colorToHSV(choice.color.toArgb(), hsv)
                                    hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                                    hexInput = hsvToHex(hue, sat, value).removePrefix("#")
                                },
                                modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            )
                        }
                        Swatch(current, stringResource(R.string.settings_accent_yours), chosen = true, onClick = {})
                    }
                    StageFieldRow(
                        icon = OwnTVIcon.PENCIL,
                        label = stringResource(R.string.settings_hex_code),
                        value = hexInput,
                        onValueChange = { s ->
                            hexInput = s.removePrefix("#").take(6)
                            parseAccentHex(hexInput)?.let { c ->
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(c.toArgb(), hsv)
                                moved = true
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                            }
                        },
                        placeholder = "4E952A",
                    )
                    SectionLabel(stringResource(R.string.settings_panel_width_preview), Modifier.padding(top = 26.mpx))
                    preview()
                }
                Column(Modifier.weight(1f)) {
                    ColorField(
                        hue = hue, sat = sat, value = value,
                        onHue = { moved = true; hue = it },
                        onSat = { moved = true; sat = it },
                        onValue = { moved = true; value = it },
                    )
                    Text(
                        stringResource(R.string.settings_accent_picker_hint),
                        style = stageText(15.5f, 500).copy(lineHeight = (15.5f * 1.5f).mpxSpUnit()),
                        color = StageColors.Muted,
                        modifier = Modifier.padding(top = 16.mpx),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 34.mpx), horizontalArrangement = Arrangement.spacedBy(12.mpx)) {
                        Spacer(Modifier.weight(1f))
                        StageButton(stringResource(R.string.common_cancel), onClick = cancel, height = 56.mpx, textSize = 19)
                        StageButton(
                            stringResource(R.string.settings_use_color),
                            onClick = { onDone(if (moved) picked else null); onDismiss() },
                            height = 56.mpx, textSize = 19, tinted = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Float.mpxSpUnit() = with(androidx.compose.ui.platform.LocalDensity.current) { this@mpxSpUnit.mpx.toSp() }

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = stageText(13, 800, 0.12.em), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(bottom = 12.mpx))
}

/**
 * The keys of a box you enter with OK: OK toggles editing, Back leaves it, and while editing ◀ ▶ go to
 * [onX] and ▲ ▼ to [onY] (all consumed, so focus stays put). Outside editing every key passes on.
 */
private fun boxKeys(
    e: androidx.compose.ui.input.key.KeyEvent,
    editing: Boolean,
    setEditing: (Boolean) -> Unit,
    onY: ((Int) -> Unit)? = null,
    onX: (Int) -> Unit,
): Boolean {
    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
    // Back leaves on its release: the popup closes on Back's release, so that one must be consumed here.
    if (e.key == Key.Back) {
        if (!editing) return false
        if (e.type == KeyEventType.KeyUp) setEditing(false)
        return true
    }
    if (e.type != KeyEventType.KeyDown) return editing && (ok || e.key in Arrows)
    return when {
        ok -> { setEditing(!editing); true }
        !editing -> false
        e.key == Key.DirectionLeft -> { onX(-1); true }
        e.key == Key.DirectionRight -> { onX(1); true }
        e.key == Key.DirectionUp -> { onY?.invoke(-1); true }
        e.key == Key.DirectionDown -> { onY?.invoke(1); true }
        else -> false
    }
}

private val Arrows = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

/** A 62 px preset swatch with its name; the chosen one ringed white over a 50% dark ring. */
@Composable
private fun Swatch(color: Color, label: String, chosen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StageSurface(onClick = onClick, radius = 31.mpx, focusStyle = StageFocus.POSTER, modifier = modifier.size(62.mpx)) { _ ->
            Box(
                Modifier
                    .size(62.mpx)
                    .drawBehind {
                        val r = size.minDimension / 2f
                        if (chosen) {
                            drawCircle(Color.Black.copy(alpha = 0.5f), radius = r + 6.mpx.toPx())
                            drawCircle(Color.White, radius = r + 3.mpx.toPx())
                        }
                        drawCircle(color, radius = r)
                    },
            )
        }
        Text(
            label, style = stageText(14, if (chosen) 700 else 600),
            color = if (chosen) StageColors.Text else StageColors.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.mpx),
        )
    }
}

/**
 * The saturation/brightness square and the hue bar, each its own focus stop: focus
 * rings it, OK goes in, then the square moves its point freely (◀ ▶ saturation, ▲ ▼ brightness) and the
 * bar its hue (◀ ▶); OK or Back comes out. Outside, the D-pad only moves focus, so nothing changes by
 * passing over them.
 */
@Composable
private fun ColorField(hue: Float, sat: Float, value: Float, onHue: (Float) -> Unit, onSat: (Float) -> Unit, onValue: (Float) -> Unit) {
    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    Column {
        EditBox(
            radius = 22.mpx,
            onX = { dx -> onSat((sat + dx * 0.04f).coerceIn(0f, 1f)) },
            onY = { dy -> onValue((value - dy * 0.04f).coerceIn(0f, 1f)) },
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(330.mpx)
                    .clip(RoundedCornerShape(22.mpx))
                    .background(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))),
            ) {
                val dot = 30.mpx
                Box(
                    Modifier
                        .offset(x = (maxWidth - dot) * sat.coerceIn(0f, 1f), y = (maxHeight - dot) * (1f - value).coerceIn(0f, 1f))
                        .size(dot)
                        .drawBehind {
                            val r = size.minDimension / 2f
                            drawCircle(Color.Black.copy(alpha = 0.5f), radius = r + 2.mpx.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.mpx.toPx()))
                            drawCircle(Color.White, radius = r - 1.5f.mpx.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(3.mpx.toPx()))
                        },
                )
            }
        }
        EditBox(
            radius = 13.mpx,
            modifier = Modifier.padding(top = 22.mpx),
            onX = { dx -> onHue(((hue + dx * 4f) % 360f + 360f) % 360f) },
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(26.mpx)
                    .background(
                        Brush.horizontalGradient((0..360 step 60).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it.toFloat(), 1f, 1f))) }),
                        RoundedCornerShape(13.mpx),
                    ),
            ) {
                Box(
                    Modifier
                        .offset(x = (maxWidth - 12.mpx) * (hue / 360f).coerceIn(0f, 1f), y = (-4).mpx)
                        .size(12.mpx, 34.mpx)
                        .background(Color.White, RoundedCornerShape(6.mpx)),
                )
            }
        }
    }
}

/** One focus stop you enter with OK ([boxKeys]): the focus ring around it, in the accent while editing. */
@Composable
private fun EditBox(
    radius: androidx.compose.ui.unit.Dp,
    onX: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onY: ((Int) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { if (!focused) editing = false }
    val a = stageAccent
    Box(
        modifier
            .drawBehind { if (focused) drawOuterRing(if (editing) a.accent else a.focus, if (editing) 3.mpx.toPx() else 2.mpx.toPx(), radius.toPx()) }
            .onKeyEvent { e -> boxKeys(e, editing, { editing = it }, onY, onX) }
            .focusable(interactionSource = interaction),
    ) { content() }
}
