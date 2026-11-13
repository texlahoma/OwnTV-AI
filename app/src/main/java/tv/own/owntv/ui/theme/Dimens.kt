package tv.own.owntv.ui.theme

import androidx.compose.ui.unit.dp

/** Shared sizing tokens for the TV shell. */
object Dimens {
    val RailWidthFixed = 272.dp    // fixed full-label category column (names wrap to 2 lines)
    val ChannelListWidth = 460.dp  // fixed channel-list column (Live); preview/detail fills the rest.
    // Wide enough for each row to also show the current programme (EPG) on a second line — the
    // preview pane (Modifier.weight(1f)) narrows in step, so no other layout value needs to change.

    val FocusBorderWidth = 2.dp
}
