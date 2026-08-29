package com.libreplayer.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until

internal object BaselineProfileJourneys {
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
    const val CONTENT_TIMEOUT_MS = 15_000L
    const val ROUTE_TRANSITION_TIMEOUT_MS = 3_000L
    const val REFRESH_SETTLE_TIMEOUT_MS = 30_000L
    const val SCROLL_GESTURES = 6
    const val SWIPE_STEPS = 100
    const val MEDIA_SESSION_CONTEXT_CHARS = 4_096

    fun MacrobenchmarkScope.startUsableSongs() {
        pressHome()
        startActivityAndWait()
        requireText(SONGS_TITLE)
        requireText(FIRST_SONG_TITLE)
        device.wait(Until.gone(By.text(UPDATING_LIBRARY_TITLE)), REFRESH_SETTLE_TIMEOUT_MS)
        device.waitForIdle()
    }

    fun MacrobenchmarkScope.prepareSongsAtTop() {
        killProcess()
        startUsableSongs()
    }

    fun MacrobenchmarkScope.scrollSongs() {
        repeat(SCROLL_GESTURES) { humanScrollDown() }
        check(!device.hasObject(By.text(FIRST_SONG_PATH))) {
            "Songs scroll did not move beyond the known first fixture row"
        }
        requireText(SONGS_TITLE)
    }

    fun MacrobenchmarkScope.openAndScrollAlbums() {
        requireObject(By.text(ALBUMS_TITLE), "Albums navigation item").click()
        requireText(ALBUMS_TITLE)
        requireText(FIRST_ALBUM_TITLE)
        device.waitForIdle()
        repeat(SCROLL_GESTURES) { humanScrollDown() }
        check(!device.hasObject(By.text(FIRST_ALBUM_TITLE))) {
            "Albums scroll did not move beyond the known first fixture album"
        }
        check(device.hasObject(By.textStartsWith("Album "))) {
            "Albums scroll did not leave fixture album content visible"
        }
    }

    fun MacrobenchmarkScope.startFixturePlaybackAndOpenNowPlaying() {
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
                check(device.click(searchBackBounds.centerX(), searchBackBounds.centerY())) {
                    "Search Back coordinate injection failed"
                }
                exitedSearch = device.wait(
                    Until.gone(By.clazz("android.widget.EditText")),
                    ROUTE_TRANSITION_TIMEOUT_MS,
                )
            }
        }
        check(exitedSearch) {
            "Search route remained visible after Back"
        }
        requireText(SONGS_TITLE)
        clickStable(By.desc(MINI_PLAYER_DESCRIPTION), "Mini player")
        requireText(NOW_PLAYING_TITLE)
        requireText(PLAYBACK_TITLE)
        device.waitForIdle()
        requireActiveFixtureMediaSession()
    }

    private fun MacrobenchmarkScope.requireActiveFixtureMediaSession() {
        val dump = device.executeShellCommand("dumpsys media_session")
        val titleIndex = dump.indexOf(PLAYBACK_TITLE)
        check(titleIndex >= 0) {
            "MediaSession did not expose the expected MediaItem title '$PLAYBACK_TITLE'"
        }
        val blockStart = (titleIndex - MEDIA_SESSION_CONTEXT_CHARS).coerceAtLeast(0)
        val blockEnd = (titleIndex + MEDIA_SESSION_CONTEXT_CHARS).coerceAtMost(dump.length)
        val activeBlock = dump.substring(blockStart, blockEnd)
        check(activeBlock.contains(TARGET_PACKAGE)) {
            "Expected MediaItem was not owned by LibrePlayer's MediaSession"
        }
        check(activeBlock.contains("state=PLAYING(3)")) {
            "LibrePlayer MediaSession was not in PLAYING state: $activeBlock"
        }
        check(activeBlock.contains("error=null")) {
            "LibrePlayer MediaSession reported a player error: $activeBlock"
        }
    }

    fun MacrobenchmarkScope.humanScrollDown() {
        val x = device.displayWidth / 2
        val startY = (device.displayHeight * 0.78f).toInt()
        val endY = (device.displayHeight * 0.31f).toInt()
        check(device.swipe(x, startY, x, endY, SWIPE_STEPS)) {
            "Normalized scroll gesture injection failed"
        }
    }

    fun MacrobenchmarkScope.requireText(text: String) =
        requireObject(By.text(text), "text '$text'")

    fun MacrobenchmarkScope.requireObject(selector: BySelector, label: String) =
        device.wait(Until.findObject(selector), CONTENT_TIMEOUT_MS)
            ?: error("Timed out waiting for $label")

    fun MacrobenchmarkScope.clickStable(selector: BySelector, label: String) {
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
}
