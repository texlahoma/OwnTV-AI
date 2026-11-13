package tv.own.owntv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the OwnTV baseline profile.
 *
 * Everything this journey touches gets AOT-compiled into the shipped APK, so on the low-end ARM
 * boxes OwnTV targets the Compose runtime, Room's query machinery, Koin's graph resolution and the
 * shell composition no longer run interpreted while the user waits for the first screen.
 *
 * **Must be recorded on an API 33+ device** — below that the benchmark library refuses to collect
 * without root, and the arm TV boxes this app targets are typically API 31/32. In practice that
 * means an Android TV **emulator** (API 33+, x86_64 image), with exactly one device attached:
 *
 * ```powershell
 * ./gradlew :app:generateBaselineProfile
 * ```
 *
 * There is no per-variant `generate<Flavor>ReleaseBaselineProfile` task here — `mergeIntoMain = true`
 * collapses them into that one. It records against :app's **x86_64** flavor, which this module pins
 * with `missingDimensionStrategy("abi", "x86_64")`: the arm `standard` APK cannot install on an
 * x86_64 emulator, and the emulator is the only place recording can happen.
 *
 * `mergeIntoMain = true` in `app/build.gradle.kts` puts the result in
 * `app/src/main/generated/baselineProfiles/` so the emulator-recorded profile also ships in the
 * `standard` (arm) APK — the profile is a list of code paths, not machine code, so this is correct. **Regenerate it whenever the startup path changes materially** —
 * a stale profile silently stops helping, it does not fail the build.
 *
 * The journey is deliberately input-driven (D-pad only, no text/resource matching) so it records
 * the same code paths on any box regardless of which playlist, catalog or language is installed,
 * and cannot fail on a device whose catalog is empty.
 *
 * **The emulator must already be set up and carry a catalog before recording.** A fresh install
 * opens the setup wizard, and a blind D-pad walk never escapes it — the profile then records the
 * wizard instead of the app, which is exactly what happened to the first recorded profile (555
 * entries for settings, 79 for setup, *one* each for home/movies/series/live/search). So: install
 * `:app:assembleX86_64BenchmarkRelease` by hand, complete the wizard, import a playlist, and only
 * then run the Gradle task — it reinstalls the same APK with `install -r`, which keeps that data.
 * A synthetic M3U served over `http://10.0.2.2:<port>/` from the host is enough and needs no real
 * credentials.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        // Also emit the startup profile — the subset ART compiles before the first frame.
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        settle()

        // Home first, with its rows actually scrolled: the carousel, the row headers and the poster
        // items are the first real composition the user ever waits for.
        browseContentArea()

        // Then every other section in turn. Entering the content area is what matters — a section
        // whose grid never composes contributes nothing but its empty scaffold, which is how the
        // previous profile ended up with a single entry per browse screen.
        repeat(NAV_ITEMS) {
            device.pressDPadDown()
            settle()
            browseContentArea()
        }

        // Open whatever is focused (channel or detail page) and come back — playback setup, the
        // detail screen and the back path all get compiled.
        device.pressDPadRight()
        settle()
        device.pressDPadCenter()
        settle()
        Thread.sleep(OPEN_SETTLE_MS)
        device.pressBack()
        settle()
    }

    /**
     * From the nav rail: step into the section's content, scroll it in both axes, and step back out.
     * Returning is done with repeated LEFT rather than BACK, because BACK on the first section
     * leaves the app and ends the journey early.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.browseContentArea() {
        device.pressDPadRight()
        settle()
        repeat(SCROLL_STEPS) {
            device.pressDPadRight()
            device.waitForIdle()
        }
        repeat(SCROLL_STEPS) {
            device.pressDPadDown()
            device.waitForIdle()
        }
        settle()
        repeat(SCROLL_STEPS + 2) {
            device.pressDPadLeft()
            device.waitForIdle()
        }
        settle()
    }

    /** Give Compose a beat to actually run the frames we want recorded. */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.settle() {
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
    }

    private companion object {
        const val PACKAGE = "tv.own.owntv"
        const val NAV_ITEMS = 6
        const val SCROLL_STEPS = 8
        const val SETTLE_MS = 350L
        const val OPEN_SETTLE_MS = 2_500L
    }
}
