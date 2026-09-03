package com.libreplayer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.LibrarySortOption
import com.libreplayer.data.repository.ThemeMode
import com.libreplayer.util.appPreferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val context: Context) {
    private val dataStore = context.appPreferencesDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            themeMode = preferences[KEY_THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            showFileNamesWhenMetadataMissing = preferences[KEY_SHOW_FILE_NAMES] ?: true,
            defaultSortOption = persistedLibrarySortOption(preferences[KEY_DEFAULT_SORT]),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setShowFileNamesWhenMetadataMissing(enabled: Boolean) {
        dataStore.edit { it[KEY_SHOW_FILE_NAMES] = enabled }
    }

    suspend fun setDefaultSortOption(option: LibrarySortOption) {
        dataStore.edit { it[KEY_DEFAULT_SORT] = option.name }
    }

    companion object {
        internal val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        internal val KEY_SHOW_FILE_NAMES = booleanPreferencesKey("show_file_names_when_metadata_missing")
        internal val KEY_DEFAULT_SORT = stringPreferencesKey("default_sort")
    }
}

internal fun persistedLibrarySortOption(value: String?): LibrarySortOption =
    value?.let { runCatching { LibrarySortOption.valueOf(it) }.getOrNull() }
        ?: LibrarySortOption.TITLE
