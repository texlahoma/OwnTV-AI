package tv.own.owntv.core.parser

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [parseXmltvTime] replaces two per-call `SimpleDateFormat` constructions in the
 * hottest loop of EPG import (~200,000 of them on a 100k-programme guide).
 *
 * The risk in this change is entirely timezone semantics: the no-offset form means **UTC**, and
 * getting that backwards shifts every programme in the guide by hours without failing anything.
 * So the old implementation is reproduced verbatim here as [legacyParseTime] and the new one is
 * asserted equal to it across the input table — a characterization test, per the audit's
 * instruction to capture current behaviour first.
 */
class XmltvTimeTest {

    /** The exact earlier implementation from `XmltvParser.parseTime`, kept as the oracle. */
    private fun legacyParseTime(raw: String?): Long {
        val t = raw?.trim()?.replace(" ", "") ?: return 0
        if (t.length < 14) return 0
        return try {
            if (t.length >= 15 && (t[14] == '+' || t[14] == '-')) {
                SimpleDateFormat("yyyyMMddHHmmssZ", Locale.US).parse(t)?.time ?: 0
            } else {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(t.take(14))?.time ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private val inputs = listOf(
        // Well-formed, both branches.
        "20260725143000",
        "20260725143000 +0000",
        "20260725143000 +0200",
        "20260725143000 -0500",
        "20260725143000 +0530",       // half-hour offset (India)
        "20260725143000 -0930",       // negative half-hour offset
        "20260725143000+0200",        // no space before the offset
        "  20260725143000 +0200  ",   // surrounding whitespace
        "2026 07 25 14 30 00 +0200",  // spaces throughout
        // Boundaries.
        "19700101000000",             // the epoch itself
        "19691231235959",             // just before the epoch (negative result)
        "20000229120000",             // leap day, century leap year
        "24000229120000",             // leap day, far future
        "21000228120000",             // 2100 is NOT a leap year
        "20261231235959",             // year end
        // Trailing junk after a valid timestamp — SimpleDateFormat.parse ignored it.
        "20260725143000Z",
        "20260725143000 +0200 CEST",
        // Malformed / rejected.
        null,
        "",
        "   ",
        "2026",
        "2026072514300",              // 13 chars
        "abcdefghijklmn",
        "2026o725143000",             // a letter mid-field
        "20260725143000+",            // sign with nothing behind it
        "20260725143000+02",          // truncated offset
        "20260725143000+02:00",       // colon form is not RFC822
    )

    @Test
    fun `matches the SimpleDateFormat implementation it replaces`() {
        for (input in inputs) {
            assertEquals(
                "parseXmltvTime disagreed with the legacy parser for ${input?.let { "\"$it\"" }}",
                legacyParseTime(input),
                parseXmltvTime(input),
            )
        }
    }

    /** Pinned absolute values, so a matching pair of bugs in both implementations can't hide. */
    @Test
    fun `absolute epoch values are correct`() {
        assertEquals(0L, parseXmltvTime("19700101000000"))
        // 2026-07-25T14:30:00Z
        assertEquals(1784989800000L, parseXmltvTime("20260725143000"))
        // Same instant expressed as 16:30 in UTC+2 — an offset is subtracted, not added.
        assertEquals(parseXmltvTime("20260725143000"), parseXmltvTime("20260725163000 +0200"))
        // …and as 09:30 in UTC-5.
        assertEquals(parseXmltvTime("20260725143000"), parseXmltvTime("20260725093000 -0500"))
    }

    /** The no-offset form is UTC regardless of where the device is — the whole-guide-shifts bug. */
    @Test
    fun `no-offset timestamps are UTC and ignore the default timezone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka")) // UTC+6, no DST
            assertEquals(1784989800000L, parseXmltvTime("20260725143000"))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(1784989800000L, parseXmltvTime("20260725143000"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `malformed input returns zero and never throws`() {
        for (bad in listOf(null, "", "   ", "2026", "abcdefghijklmn", "20260725143000+02")) {
            assertEquals("expected 0 for ${bad?.let { "\"$it\"" }}", 0L, parseXmltvTime(bad))
        }
    }

    /**
     * The reason for the rewrite: `SimpleDateFormat` is not thread-safe, so the old code could not
     * have been fixed by simply hoisting the formatters to fields. This runs the parser from eight
     * threads at once and requires every result to be right.
     */
    @Test
    fun `is safe under concurrent use`() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val expected = inputs.map { legacyParseTime(it) }
            val tasks = (1..threads).map {
                Callable { repeat(500) { assertEquals(expected, inputs.map { i -> parseXmltvTime(i) }) } }
            }
            pool.invokeAll(tasks).forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
