package tv.own.owntv.features.epg

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.PopupFontTheme
import tv.own.owntv.ui.theme.glass

internal object GuideGridDefaults {
    val ChannelCol = 176.dp
    val RowHeight = 64.dp
    val PxPerMin = 4.dp
    const val SlotMin = 30
}

@Composable
internal fun ProgrammeStripCanvas(
    programmes: List<EpgProgrammeEntity>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    highlightTime: Long?,
    catchupIds: Set<Long>,
    hScroll: androidx.compose.foundation.ScrollState,
) {
    val colors = OwnTVTheme.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)

    // Pre-computed once — nothing here is allocated inside the draw loop.
    val pxPerMin = with(density) { GuideGridDefaults.PxPerMin.toPx() }
    val gapPx = with(density) { 4.dp.toPx() }
    val padPx = with(density) { 10.dp.toPx() }
    val borderPx = with(density) { tv.own.owntv.ui.theme.LocalFocusBorderWidth.current.toPx() }
    val airingBarPx = with(density) { 3.dp.toPx() }
    val airingBarInsetPx = with(density) { 4.dp.toPx() }
    val corner = with(density) { CornerRadius(10.dp.toPx(), 10.dp.toPx()) }
    val titleStyle = MaterialTheme.typography.titleSmall.copy(
        color = colors.onSurface,
        textDirection = TextDirection.Content,
    )
    val titleNowStyle = MaterialTheme.typography.titleSmall.copy(
        color = colors.onSurface,
        textDirection = TextDirection.Content,
    )
    val timeStyle = MaterialTheme.typography.labelSmall.copy(
        color = colors.onSurfaceVariant,
        textDirection = TextDirection.Content,
    )
    val timeNowStyle = MaterialTheme.typography.labelSmall.copy(
        color = colors.onSurfaceVariant,
        textDirection = TextDirection.Content,
    )
    val formatTime = rememberSystemTimeFormatter()
    // Time labels built once (string formatting kept out of the per-frame draw loop).
    // Resolve the templates through Compose so a live locale change invalidates the labels.
    val timeRangeTemplate = stringResource(R.string.content_epg_time_range)
    val nowTemplate = stringResource(R.string.content_epg_now)
    val labels = remember(programmes, now, formatTime, timeRangeTemplate, nowTemplate) {
        programmes.map { p ->
            val t = String.format(
                java.util.Locale.ROOT,
                timeRangeTemplate,
                formatTime(p.startMs),
                formatTime(p.stopMs),
            )
            if (now in p.startMs until p.stopMs) {
                String.format(java.util.Locale.ROOT, nowTemplate, t)
            } else {
                t
            }
        }
    }
    // Vertical "now" marker + catch-up glyph — measured once, reused each frame.
    val nowColor = Color(0xFFFFC857)
    val nowLinePx = with(density) { 2.dp.toPx() }
    val catchupStyle = MaterialTheme.typography.labelSmall.copy(
        color = colors.primary,
        textDirection = TextDirection.Content,
    )
    val catchupGlyph = remember(catchupStyle) { measurer.measure("↻", catchupStyle) }

    val scrollPx = hScroll.value.toFloat() // read in composable scope so Canvas redraws on scroll
    Canvas(Modifier.fillMaxSize()) {
        val viewW = size.width
        val h = size.height
        programmes.forEachIndexed { i, p ->
            val s = p.startMs.coerceIn(windowStart, windowEnd)
            val e = p.stopMs.coerceIn(windowStart, windowEnd)
            if (e <= s) return@forEachIndexed
            val x = ((s - windowStart) / 60_000f) * pxPerMin - scrollPx
            val w = (((e - s) / 60_000f) * pxPerMin - gapPx).coerceAtLeast(0f)
            if (x + w <= 0f || x >= viewW) return@forEachIndexed // cull off-screen programmes
            val isNow = now in p.startMs until p.stopMs
            val hi = highlightTime != null && highlightTime in p.startMs until p.stopMs
            val bg = when {
                hi -> colors.card
                isNow -> colors.primaryContainer.copy(alpha = 0.18f)
                else -> colors.surfaceContainerHigh
            }
            drawRoundRect(color = bg, topLeft = Offset(x, 0f), size = Size(w, h), cornerRadius = corner)
            if (isNow) {
                drawRoundRect(
                    color = colors.primary,
                    topLeft = Offset(x + airingBarInsetPx, airingBarInsetPx),
                    size = Size(airingBarPx, (h - airingBarInsetPx * 2f).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(airingBarPx / 2f, airingBarPx / 2f),
                )
            }
            if (hi) drawRoundRect(color = colors.focusBorder, topLeft = Offset(x, 0f), size = Size(w, h), cornerRadius = corner, style = Stroke(borderPx))
            val textW = (w - padPx * 2f).toInt()
            if (textW > 8) {
                val tStyle = if (isNow && !hi) titleNowStyle else titleStyle
                val mStyle = if (isNow && !hi) timeNowStyle else timeStyle
                val title = measurer.measure(p.title, tStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = textW))
                val time = measurer.measure(labels[i], mStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = textW))
                val top = (h - (title.size.height + time.size.height + 2)) / 2f
                drawText(title, topLeft = Offset(x + padPx, top))
                drawText(time, topLeft = Offset(x + padPx, top + title.size.height + 2))
            }
            // Catch-up badge (↻) at the cell's top-right — only on programmes this channel can rewind from.
            if (p.id in catchupIds && w > 50f) {
                drawText(catchupGlyph, topLeft = Offset(x + w - catchupGlyph.size.width - 4f, 3f))
            }
        }
        // Vertical "now" marker — drawn on every row so it reads as one continuous line down the grid.
        if (now in windowStart..windowEnd) {
            val nowX = ((now - windowStart) / 60_000f) * pxPerMin - scrollPx
            if (nowX in 0f..viewW) {
                drawLine(
                    brush = Brush.verticalGradient(
                        colors = listOf(nowColor, nowColor.copy(alpha = 0.72f), nowColor.copy(alpha = 0.08f)),
                        startY = 0f,
                        endY = h,
                    ),
                    start = Offset(nowX, 0f),
                    end = Offset(nowX, h),
                    strokeWidth = nowLinePx,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ProgrammeDetailDialog(
    channelName: String,
    programme: EpgProgrammeEntity,
    loadDescription: suspend (Long) -> String?,
    canCatchup: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onWatch: () -> Unit,
    onPlayCatchup: () -> Unit,
    onDismiss: () -> Unit,
    // Where "Watch from start" sends the archive. ASK shows a chooser popup on top of this dialog;
    // INTERNAL/EXTERNAL go straight there. Defaulted so non-catch-up callers can ignore it.
    catchupPlayer: SettingsRepository.CatchupPlayer = SettingsRepository.CatchupPlayer.INTERNAL,
    onPlayCatchupExternal: () -> Unit = {},
    // Denser variant for the Live TV catch-up picker, which opens this on top of an already-small
    // popup chain — full-size chrome dwarfed the picker it came from. Guide keeps the roomy layout.
    compact: Boolean = false,
    // --- Recording (Plan D, Feature A). All defaulted, so the Live TV catch-up picker — which opens
    // this dialog on top of the player and has no business scheduling anything — is unchanged.
    /** Whether Record can be offered at all: still to come, or on a catch-up channel and already aired. */
    canRecord: Boolean = false,
    /** The recording already covering this programme, which decides what the button says. */
    recording: RecordingEntity? = null,
    /** The title of a recording this one would contend with, shown as a warning before committing (D10). */
    clashWith: String? = null,
    onRecord: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    /** Non-null when a standing "record every showing" rule already covers this title (D7). */
    seriesRuleActive: Boolean = false,
    onRecordSeries: () -> Unit = {},
    onStopSeries: () -> Unit = {},
) {
    val colors = OwnTVTheme.colors
    val formatTime = rememberSystemTimeFormatter()
    // The grid load drops `description` to stay under the CursorWindow limit, so fetch it on demand
    // here (fall back to the row's own value when it was loaded by the lazy per-row path).
    val description by produceState(programme.description, programme.id) {
        value = programme.description ?: loadDescription(programme.id)
    }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { fr.requestFocus() }
    }
    // "Always ask" → a second, small popup asking which player takes the archive.
    var showPlayerChooser by remember { mutableStateOf(false) }
    if (showPlayerChooser) {
        CatchupPlayerChooser(
            onInternal = { showPlayerChooser = false; onPlayCatchup() },
            onExternal = { showPlayerChooser = false; onPlayCatchupExternal() },
            onDismiss = { showPlayerChooser = false },
        )
    }
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
        BackHandler { onDismiss() }
      CompactPopupFont(compact) {
        Box(
            // The dialog can be opened by a long-press on the programme cell; the OK key is often still
            // held when it appears, which would instantly fire the focused action. Swallow OK until it's
            // released once so the held long-press only reveals the dialog, then the user chooses.
            Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup().longPressMenuGuard(),
            contentAlignment = Alignment.Center,
        ) {
            // Scrollable: long XMLTV descriptions can exceed a small screen's height. widthIn (not a
            // fixed width) keeps it responsive on narrow screens, so this uses .glass() directly rather
            // than dialogPanel (which sets a fixed width) — same DIALOGS surface + fill hook.
            val corner = if (compact) 16.dp else 20.dp
            Column(
                Modifier.widthIn(max = if (compact) 400.dp else 560.dp).clip(RoundedCornerShape(corner))
                    .glass(surface = GlassSurface.DIALOGS, baseFill = colors.surfaceContainerHigh, shape = RoundedCornerShape(corner))
                    .verticalScroll(rememberScrollState()).padding(if (compact) 18.dp else 28.dp),
            ) {
                Text(channelName.uppercase(), style = MaterialTheme.typography.labelMedium, color = colors.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(programme.title, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, color = colors.onSurface)
                Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                Text(stringResource(R.string.content_epg_time_range, formatTime(programme.startMs), formatTime(programme.stopMs)), style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                if (!description.isNullOrBlank()) {
                    Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
                    Text(description.orEmpty(), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                // The clash, said before the user commits to anything. A live programme cannot wait
                // its turn — "start when the other finishes" would mean "start half-way through"
                // (D10) — so this is a warning at the moment of choosing, not a failure afterwards.
                if (canRecord && clashWith != null && recording == null) {
                    Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
                    Text(
                        stringResource(R.string.recording_clash_with, clashWith),
                        style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        // The app's warning red, the same one a failed download and a failed
                        // restore already use. There is no named error role in OwnTVTheme.
                        color = Color(0xFFEF4444),
                    )
                }
                Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
                // FlowRow so the actions wrap to a second line on narrower screens instead of the last
                // button being clipped off the dialog edge (4 buttons don't fit one row when catch-up adds
                // "Watch from start" + "Watch channel").
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                ) {
                    // Catch-up channels: replay this programme from its start (seekable archive playback).
                    if (canCatchup) {
                        val startCatchup = {
                            when (catchupPlayer) {
                                SettingsRepository.CatchupPlayer.ASK -> showPlayerChooser = true
                                SettingsRepository.CatchupPlayer.INTERNAL -> onPlayCatchup()
                                SettingsRepository.CatchupPlayer.EXTERNAL -> onPlayCatchupExternal()
                            }
                        }
                        OwnTVButton(stringResource(R.string.content_epg_watch_start), onClick = startCatchup, icon = OwnTVIcon.PLAY, compact = compact, modifier = Modifier.focusRequester(fr))
                        OwnTVButton(stringResource(R.string.content_epg_watch_channel), onClick = onWatch, style = OwnTVButtonStyle.SECONDARY, compact = compact)
                    } else {
                        OwnTVButton(stringResource(R.string.content_epg_watch_channel), onClick = onWatch, icon = OwnTVIcon.PLAY, compact = compact, modifier = Modifier.focusRequester(fr))
                    }
                    // Record. What it offers depends on what is already true of this programme, so
                    // the button never lies: nothing yet → Record (or "from catch-up" when the
                    // programme has already been on); running → Stop; scheduled → Cancel.
                    if (canRecord) {
                        when (recording?.status) {
                            RecordingStatus.RECORDING -> OwnTVButton(
                                stringResource(R.string.recording_stop),
                                onClick = onStopRecording,
                                style = OwnTVButtonStyle.SECONDARY,
                                compact = compact,
                            )
                            RecordingStatus.SCHEDULED -> OwnTVButton(
                                stringResource(R.string.common_cancel),
                                onClick = onCancelRecording,
                                style = OwnTVButtonStyle.SECONDARY,
                                compact = compact,
                            )
                            // Already recorded, or failed and worth another go: Record again is the
                            // honest offer, and the Recordings screen is where the result lives.
                            else -> OwnTVButton(
                                stringResource(
                                    if (programme.stopMs <= System.currentTimeMillis()) {
                                        R.string.recording_from_archive
                                    } else {
                                        R.string.recording_record
                                    },
                                ),
                                onClick = onRecord,
                                style = OwnTVButtonStyle.SECONDARY,
                                compact = compact,
                            )
                        }
                    }
                    // "Every showing" only for a programme still to come — a rule is a standing
                    // instruction about the future, and offering it on last night's repeat would
                    // promise something it cannot do.
                    if (canRecord && programme.stopMs > System.currentTimeMillis()) {
                        OwnTVButton(
                            stringResource(
                                if (seriesRuleActive) R.string.recording_stop_series
                                else R.string.recording_record_series,
                            ),
                            onClick = if (seriesRuleActive) onStopSeries else onRecordSeries,
                            style = OwnTVButtonStyle.SECONDARY,
                            compact = compact,
                        )
                    }
                    // Favourite the channel without leaving the guide; the label flips in place.
                    OwnTVButton(
                        stringResource(if (isFavorite) R.string.content_epg_unfavourite else R.string.content_epg_favourite),
                        onClick = onToggleFavorite,
                        style = OwnTVButtonStyle.SECONDARY,
                        icon = OwnTVIcon.FAVORITE,
                        compact = compact,
                    )
                    OwnTVButton(stringResource(R.string.settings_close), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY, compact = compact)
                }
            }
        }
      }
    }
}

/** The "Always ask" chooser: which player takes this catch-up archive. Deliberately tiny — it sits on
 *  top of the programme dialog, so it only asks the one question and gets out of the way. */
@Composable
private fun CatchupPlayerChooser(
    onInternal: () -> Unit,
    onExternal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { fr.requestFocus() }
    }
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
        BackHandler { onDismiss() }
        PopupFontTheme(fontScale = 0.7f) {
            Box(
                Modifier.fillMaxSize().modalScrim()
                    .trapAllFocusExit().focusGroup(),
                contentAlignment = Alignment.Center,
            ) {
                Column(Modifier.dialogPanel(width = 340.dp, corner = 16.dp, padding = 18.dp, scroll = false)) {
                    Text(stringResource(R.string.settings_catchup_player), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.content_epg_player_choice_description),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    OwnTVButton(stringResource(R.string.content_epg_own_player), onClick = onInternal, icon = OwnTVIcon.PLAY, compact = true, modifier = Modifier.fillMaxWidth().focusRequester(fr))
                    Spacer(Modifier.height(8.dp))
                    OwnTVButton(stringResource(R.string.content_epg_external_player), onClick = onExternal, style = OwnTVButtonStyle.SECONDARY, compact = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OwnTVButton(stringResource(R.string.common_cancel), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY, compact = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Applies the shared popup type ramp at a reduced scale only when [compact]; otherwise leaves the
 *  caller's typography untouched, so the Guide's own dialog keeps its existing look. */
@Composable
private fun CompactPopupFont(compact: Boolean, content: @Composable () -> Unit) {
    if (compact) PopupFontTheme(fontScale = 0.7f, content = content) else content()
}
