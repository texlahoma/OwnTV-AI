package tv.own.owntv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon

/**
 * "Audio only" plate, drawn over the (black) video surface while the playing item carries no video track
 * of its own — a radio channel in a TV playlist, a music-only file filed under Movies.
 *
 * **Why it exists.** The player deliberately does NOT treat a missing video track as a failure: the
 * stream is healthy and the sound is correct. But sound over a black screen is indistinguishable from a
 * broken player, so with nothing on screen the honest outcome looks exactly like the bug and gets
 * reported as one. This says which it is, and it stays up — a toast would vanish and leave the same
 * black screen behind it for whoever walks in two minutes later.
 *
 * **Hardware-overlay note.** Composing anything over the video SurfaceView costs mpv's direct scan-out
 * path (4K drops to a slideshow). That is why this is mounted only while
 * [PlaybackEngine.audioOnlyMedia] is true: an item with no video has no scan-out to lose, so the
 * invariant holds by construction.
 *
 * [compact] drops the text for the docked mini-player, where a sentence would be unreadable anyway.
 */
@Composable
fun AudioOnlyBadge(modifier: Modifier = Modifier, compact: Boolean = false) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 12.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(horizontal = if (compact) 14.dp else 36.dp, vertical = if (compact) 14.dp else 28.dp),
        ) {
            OwnTVIcon(
                icon = OwnTVIcon.AUDIO,
                tint = tv.own.owntv.ui.theme.stageAccent.accent,
                modifier = Modifier.size(if (compact) 30.dp else 52.dp),
            )
            if (!compact) {
                Text(
                    text = stringResource(R.string.player_audio_only_title),
                    style = tv.own.owntv.ui.theme.stageText(40, 600),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.player_audio_only_body),
                    style = tv.own.owntv.ui.theme.stageText(25, 400),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.9f),
                )
            }
        }
    }
}
