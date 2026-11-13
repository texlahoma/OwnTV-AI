package tv.own.owntv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.theme.OwnTVTheme
import java.io.File

/**
 * Background image picker chooser — mirrors Backup's Local/Remote chooser UX:
 * - **From device**: opens the StorageBrowser to pick an image file, which is then copied into
 *   app-private storage (so a USB unplug / source-folder delete can't blank it).
 * - **Remote**: opens the LAN companion upload flow (PIN + QR, same as Remote backup restore)
 *   so another device on the same Wi-Fi — phone, tablet or PC — can send a photo to the TV.
 * - **Clear**: removes the current background (panels return to solid).
 *
 * @param onPickLocal invoked when the user taps "From device" (the host opens the StorageBrowser).
 * @param onPickRemote invoked when the user taps "Remote" (the host opens [RemoteBackgroundDialog]).
 * @param onClear invoked when the user taps "Clear".
 * @param onDismiss invoked on Cancel / Back / outside.
 */
@Composable
fun BackgroundImageChooserDialog(
    hasImage: Boolean,
    onPickLocal: () -> Unit,
    onPickRemote: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onDismiss() }

    Box(
        Modifier.fillMaxSize()
            .modalScrim()
            .trapAllFocusExit()
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.dialogPanel(width = 560.dp, padding = 28.dp)) {
            Text(stringResource(R.string.setup_background_image), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.setup_background_image_description),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OwnTVButton(stringResource(R.string.common_cancel), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY)
                Spacer(Modifier.weight(1f))
                if (hasImage) {
                    OwnTVButton(stringResource(R.string.common_clear), onClick = onClear, style = OwnTVButtonStyle.SECONDARY)
                }
                OwnTVButton(stringResource(R.string.setup_from_phone), onClick = onPickRemote, style = OwnTVButtonStyle.SECONDARY)
                OwnTVButton(stringResource(R.string.setup_from_device), onClick = onPickLocal, modifier = Modifier.focusRequester(firstFocus))
            }
        }
    }
}

/**
 * Remote background upload: opens the LAN companion server in image-upload mode and shows the PIN,
 * a QR of the URL, and the URL text — the same flow as Remote backup restore. When an image arrives,
 * [onImageReceived] hands the saved cache file to the host (which ingests it as the background and
 * closes this dialog). The server stops automatically when the dialog leaves composition.
 */
@Composable
fun RemoteBackgroundDialog(
    state: tv.own.owntv.core.companion.CompanionServerState,
    images: kotlinx.coroutines.flow.Flow<File>,
    onStart: (port: Int) -> Unit,
    onStop: () -> Unit,
    onImageReceived: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onDismiss() }

    // Open a fresh image-upload session on entry; stop it when the dialog leaves.
    LaunchedEffect(Unit) { onStart(tv.own.owntv.core.companion.CompanionLink.DEFAULT_PORT) }
    LaunchedEffect(images) { images.collect(onImageReceived) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onStop() } }

    Box(
        Modifier.fillMaxSize()
            .modalScrim()
            .trapAllFocusExit()
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.dialogPanel(width = 560.dp, padding = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.setup_send_from_phone), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.setup_phone_background_description),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            when (state) {
                tv.own.owntv.core.companion.CompanionServerState.Idle,
                tv.own.owntv.core.companion.CompanionServerState.Starting,
                -> Text(stringResource(R.string.setup_opening_server), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                is tv.own.owntv.core.companion.CompanionServerState.Listening -> {
                    Text(stringResource(R.string.setup_enter_pin_browser), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.pin,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = colors.primary,
                        letterSpacing = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp),
                    )
                    Spacer(Modifier.height(12.dp))
                    state.qr?.let { qr ->
                        androidx.compose.foundation.Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = stringResource(R.string.common_qr_code_companion_url),
                            // White backing panel like the backup screens — a QR on a dark/glass panel may not scan.
                            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(stringResource(R.string.setup_open_url), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    state.urls.forEach { url ->
                        Text(url, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    }
                }
                is tv.own.owntv.core.companion.CompanionServerState.Failed -> {
                    Text(state.failure.displayText(), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFEF4444), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    OwnTVButton(stringResource(R.string.setup_try_again), onClick = { onStart(tv.own.owntv.core.companion.CompanionLink.DEFAULT_PORT) })
                }
                tv.own.owntv.core.companion.CompanionServerState.Locked -> {
                    Text(
                        companionLockedText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFEF4444),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    // Restarting mints a fresh PIN, so this is the recovery path — not a retry of a failure.
                    OwnTVButton(stringResource(R.string.setup_start_again_new_pin), onClick = { onStart(tv.own.owntv.core.companion.CompanionLink.DEFAULT_PORT) })
                }
            }
            Spacer(Modifier.height(20.dp))
            OwnTVButton(stringResource(R.string.common_cancel), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.focusRequester(firstFocus))
        }
    }
}

/**
 * Copy a user-picked image [source] File into app-private storage under
 * [dir]/`background_<timestamp>.<ext>`, returning the new absolute path. The copy is what gets
 * persisted — the original may live on a USB stick / removable folder that could vanish, so owning
 * our own copy guarantees the background survives.
 *
 * The timestamp makes every ingest a NEW path. A fixed `background.<ext>` name meant picking a
 * second image of the same type produced an identical path: the settings Flow deduplicates, so
 * nothing recomposed, and Coil keyed its cache on the path and served the old bitmap — the
 * background only changed after an app restart.
 */
fun ingestBackgroundImage(source: File, dir: File): String {
    if (!dir.exists()) dir.mkdirs()
    // Wipe any previous ingest so the folder never accumulates old backgrounds.
    dir.listFiles()?.forEach { runCatching { it.delete() } }
    val ext = source.extension.ifBlank { "png" }.lowercase()
    val dest = File(dir, "background_${System.currentTimeMillis()}.$ext")
    source.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
    return dest.absolutePath
}
