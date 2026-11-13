package tv.own.owntv.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.core.live.MAX_MULTIVIEW_TILES
import tv.own.owntv.core.live.MIN_MULTIVIEW_TILES

/**
 * The **most** tiles the Multiview grid may have. Every value is always offered — nothing is greyed
 * out — and the grid still opens with two, growing only when the user asks for more.
 */
@Composable
internal fun MultiviewTilesDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val tiles = (MIN_MULTIVIEW_TILES..MAX_MULTIVIEW_TILES).toList()
    // Each option is a ceiling too, so it reads the same as the row it came from. A simple choice:
    // on the settings page it opens in the panel.
    PickerDialog(
        title = stringResource(R.string.settings_multiview_tiles_max),
        options = tiles.map { it.toString() to stringResource(R.string.settings_multiview_tiles_max_value, it) },
        selected = current.toString(),
        onSelect = { onPick(it.toInt()) },
        onDismiss = onDismiss,
    )
}

/**
 * Three or four tiles need three or four provider connections and three or four hardware decoders.
 *
 * Shaped exactly like the Auto-frame-rate warning, and asked in Settings only. It never
 * refuses: "Use anyway" gives the count the user picked, and a tile that then cannot start says so
 * itself. Focus starts on Keep two.
 */
@Composable
internal fun MultiviewWarningDialog(onUseAnyway: () -> Unit, onKeepTwo: () -> Unit) {
    tv.own.owntv.ui.stage.StageConfirm(
        title = stringResource(R.string.settings_multiview_warning_title),
        body = stringResource(R.string.settings_multiview_warning_description),
        cancel = stringResource(R.string.settings_multiview_keep_two),
        confirm = stringResource(R.string.settings_multiview_use_anyway),
        onConfirm = onUseAnyway,
        onCancel = onKeepTwo,
        focusCancel = true,
    )
}
