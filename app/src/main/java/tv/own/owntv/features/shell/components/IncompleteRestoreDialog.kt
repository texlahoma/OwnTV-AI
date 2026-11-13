package tv.own.owntv.features.shell.components

import androidx.compose.runtime.Composable
import tv.own.owntv.R

/**
 * Shown at launch when a backup restore was interrupted. A restore spans the database and
 * several preference files, so an unlucky kill can leave it half-applied — a merge, so nothing was
 * deleted, but some sections may simply not be there. Rather than let that pass unnoticed, the app
 * says so and points at the fix: run the same restore again (it's idempotent — a re-run merges the
 * missing parts back in).
 *
 * [description] is the backup file name plus the sections that were being applied; never a secret.
 */
@Composable
fun IncompleteRestoreDialog(
    description: String,
    onDismiss: () -> Unit,
) {
    tv.own.owntv.ui.stage.StageNotice(
        title = androidx.compose.ui.res.stringResource(R.string.content_restore_incomplete_title),
        body = androidx.compose.ui.res.stringResource(R.string.content_restore_incomplete_message, description),
        onDismiss = onDismiss,
        eyebrow = null,
    )
}
