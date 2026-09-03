package com.libreplayer.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class LibraryUiBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun songsScroll() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = SCROLL_ITERATIONS,
        setupBlock = {
            prepareSongsAtTop()
        },
    ) {
        repeat(SCROLL_GESTURES) { humanScrollDown() }
        check(!device.hasObject(By.text(FIRST_SONG_PATH))) {
            "Songs scroll did not move beyond the known first fixture row"
        }
        requireText(SONGS_TITLE)
    }

    @Test
    fun albumsScrollColdAppArtworkCache() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = SCROLL_ITERATIONS,
        setupBlock = {
            prepareSongsAtTop()
            requireObject(By.text(ALBUMS_TITLE), "Albums navigation item").click()
            requireText(ALBUMS_TITLE)
            requireText(FIRST_ALBUM_TITLE)
            device.waitForIdle()
        },
    ) {
        repeat(SCROLL_GESTURES) { humanScrollDown() }
        check(!device.hasObject(By.text(FIRST_ALBUM_TITLE))) {
            "Albums scroll did not move beyond the known first fixture album"
        }
        check(device.hasObject(By.textStartsWith("Album "))) {
            "Albums scroll did not leave fixture album content visible"
        }
    }

    @Test
    fun albumsAndArtistsProjectionSmoke() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("logcat -c")
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
        device.executeShellCommand("am start -W -n $TARGET_PACKAGE/com.libreplayer.app.MainActivity")

        requireNotNull(device.wait(Until.findObject(By.text(SONGS_TITLE)), CONTENT_TIMEOUT_MS)) {
            "Songs did not reach a stable state"
        }
        device.wait(Until.gone(By.text(UPDATING_LIBRARY_TITLE)), REFRESH_SETTLE_TIMEOUT_MS)

        requireNotNull(device.wait(Until.findObject(By.text(ALBUMS_TITLE)), CONTENT_TIMEOUT_MS)) {
            "Albums navigation item was not available"
        }.click()
        requireNotNull(device.wait(Until.findObject(By.text(FIRST_ALBUM_TITLE)), CONTENT_TIMEOUT_MS)) {
            "Albums projection did not expose canonical MEDIUM content"
        }
        repeat(PROJECTION_SMOKE_SCROLL_GESTURES) { device.humanScrollDown() }
        check(device.hasObject(By.textStartsWith("Album "))) {
            "Albums projection did not remain populated after scrolling"
        }

        requireNotNull(device.wait(Until.findObject(By.text(ARTISTS_TITLE)), CONTENT_TIMEOUT_MS)) {
            "Artists navigation item was not available"
        }.click()
        check(device.wait(Until.hasObject(By.textStartsWith(ARTIST_PREFIX)), CONTENT_TIMEOUT_MS)) {
            "Artists projection did not expose canonical MEDIUM content"
        }
        repeat(PROJECTION_SMOKE_SCROLL_GESTURES) { device.humanScrollDown() }
        check(device.hasObject(By.textStartsWith(ARTIST_PREFIX))) {
            "Artists projection did not remain populated after scrolling"
        }

        val logs = device.executeShellCommand("logcat -d -v brief")
        check("Key \"" !in logs || "was already used" !in logs) {
            "Compose reported a duplicate lazy-list key"
        }
        check("FATAL EXCEPTION" !in logs || TARGET_PACKAGE !in logs) {
            "LibrePlayer crashed during the projection smoke"
        }
    }

    @Test
    fun sortingSearchProjectionSmoke() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("logcat -c")
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
        device.executeShellCommand("am start -W -n $TARGET_PACKAGE/com.libreplayer.app.MainActivity")

        requireNotNull(device.wait(Until.findObject(By.text(SONGS_TITLE)), CONTENT_TIMEOUT_MS)) {
            "Songs did not reach a stable state"
        }
        device.wait(Until.gone(By.text(UPDATING_LIBRARY_TITLE)), REFRESH_SETTLE_TIMEOUT_MS)
        requireNotNull(device.findObject(By.desc(SORT_DESCRIPTION))).click()
        requireNotNull(device.wait(Until.findObject(By.text("Title")), CONTENT_TIMEOUT_MS)).click()
        requireNotNull(device.findObject(By.desc(SORT_DESCRIPTION))).click()
        requireNotNull(device.wait(Until.findObject(By.text("Album")), CONTENT_TIMEOUT_MS)).click()
        requireNotNull(device.findObject(By.desc(SEARCH_DESCRIPTION))).click()
        requireNotNull(device.wait(Until.findObject(By.text(SEARCH_EMPTY_TITLE)), CONTENT_TIMEOUT_MS))

        requireNotNull(device.findObject(By.clazz("android.widget.EditText"))).text = "Duplicate"
        requireNotNull(device.wait(Until.findObject(By.text(EXPECTED_SEARCH_RESULT)), CONTENT_TIMEOUT_MS)) {
            "Representative MEDIUM search result did not stabilize"
        }
        requireNotNull(device.findObject(By.clazz("android.widget.EditText"))).text = ""
        requireNotNull(device.wait(Until.findObject(By.text(SEARCH_EMPTY_TITLE)), CONTENT_TIMEOUT_MS)) {
            "Search clear did not return to the non-search state"
        }
        requireNotNull(device.findObject(By.text("Back"))).click()
        requireNotNull(device.wait(Until.findObject(By.text(SONGS_TITLE)), CONTENT_TIMEOUT_MS))

        requireNotNull(device.findObject(By.text(ALBUMS_TITLE)).click())
        requireNotNull(device.wait(Until.findObject(By.text(FIRST_ALBUM_TITLE)), CONTENT_TIMEOUT_MS)) {
            "Albums did not remain stable after sort/search projection work"
        }
        requireNotNull(device.findObject(By.text(ARTISTS_TITLE)).click())
        check(device.wait(Until.hasObject(By.textStartsWith(ARTIST_PREFIX)), CONTENT_TIMEOUT_MS)) {
            "Artists did not remain stable after sort/search projection work"
        }

        val logs = device.executeShellCommand("logcat -d -v brief")
        check("Key \"" !in logs || "was already used" !in logs) {
            "Compose reported a duplicate lazy-list key"
        }
        check("FATAL EXCEPTION" !in logs || TARGET_PACKAGE !in logs) {
            "LibrePlayer crashed during the sort/search projection smoke"
        }
    }

    @Test
    fun searchProgressiveQuery() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = SEARCH_ITERATIONS,
        setupBlock = {
            prepareSongsAtTop()
            requireObject(By.desc(SEARCH_DESCRIPTION), "Search library action").click()
            requireText(SEARCH_EMPTY_TITLE)
            requireObject(By.clazz("android.widget.EditText"), "Search field").click()
            device.waitForIdle()
        },
    ) {
        val searchField = requireObject(By.clazz("android.widget.EditText"), "Search field")
        SEARCH_STATES.forEach { query ->
            searchField.text = query
            check(device.wait(Until.hasObject(By.text(EXPECTED_SEARCH_RESULT)), CONTENT_TIMEOUT_MS)) {
                "Known MEDIUM fixture result was not visible for query '$query'"
            }
        }
        check(device.hasObject(By.text(EXPECTED_SEARCH_RESULT))) {
            "Expected final search result is not visible"
        }
    }

    @Test
    fun backgroundForegroundReturn() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), StartupTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = RESUME_ITERATIONS,
        startupMode = StartupMode.HOT,
        setupBlock = {
            prepareSongsAtTop()
        },
    ) {
        pressHome()
        device.waitForIdle()
        startActivityAndWait()
        requireText(SONGS_TITLE)
        requireText(FIRST_SONG_TITLE)
        requireText(FIRST_SONG_PATH)
        check(!device.hasObject(By.text(SCANNING_LIBRARY_TITLE))) {
            "Resident foreground return unexpectedly showed a blank scanning state"
        }
    }

    private fun MacrobenchmarkScope.prepareSongsAtTop() {
        killProcess()
        pressHome()
        startActivityAndWait()
        requireText(SONGS_TITLE)
        requireText(FIRST_SONG_TITLE)
        device.wait(Until.gone(By.text(UPDATING_LIBRARY_TITLE)), REFRESH_SETTLE_TIMEOUT_MS)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.humanScrollDown() {
        device.humanScrollDown()
    }

    private fun UiDevice.humanScrollDown() {
        val x = displayWidth / 2
        val startY = (displayHeight * 0.78f).toInt()
        val endY = (displayHeight * 0.31f).toInt()
        check(swipe(x, startY, x, endY, SWIPE_STEPS)) {
            "Normalized scroll gesture injection failed"
        }
    }

    private fun MacrobenchmarkScope.requireText(text: String) =
        requireObject(By.text(text), "text '$text'")

    private fun MacrobenchmarkScope.requireObject(
        selector: androidx.test.uiautomator.BySelector,
        label: String,
    ) = device.wait(Until.findObject(selector), CONTENT_TIMEOUT_MS)
        ?: error("Timed out waiting for $label")

    private companion object {
        const val TARGET_PACKAGE = "com.libreplayer"
        const val SCROLL_ITERATIONS = 5
        const val PROJECTION_SMOKE_SCROLL_GESTURES = 2
        const val SEARCH_ITERATIONS = 15
        const val RESUME_ITERATIONS = 20
        const val SCROLL_GESTURES = 6
        const val SWIPE_STEPS = 100
        const val CONTENT_TIMEOUT_MS = 15_000L
        const val REFRESH_SETTLE_TIMEOUT_MS = 30_000L
        const val SONGS_TITLE = "Songs"
        const val ALBUMS_TITLE = "Albums"
        const val ARTISTS_TITLE = "Artists"
        const val ARTIST_PREFIX = "Artist "
        const val SEARCH_DESCRIPTION = "Search library"
        const val SORT_DESCRIPTION = "Sort songs"
        const val SEARCH_EMPTY_TITLE = "Search your library"
        const val FIRST_SONG_TITLE = "Duplicate Display Metadata"
        const val FIRST_SONG_PATH =
            "Music/LibrePlayerBenchmark/MEDIUM/audio/artist-00093/album-00093/disc-01/"
        const val FIRST_ALBUM_TITLE = "Album 00006"
        const val EXPECTED_SEARCH_RESULT = "Duplicate Display Metadata"
        const val UPDATING_LIBRARY_TITLE = "Updating library"
        const val SCANNING_LIBRARY_TITLE = "Scanning library"
        val SEARCH_STATES = listOf("D", "Du", "Dup", "Dupl", "Duplicate")
    }
}
