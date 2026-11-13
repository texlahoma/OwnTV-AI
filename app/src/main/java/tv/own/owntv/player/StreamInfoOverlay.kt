package tv.own.owntv.player

import tv.own.owntv.ui.theme.hudShade
import tv.own.owntv.ui.theme.hudInk
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import tv.own.owntv.R

/**
 * Non-interactive technical readout for the current stream (codec, resolution, HDR, bitrate, decoder, audio,
 * buffer, source). Reads [PlaybackEngine.streamInfo] live — re-polled once a second so bitrate/buffer update
 * — and works on whichever engine is playing (mpv or ExoPlayer). Toggled from the player's info button.
 */
@Composable
fun StreamInfoOverlay(player: PlaybackEngine, modifier: Modifier = Modifier) {
    // Starts empty and is filled by the effect below: the mpv read now happens on the player's own
    // executor, so there is nothing to read synchronously during composition.
    var rows by remember { mutableStateOf(emptyList<StreamInfoRow>()) }
    // Re-read on the player's shared cosmetic beat rather than a timer of its own, so having the overlay
    // up alongside the position readout costs one wake-up rather than two (see [PlayerHeartbeat]).
    LaunchedEffect(player) {
        PlayerHeartbeat.beat.collect { rows = player.streamInfo() }
    }
    if (rows.isEmpty()) return
    val res = LocalResources.current

    Column(
        modifier = modifier
            .widthIn(min = 300.dp, max = 460.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(hudShade(0.78f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            stringResource(R.string.player_stream_info),
            style = tv.own.owntv.ui.theme.stageText(22, 700),
            color = tv.own.owntv.ui.theme.stageAccent.accent,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(2.dp))
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Text(
                    stringResource(row.label.titleRes),
                    style = tv.own.owntv.ui.theme.stageText(22, 400),
                    color = tv.own.owntv.ui.theme.StageColors.Muted,
                    modifier = Modifier.weight(0.38f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.value.displayText(res),
                    style = tv.own.owntv.ui.theme.stageText(22, 400),
                    color = hudInk,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(0.62f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
