package com.libreplayer.benchmark

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetadataPathologyBenchmark {
    @Test
    fun metadataPathologyAuthority() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
        check(device.executeShellCommand("getprop ro.build.version.sdk").trim() == "36")
        check(device.executeShellCommand("pm clear $PACKAGE_NAME").contains("Success"))
        device.executeShellCommand("pm grant $PACKAGE_NAME android.permission.READ_MEDIA_AUDIO")
        val resolver = instrumentation.context.contentResolver
        val probeUri = Uri.parse("content://com.libreplayer.synchronization-probe")

        val initial = requireNotNull(resolver.call(probeUri, METHOD_SYNC, null, null))
        assertCatalog(initial)
        val unchanged = requireNotNull(resolver.call(probeUri, METHOD_SYNC, null, null))
        assertCatalog(unchanged)
        check(initial.getString(KEY_FINGERPRINT) == unchanged.getString(KEY_FINGERPRINT))
        listOf(KEY_IDS, KEY_URIS, KEY_TITLES, KEY_ARTISTS, KEY_ALBUMS, KEY_ALBUM_IDS, KEY_ARTIST_IDS).forEach { key ->
            check(initial.strings(key) == unchanged.strings(key)) { "Unchanged refresh changed $key" }
        }

        device.executeShellCommand("am force-stop $PACKAGE_NAME")
        val launchIntent = requireNotNull(
            instrumentation.targetContext.packageManager.getLaunchIntentForPackage(PACKAGE_NAME),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.targetContext.startActivity(launchIntent)
        check(device.wait(Until.hasObject(By.text("Songs")), UI_TIMEOUT_MS))
        device.wait(Until.gone(By.text("Updating library")), REFRESH_TIMEOUT_MS)
        requireNotNull(device.findObject(By.desc("Search library"))).click()
        check(device.wait(Until.hasObject(By.text("Search your library")), UI_TIMEOUT_MS))
        setSearchText(device, "long-needle")
        check(device.wait(Until.hasObject(By.textStartsWith("Q34 Long ")), UI_TIMEOUT_MS))
        requireNotNull(device.findObject(By.desc("Song actions"))).click()
        requireNotNull(device.wait(Until.findObject(By.text("Open album")), UI_TIMEOUT_MS)).click()
        check(device.wait(Until.hasObject(By.textStartsWith("Q34 Long Album ")), UI_TIMEOUT_MS)) {
            "Long semantic album ID did not survive production route navigation"
        }

        val playback = requireNotNull(resolver.call(probeUri, METHOD_PLAY, null, null))
        check(playback.getString("q34PlayExpectedId") == playback.getString("q34PlayCurrentId"))
        check(playback.getString("q34PlayExpectedUri") == playback.getString("q34PlayCurrentUri"))
        check(playback.getBoolean("q34PlayIsPlaying")) {
            "Malformed-tag fixture did not play: ${playback.getString("q34PlayError")}"
        }
        check(playback.getString("q34PlayError") == null)
    }

    private fun assertCatalog(result: Bundle) {
        check(result.getInt("q34Count") == EXPECTED_COUNT)
        val files = result.strings(KEY_FILES)
        val ids = result.strings(KEY_IDS)
        val uris = result.strings(KEY_URIS)
        val titles = result.strings(KEY_TITLES)
        val artists = result.strings(KEY_ARTISTS)
        val albums = result.strings(KEY_ALBUMS)
        val resolvedTitles = result.strings("q34ResolvedTitles")
        val resolvedArtists = result.strings("q34ResolvedArtists")
        val resolvedAlbums = result.strings("q34ResolvedAlbums")
        val tracks = requireNotNull(result.getIntArray("q34Tracks")).toList()
        val discs = requireNotNull(result.getIntArray("q34Discs")).toList()
        val years = requireNotNull(result.getIntArray("q34Years")).toList()
        val albumIds = result.strings(KEY_ALBUM_IDS)
        val artistIds = result.strings(KEY_ARTIST_IDS)

        listOf(ids, uris, titles, artists, albums, resolvedTitles, resolvedArtists, resolvedAlbums, albumIds, artistIds)
            .forEach { values -> check(values.size == EXPECTED_COUNT) }
        check(files == EXPECTED_FILES)
        check(ids.distinct().size == EXPECTED_COUNT && ids.all { it.startsWith("media:") })
        check(uris.distinct().size == EXPECTED_COUNT)
        check(resolvedTitles.none(String::isBlank))
        check(resolvedArtists.none(String::isBlank))
        check(resolvedAlbums.none(String::isBlank))
        check(tracks.all { it == -1 || it > 0 })
        check(discs.all { it == -1 || it > 0 })
        check(years.all { it == -1 || it in 1..9_999 })

        val absent = files.indexOf("02-absent.mp3")
        val whitespace = files.indexOf("03-whitespace.mp3")
        val literal = files.indexOf("04-literal-unknown.mp3")
        val precomposed = files.indexOf("05-unicode-precomposed.mp3")
        val decomposed = files.indexOf("06-unicode-decomposed.mp3")
        check(artistIds[absent] != artistIds[literal])
        check(albumIds[absent] != albumIds[literal])
        check(artistIds[precomposed] != artistIds[decomposed])
        check(albumIds[precomposed] != albumIds[decomposed])
        check(resolvedTitles[whitespace].isNotBlank())

        check(result.getInt("q34LongTitleLength") == 1_024)
        check(result.getInt("q34LongArtistLength") == 4_096)
        check(result.getInt("q34LongAlbumLength") == 4_096)
        check(result.strings("q34LongSearchFiles") == listOf("08-long-needle.mp3"))
        check(result.getString("q34LongAlbumId") == result.getString("q34LongRouteArgument"))
        check(result.getString("q34SelectedId") == result.getString("q34SelectedMediaId"))
        check(result.getString("q34SelectedUri") == result.getString("q34SelectedMediaUri"))
        check(result.strings("q34TitleSortFiles").toSet() == EXPECTED_FILES.toSet())
        check(result.getInt("q34RetrieverNumericTrack") == -1)
        check(result.getInt("q34RetrieverNumericDisc") == -1)
        check(result.getInt("q34RetrieverNumericYear") == -1)
        check(result.getBoolean("q34RetrieverMalformedReadable"))
        check(result.getLong("q34RetrieverMalformedDuration") >= 30_000L)
        check(result.getString("q34RetrieverMalformedTitle") == null)
    }

    private fun Bundle.strings(key: String): List<String> =
        requireNotNull(getStringArray(key)) { "Missing String array: $key" }.toList()

    private fun setSearchText(device: UiDevice, text: String) {
        repeat(UI_OBJECT_RETRIES) {
            try {
                requireNotNull(device.findObject(By.clazz("android.widget.EditText"))) {
                    "Search field is not present"
                }.text = text
                return
            } catch (_: StaleObjectException) {
                SystemClock.sleep(UI_OBJECT_RETRY_MS)
            }
        }
        error("Search field remained stale")
    }

    private companion object {
        const val PACKAGE_NAME = "com.libreplayer"
        const val METHOD_SYNC = "q3.4-sync"
        const val METHOD_PLAY = "q3.4-play"
        const val KEY_FILES = "q34Files"
        const val KEY_IDS = "q34Ids"
        const val KEY_URIS = "q34Uris"
        const val KEY_TITLES = "q34Titles"
        const val KEY_ARTISTS = "q34Artists"
        const val KEY_ALBUMS = "q34Albums"
        const val KEY_ALBUM_IDS = "q34AlbumIds"
        const val KEY_ARTIST_IDS = "q34ArtistIds"
        const val KEY_FINGERPRINT = "q34Fingerprint"
        const val EXPECTED_COUNT = 11
        const val UI_TIMEOUT_MS = 15_000L
        const val REFRESH_TIMEOUT_MS = 30_000L
        const val UI_OBJECT_RETRIES = 20
        const val UI_OBJECT_RETRY_MS = 50L
        val EXPECTED_FILES = (1..11).map { index ->
            when (index) {
                1 -> "01-control.mp3"
                2 -> "02-absent.mp3"
                3 -> "03-whitespace.mp3"
                4 -> "04-literal-unknown.mp3"
                5 -> "05-unicode-precomposed.mp3"
                6 -> "06-unicode-decomposed.mp3"
                7 -> "07-controls.mp3"
                8 -> "08-long-needle.mp3"
                9 -> "09-numeric.mp3"
                10 -> "10-conflicting.mp3"
                else -> "11-malformed-playable.mp3"
            }
        }
    }
}
