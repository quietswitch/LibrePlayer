package com.libreplayer.library.semantics

import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.LibrarySortOption
import com.libreplayer.data.repository.Song
import java.util.Locale

/** Q3.3 locale-independent presentation key. Stored metadata and identity are unchanged. */
internal fun String.normalizedSortKey(): String = trim().lowercase(Locale.ROOT)

/**
 * The product exposes no direction toggle. Text modes are ascending; duration is longest-first;
 * date added is newest-first. Every mode ends with the source-occurrence ID.
 */
internal fun songComparator(option: LibrarySortOption): Comparator<Song> =
    when (option) {
        LibrarySortOption.TITLE -> compareBy<Song> { it.resolvedTitle.normalizedSortKey() }
            .thenBy { it.resolvedArtist.normalizedSortKey() }
            .thenBy { it.resolvedAlbum.normalizedSortKey() }
            .thenBy(Song::id)
        LibrarySortOption.ARTIST -> compareBy<Song> { it.resolvedArtist.normalizedSortKey() }
            .thenBy { it.resolvedAlbum.normalizedSortKey() }
            .thenBy { it.resolvedTitle.normalizedSortKey() }
            .thenBy(Song::id)
        LibrarySortOption.ALBUM -> compareBy<Song> { it.resolvedAlbum.normalizedSortKey() }
            .thenBy { it.resolvedArtist.normalizedSortKey() }
            .thenBy { it.discNumber.missingOrdinalLast() }
            .thenBy { it.trackNumber.missingOrdinalLast() }
            .thenBy { it.resolvedTitle.normalizedSortKey() }
            .thenBy(Song::id)
        LibrarySortOption.DURATION -> compareByDescending<Song> { it.durationMs.positiveOrMissing() }
            .thenBy { it.resolvedTitle.normalizedSortKey() }
            .thenBy { it.resolvedArtist.normalizedSortKey() }
            .thenBy { it.resolvedAlbum.normalizedSortKey() }
            .thenBy(Song::id)
        LibrarySortOption.DATE_ADDED -> compareByDescending<Song> { it.dateAddedEpochSeconds.positiveOrMissing() }
            .thenBy { it.resolvedTitle.normalizedSortKey() }
            .thenBy { it.resolvedArtist.normalizedSortKey() }
            .thenBy { it.resolvedAlbum.normalizedSortKey() }
            .thenBy(Song::id)
    }

internal fun albumComparator(): Comparator<Album> =
    compareBy<Album> { it.title.normalizedSortKey() }
        .thenBy { resolvedTrackArtist(it.artist).normalizedSortKey() }
        .thenBy(Album::id)

internal fun artistComparator(): Comparator<Artist> =
    compareBy<Artist> { it.name.normalizedSortKey() }
        .thenBy(Artist::id)

/** Album members always use numeric disc/track order, independent of the global Song sort. */
internal fun albumTrackComparator(): Comparator<Song> =
    compareBy<Song> { it.discNumber.missingOrdinalLast() }
        .thenBy { it.trackNumber.missingOrdinalLast() }
        .thenBy { it.resolvedTitle.normalizedSortKey() }
        .thenBy(Song::id)

internal fun sortSongs(songs: List<Song>, option: LibrarySortOption): List<Song> =
    songs.sortedWith(songComparator(option))

internal fun sortAlbums(albums: List<Album>): List<Album> = albums.sortedWith(albumComparator())

internal fun sortArtists(artists: List<Artist>): List<Artist> = artists.sortedWith(artistComparator())

internal fun sortAlbumTracks(songs: List<Song>): List<Song> = songs.sortedWith(albumTrackComparator())

private fun Int?.missingOrdinalLast(): Int = this?.takeIf { it > 0 } ?: Int.MAX_VALUE

private fun Long.positiveOrMissing(): Long = takeIf { it > 0L } ?: Long.MIN_VALUE
