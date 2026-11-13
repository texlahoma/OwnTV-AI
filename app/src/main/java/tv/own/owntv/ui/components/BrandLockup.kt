package tv.own.owntv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.core.brand.AppIconSwitcher
import tv.own.owntv.core.brand.PixelMark as CorePixelMark
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.ui.theme.stageAccent

/**
 * The icon colour the launcher shows right now. The in-app logo follows it, not the saved choice, so
 * logo and home-screen icon change together after the restart.
 */
@Composable
fun rememberAppliedIcon(): AppIcon {
    val context = LocalContext.current
    return remember(context) { AppIconSwitcher.applied(context) }
}

/** Settings › Accent-colored logo: the play triangle of every in-app logo in the accent (default on). */
@Composable
private fun accentTriangle(): Color? {
    val settings: SettingsRepository = koinInject()
    val on by settings.brandAccentTriangle.collectAsStateWithLifecycle(initialValue = true)
    return if (on) stageAccent.accent else null
}

/**
 * The icon's flat mark (no shadow, no next card); at 32 dp and below the simpler small drawing. Pixel
 * above 32 dp is its dot matrix ([PixelMark]). With Accent-colored logo on, the play triangle takes the
 * accent: on a card a white triangle is drawn over the card's own and tinted (Pixel has none).
 * [followAccent] false draws the icon as the launcher shows it (the icon pickers).
 */
@Composable
fun BrandMark(icon: AppIcon, size: Dp, modifier: Modifier = Modifier, followAccent: Boolean = true) {
    val small = size <= 32.dp
    val accent = accentTriangle().takeIf { followAccent }
    if (icon == AppIcon.PIXEL && !small) {
        PixelMark(modifier.size(size), triangle = accent ?: Color(icon.accent))
        return
    }
    Box(modifier.size(size)) {
        Image(painterResource(if (small) icon.markSmall else icon.mark), contentDescription = null, modifier = Modifier.fillMaxSize())
        if (accent != null && icon.isCard(small)) {
            Image(
                painterResource(if (small) R.drawable.owntv_mark_play_small else R.drawable.owntv_mark_play),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                colorFilter = ColorFilter.tint(accent),
            )
        }
    }
}

/**
 * "Pixel": the dot-matrix TV set (core's [CorePixelMark], the same grid as `owntv_mark_pixel`), the dots
 * of the play triangle in [triangle].
 */
@Composable
fun PixelMark(modifier: Modifier = Modifier, triangle: Color = stageAccent.accent) {
    Canvas(modifier) {
        val u = size.minDimension / 100f
        CorePixelMark.dots.forEach { d ->
            val color = when (d.kind) {
                CorePixelMark.Kind.BODY -> PixelBody
                CorePixelMark.Kind.TRIANGLE -> triangle
                CorePixelMark.Kind.SIDE -> PixelSide
            }
            drawCircle(color, CorePixelMark.DOT_RADIUS * u, Offset(d.x * u, d.y * u))
        }
        drawCircle(PixelPower, CorePixelMark.POWER_RADIUS * u, Offset(CorePixelMark.POWER_X * u, CorePixelMark.POWER_Y * u))
    }
}

private val PixelBody = Color(CorePixelMark.BODY)
private val PixelSide = Color(CorePixelMark.SIDE)
private val PixelPower = Color(CorePixelMark.POWER)

/**
 * The wordmark: lowercase "own" in cream, "tv" in [tv] (the accent), drawn from
 * core's two vectors at [width]; its height follows the 988 × 182 drawing.
 */
@Composable
fun Wordmark(width: Dp, modifier: Modifier = Modifier, tv: Color = stageAccent.accent, own: Color = WordmarkCream) {
    Box(modifier.width(width).height(width * 182f / 988f)) {
        Image(painterResource(R.drawable.owntv_wordmark_own), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(own))
        Image(painterResource(R.drawable.owntv_wordmark_tv), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(tv))
    }
}

val WordmarkCream = Color(0xFFFFF6EE)

/**
 * The lockup (Brand tab, C column): the mark, then the wordmark at 2.75 × the mark's width (62 →
 * 170 in the design). Side by side the gap is 0.23 × the mark (14 / 62); [stacked] (the preview pane)
 * puts the mark above with a 0.2 × gap.
 */
@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    markSize: Int = 36,
    stacked: Boolean = false,
) {
    val icon = rememberAppliedIcon()
    val parts = @Composable {
        BrandMark(icon, markSize.dp)
        // On a light theme "own" takes the text colour; the cream is for dark surfaces.
        Wordmark((markSize * 2.75f).dp, own = if (tv.own.owntv.ui.theme.LocalStagePalette.current.isLight) tv.own.owntv.ui.theme.StageColors.Text else WordmarkCream)
    }
    if (stacked) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((markSize * 0.2f).dp),
        ) { parts() }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((markSize * 0.23f).dp),
        ) { parts() }
    }
}
