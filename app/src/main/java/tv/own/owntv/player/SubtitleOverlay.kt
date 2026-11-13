package tv.own.owntv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.compose.koinInject
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SubtitleStyle
import tv.own.owntv.ui.theme.LocalUiFontScaleFactor
import tv.own.owntv.ui.theme.asComposeFamily

/**
 * App-drawn subtitles for the direct render path: the decoder owns the video surface there, so mpv
 * can't draw its OSD — instead the player polls the active subtitle line ([OwnTVPlayer.subText])
 * and this overlay renders it Netflix-style. Inactive (empty) in GL mode, where mpv draws its own.
 */
@Composable
fun SubtitleOverlay(
    player: OwnTVPlayer,
    modifier: Modifier = Modifier,
    /** Shrinks text and insets so the same overlay fits the docked mini-player; 1f = full screen. */
    sizeScale: Float = 1f,
) {
    val text by player.subText.collectAsStateWithLifecycle()
    val settings = koinInject<SettingsRepository>()
    // Subtitle appearance — every option left on its own "Default" (and everything, while the
    // master toggle is off) resolves to the exact look this overlay has always had: white text on a
    // 45%-black box, centred 56dp above the bottom edge.
    val styleOn by settings.subtitleStyleEnabled.collectAsStateWithLifecycle(initialValue = false)
    // This overlay draws mpv's own subtitle line (polled from the direct render path), so it takes the
    // mpv size — including in the mini player, where sizeScale then shrinks it to the window.
    val scale by settings.subtitleScaleMpv.collectAsStateWithLifecycle(initialValue = SubtitleStyle.SCALE_DEFAULT)
    val font by settings.subtitleFont.collectAsStateWithLifecycle(initialValue = null)
    val colorHex by settings.subtitleColor.collectAsStateWithLifecycle(initialValue = SubtitleStyle.COLOR_DEFAULT)
    val position by settings.subtitlePosition.collectAsStateWithLifecycle(initialValue = SubtitleStyle.Position.DEFAULT)
    val bgOpacity by settings.subtitleBgOpacity.collectAsStateWithLifecycle(initialValue = SubtitleStyle.OPACITY_DEFAULT)
    val line = text ?: return

    val textScale = if (styleOn) scale else SubtitleStyle.SCALE_DEFAULT
    val textColor = if (styleOn && SubtitleStyle.hasColor(colorHex)) Color(SubtitleStyle.colorArgb(colorHex)) else Color.White
    val boxColor = if (styleOn && SubtitleStyle.hasOpacity(bgOpacity)) {
        Color(SubtitleStyle.backgroundArgb(bgOpacity))
    } else {
        Color.Black.copy(alpha = 0.45f)
    }
    val anchor = if (styleOn) position else SubtitleStyle.Position.DEFAULT
    // Font customization is UI-only. Subtitles keep their dedicated size control and system family.
    val uiFontCompensation = 1f / LocalUiFontScaleFactor.current

    val alignment = anchor.alignment()
    val textAlign = anchor.textAlign()
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = 40.dp * sizeScale,
                vertical = (if (anchor.isTop) 40.dp else 56.dp) * sizeScale,
            ),
        contentAlignment = alignment,
    ) {
        Text(
            text = line,
            textAlign = textAlign,
            style = TextStyle(
                color = textColor,
                fontSize = (24 * textScale * sizeScale * uiFontCompensation).sp,
                lineHeight = (30 * textScale * sizeScale * uiFontCompensation).sp,
                fontFamily = if (styleOn) font?.asComposeFamily() ?: FontFamily.SansSerif else FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                shadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 6f),
                textDirection = if (isRtlSubtitle(line)) TextDirection.Rtl else TextDirection.Ltr,
            ),
            modifier = Modifier
                .widthIn(max = 1100.dp * sizeScale)
                .clip(RoundedCornerShape(8.dp))
                .background(boxColor)
                .padding(horizontal = 16.dp * sizeScale, vertical = 6.dp * sizeScale),
        )
    }
}

/**
 * Whether a cue reads right to left: more Hebrew/Arabic letters than Latin ones. Decided once for the
 * whole cue — left to Compose, every line took the app's own direction, so a Hebrew line ended
 * up with its full stop, dash or number on the wrong side unless the file carried bidi marks.
 */
internal fun isRtlSubtitle(text: String): Boolean {
    var rtl = 0
    var ltr = 0
    for (c in text) when (Character.getDirectionality(c)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
    }
    return rtl > ltr
}

/**
 * The one derivation of a subtitle anchor into Compose layout — used by this overlay and by both
 * previews in Settings, so the picker cannot show a corner the player then renders somewhere else.
 *
 * Six fixed anchors; anything unrecognised keeps the historical bottom-centre placement. The Media3
 * `Cue` path in [MpvVideoSurface] expresses the same six positions in Media3's own line/position
 * fraction geometry rather than in Compose types, so it necessarily stays a separate mapping — but it
 * reads the same [SubtitleStyle.Position] flags, and there is nowhere else these are derived.
 */
internal fun SubtitleStyle.Position.alignment(): Alignment = when {
    isTop && isLeft -> Alignment.TopStart
    isTop && isRight -> Alignment.TopEnd
    isTop -> Alignment.TopCenter
    isLeft -> Alignment.BottomStart
    isRight -> Alignment.BottomEnd
    else -> Alignment.BottomCenter
}

/** Text alignment matching [alignment] — a left-anchored block reads ragged-right, and vice versa. */
internal fun SubtitleStyle.Position.textAlign(): TextAlign = when {
    isLeft -> TextAlign.Start
    isRight -> TextAlign.End
    else -> TextAlign.Center
}
