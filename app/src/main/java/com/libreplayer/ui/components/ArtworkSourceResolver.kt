package com.libreplayer.ui.components

internal enum class ArtworkVariant(val targetSizePx: Int) {
    LIST(160),
    MINI(144),
    FULL(768),
}

internal data class ArtworkRequest(
    val artworkUri: String?,
    val fallbackArtworkUri: String?,
    val variant: ArtworkVariant,
) {
    val candidateUris: List<String> =
        ArtworkSourceResolver.selectCandidates(
            artworkUri = artworkUri,
            fallbackArtworkUri = fallbackArtworkUri,
        )

    val cacheKey: String =
        buildString {
            append(variant.name)
            candidateUris.forEach { candidate ->
                append('|')
                append(candidate)
            }
        }
}

internal object ArtworkSourceResolver {
    fun selectCandidates(
        artworkUri: String?,
        fallbackArtworkUri: String?,
    ): List<String> =
        buildList {
            addIfValid(artworkUri)
            addIfValid(fallbackArtworkUri)
        }

    private fun MutableList<String>.addIfValid(candidate: String?) {
        val normalized = candidate?.trim()?.takeIf(String::isNotBlank) ?: return
        if (contains(normalized)) return
        add(normalized)
    }
}
