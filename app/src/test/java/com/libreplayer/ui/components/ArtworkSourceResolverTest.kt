package com.libreplayer.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArtworkSourceResolverTest {
    @Test
    fun `selectCandidates prefers stored artwork then file fallback`() {
        assertThat(
            ArtworkSourceResolver.selectCandidates(
                artworkUri = "content://media/albumart/7",
                fallbackArtworkUri = "content://media/audio/7",
            ),
        ).containsExactly(
            "content://media/albumart/7",
            "content://media/audio/7",
        ).inOrder()
    }

    @Test
    fun `selectCandidates de-duplicates identical values`() {
        assertThat(
            ArtworkSourceResolver.selectCandidates(
                artworkUri = "content://media/audio/7",
                fallbackArtworkUri = "content://media/audio/7",
            ),
        ).containsExactly("content://media/audio/7")
    }

    @Test
    fun `selectCandidates returns empty list when placeholder should be used`() {
        assertThat(
            ArtworkSourceResolver.selectCandidates(
                artworkUri = null,
                fallbackArtworkUri = "   ",
            ),
        ).isEmpty()
    }
}
