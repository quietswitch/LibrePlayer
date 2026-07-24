package com.libreplayer.util

import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.SearchResult
import com.libreplayer.data.repository.Song

object LibrarySearchEngine {
    fun search(
        query: String,
        songs: List<Song>,
        albums: List<Album>,
        artists: List<Artist>,
    ): SearchResult {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return SearchResult()

        fun String?.matches(): Boolean = !this.isNullOrBlank() && lowercase().contains(normalized)

        val matchedSongs = songs.filter { song ->
            song.resolvedTitle.lowercase().contains(normalized) ||
                song.artist.matches() ||
                song.album.matches() ||
                song.displayName.lowercase().contains(normalized)
        }
        val matchedAlbums = albums.filter { album ->
            album.title.lowercase().contains(normalized) ||
                album.artist.matches()
        }
        val matchedArtists = artists.filter { artist ->
            artist.name.lowercase().contains(normalized)
        }

        return SearchResult(
            songs = matchedSongs,
            albums = matchedAlbums,
            artists = matchedArtists,
        )
    }
}

