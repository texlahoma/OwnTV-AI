package tv.own.owntv.core.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * T2 — a merge-restore never trusts the ids in the backup file: profiles are matched by name and
 * sources by type+url+username, so every id the file carries has to be rewritten to the device's own
 * id before anything is written. Getting that wrong is silent and destructive — a restored profile's
 * favorites, folder customizations or TMDB name overrides end up attached to a DIFFERENT source or a
 * different person, with no error anywhere. None of it was covered.
 */
class BackupMergeRemappingTest {

    // --- match keys: which rows merge, and which duplicate ---

    @Test
    fun `profiles match on the trimmed, case-folded name`() {
        assertEquals(profileMatchKey("Kids"), profileMatchKey("  KIDS  "))
        assertEquals(profileMatchKey("Kids"), profileMatchKey("kids"))
        assertTrue(profileMatchKey("Kids") != profileMatchKey("Kids 2"))
    }

    @Test
    fun `sources match on type, url and username, but not on the password`() {
        val a = sourceMatchKey("XTREAM", "https://portal.test ", "user")
        val b = sourceMatchKey("XTREAM", "https://portal.test", "user")
        assertEquals("a rotated password must still merge onto the same source", a, b)
        // One portal, two accounts — must stay two sources.
        assertTrue(a != sourceMatchKey("XTREAM", "https://portal.test", "other"))
        // Same URL added twice as different source types is still two sources.
        assertTrue(a != sourceMatchKey("M3U", "https://portal.test", "user"))
        // A missing username is not the same as a blank one being ignored: both fold to "".
        assertEquals(
            sourceMatchKey("M3U", "https://list.test", null),
            sourceMatchKey("M3U", "https://list.test", ""),
        )
    }

    /**
     * #114 — a Stalker source has no username and several playlists routinely share one portal URL,
     * so type+url+username made every one of them the same source. On restore they all merged onto a
     * single device row, each overwriting its MAC, and the user was left with playlists that all
     * carried the same MAC and could not log in.
     */
    @Test
    fun `stalker sources on one portal are told apart by their MAC`() {
        val a = sourceMatchKey("STALKER", "http://portal.test", null, "00:1A:79:AA:BB:01")
        val b = sourceMatchKey("STALKER", "http://portal.test", null, "00:1A:79:AA:BB:02")
        assertTrue("two MACs on one portal are two playlists", a != b)
        // Punctuation and case are formatting, not identity — the same login must still merge.
        assertEquals(a, sourceMatchKey("STALKER", "http://portal.test ", null, "001a79aabb01"))
        // A backup written without a passphrase carries no MAC at all; that is its own key, and the
        // portal-only fallback in matchSourceForRestore is what stops it duplicating the playlist.
        assertTrue(a != sourceMatchKey("STALKER", "http://portal.test", null, null))
    }

    @Test
    fun `non-stalker keys ignore the mac argument entirely`() {
        assertEquals(
            sourceMatchKey("XTREAM", "https://portal.test", "user"),
            sourceMatchKey("XTREAM", "https://portal.test", "user", "00:1A:79:AA:BB:01"),
        )
    }

    // --- matchSourceForRestore: which device row an incoming source is allowed to claim ---

    private fun stalker(id: Long, mac: String?, url: String = "http://portal.test") =
        SourceEntity(id = id, name = "P$id", type = SourceType.STALKER, url = url, mac = mac)

    @Test
    fun `each stalker playlist claims its own device row instead of piling onto one`() {
        val device = mutableListOf(stalker(1, "00:1A:79:AA:BB:01"), stalker(2, "00:1A:79:AA:BB:02"))
        val first = matchSourceForRestore(device, stalker(9, "00:1A:79:AA:BB:02"))
        assertEquals(2L, first?.id)
        device.remove(first)
        assertEquals(1L, matchSourceForRestore(device, stalker(8, "00:1A:79:AA:BB:01"))?.id)
    }

    @Test
    fun `a new MAC on a known portal is a new playlist, not a merge`() {
        val device = listOf(stalker(1, "00:1A:79:AA:BB:01"))
        assertNull(matchSourceForRestore(device, stalker(9, "00:1A:79:AA:BB:99")))
    }

    @Test
    fun `a MAC missing on either side still merges on the portal rather than duplicating`() {
        val device = listOf(stalker(1, "00:1A:79:AA:BB:01"))
        // Passphrase-less backup: the file carries no MAC, so the device's own MAC is kept.
        assertEquals(1L, matchSourceForRestore(device, stalker(9, null))?.id)
        // The reverse — a device row left MAC-less by an earlier passphrase-less restore — adopts
        // the MAC this backup does carry instead of becoming a second playlist.
        assertEquals(
            1L,
            matchSourceForRestore(listOf(stalker(1, null)), stalker(9, "00:1A:79:AA:BB:01"))?.id,
        )
        // A different portal is never a fallback match.
        assertNull(matchSourceForRestore(device, stalker(9, null, url = "http://other.test")))
    }

    @Test
    fun `non-stalker sources merge on type, url and username with no fallback`() {
        val device = listOf(
            SourceEntity(id = 1, name = "X", type = SourceType.XTREAM, url = "https://p.test", username = "u", password = "old"),
        )
        val incoming = SourceEntity(id = 9, name = "X", type = SourceType.XTREAM, url = "https://p.test ", username = "u", password = "new")
        assertEquals("a rotated password still merges", 1L, matchSourceForRestore(device, incoming)?.id)
        assertNull(matchSourceForRestore(device, incoming.copy(username = "other")))
    }

    // --- remapKeys: profile-id-keyed and source-id-keyed settings maps ---

    @Test
    fun `remapKeys rewrites mapped ids and drops unmapped numeric keys`() {
        val file = JSONObject().put("1", "a").put("2", "b").put("9", "c")
        val out = remapKeys(file, mapOf(1L to 40L, 2L to 41L))

        assertEquals("a", out.getString("40"))
        assertEquals("b", out.getString("41"))
        // 9 had no match on this device — it must be DROPPED, never kept: the number 9 might be
        // held by an unrelated profile/source here, and downstream checks only test existence.
        assertNull(out.opt("9"))
        assertEquals(2, out.length())
    }

    @Test
    fun `remapKeys leaves non-numeric keys alone and drops every id when the map is empty`() {
        val file = JSONObject().put("theme", "dark").put("3", "x")
        val out = remapKeys(file, mapOf(3L to 7L))
        assertEquals("dark", out.getString("theme"))
        assertEquals("x", out.getString("7"))
        // No id survived the merge: every numeric key goes, scalars stay.
        val empty = remapKeys(JSONObject().put("theme", "dark").put("3", "x"), emptyMap())
        assertEquals("dark", empty.getString("theme"))
        assertEquals(1, empty.length())
    }

    @Test
    fun `remapKeys collapsing two file ids onto one device id keeps a single entry`() {
        // Two profiles in the file whose names both match the same device profile.
        val out = remapKeys(JSONObject().put("1", "first").put("2", "second"), mapOf(1L to 5L, 2L to 5L))
        assertEquals(1, out.length())
        assertNotNull(out.opt("5"))
    }

    // --- remapCustomizationValue: "<sourceId>:<rest>" content keys inside a customization blob ---

    @Test
    fun `customization content keys are rewritten in both array and object fields`() {
        val raw = JSONObject()
            .put("hidden", JSONArray(listOf("10:movie:99", "11:live:7")))
            .put("renamed", JSONObject().put("10:series:3", "My Show"))
            .put("sortMode", "NAME")
            .toString()

        val out = JSONObject(remapCustomizationValue(raw, mapOf(10L to 200L)))

        val hidden = out.getJSONArray("hidden")
        assertEquals(1, hidden.length())
        assertEquals("200:movie:99", hidden.getString(0))
        assertEquals("My Show", out.getJSONObject("renamed").getString("200:series:3"))
        assertNull(out.getJSONObject("renamed").opt("10:series:3"))
        assertEquals("NAME", out.getString("sortMode")) // scalar fields pass straight through
    }

    @Test
    fun `customization entries naming an absent source are dropped from arrays and maps`() {
        // Source 11 took no part in the restore. Keeping "11:live:7" would risk hiding/pinning items
        // of whatever unrelated source happens to be 11 on this device — the entry is removed.
        val raw = JSONObject()
            .put("hidden", JSONArray(listOf("10:movie:99", "11:live:7")))
            .put("renamed", JSONObject().put("10:series:3", "Mine").put("11:live:2", "Other"))
            .toString()

        val out = JSONObject(remapCustomizationValue(raw, mapOf(10L to 200L)))

        assertEquals(1, out.getJSONArray("hidden").length())
        assertEquals("200:movie:99", out.getJSONArray("hidden").getString(0))
        val renamed = out.getJSONObject("renamed")
        assertEquals("Mine", renamed.optString("200:series:3"))
        assertNull(renamed.opt("11:live:2"))
    }

    @Test
    fun `customization with no surviving sources keeps only custom and scalar fields`() {
        val raw = JSONObject()
            .put("hidden", JSONArray(listOf("10:movie:99", "custom:abc")))
            .put("sortMode", "NAME")
            .toString()

        val out = JSONObject(remapCustomizationValue(raw, emptyMap()))

        val hidden = out.getJSONArray("hidden")
        assertEquals(1, hidden.length())
        assertEquals("custom:abc", hidden.getString(0))
        assertEquals("NAME", out.getString("sortMode"))
    }

    @Test
    fun `a customization key whose prefix is not a source id is left alone`() {
        val raw = JSONObject().put("hidden", JSONArray(listOf("all:movie:1"))).toString()
        val out = JSONObject(remapCustomizationValue(raw, mapOf(10L to 200L)))
        assertEquals("all:movie:1", out.getJSONArray("hidden").getString(0))
    }

    @Test
    fun `custom category ids pass through unremapped and the object array survives`() {
        // Issue #87: a custom category's id is "custom:<uuid>" — no source id to remap, and the
        // customCats field is an array of OBJECTS, not strings. Before the fix the getString(i) on
        // an object threw and the WHOLE blob fell back to the raw (unremapped) value, so a profile
        // with a custom category silently lost every source-id remap on restore.
        val raw = JSONObject()
            .put("catOrder", JSONArray(listOf("custom:abc-123", "10:live:1")))
            .put("hiddenItems", JSONObject().put("10:live:7", "News"))
            .put(
                "customCats",
                JSONArray().apply { put(JSONObject().put("id", "custom:abc-123").put("name", "My Cat")) },
            )
            .toString()

        val out = JSONObject(remapCustomizationValue(raw, mapOf(10L to 200L)))

        // Custom ids in the keyed arrays pass through verbatim; source-keyed entries still remap.
        assertEquals("custom:abc-123", out.getJSONArray("catOrder").getString(0))
        assertEquals("200:live:1", out.getJSONArray("catOrder").getString(1))
        // The object array is copied verbatim (its ids can never be source ids).
        assertEquals("custom:abc-123", out.getJSONArray("customCats").getJSONObject(0).getString("id"))
        assertEquals("My Cat", out.getJSONArray("customCats").getJSONObject(0).getString("name"))
        // Ordinary content keys in the maps still remap — the blob did NOT fall back.
        assertEquals("News", out.getJSONObject("hiddenItems").getString("200:live:7"))
    }

    @Test
    fun `moved origin remaps both the item key and provider category value`() {
        val raw = JSONObject()
            .put("movedFrom", JSONObject().put("10:movie:99", "10:category:7"))
            .toString()

        val moved = JSONObject(remapCustomizationValue(raw, mapOf(10L to 200L)))
            .getJSONObject("movedFrom")

        assertEquals("200:category:7", moved.getString("200:movie:99"))
        assertNull(moved.opt("10:movie:99"))
    }

    @Test
    fun `user data context remaps provider folders, preserves custom and built-in, rejects absent sources`() {
        val ids = mapOf(10L to 200L)
        assertEquals("200:category:7", remapContentContextKey("10:category:7", ids))
        assertEquals("custom:abc-123", remapContentContextKey("custom:abc-123", ids))
        assertEquals("favorites", remapContentContextKey("favorites", ids))
        assertNull(remapContentContextKey("11:category:7", ids))
    }

    @Test
    fun `malformed customization json is returned verbatim rather than dropped`() {
        // Restore must degrade to "this setting stays as it was", never to an exception mid-import.
        assertEquals("not json at all", remapCustomizationValue("not json at all", mapOf(1L to 2L)))
        assertEquals("{\"a\":1}", remapCustomizationValue("{\"a\":1}", emptyMap()))
    }

    // --- remapTmdbOverrideKeys: "type:<sourceId>:<rest>" ---

    @Test
    fun `tmdb override keys rewrite only the middle source-id segment`() {
        val raw = JSONObject()
            .put("movie:10:42", "Blade Runner")
            .put("series:10:some:id:with:colons", "The Show")
            .put("movie:11:1", "Unmapped Source")
            .toString()

        val out = JSONObject(remapTmdbOverrideKeys(raw, mapOf(10L to 200L)))

        assertEquals("Blade Runner", out.getString("movie:200:42"))
        // split(limit = 3): everything after the source id is one opaque remainder, colons and all.
        assertEquals("The Show", out.getString("series:200:some:id:with:colons"))
        // Source 11 did not survive the merge: the rename is dropped, never applied to whatever
        // unrelated source holds id 11 on this device.
        assertNull(out.opt("movie:11:1"))
    }

    @Test
    fun `tmdb override keys without three segments are passed through`() {
        val raw = JSONObject().put("movie:10", "Two segments").toString()
        val out = JSONObject(remapTmdbOverrideKeys(raw, mapOf(10L to 200L)))
        assertEquals("Two segments", out.getString("movie:10"))
    }

    @Test
    fun `tmdb overrides naming only absent sources come out empty`() {
        val raw = JSONObject().put("movie:11:1", "Nope").put("series:12:2", "Also nope").toString()
        val out = JSONObject(remapTmdbOverrideKeys(raw, mapOf(10L to 200L)))
        assertEquals(0, out.length())
    }

    @Test
    fun `malformed tmdb override json is returned verbatim`() {
        assertEquals("{oops", remapTmdbOverrideKeys("{oops", mapOf(1L to 2L)))
    }

    // --- remapUserData: favorites/history/progress/order records (and their tombstones) ---

    private fun rec(kind: String, p: Long, src: Long, ctx: String? = null) =
        JSONObject().put("kind", kind).put("p", p).put("src", src).put("t", "MOVIE").also {
            if (ctx != null) it.put("ctx", ctx)
        }

    @Test
    fun `a record whose profile has no device match is dropped, not passed through with the file id`() {
        // The bug: profile ids are per-device autoincrements. File id 3 (a parent on the old device)
        // and device id 3 (a Kids profile here) are different people; name matching found nothing, so
        // the old code wrote the record out with p=3 whenever SOURCES was not ticked, and the
        // restored favorite/history silently landed on the Kids profile.
        val arr = JSONArray().put(rec("fav", p = 3, src = 10))
        val kinds = setOf("fav", "his", "prog", "order", "member", "sort")

        val out = remapUserData(arr, kinds, profileIdMap = emptyMap(), sourceIdMap = emptyMap())

        assertEquals("the record has no home on this device — it must be skipped", 0, out.length())
    }

    @Test
    fun `dropping unmapped profiles does not depend on whether sources are restored`() {
        // The old branch was `?: if (applySources) continue else fileP` — the dangerous pass-through
        // happened exactly on a merge restore (profiles+favorites ticked, SOURCES unticked). The
        // decision now depends solely on whether a profile mapping exists.
        val arr = JSONArray().put(rec("his", p = 9, src = 99))
        val kinds = setOf("fav", "his")
        val withSourcesMapped = mapOf(99L to 200L)

        val out = remapUserData(arr, kinds, profileIdMap = emptyMap(), sourceIdMap = withSourcesMapped)

        assertEquals("even a resolvable source cannot rescue an unresolvable profile", 0, out.length())
    }

    @Test
    fun `mapped records get their profile, source and context ids rewritten`() {
        val arr = JSONArray()
            .put(rec("fav", p = 1, src = 10))
            .put(rec("order", p = 2, src = 11, ctx = "10:category:7"))
        val kinds = setOf("fav", "order")

        val out = remapUserData(
            arr,
            kinds,
            profileIdMap = mapOf(1L to 40L, 2L to 41L),
            sourceIdMap = mapOf(10L to 200L, 11L to 201L),
        )

        assertEquals(2, out.length())
        assertEquals(40L, out.getJSONObject(0).getLong("p"))
        assertEquals(200L, out.getJSONObject(0).getLong("src"))
        assertEquals(41L, out.getJSONObject(1).getLong("p"))
        assertEquals(201L, out.getJSONObject(1).getLong("src"))
        assertEquals("200:category:7", out.getJSONObject(1).getString("ctx"))
    }

    @Test
    fun `unmapped profiles are dropped while mapped ones survive in the same batch`() {
        // File profile 1 matches a device profile by name; file profile 2 does not exist here at
        // all, but happens to share its number with an unrelated device profile. Only the first may
        // come through — tombstones are filtered by the exact same function.
        val arr = JSONArray()
            .put(rec("fav", p = 1, src = 10))
            .put(rec("fav", p = 2, src = 10))
        val kinds = setOf("fav")

        val out = remapUserData(arr, kinds, mapOf(1L to 77L), mapOf(10L to 200L))

        assertEquals(1, out.length())
        assertEquals(77L, out.getJSONObject(0).getLong("p"))
    }

    @Test
    fun `a record whose source has no device match is dropped, not pointed at an unrelated source`() {
        // Profile resolves, but the playlist does not exist here. Keeping the file src id could
        // attach the favorite to content of whatever unrelated device source holds that number.
        val arr = JSONArray().put(rec("fav", p = 1, src = 99))
        val kinds = setOf("fav")

        val out = remapUserData(arr, kinds, mapOf(1L to 40L), emptyMap())

        assertEquals(0, out.length())
    }

    @Test
    fun `an order record whose folder context names an absent source is dropped`() {
        val arr = JSONArray().put(rec("order", p = 1, src = 10, ctx = "11:category:7"))
        val kinds = setOf("order")

        val out = remapUserData(arr, kinds, mapOf(1L to 40L), mapOf(10L to 200L))

        assertEquals("the item's source survived, but the provider folder belongs to source 11 — gone", 0, out.length())
    }

    @Test
    fun `a custom-category context survives when its source is mapped`() {
        val arr = JSONArray().put(rec("member", p = 1, src = 10, ctx = "custom:abc"))
        val out = remapUserData(arr, setOf("member"), mapOf(1L to 40L), mapOf(10L to 200L))
        assertEquals(1, out.length())
        assertEquals("custom:abc", out.getJSONObject(0).getString("ctx"))
    }

    @Test
    fun `records outside the selected kinds, malformed rows and records without a profile are skipped`() {
        val arr = JSONArray()
            .put(rec("his", p = 1, src = 10)) // kind not selected
            .put(JSONObject().put("kind", "fav")) // missing p/src
            .put("not-an-object")
            .put(rec("fav", p = 1, src = 10))
        val kinds = setOf("fav")

        val out = remapUserData(arr, kinds, mapOf(1L to 77L), mapOf(10L to 200L))

        assertEquals(1, out.length())
        assertEquals(77L, out.getJSONObject(0).getLong("p"))
    }

    // --- filterByProfile: the "only the ticked profiles" gate ---

    @Test
    fun `filterByProfile keeps only the selected profile ids`() {
        val map = JSONObject().put("1", "a").put("2", "b").put("3", "c")
        val out = filterByProfile(map, setOf("1", "3"))
        assertEquals(2, out.length())
        assertEquals("a", out.getString("1"))
        assertEquals("c", out.getString("3"))
        assertNull(out.opt("2"))
    }

    @Test
    fun `filterByProfile with no selection keeps nothing`() {
        assertEquals(0, filterByProfile(JSONObject().put("1", "a"), emptySet()).length())
    }

    /**
     * The order the import applies these in matters: keys are remapped to device ids FIRST, then
     * filtered against the device's profile ids. Filtering first would test file ids against device
     * ids and throw away everything whenever the two devices number their profiles differently.
     */
    @Test
    fun `remap then filter keeps the restored profile, filter then remap loses it`() {
        val file = JSONObject().put("1", "kids-config")
        val profileIdMap = mapOf(1L to 77L)
        val deviceProfileIds = setOf("77")

        val correct = filterByProfile(remapKeys(file, profileIdMap), deviceProfileIds)
        assertEquals("kids-config", correct.getString("77"))

        val wrongOrder = remapKeys(filterByProfile(file, deviceProfileIds), profileIdMap)
        assertEquals(0, wrongOrder.length())
    }
}
