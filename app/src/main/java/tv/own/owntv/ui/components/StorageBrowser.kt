package tv.own.owntv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.core.storage.StorageAccess
import java.io.File

enum class BrowseMode { FOLDER, FILE }

/** One directory's contents, already split and sorted off the main thread. */
private data class Listing(val folders: List<File>, val files: List<File>) {
    companion object { val EMPTY = Listing(emptyList(), emptyList()) }
}

/**
 * An in-app file/folder picker (the TV-safe replacement for SAF). In [BrowseMode.FOLDER] the user
 * navigates and taps "Use this folder"; in [BrowseMode.FILE] tapping a matching file picks it. Grabs
 * focus on open and after each navigation so the remote lands on the list immediately.
 */
@Composable
fun StorageBrowser(
    title: String,
    mode: BrowseMode,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
    fileExtensions: Set<String>? = null,
) {
    // Hosted in a real window: D-pad focus physically cannot escape to the screen behind. An
    // inline overlay loses focus containment when rows are added/removed (the grant-access row
    // after returning from system settings) and Compose reassigns focus outside the trap.
    // "New folder" is opened beside this popup, never inside it: a popup nested in another inherits the
    // first one's already-applied popup theme, so it skipped the user's popup font and size settings.
    var createIn by remember { mutableStateOf<((String) -> Unit)?>(null) }
    // Back is the browser's own (it climbs a folder first), so the popup does not close on it.
    tv.own.owntv.ui.stage.StagePopup(onDismiss = onDismiss, title = null, eyebrow = null, width = 900.mpx, scroll = false, dismissOnBackPress = false) {
        StorageBrowserContent(title, mode, onPick, onDismiss, fileExtensions, onNewFolder = { createIn = it })
    }
    createIn?.let { create ->
        NewFolderDialog(
            onCreate = { name -> create(name); createIn = null },
            onDismiss = { createIn = null },
        )
    }
}

@Composable
private fun StorageBrowserContent(
    title: String,
    mode: BrowseMode,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
    fileExtensions: Set<String>?,
    /** Ask for a folder name; the lambda creates it in the folder shown now. */
    onNewFolder: (onCreate: (String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val roots = remember { StorageAccess.storageRoots(context) }
    var current by remember { mutableStateOf<File?>(null) } // null = the roots list
    var hasAccess by remember { mutableStateOf(StorageAccess.hasStorageAccess(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    // Re-check on resume — the settings screen returns no activity result.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasAccess = StorageAccess.hasStorageAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler { if (current != null) current = current?.parentFile else onDismiss() }

    // Re-grab focus whenever the listing changes (open / navigate / refresh). Deferred a beat: the
    // clicked row is removed in the same recompose, and its focus teardown lands AFTER an immediate
    // request — which would leave focus on whatever sits behind the overlay.
    LaunchedEffect(current, hasAccess, refresh) {
        kotlinx.coroutines.delay(120)
        runCatching { firstFocus.requestFocus() }
    }


        // scroll = false: the listing below is a height-capped LazyColumn, which cannot nest inside
        // the popup's own vertical scroll.
    Column(Modifier.fillMaxWidth()) {
            Text(title, style = tv.own.owntv.ui.theme.stageText(34, 800), color = tv.own.owntv.ui.theme.StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(current?.absolutePath ?: stringResource(R.string.setup_pick_location), style = tv.own.owntv.ui.theme.stageText(16, 500), color = tv.own.owntv.ui.theme.StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.mpx, bottom = 18.mpx))

            val dir = current
            // listFiles() plus the per-child isDirectory/isFile stats are disk work, and a
            // `remember` block still runs it on the main thread during composition: a USB drive with
            // a large folder stalled the frame. Load it on IO instead; the ".." / roots rows render
            // immediately either way, so D-pad focus still lands the moment the dialog opens.
            val listing by produceState(Listing.EMPTY, dir, refresh, mode, fileExtensions) {
                value = Listing.EMPTY
                value = withContext(Dispatchers.IO) {
                    val children = runCatching { dir?.listFiles()?.toList() }.getOrNull().orEmpty()
                    Listing(
                        folders = children.filter { it.isDirectory }.sortedBy { it.name.lowercase() },
                        files = if (mode == BrowseMode.FILE) {
                            children.filter { it.isFile && (fileExtensions == null || it.extension.lowercase() in fileExtensions) }
                                .sortedBy { it.name.lowercase() }
                        } else emptyList(),
                    )
                }
            }
            val folders = listing.folders
            val files = listing.files

            // Above the list, not below it. Below, the only way down to it was through every folder
            // in the listing — about thirty presses in a full directory, for the one action the user
            // opened a folder picker to perform. Above, it is a single Up from the first row no
            // matter how long the listing is, and the list still opens focused so navigating first
            // costs nothing.
            if (mode == BrowseMode.FOLDER && current != null) {
                tv.own.owntv.ui.stage.StageButton(stringResource(R.string.setup_use_folder), onClick = { current?.let(onPick) }, icon = OwnTVIcon.CHECK, height = 56.mpx, textSize = 19, tinted = true, modifier = Modifier.fillMaxWidth().padding(bottom = 10.mpx))
            }

            // Cap the list to the screen (minus dialog chrome) so the footer buttons stay reachable.
            LazyColumn(Modifier.heightIn(max = 520.mpx).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.mpx)) {
                if (dir == null) {
                    if (!hasAccess) {
                        item {
                            BrowserRow(OwnTVIcon.SETTINGS, stringResource(R.string.setup_grant_storage_access), Modifier.focusRequester(firstFocus)) {
                                StorageAccess.openStoragePermissionSettings(context)
                            }
                        }
                    }
                    itemsIndexed(roots) { i, root ->
                        val m = if (i == 0 && hasAccess) Modifier.focusRequester(firstFocus) else Modifier
                        BrowserRow(OwnTVIcon.DOWNLOADS, root.displayLabel(), m) { current = root.file }
                    }
                } else {
                    item { BrowserRow(OwnTVIcon.BACK, stringResource(R.string.setup_from_current_folder), Modifier.focusRequester(firstFocus)) { current = dir.parentFile } }
                    itemsIndexed(folders) { _, f -> BrowserRow(OwnTVIcon.DOWNLOADS, f.name) { current = f } }
                    itemsIndexed(files) { _, f -> BrowserRow(OwnTVIcon.PLAYLIST, f.name) { onPick(f) } }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 22.mpx), horizontalArrangement = Arrangement.spacedBy(14.mpx, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (current != null) tv.own.owntv.ui.stage.StageButton(stringResource(R.string.setup_new_folder), onClick = {
                    onNewFolder { name ->
                        current?.let { runCatching { File(it, StorageAccess.sanitize(name)).mkdirs() } }
                        refresh++
                    }
                }, icon = OwnTVIcon.ADD, height = 56.mpx, textSize = 19)
                tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_cancel), onClick = onDismiss, height = 56.mpx, textSize = 19)
            }
        }
}

@Composable
private fun NewFolderDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    // A popup window of its own: drawn in the browser's layer, the browser's focus trap kept the cursor
    // behind this dialog and the name could never be typed. Deferred a beat so the window is attached.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { focus.requestFocus() }
    }
    tv.own.owntv.ui.stage.StagePopup(onDismiss = onDismiss, title = null, eyebrow = null, width = 756.mpx) {
        Text(stringResource(R.string.setup_new_folder), style = tv.own.owntv.ui.theme.stageText(38, 800), color = tv.own.owntv.ui.theme.StageColors.Text, modifier = Modifier.padding(bottom = 22.mpx))
        tv.own.owntv.features.settings.StageFieldRow(
            icon = tv.own.owntv.ui.components.OwnTVIcon.PENCIL,
            label = stringResource(R.string.setup_folder_name),
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = stringResource(R.string.setup_folder_example),
        )
        Row(Modifier.fillMaxWidth().padding(top = 24.mpx), horizontalArrangement = Arrangement.spacedBy(14.mpx, Alignment.End)) {
            tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_cancel), onClick = onDismiss, height = 56.mpx, textSize = 19)
            tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_create), onClick = { if (name.isNotBlank()) onCreate(name) }, height = 56.mpx, textSize = 19, tinted = true)
        }
    }
}

@Composable
private fun StorageAccess.StorageRoot.displayLabel(): String = when (kind) {
    StorageAccess.RootKind.INTERNAL -> stringResource(R.string.content_storage_internal)
    StorageAccess.RootKind.REMOVABLE -> volumeName ?: stringResource(R.string.content_storage_removable)
    StorageAccess.RootKind.APP -> stringResource(R.string.content_storage_app)
}

@Composable
private fun BrowserRow(icon: OwnTVIcon, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    tv.own.owntv.ui.stage.StagePopupOption(
        title = label, onClick = onClick, modifier = modifier,
        leading = { tv.own.owntv.ui.stage.StagePopupIcon(icon) },
    )
}
