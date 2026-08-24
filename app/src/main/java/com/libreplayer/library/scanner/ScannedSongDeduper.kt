package com.libreplayer.library.scanner

import com.libreplayer.data.repository.SongSourceType
import java.net.URLDecoder
import java.util.Locale

internal object ScannedSongDeduper {
    fun dedupe(songs: List<ScannedSong>): List<ScannedSong> {
        if (songs.size < 2) return songs

        val deduped = LinkedHashMap<String, ScannedSong>()
        songs.forEach { song ->
            val dedupeKey = canonicalPathKey(song)
                ?: conservativeMetadataKey(song)
                ?: "id:${song.id}"
            val existing = deduped[dedupeKey]
            deduped[dedupeKey] = if (existing == null) song else mergeDuplicate(existing, song)
        }
        return deduped.values.toList()
    }

    private fun mergeDuplicate(
        first: ScannedSong,
        second: ScannedSong,
    ): ScannedSong {
        val preferred = choosePreferred(first, second)
        val alternate = if (preferred == first) second else first
        return preferred.copy(
            title = preferred.title.orPreferred(alternate.title),
            artist = preferred.artist.orPreferred(alternate.artist),
            album = preferred.album.orPreferred(alternate.album),
            durationMs = preferred.durationMs.takeIf { it > 0L } ?: alternate.durationMs,
            trackNumber = preferred.trackNumber ?: alternate.trackNumber,
            discNumber = preferred.discNumber ?: alternate.discNumber,
            year = preferred.year ?: alternate.year,
            dateAddedEpochSeconds = preferred.dateAddedEpochSeconds.takeIf { it > 0L }
                ?: alternate.dateAddedEpochSeconds,
            dateModifiedEpochSeconds = maxOf(
                preferred.dateModifiedEpochSeconds,
                alternate.dateModifiedEpochSeconds,
            ),
            displayName = preferred.displayName.ifBlank { alternate.displayName },
            relativePath = preferred.relativePath.orPreferred(alternate.relativePath),
            mimeType = preferred.mimeType.orPreferred(alternate.mimeType),
            artworkUri = preferred.artworkUri.orPreferred(alternate.artworkUri),
        )
    }

    private fun choosePreferred(
        first: ScannedSong,
        second: ScannedSong,
    ): ScannedSong {
        val firstSourceRank = sourceRank(first.sourceType)
        val secondSourceRank = sourceRank(second.sourceType)
        if (firstSourceRank != secondSourceRank) {
            return if (firstSourceRank > secondSourceRank) first else second
        }

        val firstMetadataScore = metadataScore(first)
        val secondMetadataScore = metadataScore(second)
        if (firstMetadataScore != secondMetadataScore) {
            return if (firstMetadataScore >= secondMetadataScore) first else second
        }

        return if (first.dateModifiedEpochSeconds >= second.dateModifiedEpochSeconds) first else second
    }

    internal fun canonicalPathKey(song: ScannedSong): String? =
        when (song.sourceType) {
            SongSourceType.MEDIA_STORE -> song.relativePath
                ?.takeIf(String::isNotBlank)
                ?.let { buildPathKey(it, song.displayName) }

            SongSourceType.DOCUMENT -> canonicalDocumentPathKey(
                contentUri = song.contentUri,
                relativePath = song.relativePath,
                displayName = song.displayName,
            )
        }

    internal fun canonicalDocumentPathKey(
        contentUri: String,
        relativePath: String?,
        displayName: String,
    ): String? {
        val documentPath = extractDocumentPath(contentUri)
            ?: relativePath?.takeIf(String::isNotBlank)
        return documentPath?.let { buildPathKey(it, displayName) }
    }

    private fun buildPathKey(
        rawPath: String,
        displayName: String,
    ): String? {
        val normalizedPath = normalizeComparablePath(rawPath) ?: return null
        val normalizedDisplayName = displayName.normalizedValue() ?: return null
        val fullPath = when {
            normalizedPath == normalizedDisplayName -> normalizedPath
            normalizedPath.endsWith("/$normalizedDisplayName") -> normalizedPath
            else -> "${normalizedPath.trimEnd('/')}/$normalizedDisplayName"
        }
        return "path:$fullPath"
    }

    private fun extractDocumentPath(contentUri: String): String? {
        val decoded = decode(contentUri)
            .substringBefore('?')
            .replace('\\', '/')
            .trim()
        if (decoded.isBlank()) return null

        return when {
            decoded.contains("/document/") -> decoded.substringAfter("/document/")
            decoded.contains("/tree/") -> decoded.substringAfter("/tree/")
            else -> null
        }
    }

    private fun normalizeComparablePath(rawPath: String): String? {
        var path = decode(rawPath)
            .substringBefore('?')
            .replace('\\', '/')
            .trim()
        if (path.isBlank()) return null

        path = path.removePrefix("raw:/")
        path = path.removePrefix("/storage/emulated/0/")
        path = path.removePrefix("storage/emulated/0/")
        path = path.removePrefix("/")
        if (path.startsWith("primary:")) {
            path = path.removePrefix("primary:")
        }
        if (path.startsWith("document/")) {
            path = path.removePrefix("document/")
        }
        if (path.startsWith("tree/")) {
            path = path.removePrefix("tree/")
        }

        if (path.contains(':') && !path.startsWith("content://")) {
            val volumePrefix = path.substringBefore(':')
            if (!volumePrefix.contains('/')) {
                path = path.substringAfter(':')
            }
        }

        return path.trim('/')
            .lowercase(Locale.US)
            .takeIf { it.isNotBlank() && !it.startsWith("content://") }
    }

    private fun conservativeMetadataKey(song: ScannedSong): String? {
        val displayName = song.displayName.normalizedValue() ?: return null
        val artist = song.artist.normalizedValue() ?: return null
        val durationBucket = song.durationMs.takeIf { it > 0L }?.let { (it + 500L) / 1000L } ?: return null
        val title = song.title.normalizedValue() ?: displayName.substringBeforeLast('.')
        val album = song.album.normalizedValue().orEmpty()
        return "meta:$displayName|$title|$artist|$album|$durationBucket"
    }

    private fun metadataScore(song: ScannedSong): Int =
        listOf(song.title, song.artist, song.album, song.relativePath, song.mimeType, song.artworkUri)
            .count { !it.isNullOrBlank() } +
            listOf(song.trackNumber, song.discNumber, song.year).count { it != null } +
            if (song.durationMs > 0L) 1 else 0

    private fun sourceRank(sourceType: SongSourceType): Int =
        when (sourceType) {
            SongSourceType.MEDIA_STORE -> 2
            SongSourceType.DOCUMENT -> 1
        }

    private fun String?.normalizedValue(): String? =
        this?.trim()
            ?.takeIf(String::isNotBlank)
            ?.lowercase(Locale.US)

    private fun String?.orPreferred(alternate: String?): String? =
        takeIf { !it.isNullOrBlank() } ?: alternate?.takeIf { it.isNotBlank() }

    private fun decode(value: String): String =
        runCatching {
            URLDecoder.decode(value, "UTF-8")
        }.getOrDefault(value)
}
