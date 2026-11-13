package tv.own.owntv.core.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

/** How often a screen that is showing finished items looks again whether their files are there. */
private const val RECHECK_MS = 5_000L

/**
 * Which of [paths] (stored `filePath`s of finished downloads or recordings) no longer exist — the
 * drive was formatted, swapped or unplugged, or the file was deleted outside the app. A row whose
 * file is gone must not say "Ready to watch". Checked on IO: a document is a provider query.
 *
 * Looked at again every [RECHECK_MS] while collected, not only when the list changes: a stick pulled
 * or formatted while the screen is open changes no row, and must still show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<List<String?>>.missingFiles(context: Context): Flow<Set<String>> = transformLatest { paths ->
    while (true) {
        emit(withContext(Dispatchers.IO) {
            paths.filterNotNull().filter { MediaTarget.of(context, it)?.exists() != true }.toSet()
        })
        delay(RECHECK_MS)
    }
}.distinctUntilChanged()
