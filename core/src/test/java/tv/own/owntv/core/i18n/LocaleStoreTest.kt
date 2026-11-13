package tv.own.owntv.core.i18n

import android.content.SharedPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * LocaleStore unit tests (docs/internationalization.md, "Unit tests → LocaleStore").
 *
 * Runs on the plain JVM with a tiny in-memory [SharedPreferences] fake — the real store is just a
 * typed view over SharedPreferences, so no Robolectric is needed. The durability-after-restart test
 * models "process death" by constructing a fresh LocaleStore over the same fake prefs (the same way a
 * new process reopens the same SharedPreferences file).
 */
class LocaleStoreTest {

    private fun newPrefs(): FakeSharedPreferences = FakeSharedPreferences()

    @Test
    fun `readBlocking returns the empty tag when nothing is stored`() {
        val store = LocaleStore(newPrefs(), null)
        assertEquals("", store.readBlocking())
        assertEquals("", store.currentTag.value)
    }

    @Test
    fun `empty tag is a valid persisted value distinct from unset`() {
        // "follow system" is the durable instruction, not "leave the current locale unchanged".
        val prefs = newPrefs()
        runBlocking { LocaleStore(prefs, null).set("") }
        val reopened = LocaleStore(prefs, null)
        assertEquals("", reopened.readBlocking())
    }

    @Test
    fun `set persists and publishes the canonical tag`() {
        val prefs = newPrefs()
        val store = LocaleStore(prefs, null)
        runBlocking { store.set(" DE ") }
        assertEquals("de", store.currentTag.value)
        assertEquals("de", store.readBlocking())
    }

    @Test
    fun `unsupported and malformed tags are rejected`() {
        val store = LocaleStore(newPrefs(), null)
        listOf("und", "de-DE", "fr-FR", "xx", "not a locale", "en_US").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.set(raw) } }
        }
        runBlocking { store.set(" en-gb ") }
        assertEquals("en-GB", store.currentTag.value)
    }

    @Test
    fun `corrupt stored locale falls back to system default`() {
        val prefs = newPrefs().apply { edit().putString("ui_language", "und").commit() }
        val store = LocaleStore(prefs, null)
        assertEquals("", store.readBlocking())
        assertEquals("", store.currentTag.value)
    }

    @Test
    fun `wrong SharedPreferences value type cannot crash bootstrap read`() {
        val prefs = newPrefs().apply { putRaw("ui_language", 42) }
        assertEquals("", LocaleStore(prefs, null).readBlocking())
    }

    @Test
    fun `the value is readable after a simulated process restart`() {
        val prefs = newPrefs()
        runBlocking { LocaleStore(prefs, null).set("fr") }
        // A new process reopens the same SharedPreferences file; the StateFlow is per-instance.
        val afterRestart = LocaleStore(prefs, null)
        assertEquals("fr", afterRestart.readBlocking())
        assertEquals("fr", afterRestart.currentTag.value)
    }

    @Test
    fun `reset to system default writes the empty tag through the same API`() {
        val prefs = newPrefs()
        val store = LocaleStore(prefs, null)
        runBlocking { store.set("de") }
        runBlocking { store.set("") } // same write path the picker / backup import use
        assertEquals("", store.readBlocking())
    }

    @Test
    fun `a failed commit is surfaced, not swallowed`() {
        val prefs = newPrefs().apply { commitResult = false }
        val store = LocaleStore(prefs, null)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.set("de") }
        }
        // The in-memory StateFlow is only updated after a successful commit, so it stays empty.
        assertEquals("", store.currentTag.value)
    }

    @Test
    fun `backup import writes through the same API`() {
        // Backup import calls LocaleStore.set(tag); there is no second write path.
        val prefs = newPrefs()
        runBlocking { LocaleStore(prefs, null).set("tr") }
        assertTrue("tr" in prefs.stored.values)
        assertEquals("tr", LocaleStore(prefs, null).readBlocking())
    }

    @Test
    fun `concurrent sets complete in call order so the last selection wins`() = runBlocking {
        // Force the first selection to finish after every later selection if writes overlap. Without
        // writeMutex, "de" therefore wins incorrectly; with serialization, "en-US" remains last.
        val prefs = newPrefs().apply { commitDelaysMs = mapOf("de" to 120L) }
        val store = LocaleStore(prefs, null)
        val tags = listOf("de", "fr", "tr", "en-US")
        tags.map { tag -> async { store.set(tag) } }.awaitAll()
        assertEquals("en-US", store.currentTag.value)
        assertEquals("en-US", store.readBlocking())
    }

    // --- applicationContext nullable behaviour ---

    @Test
    fun `attachBaseContext read-only store has null applicationContext and never writes globally`() {
        // During Application.attachBaseContext, applicationContext is null (LoadedApk.mApplication
        // unassigned). A read-only bootstrap store must not attempt applyGlobally on set(). This test
        // pins that: a store built with null applicationContext still persists and publishes, but
        // does NOT call the platform Locale/LocaleList defaults — verified by the fact that set()
        // does not crash on stubbed android.jar (Resources.getSystem() returns null with stubs,
        // so applyGlobally WOULD NPE if it were called).
        val prefs = newPrefs()
        val store = LocaleStore(prefs, null)
        runBlocking { store.set("de") }
        assertEquals("de", store.currentTag.value)
        assertEquals("de", store.readBlocking())
        // No crash — applyGlobally was skipped because applicationContext was null.
    }

    @Test
    fun `null applicationContext store survives multiple writes`() {
        // The attachBaseContext bootstrap path constructs a store per call; verify repeated writes
        // through different null-appContext instances all persist to the same SharedPreferences file.
        val prefs = newPrefs()
        runBlocking { LocaleStore(prefs, null).set("de") }
        runBlocking { LocaleStore(prefs, null).set("fr") }
        runBlocking { LocaleStore(prefs, null).set("") }
        assertEquals("", LocaleStore(prefs, null).readBlocking())
    }

    // NOTE: Testing set() with a non-null applicationContext (the Koin singleton path that calls
    // AppLocale.applyGlobally) requires Robolectric or an instrumented test — on the JVM with stubbed
    // android.jar, Resources.getSystem() returns null and applyGlobally NPEs. The null-appContext
    // tests above verify the guard; the non-null path is covered by the build + manual device QA.
}

/** Minimal in-memory SharedPreferences. Implements only what LocaleStore uses; the rest throw. */
private class FakeSharedPreferences : SharedPreferences {
    private val entries: MutableMap<String, Any?> = Collections.synchronizedMap(mutableMapOf())
    var commitResult: Boolean = true
    /** Per-value IO latency used to force deterministic completion order in concurrency tests. */
    var commitDelaysMs: Map<String, Long> = emptyMap()

    /** Test-only accessor over the stored entries. */
    val stored: Map<String, Any?> get() = entries

    fun putRaw(key: String, value: Any?) { entries[key] = value }

    override fun getAll(): Map<String, *> = entries
    override fun getString(key: String, defValue: String?): String? {
        if (!entries.containsKey(key)) return defValue
        return entries[key] as? String
            ?: throw ClassCastException("Preference '$key' is not a String")
    }
    override fun getStringSet(key: String, defValues: Set<String>?) = throw UnsupportedOperationException()
    override fun getInt(key: String, defValue: Int) = throw UnsupportedOperationException()
    override fun getLong(key: String, defValue: Long) = throw UnsupportedOperationException()
    override fun getFloat(key: String, defValue: Float) = throw UnsupportedOperationException()
    override fun getBoolean(key: String, defValue: Boolean) = throw UnsupportedOperationException()
    override fun contains(key: String) = key in entries
    override fun edit(): SharedPreferences.Editor = Editor(this)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private class Editor(val outer: FakeSharedPreferences) : SharedPreferences.Editor {
        var stagedKey: String? = null
        var stagedValue: String? = null
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            stagedKey = key; stagedValue = value; return this
        }
        override fun putStringSet(key: String, values: Set<String>?) = throw UnsupportedOperationException()
        override fun putInt(key: String, value: Int) = throw UnsupportedOperationException()
        override fun putLong(key: String, value: Long) = throw UnsupportedOperationException()
        override fun putFloat(key: String, value: Float) = throw UnsupportedOperationException()
        override fun putBoolean(key: String, value: Boolean) = throw UnsupportedOperationException()
        override fun remove(key: String) = throw UnsupportedOperationException()
        override fun clear() = throw UnsupportedOperationException()
        override fun commit(): Boolean {
            val delayMs = stagedValue?.let { outer.commitDelaysMs[it] } ?: 0L
            if (delayMs > 0) {
                // Blocking sleep mirrors real SharedPreferences.commit() IO on Dispatchers.IO.
                Thread.sleep(delayMs)
            }
            if (!outer.commitResult) return false
            outer.entries[stagedKey!!] = stagedValue
            return true
        }
        override fun apply() = throw UnsupportedOperationException()
    }
}
