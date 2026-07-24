package com.libreplayer.media.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.libreplayer.util.appPreferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

data class PlaybackSnapshot(
    val queueIds: List<String> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val repeatMode: Int = 0,
    val shuffleEnabled: Boolean = false,
    val playWhenReady: Boolean = false,
)

class PlaybackSnapshotStore(private val context: Context) {
    private val dataStore = context.appPreferencesDataStore

    val snapshot: Flow<PlaybackSnapshot> = dataStore.data.map { preferences ->
        PlaybackSnapshot(
            queueIds = decodeQueue(preferences[KEY_QUEUE]),
            currentIndex = preferences[KEY_CURRENT_INDEX] ?: -1,
            positionMs = preferences[KEY_POSITION_MS] ?: 0L,
            repeatMode = preferences[KEY_REPEAT_MODE] ?: 0,
            shuffleEnabled = preferences[KEY_SHUFFLE_ENABLED] ?: false,
            playWhenReady = preferences[KEY_PLAY_WHEN_READY] ?: false,
        )
    }

    suspend fun save(snapshot: PlaybackSnapshot) {
        dataStore.edit { preferences ->
            preferences[KEY_QUEUE] = JSONArray(snapshot.queueIds).toString()
            preferences[KEY_CURRENT_INDEX] = snapshot.currentIndex
            preferences[KEY_POSITION_MS] = snapshot.positionMs
            preferences[KEY_REPEAT_MODE] = snapshot.repeatMode
            preferences[KEY_SHUFFLE_ENABLED] = snapshot.shuffleEnabled
            preferences[KEY_PLAY_WHEN_READY] = snapshot.playWhenReady
        }
    }

    private fun decodeQueue(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    add(array.getString(index))
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private val KEY_QUEUE = stringPreferencesKey("playback_queue")
        private val KEY_CURRENT_INDEX = intPreferencesKey("playback_current_index")
        private val KEY_POSITION_MS = longPreferencesKey("playback_position_ms")
        private val KEY_REPEAT_MODE = intPreferencesKey("playback_repeat_mode")
        private val KEY_SHUFFLE_ENABLED = booleanPreferencesKey("playback_shuffle_enabled")
        private val KEY_PLAY_WHEN_READY = booleanPreferencesKey("playback_play_when_ready")
    }
}
