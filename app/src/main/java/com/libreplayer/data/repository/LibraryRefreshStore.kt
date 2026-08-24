package com.libreplayer.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.libreplayer.library.scanner.MediaStoreCheckpoint
import com.libreplayer.util.appPreferencesDataStore
import kotlinx.coroutines.flow.first

class LibraryRefreshStore(context: Context) {
    private val dataStore = context.appPreferencesDataStore

    suspend fun lastSuccessfulRefreshAtMillis(): Long =
        dataStore.data.first()[KEY_LAST_SUCCESSFUL_REFRESH_AT_MILLIS] ?: 0L

    suspend fun mediaStoreCheckpoint(): MediaStoreCheckpoint? {
        val preferences = dataStore.data.first()
        val version = preferences[KEY_MEDIA_STORE_VERSION] ?: return null
        val generation = preferences[KEY_MEDIA_STORE_GENERATION] ?: return null
        return MediaStoreCheckpoint(version, generation)
    }

    suspend fun markSuccessfulSync(
        checkpoint: MediaStoreCheckpoint?,
        fullReconciliation: Boolean,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_SUCCESSFUL_REFRESH_AT_MILLIS] = atMillis
            if (checkpoint != null) {
                preferences[KEY_MEDIA_STORE_VERSION] = checkpoint.version
                preferences[KEY_MEDIA_STORE_GENERATION] = checkpoint.generation
            } else {
                preferences.remove(KEY_MEDIA_STORE_VERSION)
                preferences.remove(KEY_MEDIA_STORE_GENERATION)
            }
            if (fullReconciliation) {
                preferences[KEY_LAST_FULL_RECONCILIATION_AT_MILLIS] = atMillis
            }
        }
    }

    suspend fun lastFullReconciliationAtMillis(): Long =
        dataStore.data.first()[KEY_LAST_FULL_RECONCILIATION_AT_MILLIS] ?: 0L

    private companion object {
        val KEY_LAST_SUCCESSFUL_REFRESH_AT_MILLIS =
            longPreferencesKey("library_last_successful_refresh_at_millis")
        val KEY_LAST_FULL_RECONCILIATION_AT_MILLIS =
            longPreferencesKey("library_last_full_reconciliation_at_millis")
        val KEY_MEDIA_STORE_VERSION = stringPreferencesKey("library_media_store_version")
        val KEY_MEDIA_STORE_GENERATION = longPreferencesKey("library_media_store_generation")
    }
}
