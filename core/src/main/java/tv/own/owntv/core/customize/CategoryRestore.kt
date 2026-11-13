package tv.own.owntv.core.customize

import android.content.res.Resources
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.R
import tv.own.owntv.core.database.dao.ContentOrderDao
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.model.MediaType

/**
 * What "Restore to playlist default" would undo in one category, so the menu can offer it only when
 * it has something to do and the confirmation can say exactly what it found.
 *
 *  - [hidden]: items that belong to the category and were hidden (stable key → label). Hiding is
 *    global, so they show again everywhere, not just here;
 *  - [movedOut]: items moved out of this provider folder into a custom category (movedFromOrigin);
 *  - [hasOrder]: a manual order saved for this category (content_order).
 */
data class CategoryRestorePlan(
    val hidden: Map<String, String> = emptyMap(),
    val movedOut: Set<String> = emptySet(),
    val hasOrder: Boolean = false,
) {
    val isEmpty: Boolean get() = hidden.isEmpty() && movedOut.isEmpty() && !hasOrder
}

/**
 * Works out the plan for [contextKey]. [memberKeys] lists the customization keys of the items that
 * natively belong to the category (provider folder rows, or a custom category's members); it is only
 * called when something is hidden, so a profile that hides nothing never pays for it.
 *
 * A provider folder has an order to clear whenever one is saved. A combined category always has an
 * order (the one its items were added in), so a saved order only counts when it differs from that.
 */
suspend fun planCategoryRestore(
    customize: CustomizationStore,
    contentOrderDao: ContentOrderDao,
    profileId: Long,
    type: MediaType,
    contextKey: String,
    customCategoryDao: CustomCategoryDao,
    memberKeys: suspend () -> Set<String>,
): CategoryRestorePlan {
    val cust = customize.observe(profileId, type).first()
    val hidden = if (cust.hiddenItems.isEmpty()) emptyMap() else {
        val members = memberKeys()
        cust.hiddenItems.filterKeys { it in members }
    }
    return CategoryRestorePlan(
        hidden = hidden,
        movedOut = cust.movedFromOrigin.filterValues { it == contextKey }.keys,
        hasOrder = contextKey in contentOrderDao.observeContextKeys(profileId, type).first() &&
            (!CustomizeKeys.isCustom(contextKey) || combinedOrderDiffers(contentOrderDao, customCategoryDao, profileId, type, contextKey)),
    )
}

/** Whether the saved order of combined category [contextKey] differs from the order its items were
 *  added in — the order it falls back to when the saved one is cleared. */
private suspend fun combinedOrderDiffers(
    contentOrderDao: ContentOrderDao,
    customCategoryDao: CustomCategoryDao,
    profileId: Long,
    type: MediaType,
    contextKey: String,
): Boolean {
    val added = customCategoryDao.getAllOnce()
        .filter { it.profileId == profileId && it.mediaType == type && it.contextKey == contextKey }
        .sortedBy { it.position }
        .map { it.itemId }
    val saved = contentOrderDao.getAllOnce()
        .filter { it.profileId == profileId && it.mediaType == type && it.contextKey == contextKey }
        .associate { it.itemId to it.position }
    return savedOrderDiffers(added, saved)
}

/** Items with a saved position come first, in that order; the rest follow in the order added. */
internal fun savedOrderDiffers(added: List<Long>, saved: Map<Long, Int>): Boolean {
    val shown = added.filter { it in saved }.sortedBy { saved[it] } + added.filter { it !in saved }
    return shown != added
}

/**
 * Undoes what [planCategoryRestore] found, using the records those edits wrote — nothing new is
 * stored. Items moved out stay in the custom category they were moved to; renames and the category's
 * own place in the rail are left alone.
 */
suspend fun restoreCategoryToDefault(
    customize: CustomizationStore,
    contentOrderDao: ContentOrderDao,
    profileId: Long,
    type: MediaType,
    contextKey: String,
    plan: CategoryRestorePlan,
) {
    if (plan.hasOrder) contentOrderDao.clearContext(profileId, type, contextKey)
    if (plan.hidden.isEmpty() && plan.movedOut.isEmpty()) return
    customize.update(profileId, type) { c ->
        c.copy(
            hiddenItems = c.hiddenItems - plan.hidden.keys,
            movedFromOrigin = c.movedFromOrigin - plan.movedOut,
        )
    }
}

/** The confirmation lines for [plan]: one per thing the restore will do (empty when there is nothing to undo). */
fun categoryRestoreSummary(plan: CategoryRestorePlan, resources: Resources): List<String> = buildList {
    if (plan.hidden.isNotEmpty()) {
        add(resources.getQuantityString(R.plurals.content_category_restore_hidden, plan.hidden.size, plan.hidden.size))
    }
    if (plan.movedOut.isNotEmpty()) {
        add(resources.getQuantityString(R.plurals.content_category_restore_moved, plan.movedOut.size, plan.movedOut.size))
    }
    if (plan.hasOrder) add(resources.getString(R.string.content_category_restore_order))
}
