package com.libreplayer.data.repository

import com.google.common.truth.Truth.assertThat
import com.libreplayer.library.scanner.ScannedSong
import org.junit.Test
import kotlin.system.measureNanoTime

class LibrarySyncPerformanceTest {
    @Test
    fun `synthetic 10000 song refresh operation counts`() {
        val original = syntheticLibrary(songCount = 10_000)
        val scenarios = listOf(
            scenario("full", emptyList(), original),
            scenario("unchanged", original, original),
            scenario("add-one", original, original + syntheticSong(10_000)),
            scenario(
                "add-album",
                original,
                original + (10_000 until 10_012).map { syntheticSong(it, album = "Added album") },
            ),
            scenario(
                "modify-one",
                original,
                original.toMutableList().apply {
                    this[5_000] = this[5_000].copy(title = "Retagged title", dateModifiedEpochSeconds = 2L)
                },
            ),
            scenario("delete-ten", original, original.dropLast(10)),
            // The planner has no playback dependency; this duplicates add-one while the playback
            // subsystem remains active in the application process.
            scenario("playback-active-add-one", original, original + syntheticSong(10_000)),
        )

        val results = scenarios.map { scenario ->
            val currentEntities = scenario.before.map { it.asEntity(isFavorite = false) }
            val afterEntities = scenario.after.map { it.asEntity(isFavorite = false) }
            lateinit var changes: LibraryDatabaseChanges
            val elapsedNanos = measureNanoTime {
                changes = prepareLibraryChanges(
                    scannedSongs = scenario.after,
                    currentSongs = currentEntities,
                    currentAlbums = buildAlbums(currentEntities),
                    currentArtists = buildArtists(currentEntities),
                )
            }
            PerformanceResult(
                name = scenario.name,
                elapsedMillis = elapsedNanos / 1_000_000.0,
                legacyRows = afterEntities.size + buildAlbums(afterEntities).size + buildArtists(afterEntities).size,
                incrementalRows = changes.writeCount(),
            )
        }

        results.forEach { result ->
            println(
                "LIBRARY_SYNC_PERF scenario=${result.name} plannerMs=${"%.3f".format(result.elapsedMillis)} " +
                    "legacyRows=${result.legacyRows} incrementalRows=${result.incrementalRows}",
            )
        }

        assertThat(results.single { it.name == "unchanged" }.incrementalRows).isEqualTo(0)
        assertThat(results.single { it.name == "add-one" }.incrementalRows).isEqualTo(3)
        assertThat(results.single { it.name == "add-album" }.incrementalRows).isEqualTo(14)
        assertThat(results.single { it.name == "modify-one" }.incrementalRows).isEqualTo(1)
        assertThat(results.single { it.name == "delete-ten" }.incrementalRows).isEqualTo(12)
        assertThat(results.single { it.name == "playback-active-add-one" }.incrementalRows).isEqualTo(3)
    }
}

private data class PerformanceScenario(
    val name: String,
    val before: List<ScannedSong>,
    val after: List<ScannedSong>,
)

private data class PerformanceResult(
    val name: String,
    val elapsedMillis: Double,
    val legacyRows: Int,
    val incrementalRows: Int,
)

private fun scenario(
    name: String,
    before: List<ScannedSong>,
    after: List<ScannedSong>,
) = PerformanceScenario(name, before, after)

private fun syntheticLibrary(songCount: Int): List<ScannedSong> =
    (0 until songCount).map(::syntheticSong)

private fun syntheticSong(
    index: Int,
    album: String = "Album ${index / 10}",
): ScannedSong =
    ScannedSong(
        id = "media:$index",
        sourceType = SongSourceType.MEDIA_STORE,
        contentUri = "content://media/external/audio/media/$index",
        title = "Song $index",
        artist = "Artist ${index / 100}",
        album = album,
        durationMs = 180_000L,
        trackNumber = index % 10 + 1,
        discNumber = 1,
        year = 2026,
        dateAddedEpochSeconds = index.toLong(),
        dateModifiedEpochSeconds = 1L,
        displayName = "song-$index.mp3",
        relativePath = "Music/Artist ${index / 100}/",
        mimeType = "audio/mpeg",
        artworkUri = "content://media/external/audio/albumart/${index / 10}",
    )

private fun LibraryDatabaseChanges.writeCount(): Int =
    songUpserts.size + deletedSongIds.size +
        albumUpserts.size + deletedAlbumIds.size +
        artistUpserts.size + deletedArtistIds.size
