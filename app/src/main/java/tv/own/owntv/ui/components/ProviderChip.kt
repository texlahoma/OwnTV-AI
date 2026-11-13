package tv.own.owntv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import tv.own.owntv.R

/** Compact, non-focusable source label used only when a section has multiple active playlists. */
@Composable
fun ProviderChip(
    name: String,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 120.dp,
    compact: Boolean = false,
) {
    val description = stringResource(R.string.content_provider_name, name)
    Text(
        text = name,
        style = if (compact) tv.own.owntv.ui.theme.stageText(20, 500) else tv.own.owntv.ui.theme.stageText(22, 700),
        color = tv.own.owntv.ui.theme.StageColors.Text,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(7.dp))
            .background(tv.own.owntv.ui.theme.StageColors.Ink.copy(alpha = 0.08f))
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 2.dp else 3.dp)
            .semantics { contentDescription = description },
    )
}
