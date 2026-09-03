package com.libreplayer.settings

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.LibrarySortOption
import org.junit.Test

class SettingsSortSemanticsTest {
    @Test
    fun `persisted Song sort accepts supported values and defaults invalid values safely`() {
        LibrarySortOption.entries.forEach { option ->
            assertThat(persistedLibrarySortOption(option.name)).isEqualTo(option)
        }
        assertThat(persistedLibrarySortOption(null)).isEqualTo(LibrarySortOption.TITLE)
        assertThat(persistedLibrarySortOption("REMOVED_OLD_VALUE")).isEqualTo(LibrarySortOption.TITLE)
    }
}
