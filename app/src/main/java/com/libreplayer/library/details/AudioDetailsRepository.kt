package com.libreplayer.library.details

import com.libreplayer.data.repository.LibraryRepository
import com.libreplayer.data.repository.Song
import com.libreplayer.media.playback.PlaybackConnection

interface AudioDetailsRepository {
    suspend fun getAudioDetails(songId: String): AudioDetails?
}

class DefaultAudioDetailsRepository(
    private val libraryRepository: LibraryRepository,
    private val playbackConnection: PlaybackConnection,
    private val audioDetailsReader: AudioDetailsReader,
) : AudioDetailsRepository {
    override suspend fun getAudioDetails(songId: String): AudioDetails? {
        val librarySong = libraryRepository.getSongById(songId)
        val playbackSong = playbackConnection.findLoadedSong(songId)
        val song = mergeSongDetails(
            primary = librarySong,
            secondary = playbackSong,
        ) ?: return null

        return runCatching {
            audioDetailsReader.read(song)
        }.getOrElse {
            audioDetailsReader.fallback(song)
        }
    }
}

private fun mergeSongDetails(
    primary: Song?,
    secondary: Song?,
): Song? {
    if (primary == null) return secondary
    if (secondary == null) return primary
    return primary.copy(
        contentUri = primary.contentUri.ifBlank { secondary.contentUri },
        title = primary.title ?: secondary.title,
        artist = primary.artist ?: secondary.artist,
        album = primary.album ?: secondary.album,
        durationMs = primary.durationMs.takeIf { it > 0L } ?: secondary.durationMs,
        trackNumber = primary.trackNumber ?: secondary.trackNumber,
        discNumber = primary.discNumber ?: secondary.discNumber,
        year = primary.year ?: secondary.year,
        displayName = primary.displayName.ifBlank { secondary.displayName },
        relativePath = primary.relativePath ?: secondary.relativePath,
        mimeType = primary.mimeType ?: secondary.mimeType,
        artworkUri = primary.artworkUri ?: secondary.artworkUri,
    )
}
