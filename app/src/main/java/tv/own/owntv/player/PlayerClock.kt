package tv.own.owntv.player

import tv.own.owntv.ui.theme.hudShade
import tv.own.owntv.ui.theme.hudInk
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.ui.format.rememberBestDateFormatter
import tv.own.owntv.ui.format.rememberSystemTimeFormatter

/**
 * The player's clock, centred at the top of the HUD.
 *
 * Sits in the middle rather than a corner because that band is empty in every mode — so the guide card
 * on the right keeps exactly the position it has always had, and on movies and episodes nothing moves
 * at all. A corner clock would have pushed the guide down on live channels for no gain.
 *
 * One panel, one or two columns. Normally it holds only **Current time**. When [watchingMs] is non-null
 * an archive is on screen, and **Programme time** joins it on the left: the recording's own date and
 * time, advancing as it plays. Both are needed at once — a lone real clock reads 10:00 over a picture
 * from yesterday afternoon, which is worse than showing no clock at all.
 *
 * Both columns are labelled, including when only one is present. The label is what stops a lone
 * "13:00" from being read as a broken device clock, and it costs one small line.
 */
@Composable
internal fun PlayerClock(watchingMs: Long?, modifier: Modifier = Modifier) {
    val formatTime = rememberSystemTimeFormatter()
    val formatDate = rememberBestDateFormatter("EEEdMMM")
    // Minute precision is all that is displayed, so a 10 s tick keeps it honest without waking the
    // frame loop pointlessly. The HUD is only on screen in bursts anyway.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); nowMs = System.currentTimeMillis() } }

    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(hudShade(0.38f))
            .border(1.dp, hudInk.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (watchingMs != null) {
            ClockColumn(
                label = stringResource(R.string.content_clock_programme),
                time = formatTime(watchingMs),
                date = formatDate(watchingMs),
                labelColor = tv.own.owntv.ui.theme.stageAccent.accent,
                timeColor = tv.own.owntv.ui.theme.stageAccent.accent,
                dateColor = tv.own.owntv.ui.theme.stageAccent.accent.copy(alpha = 0.7f),
            )
            // Hairline between the two, exactly like the guide card's own divider.
            Box(Modifier.height(38.dp).width(1.dp).background(hudInk.copy(alpha = 0.18f)))
        }
        ClockColumn(
            label = stringResource(R.string.content_clock_current),
            time = formatTime(nowMs),
            date = formatDate(nowMs),
            labelColor = hudInk.copy(alpha = 0.45f),
            timeColor = hudInk,
            dateColor = hudInk.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun ClockColumn(
    label: String,
    time: String,
    date: String,
    labelColor: Color,
    timeColor: Color,
    dateColor: Color,
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            label.uppercase(),
            style = tv.own.owntv.ui.theme.stageText(20, 500).copy(letterSpacing = 1.2.sp),
            color = labelColor,
            fontWeight = FontWeight.Bold,
        )
        Text(
            time,
            style = tv.own.owntv.ui.theme.stageText(43, 400),
            color = timeColor,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            date.uppercase(),
            style = tv.own.owntv.ui.theme.stageText(20, 500).copy(letterSpacing = 0.8.sp),
            color = dateColor,
        )
    }
}
