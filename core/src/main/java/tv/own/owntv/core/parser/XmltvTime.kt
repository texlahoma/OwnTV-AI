package tv.own.owntv.core.parser

/**
 * XMLTV timestamp parsing, extracted from `XmltvParser` so it can be tested directly.
 *
 * The previous implementation built a fresh `SimpleDateFormat` — pattern compilation, a `Calendar`,
 * locale resolution — on **every call**, plus a `TimeZone.getTimeZone("UTC")` lookup on the common
 * branch. Every programme carries a start and a stop, so a 100,000-programme guide (the tracked
 * ceiling) paid ~200,000 formatter constructions in the hottest loop of EPG import, on exactly the
 * hardware least able to afford it.
 *
 * XMLTV times are fixed-width, so the digits are read positionally and converted arithmetically
 * instead. No allocation, no formatter, no shared mutable state — which also makes this safe to call
 * from multiple threads, something `SimpleDateFormat` never was.
 */

/**
 * Parses `yyyyMMddHHmmss`, optionally followed by a `±HHMM` offset, to epoch millis.
 *
 * Contract preserved exactly from the `SimpleDateFormat` version it replaces:
 * - null, blank, or shorter than 14 characters → `0`
 * - non-digit / malformed input → `0`, never throws
 * - spaces are stripped anywhere in the string (`20260725143000 +0200` is one timestamp)
 * - **no offset means UTC**, an offset is honoured as stated — getting this backwards would shift
 *   every programme in the guide by hours
 * - trailing junk after a valid timestamp is ignored, as `SimpleDateFormat.parse` did
 * - out-of-range components roll over (month 13 → January of the next year), matching the lenient
 *   parsing the old formatters used
 */
fun parseXmltvTime(raw: String?): Long {
    val t = raw?.trim()?.replace(" ", "") ?: return 0
    if (t.length < 14) return 0

    val year = t.digitsAt(0, 4) ?: return 0
    val month = t.digitsAt(4, 2) ?: return 0
    val day = t.digitsAt(6, 2) ?: return 0
    val hour = t.digitsAt(8, 2) ?: return 0
    val minute = t.digitsAt(10, 2) ?: return 0
    val second = t.digitsAt(12, 2) ?: return 0

    var offsetSecs = 0L
    if (t.length >= 15 && (t[14] == '+' || t[14] == '-')) {
        // A sign with a malformed offset behind it was a parse failure before, and stays one.
        if (t.length < 19) return 0
        val offHours = t.digitsAt(15, 2) ?: return 0
        val offMinutes = t.digitsAt(17, 2) ?: return 0
        offsetSecs = (offHours * 3600L + offMinutes * 60L) * (if (t[14] == '-') -1 else 1)
    }

    // Lenient month rollover, as SimpleDateFormat did: month 13 of 2026 is month 1 of 2027.
    val monthIndex = month - 1
    val civilYear = year + Math.floorDiv(monthIndex, 12)
    val civilMonth = Math.floorMod(monthIndex, 12) + 1
    // Day/hour/minute/second overflow rolls over naturally: they are added, not validated.
    val days = daysFromCivil(civilYear, civilMonth) + (day - 1)
    val secondsOfDay = hour * 3600L + minute * 60L + second
    // An offset states how far local time is ahead of UTC, so UTC is local minus the offset.
    return (days * 86_400L + secondsOfDay - offsetSecs) * 1000L
}

/** Reads [length] digits at [start] as a non-negative Int, or null if any character isn't a digit. */
private fun String.digitsAt(start: Int, length: Int): Int? {
    if (start + length > this.length) return null
    var value = 0
    for (i in start until start + length) {
        val c = this[i]
        if (c < '0' || c > '9') return null
        value = value * 10 + (c - '0')
    }
    return value
}

/**
 * Days from 1970-01-01 to the first of the given month (Howard Hinnant's days-from-civil, the
 * standard branch-free proleptic Gregorian conversion).
 */
private fun daysFromCivil(year: Int, month: Int): Long {
    val y = year - if (month <= 2) 1 else 0
    val era = Math.floorDiv(y, 400)
    val yearOfEra = y - era * 400                                        // [0, 399]
    val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 // day 1 of the month
    val dayOfEra = yearOfEra * 365L + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}
