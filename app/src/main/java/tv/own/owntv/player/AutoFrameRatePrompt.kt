package tv.own.owntv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.core.ui.findActivity
import kotlin.math.roundToInt

/** How long the mismatched stream must play before the suggestion appears — long enough that it never
 *  interrupts zapping, short enough that the judder is still on screen when it's read. */
private const val SETTLE_MS = 8_000L

/**
 * The one-time "your TV is at 60 Hz and this stream is 25 fps" suggestion.
 *
 * On mpv's direct `vo=mediacodec_embed` path the app does not control scan-out timing — frames go to the
 * decoder's surface and the display presents them on its own cadence. 25 fps on a fixed 60 Hz output
 * therefore becomes an uneven 2:3 pulldown (visible judder) while 50 fps maps cleanly. That is a known
 * limitation of the direct path, not something OwnTV can smooth over, and **Auto frame rate is the only
 * cure** — which ships off by default, so the affected user has no way to know it exists.
 *
 * Deliberately conservative, because an unsolicited dialog over a playing video is intrusive:
 * - full-screen only, and only while Auto frame rate is OFF;
 * - only when the display really is on a rate that isn't a multiple of the stream's, *and* a matching
 *   mode exists at the current resolution ([FrameRateController.betterRefreshRateFor]). On a panel with
 *   nothing but 60 Hz there is nothing to offer, so nothing is said;
 * - only after [SETTLE_MS] of continuous playback at that frame rate;
 * - shown **once ever**, whichever way it is answered.
 */
@Composable
fun AutoFrameRatePrompt(
    fps: Float?,
    afrEnabled: Boolean,
    alreadyPrompted: Boolean,
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var offer by remember { mutableStateOf<Pair<Float, Float>?>(null) } // current Hz to target Hz

    LaunchedEffect(fps, afrEnabled, alreadyPrompted) {
        offer = null
        if (afrEnabled || alreadyPrompted) return@LaunchedEffect
        // Below Android 12 the display never tells us which rates it can reach without blanking, so
        // enabling AFR there trades judder for a black screen at every switch — Settings actively warns
        // against it. Volunteering the suggestion on that hardware would be recommending both ways at
        // once, so the offer is simply never made; the setting is still there for anyone who wants it.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return@LaunchedEffect
        val rate = fps ?: return@LaunchedEffect
        if (rate <= 0f) return@LaunchedEffect
        val activity = context.findActivity() ?: return@LaunchedEffect
        val target = FrameRateController.betterRefreshRateFor(activity, rate) ?: return@LaunchedEffect
        val current = FrameRateController.currentRefreshRate(activity) ?: return@LaunchedEffect
        delay(SETTLE_MS) // cancelled by any fps change, so zapping never triggers it
        offer = current to target
    }

    val (currentHz, targetHz) = offer ?: return
    tv.own.owntv.ui.stage.StageConfirm(
        title = stringResource(R.string.player_frame_rate_prompt_title),
        body = stringResource(
            R.string.player_frame_rate_prompt_description,
            fps?.roundToInt() ?: 0,
            currentHz.roundToInt(),
            targetHz.roundToInt(),
        ),
        cancel = stringResource(R.string.settings_not_now),
        confirm = stringResource(R.string.player_frame_rate_turn_on),
        onConfirm = onEnable,
        onCancel = onDismiss,
        eyebrow = null,
    )
}
