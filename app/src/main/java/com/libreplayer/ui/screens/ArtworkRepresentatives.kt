package com.libreplayer.ui.screens

import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.ArtworkCandidate
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.Song
import com.libreplayer.library.semantics.albumTrackComparator
import com.libreplayer.ui.components.ArtworkSourceResolver
import java.util.Locale

internal data class RepresentativeArtwork(
    val candidates: List<ArtworkCandidate> = emptyList(),
) {
    val artworkUri: String?
        get() = candidates.firstOrNull()?.uri

    val fallbackArtworkUri: String?
        get() = candidates.getOrNull(1)?.uri
}

internal fun enrichAlbumsWithArtwork(
    albums: List<Album>,
    songs: List<Song>,
): List<Album> {
    val albumArtwork = songs
        .groupBy(::albumKey)
        .mapValues { (_, groupedSongs) -> selectAlbumRepresentativeArtwork(groupedSongs) }
    return albums.map { album ->
        val representative = albumArtwork[album.id] ?: RepresentativeArtwork()
        album.copy(
            artworkUri = representative.artworkUri,
            artworkFallbackUri = representative.fallbackArtworkUri,
            artworkCandidates = representative.candidates,
        )
    }
}

internal fun enrichArtistsWithArtwork(
    artists: List<Artist>,
    songs: List<Song>,
): List<Artist> {
    val artistArtwork = songs
        .groupBy(::artistKey)
        .mapValues { (_, groupedSongs) -> selectArtistRepresentativeArtwork(groupedSongs) }
    return artists.map { artist ->
        val representative = artistArtwork[artist.id] ?: RepresentativeArtwork()
        artist.copy(
            artworkUri = representative.artworkUri,
            artworkFallbackUri = representative.fallbackArtworkUri,
            artworkCandidates = representative.candidates,
        )
    }
}

internal fun selectAlbumRepresentativeArtwork(songs: List<Song>): RepresentativeArtwork =
    selectRepresentativeArtwork(songs.sortedWith(albumTrackComparator()))

internal fun selectArtistRepresentativeArtwork(songs: List<Song>): RepresentativeArtwork =
    selectRepresentativeArtwork(songs.sortedWith(representativeArtistSongComparator))

private fun selectRepresentativeArtwork(songs: List<Song>): RepresentativeArtwork =
    RepresentativeArtwork(
        candidates = ArtworkSourceResolver.mergeCandidates(
            songs.flatMap { song ->
                ArtworkSourceResolver.selectCandidates(
                    artworkUri = song.artworkUri,
                    fallbackArtworkUri = song.contentUri,
                    sourceRevisionEpochSeconds = song.dateModifiedEpochSeconds,
                )
            },
        ),
    )

private val representativeArtistSongComparator: Comparator<Song> =
    compareBy<Song>(
        { it.resolvedAlbum.normalizedArtworkKey() },
        { it.discNumber ?: Int.MAX_VALUE },
        { it.trackNumber ?: Int.MAX_VALUE },
        { it.resolvedTitle.normalizedArtworkKey() },
        { it.id },
    )

private fun String.normalizedArtworkKey(): String = trim().lowercase(Locale.US)
