package com.libreplayer.benchmark

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class SynchronizationBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun synchronizationAuthority() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val journey = requireArgument(arguments, "journey")
        val method = when (journey) {
            "unchanged", "add", "delete", "modify" -> "sync"
            "rebuild" -> "rebuild"
            else -> error("Unsupported synchronization journey: $journey")
        }
        val expectedCount = requireArgument(arguments, "expectedCount").toInt()
        val expectedFingerprint = requireArgument(arguments, "expectedFingerprint")
        val inspectedIdentity = arguments.getString("inspectedIdentity")
        val expectPresent = arguments.getString("expectPresent")?.toBooleanStrictOrNull()
        val expectedTitle = arguments.getString("expectedTitleBase64")?.let { encoded ->
            String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
        }
        val iterations = arguments.getString("iterations")?.toIntOrNull() ?: 1
        require(iterations in 1..20) { "iterations must be in 1..20" }
        val resolver = instrumentation.context.contentResolver
        val probeUri = Uri.parse("content://com.libreplayer.synchronization-probe")

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
            ),
            compilationMode = CompilationMode.Full(),
            iterations = iterations,
            setupBlock = {
                pressHome()
                requireNotNull(resolver.call(probeUri, "catalog", null, null))
            },
        ) {
            val result = requireNotNull(resolver.call(probeUri, method, inspectedIdentity, null))
            check(result.getInt("fixtureCount") == expectedCount) {
                "Expected $expectedCount fixture songs, got ${result.getInt("fixtureCount")}" 
            }
            check(result.getInt("uniqueIdentities") == expectedCount)
            check(result.getInt("duplicateIdentities") == 0)
            check(result.getString("identitySha256") == expectedFingerprint) {
                "Catalog identity fingerprint mismatch: ${result.getString("identitySha256")}" 
            }
            if (expectPresent != null) {
                check(result.getBoolean("inspectedPresent") == expectPresent) {
                    "Inspected identity presence did not match $expectPresent"
                }
            }
            if (expectedTitle != null) {
                check(result.getString("inspectedTitle") == expectedTitle) {
                    "Expected inspected title '$expectedTitle', got '${result.getString("inspectedTitle")}'"
                }
            }
        }
    }

    @Test
    fun libraryIdentityIngestionAuthority() {
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val probeUri = Uri.parse("content://com.libreplayer.synchronization-probe")
        val result = requireNotNull(resolver.call(probeUri, "q3.1-sync", null, null))

        val ids = requireNotNull(result.getStringArray("q31Ids")).toList()
        val contentUris = requireNotNull(result.getStringArray("q31ContentUris")).toList()
        val displayNames = requireNotNull(result.getStringArray("q31DisplayNames")).toList()
        val titles = requireNotNull(result.getStringArray("q31Titles")).toList()
        val artists = requireNotNull(result.getStringArray("q31Artists")).toList()
        val albums = requireNotNull(result.getStringArray("q31Albums")).toList()
        val tracks = requireNotNull(result.getIntArray("q31Tracks")).toList()
        val discs = requireNotNull(result.getIntArray("q31Discs")).toList()
        val years = requireNotNull(result.getIntArray("q31Years")).toList()
        val paths = requireNotNull(result.getStringArray("q31RelativePaths")).toList()

        check(result.getInt("q31Count") == 3) { "Expected three Q3.1 occurrences, got ${result.getInt("q31Count")}" }
        check(ids.size == 3 && ids.distinct().size == 3 && ids.all { it.startsWith("media:") })
        check(contentUris.size == 3 && contentUris.distinct().size == 3)
        check(displayNames == listOf("Twin.mp3", "Twin.mp3", "DiscTrack.mp3"))
        check(titles == listOf("Twin", "Twin", "Disc Track"))
        check(artists == listOf("Identity Artist", "Identity Artist", "Track Artist"))
        check(albums == listOf("Identity Album", "Identity Album", "Multi Album"))
        check(tracks == listOf(1, 1, 1))
        check(discs == listOf(1, 1, 2))
        check(years == listOf(2024, 2024, 2023))
        check(paths == listOf(
            "Music/LibrePlayerQ31/CopyA/",
            "Music/LibrePlayerQ31/CopyB/",
            "Music/LibrePlayerQ31/Multi/",
        ))
    }

    @Test
    fun libraryBrowsingAuthority() {
        val phase = InstrumentationRegistry.getArguments().getString("phase") ?: "initial"
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val probeUri = Uri.parse("content://com.libreplayer.synchronization-probe")
        val result = requireNotNull(resolver.call(probeUri, "q3.2-sync", null, null))

        val expectedSongCount = if (phase == "initial") 10 else 9
        val expectedArtistASongs = if (phase == "initial") 6 else 5
        val expectedBetaSongs = if (phase == "initial") 3 else 2
        check(phase == "initial" || phase == "removed") { "Unsupported Q3.2 phase: $phase" }
        check(result.getInt("q32SongCount") == expectedSongCount)
        check(result.getInt("q32AlbumCount") == 6)
        check(result.getInt("q32ArtistCount") == 5)
        check(result.getBoolean("q32AlbumCountParity"))
        check(result.getBoolean("q32ArtistCountParity"))
        check(result.getBoolean("q32PersistedAggregateParity"))

        val songIds = requireNotNull(result.getStringArray("q32SongIds")).toList()
        val songUris = requireNotNull(result.getStringArray("q32SongUris")).toList()
        val songFiles = requireNotNull(result.getStringArray("q32SongFiles")).toList()
        check(songIds.size == expectedSongCount && songIds.distinct().size == expectedSongCount)
        check(songUris.size == expectedSongCount && songUris.distinct().size == expectedSongCount)
        check(songIds.all { it.startsWith("media:") })
        check(songFiles.contains("TwinA.mp3"))
        check(songFiles.contains("TwinB.mp3") == (phase == "initial"))

        val albumTitles = requireNotNull(result.getStringArray("q32AlbumTitles"))
        val albumArtists = requireNotNull(result.getStringArray("q32AlbumArtists"))
        val albumCounts = requireNotNull(result.getIntArray("q32AlbumSongCounts"))
        val albumMembers = requireNotNull(result.getStringArray("q32AlbumMemberFiles"))
        val alphaA = albumIndex(albumTitles, albumArtists, "Album Alpha", "Artist A")
        val alphaB = albumIndex(albumTitles, albumArtists, "Album Alpha", "Artist B")
        val betaA = albumIndex(albumTitles, albumArtists, "Album Beta", "Artist A")
        check(alphaA != alphaB)
        check(albumCounts[alphaA] == 3)
        check(albumMembers[alphaA].split(FILE_SEPARATOR).toSet() == setOf("AlphaA1.mp3", "AlphaA2.mp3", "AlphaA3.mp3"))
        check(albumCounts[alphaB] == 1 && albumMembers[alphaB] == "AlphaB1.mp3")
        check(albumCounts[betaA] == expectedBetaSongs)

        val artistNames = requireNotNull(result.getStringArray("q32ArtistNames"))
        val artistCounts = requireNotNull(result.getIntArray("q32ArtistSongCounts"))
        val artistMembers = requireNotNull(result.getStringArray("q32ArtistMemberFiles"))
        val artistA = artistNames.indexOf("Artist A")
        check(artistA >= 0 && artistCounts[artistA] == expectedArtistASongs)
        check(artistMembers[artistA].split(FILE_SEPARATOR).size == expectedArtistASongs)
        check(artistNames.contains("R.E.M.") && artistNames.contains("REM"))

        check(result.getString("q32SelectedId") == result.getString("q32SelectedMediaId"))
        check(result.getString("q32SelectedUri") == result.getString("q32SelectedMediaUri"))
        check(result.getString("q32SpecialAlbumKey") == result.getString("q32SpecialRouteArgument"))
    }

    @Test
    fun sortingAndSearchAuthority() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        check(device.executeShellCommand("pm clear $PACKAGE_NAME").contains("Success"))
        device.executeShellCommand("pm grant $PACKAGE_NAME android.permission.READ_MEDIA_AUDIO")
        val resolver = instrumentation.context.contentResolver
        val probeUri = Uri.parse("content://com.libreplayer.synchronization-probe")
        val initial = requireNotNull(resolver.call(probeUri, "q3.3-sync", null, null))

        check(initial.getInt("q33SongCount") == 10)
        val titleFiles = initial.strings("q33TitleFiles")
        val titleIds = initial.strings("q33TitleIds")
        check(titleFiles.take(8) == listOf(
            "AlphaA3.mp3",
            "AlphaA1.mp3",
            "AlphaA2.mp3",
            "AlphaB1.mp3",
            "Beta.mp3",
            "Dots.mp3",
            "Plain.mp3",
            "Special.mp3",
        ))
        check(titleFiles.drop(8).toSet() == setOf("TwinA.mp3", "TwinB.mp3"))
        check(titleIds.size == 10 && titleIds.distinct().size == 10)
        check(titleIds.drop(8) == titleIds.drop(8).sorted())

        val albumSortFiles = initial.strings("q33AlbumSortFiles")
        val albumSortIds = initial.strings("q33AlbumSortIds")
        check(albumSortFiles.take(5) == listOf(
            "AlphaA1.mp3",
            "AlphaA2.mp3",
            "AlphaA3.mp3",
            "AlphaB1.mp3",
            "Beta.mp3",
        ))
        check(albumSortFiles.subList(5, 7).toSet() == setOf("TwinA.mp3", "TwinB.mp3"))
        check(albumSortIds.subList(5, 7) == albumSortIds.subList(5, 7).sorted())
        check(albumSortFiles.drop(7) == listOf("Dots.mp3", "Plain.mp3", "Special.mp3"))

        check(initial.strings("q33AlbumTitles") == listOf(
            "Album Alpha",
            "Album Alpha",
            "Album Beta",
            "Punctuation",
            "Punctuation",
            "Symbols %2F / ? # |",
        ))
        check(initial.strings("q33AlbumArtists") == listOf(
            "Artist A",
            "Artist B",
            "Artist A",
            "R.E.M.",
            "REM",
            "A|B",
        ))
        check(initial.strings("q33AlbumIds").distinct().size == 6)
        check(initial.strings("q33ArtistNames") == listOf("Artist A", "Artist B", "A|B", "R.E.M.", "REM"))
        check(initial.strings("q33ArtistIds").distinct().size == 5)
        check(initial.strings("q33AlphaTrackFiles") == listOf("AlphaA1.mp3", "AlphaA2.mp3", "AlphaA3.mp3"))
        check(initial.strings("q33AlphaTrackIds").distinct().size == 3)

        check(initial.strings("q33TitleSearchFiles") == listOf("AlphaA3.mp3", "AlphaA1.mp3", "AlphaA2.mp3"))
        check(initial.strings("q33ArtistSearchFiles") == listOf("AlphaB1.mp3"))
        check(initial.getInt("q33ArtistSearchAlbums") == 1)
        check(initial.getInt("q33ArtistSearchArtists") == 1)
        check(initial.strings("q33AlbumSearchFiles").toSet() == setOf("Beta.mp3", "TwinA.mp3", "TwinB.mp3"))
        check(initial.getInt("q33AlbumSearchAlbums") == 1)
        check(initial.strings("q33TwinFiles").toSet() == setOf("TwinA.mp3", "TwinB.mp3"))
        check(initial.strings("q33TwinIds").distinct().size == 2)
        check(initial.getInt("q33NoMatchTotal") == 0)
        check(initial.getInt("q33BlankTotal") == 0)
        check(initial.getString("q33SelectedId") == initial.getString("q33SelectedMediaId"))
        check(initial.getString("q33SelectedUri") == initial.getString("q33SelectedMediaUri"))

        device.executeShellCommand("am force-stop $PACKAGE_NAME")
        val launchIntent = requireNotNull(
            instrumentation.targetContext.packageManager.getLaunchIntentForPackage(PACKAGE_NAME),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.targetContext.startActivity(launchIntent)
        check(device.wait(Until.hasObject(By.text("Songs")), UI_TIMEOUT_MS))
        device.wait(Until.gone(By.text("Updating library")), REFRESH_TIMEOUT_MS)

        requireNotNull(device.findObject(By.desc("Sort songs"))).click()
        requireNotNull(device.wait(Until.findObject(By.text("Title")), UI_TIMEOUT_MS)).click()
        device.waitForIdle()
        val titleFirst = requireNotNull(device.findObject(By.text("Alpha A Disc Two")))
        val titleSecond = requireNotNull(device.findObject(By.text("Alpha A One")))
        check(titleFirst.visibleBounds.centerY() < titleSecond.visibleBounds.centerY())
        requireNotNull(device.findObject(By.desc("Sort songs"))).click()
        requireNotNull(device.wait(Until.findObject(By.text("Album")), UI_TIMEOUT_MS)).click()
        device.waitForIdle()

        requireNotNull(device.findObject(By.desc("Search library"))).click()
        check(device.wait(Until.hasObject(By.text("Search your library")), UI_TIMEOUT_MS))
        setSearchText(device, "Alpha A")
        val albumFirst = requireNotNull(device.wait(Until.findObject(By.text("Alpha A One")), UI_TIMEOUT_MS))
        val albumThird = requireNotNull(device.findObject(By.text("Alpha A Disc Two")))
        check(albumFirst.visibleBounds.centerY() < albumThird.visibleBounds.centerY())
        listOf("a", "al", "album", "zzz").forEach { setSearchText(device, it) }
        check(device.wait(Until.hasObject(By.text("No matches")), UI_TIMEOUT_MS)) {
            "Rapid replacement did not settle on the final no-match query"
        }

        setSearchText(device, "Alpha B One")
        requireNotNull(device.wait(Until.findObject(By.text("Alpha B One")), UI_TIMEOUT_MS)).click()
        val expectedSelectedId = requireNotNull(initial.getString("q33SelectedId"))
        val expectedSelectedUri = requireNotNull(initial.getString("q33SelectedUri"))
        val selectionDeadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        var selected = resolver.call(probeUri, "q3.3-selection", null, null)
        while (selected?.getString("q33CurrentId") != expectedSelectedId &&
            SystemClock.elapsedRealtime() < selectionDeadline
        ) {
            SystemClock.sleep(100L)
            selected = resolver.call(probeUri, "q3.3-selection", null, null)
        }
        val selectedState = requireNotNull(selected)
        check(selectedState.getString("q33CurrentId") == expectedSelectedId)
        check(selectedState.getString("q33CurrentUri") == expectedSelectedUri)

        setSearchText(device, "Twin")
        check(waitForExactTextCount(device, "Twin", 2, UI_TIMEOUT_MS))
        val twinFiles = initial.strings("q33TwinFiles")
        val twinUris = initial.strings("q33TwinUris")
        val twinBUri = twinUris[twinFiles.indexOf("TwinB.mp3")]
        device.executeShellCommand("rm -f /sdcard/Music/LibrePlayerQ32/TwinB.mp3")
        device.executeShellCommand("content delete --uri $twinBUri")
        val removed = requireNotNull(resolver.call(probeUri, "q3.3-sync", null, null))
        check(removed.getInt("q33SongCount") == 9)
        check(removed.strings("q33TwinFiles") == listOf("TwinA.mp3"))
        check(waitForExactTextCount(device, "Twin", 1, UI_TIMEOUT_MS)) {
            "Active-query results did not update after the accepted repository refresh"
        }

        setSearchText(device, "")
        check(device.wait(Until.hasObject(By.text("Search your library")), UI_TIMEOUT_MS))
        requireNotNull(device.findObject(By.text("Back"))).click()
        check(device.wait(Until.hasObject(By.text("Songs")), UI_TIMEOUT_MS))
        check(device.hasObject(By.text("Track 00006"))) {
            "Clearing search did not restore current browse membership under the active Album sort"
        }
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

    private fun waitForExactTextCount(
        device: UiDevice,
        text: String,
        expected: Int,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (device.findObjects(By.text(text).clazz("android.widget.TextView")).size == expected) return true
            SystemClock.sleep(100L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun albumIndex(
        titles: Array<String>,
        artists: Array<String>,
        title: String,
        artist: String,
    ): Int {
        val matches = titles.indices.filter { index -> titles[index] == title && artists[index] == artist }
        return matches.single()
    }

    private fun requireArgument(arguments: Bundle, name: String): String =
        requireNotNull(arguments.getString(name)) { "Missing instrumentation argument: $name" }

    private companion object {
        const val PACKAGE_NAME = "com.libreplayer"
        const val FILE_SEPARATOR = "\u001F"
        const val UI_TIMEOUT_MS = 15_000L
        const val REFRESH_TIMEOUT_MS = 30_000L
        const val UI_OBJECT_RETRIES = 20
        const val UI_OBJECT_RETRY_MS = 50L
    }
}
