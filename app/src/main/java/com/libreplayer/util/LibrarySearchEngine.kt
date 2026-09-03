package com.libreplayer.util

import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.SearchResult
import com.libreplayer.data.repository.Song
import com.libreplayer.library.semantics.normalizedSortKey

object LibrarySearchEngine {
    fun search(
        query: String,
        songs: List<Song>,
        albums: List<Album>,
        artists: List<Artist>,
    ): SearchResult {
        val normalizedQuery = normalizeSearchQuery(query)
        if (normalizedQuery.isBlank()) return SearchResult()

        fun String.matches(): Boolean = normalizedSortKey().contains(normalizedQuery)

        val matchedSongs = songs.filter { song ->
            song.resolvedTitle.matches() ||
                song.resolvedArtist.matches() ||
                song.resolvedAlbum.matches() ||
                song.displayName.matches()
        }
        val matchedAlbums = albums.filter { album ->
            album.title.matches() ||
                album.artist?.matches() == true
        }
        val matchedArtists = artists.filter { artist ->
            artist.name.matches()
        }

        return SearchResult(
            songs = matchedSongs,
            albums = matchedAlbums,
            artists = matchedArtists,
        )
    }

    internal fun normalizeSearchQuery(query: String): String = query.normalizedSortKey()
}
