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

    @Test
    fun `initial and explicit rebuilds are full reconciliations`() {
        assertThat(
            shouldPerformFullReconciliation(
                forceRebuild = false,
                cachedSongCount = 0,
                lastFullReconciliationAtMillis = 0L,
                nowMillis = 1_000L,
            ),
        ).isTrue()
        assertThat(
            shouldPerformFullReconciliation(
                forceRebuild = true,
                cachedSongCount = 100,
                lastFullReconciliationAtMillis = 900L,
                nowMillis = 1_000L,
            ),
        ).isTrue()
    }

    @Test
    fun `recent authoritative scan allows incremental refresh`() {
        assertThat(
            shouldPerformFullReconciliation(
                forceRebuild = false,
                cachedSongCount = 100,
                lastFullReconciliationAtMillis = 900L,
                nowMillis = 1_000L,
                intervalMillis = 1_000L,
            ),
        ).isFalse()
    }

    @Test
    fun `expired authority window forces reconciliation`() {
        assertThat(
            shouldPerformFullReconciliation(
                forceRebuild = false,
                cachedSongCount = 100,
                lastFullReconciliationAtMillis = 1_000L,
                nowMillis = 3_000L,
                intervalMillis = 1_000L,
            ),
        ).isTrue()
    }
}
