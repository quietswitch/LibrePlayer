package com.libreplayer.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class LibrePlayerBaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        outputFilePrefix = "libreplayer-startup",
        includeInStartupProfile = true,
        strictStability = true,
        filterPredicate = ::isLibrePlayerProductionRule,
    ) {
        grantMediaPermission()
        pressHome()
        startUsableSongs()
    }

    @Test
    fun criticalUserJourneys() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        outputFilePrefix = "libreplayer-critical-user-journeys",
        includeInStartupProfile = false,
        strictStability = false,
        filterPredicate = ::isLibrePlayerProductionRule,
    ) {
        grantMediaPermission()
        killProcess()
        startUsableSongs()
        scrollSongs()
        openAndScrollAlbums()
        killProcess()
        startUsableSongs()
        startFixturePlaybackAndOpenNowPlaying()
    }

    private fun MacrobenchmarkScope.startUsableSongs() {
        startActivityAndWait()
        requireText(SONGS_TITLE)
        requireText(FIRST_SONG_TITLE)
        device.wait(Until.gone(By.text(UPDATING_LIBRARY_TITLE)), REFRESH_SETTLE_TIMEOUT_MS)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.grantMediaPermission() {
        device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.READ_MEDIA_AUDIO")
    }

    private fun MacrobenchmarkScope.scrollSongs() {
        repeat(SCROLL_GESTURES) { humanScrollDown() }
        requireText(SONGS_TITLE)
        check(device.hasObject(By.textStartsWith("Track ")) || device.hasObject(By.text(FIRST_SONG_TITLE)))
    }

    private fun MacrobenchmarkScope.openAndScrollAlbums() {
        requireObject(By.text(ALBUMS_TITLE), "Albums navigation item").click()
        requireText(ALBUMS_TITLE)
        requireText(FIRST_ALBUM_TITLE)
        repeat(SCROLL_GESTURES) { humanScrollDown() }
        check(device.hasObject(By.textStartsWith("Album ")))
    }

    private fun MacrobenchmarkScope.startFixturePlaybackAndOpenNowPlaying() {
        requireObject(By.desc(SEARCH_DESCRIPTION), "Search library action").click()
        requireText(SEARCH_EMPTY_TITLE)
        val searchBackBounds = requireObject(By.text(BACK_TITLE), "Search back action").visibleBounds
        val searchField = requireObject(By.clazz("android.widget.EditText"), "Search field")
        searchField.text = PLAYBACK_TITLE
        var playbackRow = requireObject(By.text(PLAYBACK_TITLE), "deterministic playback fixture")
        while (!playbackRow.isClickable) {
            playbackRow = requireNotNull(playbackRow.parent) {
                "Deterministic playback fixture '$PLAYBACK_TITLE' has no clickable ancestor"
            }
        }
        playbackRow.click()
        device.waitForIdle()
        var exitedSearch = false
        repeat(3) {
            if (!exitedSearch) {
                check(device.click(searchBackBounds.centerX(), searchBackBounds.centerY()))
                exitedSearch = device.wait(
                    Until.gone(By.clazz("android.widget.EditText")),
                    ROUTE_TRANSITION_TIMEOUT_MS,
                )
            }
        }
        check(exitedSearch)
        requireText(SONGS_TITLE)
        clickStable(By.desc(MINI_PLAYER_DESCRIPTION), "Mini player")
        requireText(NOW_PLAYING_TITLE)
        requireText(PLAYBACK_TITLE)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.humanScrollDown() {
        val x = device.displayWidth / 2
        val startY = (device.displayHeight * 0.78f).toInt()
        val endY = (device.displayHeight * 0.31f).toInt()
        var injected = false
        repeat(3) {
            if (!injected) {
                device.waitForIdle()
                injected = device.swipe(x, startY, x, endY, SWIPE_STEPS)
            }
        }
        check(injected)
    }

    private fun MacrobenchmarkScope.requireText(text: String) =
        requireObject(By.text(text), "text '$text'")

    private fun MacrobenchmarkScope.requireObject(selector: BySelector, label: String) =
        device.wait(Until.findObject(selector), CONTENT_TIMEOUT_MS)
            ?: error("Timed out waiting for $label")

    private fun MacrobenchmarkScope.clickStable(selector: BySelector, label: String) {
        repeat(5) {
            try {
                requireObject(selector, label).click()
                return
            } catch (_: StaleObjectException) {
                device.waitForIdle()
            }
        }
        error("$label remained stale across retries")
    }

    private fun isLibrePlayerProductionRule(rule: String): Boolean =
        rule.contains(PRODUCTION_PACKAGE_PATTERN) && !rule.contains(BENCHMARK_PACKAGE_PATTERN)

    private companion object {
        const val TARGET_PACKAGE = "com.libreplayer"
        const val SONGS_TITLE = "Songs"
        const val ALBUMS_TITLE = "Albums"
        const val FIRST_SONG_TITLE = "Duplicate Display Metadata"
        const val FIRST_SONG_PATH =
            "Music/LibrePlayerBenchmark/MEDIUM/audio/artist-00093/album-00093/disc-01/"
        const val FIRST_ALBUM_TITLE = "Album 00006"
        const val PLAYBACK_TITLE = "Track 00010"
        const val SEARCH_DESCRIPTION = "Search library"
        const val SEARCH_EMPTY_TITLE = "Search your library"
        const val BACK_TITLE = "Back"
        const val UPDATING_LIBRARY_TITLE = "Updating library"
        const val NOW_PLAYING_TITLE = "Now Playing"
        const val MINI_PLAYER_DESCRIPTION = "Mini player"
        const val CONTENT_TIMEOUT_MS = 60_000L
        const val ROUTE_TRANSITION_TIMEOUT_MS = 3_000L
        const val REFRESH_SETTLE_TIMEOUT_MS = 30_000L
        const val SCROLL_GESTURES = 6
        const val SWIPE_STEPS = 100
        val PRODUCTION_PACKAGE_PATTERN = Regex("^.*Lcom/libreplayer/")
        const val BENCHMARK_PACKAGE_PATTERN = "Lcom/libreplayer/benchmark/"
    }
}
