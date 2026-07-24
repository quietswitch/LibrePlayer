package com.libreplayer.data.repository

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.database.entity.SongEntity
import org.junit.Test

class PartialLibraryScanMergeTest {
    @Test
    fun `partial scan preserves cached MediaStore song and favorite while adding SAF song`() {
        val cachedFavorite = songEntity(
            id = "media:1",
            sourceType = SongSourceType.MEDIA_STORE,
            isFavorite = true,
        )
        val importedSong = songEntity(
            id = "document:2",
            sourceType = SongSourceType.DOCUMENT,
        )

        val merged = mergeScanSongEntities(
            scannedSongs = listOf(importedSong),
            cachedMediaStoreSongs = listOf(cachedFavorite),
            isMediaStoreComplete = false,
        )

        assertThat(merged.map { it.id }).containsExactly("media:1", "document:2").inOrder()
        assertThat(merged.first { it.id == "media:1" }.isFavorite).isTrue()
    }

    @Test
    fun `complete scan replaces previously cached MediaStore rows`() {
        val cachedSong = songEntity(
            id = "media:old",
            sourceType = SongSourceType.MEDIA_STORE,
        )
        val currentSong = songEntity(
            id = "media:current",
            sourceType = SongSourceType.MEDIA_STORE,
        )

        val merged = mergeScanSongEntities(
            scannedSongs = listOf(currentSong),
            cachedMediaStoreSongs = listOf(cachedSong),
            isMediaStoreComplete = true,
        )

        assertThat(merged.map { it.id }).containsExactly("media:current")
    }
}

private fun songEntity(
    id: String,
    sourceType: SongSourceType,
    isFavorite: Boolean = false,
) = SongEntity(
    id = id,
    sourceType = sourceType.name,
    contentUri = "content://audio/$id",
    title = id,
    artist = "Artist",
    album = "Album",
    durationMs = 60_000L,
    trackNumber = 1,
    discNumber = 1,
    year = 2026,
    dateAddedEpochSeconds = 1L,
    dateModifiedEpochSeconds = 1L,
    displayName = "$id.mp3",
    relativePath = null,
    mimeType = "audio/mpeg",
    artworkUri = null,
    isFavorite = isFavorite,
    titleSortKey = id,
    artistSortKey = "artist",
    albumSortKey = "album",
)
