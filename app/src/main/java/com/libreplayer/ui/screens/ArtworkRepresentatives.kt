package com.libreplayer.ui.screens

import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.Song
import java.util.Locale

internal data class RepresentativeArtwork(
    val artworkUri: String? = null,
    val fallbackArtworkUri: String? = null,
)

internal fun enrichAlbumsWithArtwork(
    albums: List<Album>,
    songs: List<Song>,
): List<Album> {
    val albumArtwork = songs
        .groupBy(::albumKey)
        .mapValues { (_, groupedSongs) -> selectRepresentativeArtwork(groupedSongs) }
    return albums.map { album ->
        val representative = albumArtwork[album.id] ?: RepresentativeArtwork()
        album.copy(
            artworkUri = album.artworkUri.orPreferred(representative.artworkUri),
            artworkFallbackUri = mergeFallbackArtwork(
                primaryArtworkUri = album.artworkUri,
                representative = representative,
            ),
        )
    }
}

internal fun enrichArtistsWithArtwork(
    artists: List<Artist>,
    songs: List<Song>,
): List<Artist> {
    val artistArtwork = songs
        .groupBy(::artistKey)
        .mapValues { (_, groupedSongs) -> selectRepresentativeArtwork(groupedSongs) }
    return artists.map { artist ->
        val representative = artistArtwork[artist.id] ?: RepresentativeArtwork()
        artist.copy(
            artworkUri = artist.artworkUri.orPreferred(representative.artworkUri),
            artworkFallbackUri = mergeFallbackArtwork(
                primaryArtworkUri = artist.artworkUri,
                representative = representative,
            ),
        )
    }
}

internal fun selectRepresentativeArtwork(songs: List<Song>): RepresentativeArtwork {
    val representativeSong = songs
        .asSequence()
        .filter { song ->
            !song.artworkUri.isNullOrBlank() || song.contentUri.isNotBlank()
        }
        .minWithOrNull(representativeSongComparator)
        ?: return RepresentativeArtwork()

    val primaryArtworkUri = representativeSong.artworkUri.orPreferred(representativeSong.contentUri)
    val fallbackArtworkUri = if (
        !representativeSong.artworkUri.isNullOrBlank() &&
        representativeSong.contentUri.isNotBlank() &&
        representativeSong.contentUri != representativeSong.artworkUri
    ) {
        representativeSong.contentUri
    } else {
        null
    }

    return RepresentativeArtwork(
        artworkUri = primaryArtworkUri,
        fallbackArtworkUri = fallbackArtworkUri,
    )
}

private val representativeSongComparator: Comparator<Song> =
    compareBy<Song>(
        { if (!it.artworkUri.isNullOrBlank()) 0 else 1 },
        { it.resolvedAlbum.normalizedArtworkKey() },
        { it.discNumber ?: Int.MAX_VALUE },
        { it.trackNumber ?: Int.MAX_VALUE },
        { it.resolvedTitle.normalizedArtworkKey() },
        { it.contentUri.normalizedArtworkKey() },
    )

private fun mergeFallbackArtwork(
    primaryArtworkUri: String?,
    representative: RepresentativeArtwork,
): String? =
    when {
        representative.artworkUri == null -> null
        primaryArtworkUri.isNullOrBlank() -> representative.fallbackArtworkUri
        representative.artworkUri != primaryArtworkUri -> representative.artworkUri
        representative.fallbackArtworkUri != primaryArtworkUri -> representative.fallbackArtworkUri
        else -> null
    }

private fun String?.orPreferred(alternate: String?): String? =
    takeIf { !it.isNullOrBlank() } ?: alternate?.takeIf(String::isNotBlank)

private fun String.normalizedArtworkKey(): String = trim().lowercase(Locale.US)
