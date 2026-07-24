package com.libreplayer.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.appPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "libreplayer_preferences",
)
