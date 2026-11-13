package tv.own.owntv.ui.components

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import tv.own.owntv.ui.theme.PopupFontTheme

/**
 * The single host for OwnTV modal popups.
 *
 * MainActivity deliberately keeps Android TV's broadly-compatible `adjustPan`. Each modal owns a
 * focus-isolated platform window configured as `adjustNothing`; OwnTV measures the unobstructed
 * display band and lays the popup out inside it. This also works with TV keyboards which publish no
 * useful IME inset and ignore `adjustResize`.
 *
 * Popup chrome and the user-selected popup typography are reduced together here. Keeping the scale in the host means
 * nested popups (Rule builder -> Rule value) cannot silently return to full application size.
 *
 * A popup has no settings panel of its own: [LocalSettingsPanel][tv.own.owntv.features.settings.LocalSettingsPanel]
 * is cleared inside it, so a picker opened here draws its own popup and a row here never writes its
 * help into the page behind.
 *
 * [stageLayout] is for the Stage popups and menus, which are drawn in design pixels at the screen's own
 * scale times the user's Popup size, and placed by their caller: no 0.70 host scale, no centring.
 */
@Composable
fun OwnTVPopup(
    onDismissRequest: () -> Unit,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    fontScale: Float = 0.70f,
    stageLayout: Boolean = false,
    /** Stage popups follow Popup size and popup font size (the drawn size = 100%). False for a full-screen player. */
    stageScaled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // The app's own density (2.0 × UI Zoom). A dialog window starts again from the display's density,
    // so Stage menus, drawn in design pixels, would come out at 100% zoom whatever the user chose.
    val appDensity = LocalDensity.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = LocalView.current
        var parent = dialogView.parent
        var provider: DialogWindowProvider? = null
        while (parent != null && provider == null) {
            provider = parent as? DialogWindowProvider
            parent = parent.parent
        }
        val dialogWindow = provider?.window
        DisposableEffect(dialogWindow) {
            val previousMode = dialogWindow?.attributes?.softInputMode
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            onDispose {
                if (previousMode != null) dialogWindow.setSoftInputMode(previousMode)
            }
        }
        SideEffect {
            // Compose/OEM code can update dialog attributes after initial attachment.
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }

        val watcher = remember(dialogView) { TvImeWatcher(dialogView) }
        DisposableEffect(watcher) {
            watcher.attach()
            onDispose { watcher.detach() }
        }
        LaunchedEffect(watcher.imeRequested) {
            if (!watcher.imeRequested) return@LaunchedEffect
            val count = (TvImeDefaults.POLL_DURATION_MS / TvImeDefaults.POLL_INTERVAL_MS).toInt()
            repeat(count) {
                kotlinx.coroutines.delay(TvImeDefaults.POLL_INTERVAL_MS)
                watcher.poll()
            }
        }

        val baseDensity = LocalDensity.current
        val metrics = watcher.metrics
        val displayHeightPx = metrics.displayHeightPx.takeIf { it > 0 }
            ?: dialogView.resources.displayMetrics.heightPixels
        val topSafePx = with(baseDensity) { 24.dp.roundToPx() }
        val bottomSafePx = with(baseDensity) { 24.dp.roundToPx() }
        val keyboardGapPx = with(baseDensity) { 16.dp.roundToPx() }
        val usableBottomPx = if (metrics.visible) {
            (metrics.keyboardTopPx - keyboardGapPx).coerceAtLeast(topSafePx)
        } else {
            (displayHeightPx - bottomSafePx).coerceAtLeast(topSafePx)
        }
        val availableHeightPx = (usableBottomPx - topSafePx).coerceAtLeast(1)
        val availableHeightDp = with(baseDensity) { availableHeightPx.toDp() }

        if (stageLayout) {
            // Popup size scales the whole popup; popup font size its text, in place of the main font size.
            val stageDensity = if (!stageScaled) appDensity else Density(
                density = appDensity.density * tv.own.owntv.ui.theme.LocalPopupSizeScaleFactor.current,
                fontScale = appDensity.fontScale / tv.own.owntv.ui.theme.LocalUiFontScaleFactor.current *
                    tv.own.owntv.ui.theme.LocalPopupFontScaleFactor.current,
            )
            CompositionLocalProvider(LocalDensity provides stageDensity, LocalTvImeWatcher provides watcher, LocalTvImeMetrics provides metrics, tv.own.owntv.features.settings.LocalSettingsPanel provides null) {
                Box(Modifier.fillMaxSize()) { content() }
            }
            return@Dialog
        }

        val popupScale = 0.70f
        // The host owns the fixed TV-safe base scale. PopupFontTheme applies the user's independent
        // popup geometry and font controls for both hosted and legacy inline popup content.
        val popupDensity = Density(
            density = baseDensity.density * popupScale,
            fontScale = baseDensity.fontScale / popupScale,
        )

        Box(Modifier.fillMaxSize()) {
            // Centre inside the unobstructed physical band. ADJUST_NOTHING ensures this is the only
            // movement, eliminating double pan/translation across different TV implementations.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, topSafePx) }
                    .height(availableHeightDp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalDensity provides popupDensity,
                    LocalTvImeWatcher provides watcher,
                    LocalTvImeMetrics provides metrics,
                    tv.own.owntv.features.settings.LocalSettingsPanel provides null,
                ) {
                    PopupFontTheme(fontScale = fontScale) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            content()
                        }
                    }
                }
            }
        }
    }
}
