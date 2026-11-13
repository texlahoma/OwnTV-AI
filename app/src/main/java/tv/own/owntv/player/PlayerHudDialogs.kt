package tv.own.owntv.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.features.aisubtitles.AiSubtitleConfigStore
import tv.own.owntv.features.aisubtitles.AiSubtitleDefaults
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.displayLabel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.format.localizedDecimal
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * Every dialog the HUD opens — tracks, speed, zoom, volume and subtitle timing — plus the scaffold and
 * row they share. Split out of [PlayerHud]; behaviour unchanged.
 */

private val SPEEDS = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

/** A/V-sync nudge step, kept identical to the Settings stepper so a value found in the player can be
 *  reproduced there exactly. */
private const val AV_SYNC_STEP_MS = 25

@Composable
internal fun TrackDialog(
    title: String,
    tracks: List<TrackOption>,
    onSelect: (TrackOption) -> Unit,
    onOff: (() -> Unit)?,
    onDismiss: () -> Unit,
    audioDelayMs: Int? = null,                 // non-null on the Audio dialog (VOD) → show the A/V-sync nudge
    onAdjustAudioDelay: ((Int) -> Unit)? = null,
    // Whether this item already has a remembered A/V-sync offset, and the action that remembers or
    // forgets it. Both accompany [onAdjustAudioDelay].
    audioDelayRemembered: Boolean = false,
    onToggleRememberAudioDelay: (() -> Unit)? = null,
    // Non-null on the Subtitles dialog for a movie/episode → an "ADD SUBTITLES" row that opens the
    // OpenSubtitles search (subtitle plan §4). Absent for Live TV and when no item context exists.
    onSearchSubtitles: (() -> Unit)? = null,
    // Non-null on the Subtitles dialog for a movie/episode → "Select local subtitle file" (plan §7).
    onSelectLocalSubtitle: (() -> Unit)? = null,
    // Non-null on the Subtitles dialog when timing adjustment applies to the active track (plan §8) →
    // an "ADJUST" section with a "Subtitle timing" row.
    onSubtitleTiming: (() -> Unit)? = null,
    // Non-null on the Subtitles dialog → an "AI 字幕" row that opens the AI-subtitle panel. Absent
    // for the Audio dialog and when the feature is off at the global level.
    onOpenAiSubtitles: (() -> Unit)? = null,
) {
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    BackHandler { onDismiss() }
    // Open with focus on the CURRENTLY-selected track (so re-opening to change it lands on the right row),
    // else the "Off" row if nothing's selected, else the first track. The requestFocus must run from
    // INSIDE the target row (below) — a top-level LaunchedEffect fires before the LazyColumn has composed
    // that row, so requestFocus would throw "not initialized" and focus would fall back to the first item.
    val selectedIndex = tracks.indexOfFirst { it.selected }
    val focusOff = onOff != null && selectedIndex < 0
    // Safety net: the per-row one-shot requestFocus below can fire while the dialog window is still
    // mid-transition (seen on HDR/HDR10/DTS streams, whose surface re-layout delays window focus) or
    // before the engine has reported the tracks at all — leaving the dialog with NO focused row and
    // the D-pad locked out. Retry over a few frames, and re-run whenever the track list (re)arrives.
    // The selected row can sit beyond the LazyColumn viewport (e.g. subtitle 11 of 20): it never
    // composes, its focusRequester never attaches, and focus falls back to the first row ("Off").
    // Scroll it into view before requesting focus.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(tracks.size, focusOff) {
        val target = if (selectedIndex >= 0) selectedIndex + (if (onOff != null) 1 else 0) else 0
        repeat(10) {
            androidx.compose.runtime.withFrameNanos { }
            if (selectedIndex >= 0) runCatching { listState.scrollToItem(target) }
            if (focus.requestFocus()) return@LaunchedEffect
            delay(50)
        }
    }
    DialogScaffold(title = title, onDismiss = onDismiss, state = listState) {
        if (tracks.isEmpty() && onOff == null) {
            item { Text(stringResource(R.string.player_no_tracks), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
        }
        if (onOff != null) {
            item {
                if (focusOff) LaunchedEffect(Unit) { requestFocusRetrying(focus) }
                OptionRow(label = stringResource(R.string.common_off), selected = selectedIndex < 0, modifier = if (focusOff) Modifier.focusRequester(focus) else Modifier, onClick = onOff)
            }
        }
        items(tracks.size) { index ->
            val track = tracks[index]
            val focusThis = index == selectedIndex || (selectedIndex < 0 && onOff == null && index == 0)
            if (focusThis) LaunchedEffect(Unit) { requestFocusRetrying(focus) }
            OptionRow(
                // Image-based subs (PGS/VOBSUB/DVB) play via the ExoPlayer handoff on VOD — mark them so
                // it's clear they're a different kind of track, but they're fully selectable.
                label = if (!track.image) track.displayLabel() else stringResource(R.string.player_image_track, track.displayLabel()),
                selected = track.selected,
                modifier = if (focusThis) Modifier.focusRequester(focus) else Modifier,
                onClick = { onSelect(track) },
            )
        }
        // ADD SUBTITLES (subtitles dialog, movie/episode only) — OpenSubtitles search + local file (§4/§7).
        if (onSearchSubtitles != null || onSelectLocalSubtitle != null) {
            item {
                Text(
                    stringResource(R.string.player_add_subtitles),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                )
            }
            if (onSearchSubtitles != null) {
                item { OptionRow(label = stringResource(R.string.player_search_subtitles), selected = false, onClick = onSearchSubtitles) }
            }
            if (onSelectLocalSubtitle != null) {
                item { OptionRow(label = stringResource(R.string.player_select_local_subtitle), selected = false, onClick = onSelectLocalSubtitle) }
            }
        }
        // ADJUST (subtitles dialog): timing panel for the active subtitle (plan §8).
        if (onSubtitleTiming != null) {
            item {
                Text(
                    stringResource(R.string.player_adjust),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                )
            }
            item { OptionRow(label = stringResource(R.string.player_subtitle_timing), selected = false, onClick = onSubtitleTiming) }
        }
        // AI subtitles (subtitles dialog): opens the AI-subtitle panel — switch + recognition language,
        // remembered per channel while watching. Hidden unless the feature is usable this session.
        if (onOpenAiSubtitles != null) {
            item {
                Text(
                    stringResource(R.string.ai_subtitles_enable),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                )
            }
            item { OptionRow(label = stringResource(R.string.player_ai_subtitles), selected = false, onClick = onOpenAiSubtitles) }
        }
        // A/V-sync nudge (audio dialog, VOD only) — fixes a badly-muxed file where audio leads/lags the video.
        if (onAdjustAudioDelay != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.player_av_sync), style = MaterialTheme.typography.titleSmall, color = colors.onSurface, modifier = Modifier.weight(1f))
                    // 25 ms steps, matching Settings: what this corrects is the display's own picture-processing
                    // delay, which lands in the tens of milliseconds — 50 ms could bracket it but not hit it.
                    StepButton(stringResource(R.string.common_minus), enabled = (audioDelayMs ?: 0) > -5_000) { onAdjustAudioDelay(-AV_SYNC_STEP_MS) }
                    Text(
                        formatDelay(audioDelayMs ?: 0),
                        style = MaterialTheme.typography.bodyMedium, color = colors.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.widthIn(min = 78.dp, max = 140.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StepButton(stringResource(R.string.common_plus), enabled = (audioDelayMs ?: 0) < 5_000) { onAdjustAudioDelay(AV_SYNC_STEP_MS) }
                }
            }
            // Lip-sync error belongs to the stream, not to the user: this keeps the offset for THIS
            // film or channel, so it comes back next time without following you onto anything else.
            if (onToggleRememberAudioDelay != null) {
                item {
                    OptionRow(
                        label = stringResource(R.string.player_av_sync_remember),
                        selected = audioDelayRemembered,
                        onClick = onToggleRememberAudioDelay,
                    )
                }
            }
        }
    }
}

/**
 * Requests [focus] with retries: dialog-window content composes a frame or two after the calling
 * effect starts, so a one-shot requestFocus can fire before the target row exists and silently fail.
 */
private suspend fun requestFocusRetrying(focus: FocusRequester) {
    repeat(10) {
        androidx.compose.runtime.withFrameNanos {}
        if (focus.requestFocus()) return
        delay(50)
    }
}

@Composable
private fun formatDelay(ms: Int): String = when {
    ms == 0 -> stringResource(R.string.player_delay_zero)
    ms > 0 -> stringResource(R.string.player_delay_positive, ms)
    else -> stringResource(R.string.player_delay_negative, ms)
}

@Composable
internal fun SpeedDialog(current: Double, onSelect: (Double) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusRetrying(focus) }
    BackHandler { onDismiss() }
    val selectedIndex = SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01 }.coerceAtLeast(0)
    DialogScaffold(title = stringResource(R.string.settings_playback_speed), onDismiss = onDismiss) {
        items(SPEEDS.size) { index ->
            val speed = SPEEDS[index]
            OptionRow(
                label = if (speed == 1.0) stringResource(R.string.player_speed_normal) else stringResource(R.string.player_speed, localizedDecimal(speed)),
                selected = kotlin.math.abs(speed - current) < 0.01,
                modifier = if (index == selectedIndex) Modifier.focusRequester(focus) else Modifier,
                onClick = { onSelect(speed) },
            )
        }
    }
}

@Composable
internal fun ZoomDialog(current: ZoomMode, onSelect: (ZoomMode) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusRetrying(focus) }
    BackHandler { onDismiss() }
    // Land focus on the current mode (not always the first row) so re-opening starts on your selection.
    val selectedIndex = ZoomMode.entries.indexOf(current).coerceAtLeast(0)
    DialogScaffold(title = stringResource(R.string.settings_player_zoom), onDismiss = onDismiss) {
        items(ZoomMode.entries.size) { index ->
            val mode = ZoomMode.entries[index]
            OptionRow(label = stringResource(mode.labelRes), selected = mode == current, modifier = if (index == selectedIndex) Modifier.focusRequester(focus) else Modifier, onClick = { onSelect(mode) })
        }
    }
}

@Composable
internal fun VolumeDialog(player: PlaybackEngine, onDismiss: () -> Unit) {
    val colors = OwnTVTheme.colors
    val volume by player.volume.collectAsStateWithLifecycle()
    // Mute the channel and "–" disables; without the shared guard focus died there (see
    // [tv.own.owntv.ui.components.rememberStepperFocus]).
    val steppers = tv.own.owntv.ui.components.rememberStepperFocus(
        plusEnabled = volume < 150,
        minusEnabled = volume > 0,
    )
    // Real dialog window for the same focus isolation as DialogScaffold (see there).
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
        // Slightly stronger than the default wash: this one sits over moving video.
        Box(
            Modifier.fillMaxSize().modalScrim(strength = 1.2f).trapAllFocusExit().focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.dialogPanel(padding = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.player_volume), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    StepButton(stringResource(R.string.common_minus), enabled = volume > 0, modifier = Modifier.focusRequester(steppers.minus)) { player.adjustVolumeByUser(-5) }
                    Text(stringResource(R.string.player_percent, volume), style = MaterialTheme.typography.headlineLarge, color = colors.accent, modifier = Modifier.width(120.dp), textAlign = TextAlign.Center)
                    StepButton(stringResource(R.string.common_plus), enabled = volume < 150, modifier = Modifier.focusRequester(steppers.plus)) { player.adjustVolumeByUser(5) }
                }
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OwnTVButton(stringResource(if (volume == 0) R.string.player_unmute else R.string.player_mute), onClick = { player.toggleMute() }, style = tv.own.owntv.ui.components.OwnTVButtonStyle.SECONDARY)
                    Spacer(Modifier.weight(1f))
                    OwnTVButton(stringResource(R.string.common_done), onClick = onDismiss)
                }
            }
        }
    }
}

/**
 * Subtitle-timing panel (subtitle plan §8.2/§8.3): 100 ms and 500 ms steps + Reset, applied live while
 * the video keeps playing behind (the backdrop is NOT dimmed so speech and text can be compared).
 * Positive = subtitles shown later; the direction is always spelled out. Back keeps the value.
 */
@Composable
internal fun SubtitleTimingDialog(player: PlaybackEngine, onDismiss: () -> Unit) {
    val colors = OwnTVTheme.colors
    val delay by player.subDelayMs.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusRetrying(focus) }
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
        tv.own.owntv.ui.theme.PopupFontTheme {
            // No scrim by design — the video stays undimmed behind so speech and subtitles can be
            // compared while the offset is nudged. Only the focus trap is added.
            Box(
                Modifier.fillMaxSize().padding(bottom = 56.dp).trapAllFocusExit().focusGroup(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(Modifier.dialogPanel(width = 560.dp, padding = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.player_subtitle_timing), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                    Spacer(Modifier.height(10.dp))
                    Text(formatSubDelay(delay), style = MaterialTheme.typography.headlineLarge, color = colors.accent)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            delay > 0 -> stringResource(R.string.player_subtitles_later)
                            delay < 0 -> stringResource(R.string.player_subtitles_earlier)
                            else -> stringResource(R.string.player_no_offset)
                        },
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OwnTVButton(stringResource(R.string.player_subtitle_delay_negative, 0.5), onClick = { player.adjustSubtitleDelay(-500) }, style = tv.own.owntv.ui.components.OwnTVButtonStyle.SECONDARY)
                        OwnTVButton(stringResource(R.string.player_subtitle_delay_negative, 0.1), onClick = { player.adjustSubtitleDelay(-100) }, style = tv.own.owntv.ui.components.OwnTVButtonStyle.SECONDARY)
                        OwnTVButton(stringResource(R.string.common_reset), onClick = { player.resetSubtitleDelay() }, modifier = Modifier.focusRequester(focus))
                        OwnTVButton(stringResource(R.string.player_subtitle_delay_positive, 0.1), onClick = { player.adjustSubtitleDelay(100) }, style = tv.own.owntv.ui.components.OwnTVButtonStyle.SECONDARY)
                        OwnTVButton(stringResource(R.string.player_subtitle_delay_positive, 0.5), onClick = { player.adjustSubtitleDelay(500) }, style = tv.own.owntv.ui.components.OwnTVButtonStyle.SECONDARY)
                    }
                }
            }
        }
    }
}

@Composable
private fun formatSubDelay(ms: Int): String = when {
    ms == 0 -> stringResource(R.string.player_subtitle_delay_zero)
    ms > 0 -> stringResource(R.string.player_subtitle_delay_positive, ms / 1000.0)
    else -> stringResource(R.string.player_subtitle_delay_negative, -ms / 1000.0)
}

@Composable
private fun StepButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusableSurface(onClick = onClick, enabled = enabled, modifier = modifier.size(64.dp), shape = RoundedCornerShape(18.dp), contentAlignment = Alignment.Center, surface = GlassSurface.DIALOGS) { _ ->
        Text(label, style = MaterialTheme.typography.headlineMedium, color = if (enabled) OwnTVTheme.colors.onSurface else OwnTVTheme.colors.outline)
    }
}

@Composable
private fun DialogScaffold(
    title: String,
    onDismiss: () -> Unit,
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    panelWidth: Dp = 260.dp,
    footer: (@Composable () -> Unit)? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val colors = OwnTVTheme.colors
    // A REAL dialog window, not an in-place overlay: it owns the D-pad focus scope, so nothing in the
    // HUD behind it (play button, catch-all focusable, stream-info chips) can compete for or steal
    // focus — which is what intermittently locked the subtitle/audio pickers out of focus on
    // codec-heavy (HDR/DTS) streams. Back is handled by the window itself via onDismissRequest.
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
        // Compact glass popup matching the storage picker: smaller font + narrow box.
        tv.own.owntv.ui.theme.PopupFontTheme(fontScale = 0.72f) {
            Box(
                modifier = Modifier.fillMaxSize().modalScrim(strength = 1.2f).trapAllFocusExit().focusGroup(),
                contentAlignment = Alignment.Center,
            ) {
                // Glass effect panel (same translucent chrome as the volume/timing dialogs) — the
                // inner LazyColumn manages its own scroll, so scroll = false.
                Column(modifier = Modifier.dialogPanel(width = panelWidth, corner = 16.dp, padding = 14.dp, scroll = false)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Spacer(Modifier.height(8.dp))
                    // Cap to the screen (minus dialog chrome) so all rows stay reachable on small screens.
                    val listMax = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 160.dp).coerceIn(140.dp, 240.dp)
                    LazyColumn(state = state, modifier = Modifier.heightIn(max = listMax), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
                    // Pinned footer, outside the scroll area: callers put a long status/debug line here
                    // so the height-capped list above can never scroll it out of view.
                    footer?.invoke()
                }
            }
        }
    }
}

/**
 * A settings row that is its own stepper: focus it and left/right step the value, with the current
 * value shown right-aligned. Left/right are consumed in the preview phase so the D-pad changes the
 * value instead of walking focus out of the row; OK also steps up, so a plain "press OK" visibly does
 * something rather than looking like a dead row.
 */
@Composable
private fun StepperRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    modifier: Modifier = Modifier,
    valueText: String? = null,
    onSet: (Int) -> Unit,
) {
    OptionRow(
        label = label,
        selected = false,
        value = valueText ?: stringResource(R.string.common_percent, value),
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { onSet((value - step).coerceAtLeast(min)); true }
                Key.DirectionRight -> { onSet((value + step).coerceAtMost(max)); true }
                else -> false
            }
        },
        onClick = { onSet((value + step).coerceAtMost(max)) },
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, modifier: Modifier = Modifier, value: String? = null, onClick: () -> Unit) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick, modifier = modifier.fillMaxWidth(), selected = selected, shape = RoundedCornerShape(12.dp),
        selectedContainerColor = colors.primaryContainer, contentAlignment = Alignment.CenterStart,
        surface = GlassSurface.DIALOGS,
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) colors.onPrimaryContainer else if (focused) colors.primary else colors.onSurface)
            // Optional right-aligned current value, so a settings row can show its state in place.
            if (value != null) {
                Spacer(Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (selected) {
                Spacer(Modifier.weight(1f))
                OwnTVIcon(OwnTVIcon.STAR, tint = colors.onPrimaryContainer, filled = true, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * The AI-subtitle panel in the player's Subtitles dialog: switch + recognition language for the
 * current item, remembered per channel "while watching" (channel memory takes priority over the
 * global Settings). Driven by the effective config, so it always shows what this channel is actually
 * using.
 */
@Composable
internal fun AiSubtitleHudDialog(
    manager: tv.own.owntv.features.aisubtitles.AiSubtitleManager,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val cfg by manager.effective.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    val displayLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val languages = remember(displayLocale) { tv.own.owntv.features.aisubtitles.AiSubtitleLanguages.localized(displayLocale) }
    val sourceName = tv.own.owntv.features.aisubtitles.AiSubtitleLanguages.nameOf(cfg.sourceLanguage, displayLocale)
        ?: cfg.sourceLanguage.ifBlank { stringResource(R.string.ai_subtitles_auto) }

    var showLanguagePicker by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val configStore = koinInject<AiSubtitleConfigStore>()
    // Both look settings are the same persisted values the Settings page edits, exposed here too
    // because the natural moment to size a caption is while watching it — leaving to Settings means
    // losing the picture you are sizing against.
    val fontSizePercent = (cfg.scale * 100).roundToInt()
    val lineHeightPercent = (cfg.lineHeight * 100).roundToInt()
    // Nudge the subtitle block by a dp delta in x/y, writing through the config store so the change
    // survives the session and the overlay updates live.
    fun nudge(dx: Float, dy: Float) {
        val current = cfg.positionOffset
        scope.launch { configStore.setPositionOffset(Offset(current.x + dx, current.y + dy)) }
    }
    // Any nested picker must close before the panel itself does.
    fun dismissLayer(): Boolean = when {
        showLanguagePicker -> { showLanguagePicker = false; true }
        else -> false
    }
    LaunchedEffect(Unit) { requestFocusRetrying(focus) }
    BackHandler { if (!dismissLayer()) onDismiss() }

    DialogScaffold(
        title = stringResource(R.string.player_ai_subtitles),
        onDismiss = { if (!dismissLayer()) onDismiss() },
        // Wider than the standard popup and the long status line pinned below the list: the combined
        // status/debug line is one unbroken string, so a narrow box wrapped it past the list cap and
        // the tail never showed.
        panelWidth = 520.dp,
        footer = {
            Text(
                stringResource(R.string.ai_subtitles_status, status),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        },
    ) {
        item {
            OptionRow(
                label = stringResource(R.string.ai_subtitles_enable),
                selected = cfg.enabled,
                modifier = Modifier.focusRequester(focus),
                onClick = { manager.toggleAiSubtitles() },
            )
        }
        item {
            OptionRow(
                label = stringResource(R.string.ai_subtitles_source_language),
                selected = false,
                onClick = { showLanguagePicker = true },
            )
        }
        item {
            // Both look settings are adjusted in place: focus the row, press left/right (OK also steps
            // up). Deliberately NOT the Settings page's StepperDialog — that one draws its scrim in the
            // current composition, so called from inside this panel it lands in the HUD layer *behind*
            // the panel's popup window and is completely covered by the panel's own scrim: the row
            // looked selectable but pressing it changed nothing.
            StepperRow(
                label = stringResource(R.string.ai_subtitles_font_size),
                value = fontSizePercent,
                min = AiSubtitleDefaults.SCALE_MIN_PERCENT,
                max = AiSubtitleDefaults.SCALE_MAX_PERCENT,
                step = AiSubtitleDefaults.LOOK_STEP_PERCENT,
                onSet = { scope.launch { configStore.setScale(it / 100f) } },
            )
        }
        item {
            StepperRow(
                label = stringResource(R.string.ai_subtitles_line_height),
                value = lineHeightPercent,
                min = AiSubtitleDefaults.LINE_HEIGHT_MIN_PERCENT,
                max = AiSubtitleDefaults.LINE_HEIGHT_MAX_PERCENT,
                step = AiSubtitleDefaults.LOOK_STEP_PERCENT,
                onSet = { scope.launch { configStore.setLineHeight(it / 100f) } },
            )
        }
        item {
            // Sync trim: the residual after every measurable stage is the OUTPUT chain's own delay
            // (HDMI/ARC, a soundbar, the TV's audio DSP), which no Android API reports. Negative
            // shows the lines earlier — the common case when a soundbar sits between TV and ears.
            val syncMs = cfg.syncOffsetMs
            StepperRow(
                label = stringResource(R.string.ai_subtitles_sync_offset),
                value = syncMs,
                min = AiSubtitleDefaults.SYNC_OFFSET_MIN,
                max = AiSubtitleDefaults.SYNC_OFFSET_MAX,
                step = AiSubtitleDefaults.SYNC_OFFSET_STEP,
                valueText = stringResource(R.string.ai_subtitles_sync_value, syncMs),
                onSet = { scope.launch { configStore.setSyncOffsetMs(it) } },
            )
        }
        item {
            Text(
                stringResource(R.string.player_ai_subtitles_adjust_position),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Compact cross: up alone, then left / down / right — the classic remote-D-pad shape.
                // Two tight rows of 36 dp instead of three rows of 40 dp, so the nudger no longer
                // dominates the panel.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OwnTVButton(
                        icon = OwnTVIcon.ARROW_UP, onClick = { nudge(0f, -AiSubtitleDefaults.POSITION_NUDGE_DP) },
                        style = tv.own.owntv.ui.components.OwnTVButtonStyle.SECONDARY,
                        modifier = Modifier.size(36.dp), compact = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OwnTVButton(
                        icon = OwnTVIcon.ARROW_LEFT, onClick = { nudge(-AiSubtitleDefaults.POSITION_NUDGE_DP, 0f) },
                        style = tv.own.owntv.ui.components.OwnTVButtonStyle.SECONDARY,
                        modifier = Modifier.size(36.dp), compact = true,
                    )
                    OwnTVButton(
                        icon = OwnTVIcon.ARROW_DOWN, onClick = { nudge(0f, AiSubtitleDefaults.POSITION_NUDGE_DP) },
                        style = tv.own.owntv.ui.components.OwnTVButtonStyle.SECONDARY,
                        modifier = Modifier.size(36.dp), compact = true,
                    )
                    OwnTVButton(
                        icon = OwnTVIcon.ARROW_RIGHT, onClick = { nudge(AiSubtitleDefaults.POSITION_NUDGE_DP, 0f) },
                        style = tv.own.owntv.ui.components.OwnTVButtonStyle.SECONDARY,
                        modifier = Modifier.size(36.dp), compact = true,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                OwnTVButton(
                    label = stringResource(R.string.common_reset),
                    onClick = {
                        // Resets the whole on-screen look this panel owns (nudge + font size + line
                        // spacing), not just the nudge: the rows above are one adjustment surface, so
                        // "reset" restoring only one of them would surprise.
                        scope.launch {
                            configStore.setPositionOffset(Offset.Zero)
                            configStore.setScale(AiSubtitleDefaults.SCALE_DEFAULT)
                            configStore.setLineHeight(AiSubtitleDefaults.LINE_HEIGHT_DEFAULT)
                        }
                    },
                    style = tv.own.owntv.ui.components.OwnTVButtonStyle.SECONDARY,
                )
            }
        }
        item {
            Text(
                stringResource(R.string.ai_subtitles_remembered_hint),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }

    if (showLanguagePicker) {
        AiSubtitleLanguagePickerDialog(
            current = sourceName,
            languages = languages,
            selected = cfg.sourceLanguage,
            onSelect = { code ->
                manager.setSourceLanguage(code)
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false },
        )
    }
}

/** Source-language picker for the HUD AI-subtitle panel: the shared list plus "follow global/auto". */
@Composable
private fun AiSubtitleLanguagePickerDialog(
    current: String,
    languages: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf("" to stringResource(R.string.ai_subtitles_auto)) + languages
    val focus = remember { FocusRequester() }
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    LaunchedEffect(Unit) { requestFocusRetrying(focus) }
    BackHandler { onDismiss() }
    DialogScaffold(title = current, onDismiss = onDismiss) {
        items(options.size) { index ->
            val (code, name) = options[index]
            OptionRow(
                label = name,
                selected = code == selected,
                modifier = if (index == selectedIndex) Modifier.focusRequester(focus) else Modifier,
                onClick = { onSelect(code) },
            )
        }
    }
}
