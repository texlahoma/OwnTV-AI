package tv.own.owntv.features.settings.data

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.own.owntv.core.settings.PanelSection
import tv.own.owntv.core.settings.PanelShares
import tv.own.owntv.core.settings.PanelWidthLimits
import tv.own.owntv.core.settings.balanceToTotal
import tv.own.owntv.ui.theme.Dimens
import kotlin.math.roundToInt

/** Inset between the shared browse container edge and its columns. */
val BrowseContainerPadding: Dp = 12.dp

/** The spacing between each column, the divider, and the raised preview. */
val BrowseColumnGap: Dp = 12.dp

/** The category/list separator itself. */
val BrowseColumnDividerSpace: Dp = 1.dp

/** Non-content width inside the shared browse container. */
fun browsePanelGapTotal(previewVisible: Boolean): Dp =
    BrowseColumnDividerSpace + BrowseColumnGap * if (previewVisible) 3 else 2

/** Resolved widths for one screen's three panels. */
data class PanelWidthSpec(val category: Dp, val list: Dp, val preview: Dp)

/**
 * The shares the app uses today, for a row [rowWidth] dp wide — what the dialog seeds with, so
 * "default" starts out looking like the shipped layout. Snapped to [PanelWidthLimits.STEP] and
 * corrected so the three always add up to 100.
 *
 * [gapTotal] is the shared container's category/list divider space plus the gap before the raised
 * preview pane. The columns themselves never occupy it.
 */
fun defaultPanelShares(
    section: PanelSection,
    rowWidth: Dp,
    gapTotal: Dp = browsePanelGapTotal(previewVisible = true),
): PanelShares {
    val content = (rowWidth - gapTotal).value.coerceAtLeast(1f)
    val rail = Dimens.RailWidthFixed.value
    val listDp: Float
    val previewDp: Float
    if (section == PanelSection.LIVE) {
        listDp = Dimens.ChannelListWidth.value
        previewDp = (content - rail - listDp).coerceAtLeast(1f)
    } else {
        val rest = (content - rail).coerceAtLeast(1f)
        listDp = rest * 1.8f / 2.8f
        previewDp = rest * 1f / 2.8f
    }
    val category = PanelWidthLimits.snap((rail / content * 100f).roundToInt())
    val list = PanelWidthLimits.snap((listDp / content * 100f).roundToInt())
    val preview = PanelWidthLimits.snap((previewDp / content * 100f).roundToInt())
    return balanceToTotal(PanelShares(category, list, preview))
}

/** Turns validated shares into concrete widths for a row [total] dp wide. */
fun computePanelWidths(
    shares: PanelShares,
    total: Dp,
    gapTotal: Dp = browsePanelGapTotal(previewVisible = shares.preview != 0),
): PanelWidthSpec {
    val content = (total - gapTotal).value.coerceAtLeast(1f)
    // Normalize by the real sum rather than trusting it to be 100: a value written by an older build
    // (or an abandoned edit) must still produce a sane layout instead of over/under-filling the row.
    val sum = shares.total.coerceAtLeast(1)
    val category = content * shares.category / sum
    val list = content * shares.list / sum
    return PanelWidthSpec(
        category = category.dp,
        list = list.dp,
        // The remainder, so rounding never leaves a sliver of background down the right edge.
        // Zero is kept exact: the screen then omits this panel and its second inter-panel gap.
        preview = if (shares.preview == 0) 0.dp else (content - category - list).coerceAtLeast(1f).dp,
    )
}
