package tv.own.owntv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import tv.own.owntv.core.theme.OwnTVPalette

// Per-region colours of the browse layout, as the panel-width diagrams draw it. The values are core's,
// because the mobile app paints the same regions.
val ContentPanelFill: Color
    @Composable @ReadOnlyComposable get() =
        Color(if (tv.own.owntv.ui.theme.LocalStagePalette.current.isLight) OwnTVPalette.LightContentPanel else OwnTVPalette.DarkContentPanel)

val PreviewPanelFill: Color
    @Composable @ReadOnlyComposable get() =
        Color(if (tv.own.owntv.ui.theme.LocalStagePalette.current.isLight) OwnTVPalette.LightPreviewPanel else OwnTVPalette.DarkPreviewPanel)
