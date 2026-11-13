package tv.own.owntv.core.timeshift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** A segment address is resolved leniently: a signed token must not drop the segment. */
class SegmentUrlTest {

    @Test
    fun `a relative segment resolves against the playlist`() {
        assertEquals(
            "http://cdn.example/live/ch1/seg-42.ts",
            resolveSegmentUrl("http://cdn.example/live/ch1/index.m3u8", "seg-42.ts"),
        )
        assertEquals(
            "http://cdn.example/other/seg-42.ts",
            resolveSegmentUrl("http://cdn.example/live/ch1/index.m3u8", "/other/seg-42.ts"),
        )
        assertEquals(
            "https://edge.example/seg.ts",
            resolveSegmentUrl("http://cdn.example/live/index.m3u8", "https://edge.example/seg.ts"),
        )
    }

    /** `java.net.URI` threw on each of these, and the segment was skipped without a word. */
    @Test
    fun `characters signed tokens carry do not drop the segment`() {
        val base = "http://cdn.example/live/ch1/index.m3u8"
        assertNotNull(resolveSegmentUrl(base, "seg-42.ts?token=a|b"))
        assertNotNull(resolveSegmentUrl(base, "seg-42.ts?sig=x^y"))
        assertEquals("http://cdn.example/live/ch1/seg%2042.ts", resolveSegmentUrl(base, "seg 42.ts"))
    }
}
