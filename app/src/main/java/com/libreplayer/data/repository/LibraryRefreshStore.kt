package com.libreplayer.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.libreplayer.util.appPreferencesDataStore
import kotlinx.coroutines.flow.first

class LibraryRefreshStore(context: Context) {
    private val dataStore = context.appPreferencesDataStore

    suspend fun lastSuccessfulRefreshAtMillis(): Long =
        dataStore.data.first()[KEY_LAST_SUCCESSFUL_REFRESH_AT_MILLIS] ?: 0L

    suspend fun markSuccessfulRefresh(atMillis: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_SUCCESSFUL_REFRESH_AT_MILLIS] = atMillis
        }
    }

    private companion object {
        val KEY_LAST_SUCCESSFUL_REFRESH_AT_MILLIS =
            longPreferencesKey("library_last_successful_refresh_at_millis")
    }
}
