package com.libreplayer.library.scanner

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.SongSourceType
import org.junit.Test

class ScannedSongDeduperTest {
    @Test
    fun `dedupe prefers MediaStore entry for the same underlying file`() {
        val mediaStoreSong = scannedSong(
            id = "media:123",
            sourceType = SongSourceType.MEDIA_STORE,
            contentUri = "content://media/external/audio/media/123",
            displayName = "Track.mp3",
            relativePath = "Music/Album/",
            title = "Track",
            artist = "Artist",
            album = null,
            mimeType = "audio/mpeg",
        )
        val safSong = scannedSong(
            id = "document:tree-track",
            sourceType = SongSourceType.DOCUMENT,
            contentUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum%2FTrack.mp3",
            displayName = "Track.mp3",
            relativePath = "/tree/primary:Music/document/primary:Music/Album/Track.mp3",
            title = "Track",
            artist = "Artist",
            album = "Album",
            mimeType = "audio/mpeg",
        )

        val deduped = ScannedSongDeduper.dedupe(listOf(mediaStoreSong, safSong))

        assertThat(deduped).hasSize(1)
        assertThat(deduped.single().id).isEqualTo("media:123")
        assertThat(deduped.single().sourceType).isEqualTo(SongSourceType.MEDIA_STORE)
        assertThat(deduped.single().album).isEqualTo("Album")
    }

    @Test
    fun `dedupe does not merge different songs that only share a filename`() {
        val first = scannedSong(
            id = "media:1",
            sourceType = SongSourceType.MEDIA_STORE,
            contentUri = "content://media/external/audio/media/1",
            displayName = "Intro.mp3",
            relativePath = null,
            title = "Intro",
            artist = "Artist A",
            album = "Album One",
            durationMs = 31_000L,
        )
        val second = scannedSong(
            id = "document:2",
            sourceType = SongSourceType.DOCUMENT,
            contentUri = "content://com.android.externalstorage.documents/document/primary%3AOther%2FIntro.mp3",
            displayName = "Intro.mp3",
            relativePath = null,
            title = "Intro",
            artist = "Artist B",
            album = "Album Two",
            durationMs = 31_000L,
        )

        val deduped = ScannedSongDeduper.dedupe(listOf(first, second))

        assertThat(deduped).hasSize(2)
    }

    @Test
    fun `dedupe does not merge distinct occurrences from metadata equality`() {
        val first = scannedSong(
            id = "media:1",
            sourceType = SongSourceType.MEDIA_STORE,
            contentUri = "content://media/external/audio/media/1",
            displayName = "Twin.mp3",
            relativePath = null,
            title = "Twin",
            artist = "Identity Artist",
            album = "Identity Album",
            durationMs = 31_000L,
        )
        val second = first.copy(
            id = "media:2",
            contentUri = "content://media/external/audio/media/2",
        )

        assertThat(ScannedSongDeduper.dedupe(listOf(first, second))).hasSize(2)
    }

    @Test
    fun `dedupe preserves matching paths on different storage volumes`() {
        val primary = scannedSong(
            id = "document:primary",
            sourceType = SongSourceType.DOCUMENT,
            contentUri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2FTrack.mp3",
            displayName = "Track.mp3",
            relativePath = null,
        )
        val secondary = primary.copy(
            id = "document:secondary",
            contentUri = "content://com.android.externalstorage.documents/document/1234-5678%3AMusic%2FTrack.mp3",
        )

        assertThat(ScannedSongDeduper.dedupe(listOf(primary, secondary))).hasSize(2)
    }

    @Test
    fun `dedupe preserves alternate artwork metadata when preferred row has none`() {
        val mediaStoreSong = scannedSong(
            id = "media:123",
            sourceType = SongSourceType.MEDIA_STORE,
            contentUri = "content://media/external/audio/media/123",
            displayName = "Track.flac",
            relativePath = "Music/Album/",
            title = "Track",
            artist = "Artist",
            album = "Album",
            mimeType = "audio/flac",
            artworkUri = null,
        )
        val safSong = scannedSong(
            id = "document:tree-track",
            sourceType = SongSourceType.DOCUMENT,
            contentUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum%2FTrack.flac",
            displayName = "Track.flac",
            relativePath = "/tree/primary:Music/document/primary:Music/Album/Track.flac",
            title = "Track",
            artist = "Artist",
            album = "Album",
            mimeType = "audio/flac",
            artworkUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum%2FTrack.flac",
        )

        val deduped = ScannedSongDeduper.dedupe(listOf(mediaStoreSong, safSong))

        assertThat(deduped).hasSize(1)
        assertThat(deduped.single().id).isEqualTo("media:123")
        assertThat(deduped.single().artworkUri).isEqualTo(safSong.artworkUri)
    }
}

private fun scannedSong(
    id: String,
    sourceType: SongSourceType,
    contentUri: String,
    displayName: String,
    relativePath: String?,
    title: String? = null,
    artist: String? = null,
    album: String? = null,
    durationMs: Long = 240_000L,
    mimeType: String? = null,
    artworkUri: String? = null,
) = ScannedSong(
    id = id,
    sourceType = sourceType,
    contentUri = contentUri,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    trackNumber = 1,
    discNumber = 1,
    year = 2024,
    dateAddedEpochSeconds = 1_700_000_000L,
    dateModifiedEpochSeconds = 1_700_000_100L,
    displayName = displayName,
    relativePath = relativePath,
    mimeType = mimeType,
    artworkUri = artworkUri,
)
