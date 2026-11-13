package tv.own.owntv.core.stalker

/**
 * Pull a subscription end date out of a Stalker `account_info` / `get_profile` map.
 *
 * Portals are inconsistent: proper `end_date` / `exp_date` keys, or a date-looking string stuffed
 * into `phone`. Values like "0000-00-00", "null" or empty are ignored. Returned verbatim —
 * portals write the date in their own format and re-parsing it invents wrong dates.
 */
fun stalkerExpiryOf(fields: Map<String, String>): String? {
    val direct = EXPIRY_KEYS
        .firstNotNullOfOrNull { key -> fields[key]?.trim()?.takeIf { it.looksLikeExpiryValue() } }
    if (direct != null) return direct
    // Some portals put the expiry text in `phone` — accept it only when it actually contains a date.
    return fields["phone"]?.trim()
        ?.takeIf { it.looksLikeExpiryValue() && it.contains(Regex("\\d{4}|\\d{1,2}[./-]\\d{1,2}")) }
}

private val EXPIRY_KEYS =
    listOf("end_date", "exp_date", "expire_date", "expire_billing_date", "tariff_expired_date")

private fun String.looksLikeExpiryValue(): Boolean =
    isNotEmpty() && !equals("null", true) && !startsWith("0000") && this != "0"
