package tv.own.owntv.core.customize

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One preview line in the bulk-rename review. */
data class BulkPreviewRow(
    val key: String,
    val oldName: String,
    val newName: String,
    /** No rule changed this name — listed but not applicable. */
    val unchanged: Boolean,
    /** The rules would have blanked this name — rejected, original kept. */
    val blankRejected: Boolean,
    /** New name collides with another row's new name or an existing name in the section (warn only). */
    val duplicate: Boolean,
)

/** How many rows one bulk rename may carry; above that the flow refuses. */
const val BULK_RENAME_MAX_ROWS = 2000

/**
 * One-shot bulk rename flow: choice popup → rule builder / auto cleanup → review → apply.
 * The flow state (rules, options, preview) lives here — ViewModel state only, deliberately NOT
 * persisted: every session starts from scratch. Nothing is written until the review's Done, and
 * every accepted row lands in ONE [persist] call.
 */
class BulkRenameSession(
    private val scope: CoroutineScope,
    private val persist: suspend (Map<String, String>) -> Unit,
    private val restore: suspend (Set<String>) -> Unit,
    private val existingNames: suspend (selectedKeys: Set<String>) -> Set<String>,
) {
    enum class Screen { NONE, CHOICE, BUILDER, REVIEW, RESTORE_CONFIRM, REFUSED }

    private val _screen = MutableStateFlow(Screen.NONE)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** The rows being renamed: stable key → provider original name. */
    private val _entries = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val entries: StateFlow<List<Pair<String, String>>> = _entries.asStateFlow()

    private val _rules = MutableStateFlow<List<RenameRules.Rule>>(emptyList())
    val rules: StateFlow<List<RenameRules.Rule>> = _rules.asStateFlow()

    private val _options = MutableStateFlow(RenameRules.Options())
    val options: StateFlow<RenameRules.Options> = _options.asStateFlow()

    private val _preview = MutableStateFlow<List<BulkPreviewRow>>(emptyList())
    val preview: StateFlow<List<BulkPreviewRow>> = _preview.asStateFlow()

    /** Accepted rows (key → new name); written to the store in one call by [done]. */
    private val _accepted = MutableStateFlow<Map<String, String>>(emptyMap())
    val accepted: StateFlow<Map<String, String>> = _accepted.asStateFlow()

    fun start(entries: List<Pair<String, String>>) {
        if (entries.size > BULK_RENAME_MAX_ROWS) {
            _screen.value = Screen.REFUSED
            return
        }
        _entries.value = entries
        _rules.value = emptyList()
        _options.value = RenameRules.Options()
        _preview.value = emptyList()
        _accepted.value = emptyMap()
        _screen.value = Screen.CHOICE
    }

    fun close() { _screen.value = Screen.NONE }

    // --- choice popup ---
    fun openBuilder() { _screen.value = Screen.BUILDER }
    fun autoCleanup() { _rules.value = RenameRules.autoCleanupRules(); computePreview() }
    fun requestRestore() { _screen.value = Screen.RESTORE_CONFIRM }
    fun confirmRestore() {
        scope.launch {
            restore(_entries.value.map { it.first }.toSet())
            close()
        }
    }

    // --- rule builder ---
    fun submitRules(rules: List<RenameRules.Rule>, options: RenameRules.Options) {
        _rules.value = rules
        _options.value = options
        computePreview()
    }
    fun backToChoice() { _screen.value = Screen.CHOICE }

    // --- review ---
    /** Applies the given pending rows (unchanged rows are never applicable) and removes them. */
    fun applyRows(keys: Set<String>) {
        val pending = _preview.value
        val next = _accepted.value.toMutableMap()
        pending.filter { it.key in keys && !it.unchanged }.forEach { next[it.key] = it.newName }
        _accepted.value = next
        _preview.value = pending.filterNot { it.key in keys }
    }
    fun applyAll() = applyRows(_preview.value.filter { !it.unchanged }.map { it.key }.toSet())
    fun declineRows(keys: Set<String>) { _preview.value = _preview.value.filterNot { it.key in keys } }
    fun declineAll() { _preview.value = emptyList() }
    fun editRules() { _screen.value = Screen.BUILDER }

    /** Writes every accepted row in ONE store transaction, then closes the flow. */
    fun done() {
        scope.launch {
            if (_accepted.value.isNotEmpty()) persist(_accepted.value)
            close()
        }
    }

    fun dismissRefused() { close() }

    /** Computes the preview on Dispatchers.Default — never the main thread. */
    private fun computePreview() {
        scope.launch(Dispatchers.Default) {
            // Rows the user already applied stay applied when they choose "Edit rules". Re-running
            // them through a new rule set would make them reappear while their old accepted value
            // still remained queued for Done, which is both misleading and impossible to decline.
            val entries = _entries.value.filterNot { (key, _) -> key in _accepted.value }
            val rules = _rules.value
            val options = _options.value
            // Exclude the selected rows' own stored overrides. Otherwise re-applying the same
            // proposed name to an already-renamed row incorrectly warns that it collides with
            // itself; only names belonging to other rows are genuine existing-name collisions.
            // Exclude every row in this session from the external-name set. Rows accepted before
            // "Edit rules" contribute their accepted final names below; pending rows contribute
            // either a new-name batch candidate or (when unchanged) their original name. Counting
            // selected originals unconditionally produced false duplicate warnings for names that
            // the same batch was replacing.
            val existingOutsideSession = existingNames(_entries.value.map { it.first }.toSet())
            val rows = entries.map { (key, old) ->
                val raw = RenameRules.applyRaw(old, rules, options)
                when {
                    raw.isBlank() -> BulkPreviewRow(key, old, "", unchanged = true, blankRejected = true, duplicate = false)
                    raw == old -> BulkPreviewRow(key, old, old, unchanged = true, blankRejected = false, duplicate = false)
                    else -> BulkPreviewRow(key, old, raw, unchanged = false, blankRejected = false, duplicate = false)
                }
            }
            // Duplicates warn, never block: a new name that repeats inside the batch or already
            // exists in the section (a provider original or another rename).
            val newNameCounts = rows.filter { !it.unchanged }.groupingBy { it.newName }.eachCount()
            val occupiedNames = existingOutsideSession + _accepted.value.values +
                rows.filter { it.unchanged }.map { it.oldName }
            val batchDupKeys = rows
                .filter { !it.unchanged && (newNameCounts[it.newName] ?: 0) > 1 }
                .map { it.key }
                .toSet()
            _preview.value = rows.map { r ->
                if (r.duplicate) r
                else if (r.key in batchDupKeys || (!r.unchanged && r.newName in occupiedNames)) r.copy(duplicate = true)
                else r
            }
            _screen.value = Screen.REVIEW
        }
    }
}
