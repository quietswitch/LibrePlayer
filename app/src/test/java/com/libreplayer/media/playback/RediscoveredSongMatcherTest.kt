package com.libreplayer.media.playback

import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RediscoveredSongMatcherTest {
    @Test
    fun `returns unique rediscovered song with changed id and uri`() {
        val stale = song(
            id = "media:10",
            uri = "content://media/external/audio/media/10",
        )
        val replacement = song(
            id = "media:25",
            uri = "content://media/external/audio/media/25",
        )

        assertEquals(
            replacement,
            findUniqueRediscoveredSong(stale, listOf(replacement)),
        )
    }

    @Test
    fun `does not guess when multiple songs match`() {
        val stale = song(
            id = "media:10",
            uri = "content://media/external/audio/media/10",
        )
        val first = song(
            id = "media:25",
            uri = "content://media/external/audio/media/25",
        )
        val second = song(
            id = "media:26",
            uri = "content://media/external/audio/media/26",
        )

        assertNull(
            findUniqueRediscoveredSong(stale, listOf(first, second)),
        )
    }

    @Test
    fun `does not match a different file`() {
        val stale = song(
            id = "media:10",
            uri = "content://media/external/audio/media/10",
        )
        val different = song(
            id = "media:25",
            uri = "content://media/external/audio/media/25",
            displayName = "Different Song.mp3",
            title = "Different Song",
        )

        assertNull(
            findUniqueRediscoveredSong(stale, listOf(different)),
        )
    }

    private fun song(
        id: String,
        uri: String,
        displayName: String = "I Think He Knows.mp3",
        title: String = "I Think He Knows",
    ): Song =
        Song(
            id = id,
            sourceType = SongSourceType.MEDIA_STORE,
            contentUri = uri,
            title = title,
            artist = "Taylor Swift",
            album = "Lover",
            durationMs = 173_386L,
            trackNumber = 6,
            discNumber = null,
            year = 2019,
            dateAddedEpochSeconds = 0L,
            dateModifiedEpochSeconds = 0L,
            displayName = displayName,
            relativePath = "Music/Taylor Swift/Lover/",
            mimeType = "audio/mpeg",
            artworkUri = null,
            isFavorite = false,
        )
}
