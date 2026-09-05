package com.libreplayer.ui.components

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.ArtworkCandidate
import org.junit.Test

class ArtworkSourceResolverTest {
    @Test
    fun `selectCandidates prefers stored artwork then file fallback`() {
        assertThat(
            ArtworkSourceResolver.selectCandidates(
                artworkUri = "content://media/albumart/7",
                fallbackArtworkUri = "content://media/audio/7",
                sourceRevisionEpochSeconds = 10L,
            ),
        ).containsExactly(
            ArtworkCandidate("content://media/albumart/7", 10L),
            ArtworkCandidate("content://media/audio/7", 10L),
        ).inOrder()
    }

    @Test
    fun `selectCandidates de-duplicates identical values`() {
        assertThat(
            ArtworkSourceResolver.selectCandidates(
                artworkUri = "content://media/audio/7",
                fallbackArtworkUri = "content://media/audio/7",
                sourceRevisionEpochSeconds = 10L,
            ),
        ).containsExactly(ArtworkCandidate("content://media/audio/7", 10L))
    }

    @Test
    fun `selectCandidates returns empty list when placeholder should be used`() {
        assertThat(
            ArtworkSourceResolver.selectCandidates(
                artworkUri = null,
                fallbackArtworkUri = "   ",
                sourceRevisionEpochSeconds = 10L,
            ),
        ).isEmpty()
    }

    @Test
    fun `mergeCandidates preserves order and keeps newest revision for duplicate locator`() {
        val merged = ArtworkSourceResolver.mergeCandidates(
            listOf(
                ArtworkCandidate("content://art/one", 1L),
                ArtworkCandidate("content://art/two", 2L),
                ArtworkCandidate("content://art/one", 3L),
            ),
        )

        assertThat(merged).containsExactly(
            ArtworkCandidate("content://art/one", 3L),
            ArtworkCandidate("content://art/two", 2L),
        ).inOrder()
    }

    @Test
    fun `cache identity changes with source revision and is collision safe`() {
        val before = ArtworkRequest(
            candidates = listOf(ArtworkCandidate("content://art/a|b", 1L)),
            variant = ArtworkVariant.LIST,
        )
        val after = ArtworkRequest(
            candidates = listOf(ArtworkCandidate("content://art/a|b", 2L)),
            variant = ArtworkVariant.LIST,
        )
        val differentBoundaries = ArtworkRequest(
            candidates = listOf(
                ArtworkCandidate("content://art/a", 1L),
                ArtworkCandidate("b", 1L),
            ),
            variant = ArtworkVariant.LIST,
        )

        assertThat(after.cacheKey).isNotEqualTo(before.cacheKey)
        assertThat(differentBoundaries.cacheKey).isNotEqualTo(before.cacheKey)
    }

    @Test
    fun `decode policy downsamples bounded large artwork for each surface`() {
        assertThat(ArtworkDecodePolicy.calculateInSampleSize(2_048, 2_048, 160)).isEqualTo(8)
        assertThat(ArtworkDecodePolicy.calculateInSampleSize(4_096, 4_096, 768)).isEqualTo(4)
        assertThat(ArtworkDecodePolicy.calculateInSampleSize(512, 512, 768)).isEqualTo(1)
    }
}
