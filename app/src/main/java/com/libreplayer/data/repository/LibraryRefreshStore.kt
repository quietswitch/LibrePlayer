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

    suspend fun markSuccessfulSync(
        fullReconciliation: Boolean,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_SUCCESSFUL_REFRESH_AT_MILLIS] = atMillis
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
    }
}
