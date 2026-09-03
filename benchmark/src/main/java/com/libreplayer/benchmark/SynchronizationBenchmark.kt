package com.libreplayer.benchmark

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    }
}
