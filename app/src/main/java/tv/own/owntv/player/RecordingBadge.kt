package tv.own.owntv.player

import tv.own.owntv.ui.theme.hudShade
import tv.own.owntv.ui.theme.hudInk
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.recording.RecordingActivityTracker
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.AnimationLevel

/**
 * "REC", over the picture, for as long as anything is being recorded.
 *
 * The status pill carries recordings everywhere else in the app, but the shell hides the pill
 * during fullscreen playback — so this is the only thing standing between a user and an hour of
 * recording they had no idea was happening. It renders nothing at all when nothing is recording.
 *
 * It does not say *what* is recording. The programme title would be a second line over the picture,
 * and the question this badge answers is "is my provider's connection busy right now?", which needs
 * no title. The Recordings screen has the details.
 */
@Composable
fun RecordingBadge(modifier: Modifier = Modifier) {
    val tracker: RecordingActivityTracker = koinInject()
    val settings: SettingsRepository = koinInject()
    val active by tracker.active.collectAsStateWithLifecycle()
    if (active.isEmpty()) return

    val animation by settings.animationLevel.collectAsStateWithLifecycle(initialValue = AnimationLevel.FULL)

    // The pulse is what makes a red dot read as "recording" rather than as an error marker — but with
    // Animations off there is NO transition started at all, rather than one scaled to zero.
    //
    // That distinction is the whole reason this is written as a branch: an infinite animation whose
    // duration had been scaled to 0 ms is what crashed the app in 4.2.3, from the LIVE badge in this
    // very HUD. `AnimationLevel.scale(...)` returning 0 must never reach `infiniteRepeatable`.
    val dotAlpha = if (animation == AnimationLevel.OFF) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "rec")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = PULSE_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "recPulse",
        )
        pulse
    }

    Row(
        modifier = modifier
            .padding(top = 18.dp, end = 22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(hudShade(0.55f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(RecordingRed),
        )
        Text(
            text = stringResource(R.string.recording_badge),
            color = hudInk,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The same warning red the rest of the app uses for a failure, which is also what a REC dot is. */
private val RecordingRed = Color(0xFFEF4444)

private const val PULSE_MS = 900
