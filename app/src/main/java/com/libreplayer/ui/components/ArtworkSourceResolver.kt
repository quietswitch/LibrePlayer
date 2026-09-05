package com.libreplayer.ui.components

import com.libreplayer.data.repository.ArtworkCandidate

internal enum class ArtworkVariant(val targetSizePx: Int) {
    LIST(160),
    MINI(144),
    FULL(768),
}

internal data class ArtworkRequest(
    val candidates: List<ArtworkCandidate>,
    val variant: ArtworkVariant,
) {
    val cacheKey: String = ArtworkSourceResolver.cacheKey(candidates, variant)
}

internal object ArtworkSourceResolver {
    fun selectCandidates(
        artworkUri: String?,
        fallbackArtworkUri: String?,
        sourceRevisionEpochSeconds: Long,
    ): List<ArtworkCandidate> =
        mergeCandidates(
            listOfNotNull(
                artworkUri.toCandidate(sourceRevisionEpochSeconds),
                fallbackArtworkUri.toCandidate(sourceRevisionEpochSeconds),
            ),
        )

    /** Preserves semantic candidate order while folding duplicate locators to their newest revision. */
    fun mergeCandidates(candidates: List<ArtworkCandidate>): List<ArtworkCandidate> {
        val byUri = LinkedHashMap<String, ArtworkCandidate>()
        candidates.forEach { candidate ->
            val uri = candidate.uri.trim().takeIf(String::isNotBlank) ?: return@forEach
            val normalized = candidate.copy(uri = uri)
            val existing = byUri[uri]
            byUri[uri] = if (
                existing == null ||
                normalized.sourceRevisionEpochSeconds > existing.sourceRevisionEpochSeconds
            ) {
                normalized
            } else {
                existing
            }
        }
        return byUri.values.toList()
    }

    fun cacheKey(
        candidates: List<ArtworkCandidate>,
        variant: ArtworkVariant,
    ): String =
        buildString {
            append(variant.name)
            candidates.forEach { candidate ->
                append('|')
                append(candidate.uri.length)
                append(':')
                append(candidate.uri)
                append('@')
                append(candidate.sourceRevisionEpochSeconds)
            }
        }

    private fun String?.toCandidate(sourceRevisionEpochSeconds: Long): ArtworkCandidate? =
        this?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { uri -> ArtworkCandidate(uri, sourceRevisionEpochSeconds) }
}

internal object ArtworkDecodePolicy {
    fun calculateInSampleSize(
        width: Int,
        height: Int,
        targetSizePx: Int,
    ): Int {
        if (width <= 0 || height <= 0 || targetSizePx <= 0) return 1
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth / 2 >= targetSizePx && sampledHeight / 2 >= targetSizePx) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }
        return sampleSize
    }
}
