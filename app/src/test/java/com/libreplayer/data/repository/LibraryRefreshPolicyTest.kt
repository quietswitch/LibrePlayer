package com.libreplayer.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibraryRefreshPolicyTest {
    @Test
    fun `force refresh always scans`() {
        assertThat(
            shouldRefreshLibrary(
                forceRefresh = true,
                cachedSongCount = 250,
                lastSuccessfulRefreshAtMillis = 100L,
                nowMillis = 200L,
            ),
        ).isTrue()
    }

    @Test
    fun `empty cache refreshes immediately`() {
        assertThat(
            shouldRefreshLibrary(
                forceRefresh = false,
                cachedSongCount = 0,
                lastSuccessfulRefreshAtMillis = 0L,
                nowMillis = 200L,
            ),
        ).isTrue()
    }

    @Test
    fun `recent cached library skips automatic refresh`() {
        assertThat(
            shouldRefreshLibrary(
                forceRefresh = false,
                cachedSongCount = 500,
                lastSuccessfulRefreshAtMillis = 1_000L,
                nowMillis = 2_000L,
                refreshIntervalMillis = 5_000L,
            ),
        ).isFalse()
    }

    @Test
    fun `stale cached library refreshes in background`() {
        assertThat(
            shouldRefreshLibrary(
                forceRefresh = false,
                cachedSongCount = 500,
                lastSuccessfulRefreshAtMillis = 1_000L,
                nowMillis = 8_000L,
                refreshIntervalMillis = 5_000L,
            ),
        ).isTrue()
    }
}
