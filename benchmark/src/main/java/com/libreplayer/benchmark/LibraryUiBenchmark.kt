package com.libreplayer.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
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
        iterations = ITERATIONS,
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
        iterations = ITERATIONS,
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
    fun searchProgressiveQuery() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
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
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
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
        val x = device.displayWidth / 2
        val startY = (device.displayHeight * 0.78f).toInt()
        val endY = (device.displayHeight * 0.31f).toInt()
        check(device.swipe(x, startY, x, endY, SWIPE_STEPS)) {
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
        const val ITERATIONS = 5
        const val SCROLL_GESTURES = 6
        const val SWIPE_STEPS = 100
        const val CONTENT_TIMEOUT_MS = 15_000L
        const val REFRESH_SETTLE_TIMEOUT_MS = 30_000L
        const val SONGS_TITLE = "Songs"
        const val ALBUMS_TITLE = "Albums"
        const val SEARCH_DESCRIPTION = "Search library"
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
