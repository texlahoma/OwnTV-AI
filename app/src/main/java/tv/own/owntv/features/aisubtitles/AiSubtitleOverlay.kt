package tv.own.owntv.features.aisubtitles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.settings.SubtitleStyle
import tv.own.owntv.player.alignment
import tv.own.owntv.player.textAlign

/**
 * Base caption size at the user's 100 % setting; the adjustable [AiSubtitleConfig.scale] multiplies
 * it (50–200 %), so this is a reference point, not a fixed size. Both lines share it, so the block
 * never resizes when the translation lands.
 */
private val CAPTION_FONT_SIZE = 20.sp
private val CAPTION_LINE_HEIGHT = 26.sp

/**
 * Renders the AI-subtitle lines over the picture in a bilingual layout: the recognised source-language
 * text on top and its translation below, both at the SAME font size so the block never resizes when
 * the translation arrives (a smaller original used to read as "text jumping"/"Chinese larger than
 * English"). Both are warm-tinted so a rendered `AI translation` can never be mistaken for an authored
 * subtitle from the stream.
 *
 * The overlay is engine-agnostic (works over both the mpv and the ExoPlayer surface), drawn only when
 * the feature is enabled and at least one line is available. When only one line exists — translation is
 * still in flight, or the source language is chosen as the target — it renders that single line so the
 * caption never disappears just because the other half is not ready. If a failed translation fell back
 * to the source text, the identical string is not stacked twice.
 */
@Composable
fun AiSubtitleOverlay(modifier: Modifier = Modifier, sizeScale: Float = 1f) {
    val manager = koinInject<AiSubtitleManager>()
    val enabled by manager.effective.collectAsStateWithLifecycle()
    val source by manager.asrText.collectAsStateWithLifecycle()
    val pair by manager.pair.collectAsStateWithLifecycle()
    val cfg by manager.config.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    if (!enabled.enabled) return

    // The English line is the recognizer's LIVE partial — it must track the actor in real time, so
    // it always reflects the latest [asrText] whatever stage the first translation is at. It cannot
    // be frozen to the last committed segment (pair.source) once a pair exists, or the English line
    // would jump only on every 8-word commit and regress the real-time behaviour this feature
    // exists to provide. [source] may be blank between utterances; [pair.source] then stands in so
    // a just-finished line does not blink out before its translation. Endpoint resets of the stream
    // briefly blank [source] too, and pair.source is the last complete, correct English on screen.
    val displaySource = source ?: pair?.source
    // The Chinese line is bound to the exact committed English segment that spawned it (pair.source).
    // While the live partial still contains that segment (speech after it is not yet committed), the
    // pair's translation stays correct and on screen — no {old Chinese, new English} and no
    // translating 1–3 word fragments. Drop the target line when it is empty or an exact duplicate of
    // the source (translation fallback).
    val translated = pair?.translated?.takeIf { it.isNotBlank() && it != displaySource }
    val showSource = !displaySource.isNullOrBlank()
    val showTarget = !translated.isNullOrBlank()
    // No caption yet: surface a pipeline status line (model download in progress / failure) so a
    // silent start is diagnosable on screen instead of an empty overlay.
    val statusLine = when {
        showSource || showTarget -> null
        status.startsWith("error:") -> status.removePrefix("error:")
        status.startsWith("download:") ->
            stringResource(R.string.player_ai_subtitles_downloading, status.removePrefix("download:"))
        else -> null
    }
    if (!showSource && !showTarget && statusLine == null) return

    // Display config: relative font size plus one of the six anchors (or the historical bottom-centre).
    val subScale = cfg.scale
    // Line spacing is a separate, equally adjustable multiplier: with two stacked languages the gap
    // between the English and the Chinese line is what decides whether the block reads as one caption
    // or as two unrelated lines. It scales the in-line leading AND the gap between the two lines.
    val lineScale = cfg.lineHeight
    val tokenScale = sizeScale * subScale
    val lineSpacing = CAPTION_LINE_HEIGHT * lineScale * tokenScale
    // Base gap between the two language lines; the user's line-spacing setting scales it along with
    // the in-line leading, so the bilingual block can be spread apart or tightened as one control.
    val gapBetweenLines = 6.dp * lineScale * tokenScale
    val anchor = SubtitleStyle.Position.fromKey(cfg.position)
    val textAlign = anchor.textAlign()
    // Fine-grained nudge (dp) from the player's arrow buttons, applied on top of the anchor.
    val nudge = cfg.positionOffset

    Box(
        modifier = modifier.fillMaxSize().padding(
            horizontal = 40.dp * sizeScale,
            vertical = (if (anchor.isTop) 40.dp else 56.dp) * sizeScale,
        ),
        contentAlignment = anchor.alignment(),
    ) {
        Column(
            modifier = Modifier
                .offset(x = nudge.x.dp, y = nudge.y.dp)
                .widthIn(max = 1000.dp)
                .clip(RoundedCornerShape(8.dp))
                // 55% black instead of 40%: the amber AI lines must stay legible over bright scenes,
                // and the extra contrast is what makes the block read cleanly at a glance.
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showSource && showTarget) {
                // Bilingual: the spoken English on top, its Chinese translation below. Both lines
                // share one font size — a smaller original would shrink/jump the moment the
                // translation arrived.
                Text(
                    text = displaySource,
                    textAlign = textAlign,
                    style = TextStyle(
                        color = Color(0xFFE8C98A), // softer amber: the original spoken line
                        fontSize = CAPTION_FONT_SIZE * tokenScale,
                        lineHeight = lineSpacing,
                        fontWeight = FontWeight.Normal,
                        shadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 6f),
                    ),
                )
                Spacer(Modifier.height(gapBetweenLines))
                Text(
                    text = translated,
                    textAlign = textAlign,
                    style = TextStyle(
                        color = Color(0xFFFFC46B), // warm amber: unmistakably "AI"
                        fontSize = CAPTION_FONT_SIZE * tokenScale,
                        lineHeight = lineSpacing,
                        fontWeight = FontWeight.Medium,
                        shadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 6f),
                    ),
                )
            } else {
                // Single line: whichever of the two is present (source while translating, or the
                // translated/fallen-back text once translation completes) — same size as a bilingual
                // line so nothing resizes when the other half appears.
                Text(
                    text = translated ?: displaySource!!,
                    textAlign = textAlign,
                    style = TextStyle(
                        color = Color(0xFFFFC46B),
                        fontSize = CAPTION_FONT_SIZE * tokenScale,
                        lineHeight = lineSpacing,
                        fontWeight = FontWeight.Medium,
                        shadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 6f),
                    ),
                )
            }
        }
    }
}