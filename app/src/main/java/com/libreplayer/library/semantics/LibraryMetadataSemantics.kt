package com.libreplayer.library.semantics

import java.util.Locale

internal const val UNKNOWN_ARTIST = "Unknown artist"
internal const val UNKNOWN_ALBUM = "Unknown album"

internal data class TrackDiscNumbers(
    val trackNumber: Int?,
    val discNumber: Int?,
)

/** Android MediaStore encodes disc and track as disc * 1000 + track. */
internal fun decodeMediaStoreTrackNumber(rawValue: Int): TrackDiscNumbers {
    if (rawValue <= 0) return TrackDiscNumbers(trackNumber = null, discNumber = null)
    return TrackDiscNumbers(
        trackNumber = (rawValue % 1_000).takeIf { it > 0 },
        discNumber = (rawValue / 1_000).takeIf { it > 0 },
    )
}

/** MetadataRetriever may expose an ordinal as either `n` or `n/total`. */
internal fun parseMetadataOrdinal(rawValue: String?): Int? =
    rawValue
        ?.trim()
        ?.substringBefore('/')
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

/** The core model stores year precision only; full dates are not inferred. */
internal fun parseMetadataYear(rawValue: String?): Int? =
    rawValue
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it in 1..9_999 }

internal fun resolvedSongTitle(title: String?, displayName: String): String =
    title?.takeIf(String::isNotBlank) ?: displayName.substringBeforeLast('.')

internal fun resolvedTrackArtist(artist: String?): String =
    artist?.takeIf(String::isNotBlank) ?: UNKNOWN_ARTIST

internal fun resolvedAlbumTitle(album: String?): String =
    album?.takeIf(String::isNotBlank) ?: UNKNOWN_ALBUM

internal fun artistGroupingKey(trackArtist: String?): String =
    "artist:${trackArtist.groupingComponent()}"

internal fun albumGroupingArtist(albumArtist: String?, trackArtist: String?): String? =
    albumArtist?.takeIf(String::isNotBlank) ?: trackArtist?.takeIf(String::isNotBlank)

internal fun albumGroupingKey(
    album: String?,
    albumArtist: String?,
    trackArtist: String?,
): String =
    "album:${album.groupingComponent()}|" +
        "artist:${albumGroupingArtist(albumArtist, trackArtist).groupingComponent()}"

internal fun String.normalizedGroupingKey(): String = trim().lowercase(Locale.US)

/** Length-prefixing makes tuple identity collision-free even when metadata contains separators. */
private fun String?.groupingComponent(): String {
    val normalized = this
        ?.takeIf(String::isNotBlank)
        ?.normalizedGroupingKey()
        ?: return "missing"
    return "value:${normalized.length}:$normalized"
}
