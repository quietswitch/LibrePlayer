package com.libreplayer.app

import android.content.Context
import androidx.room.Room
import com.libreplayer.data.database.AppDatabase
import com.libreplayer.data.repository.DefaultLibraryRepository
import com.libreplayer.data.repository.LibraryRefreshStore
import com.libreplayer.data.repository.DefaultPlaylistRepository
import com.libreplayer.data.repository.LibraryRepository
import com.libreplayer.data.repository.PlaylistRepository
import com.libreplayer.library.details.AudioDetailsReader
import com.libreplayer.library.details.AudioDetailsRepository
import com.libreplayer.library.details.DefaultAudioDetailsRepository
import com.libreplayer.library.metadata.AudioMetadataReader
import com.libreplayer.library.scanner.DeviceLibraryScanner
import com.libreplayer.media.playback.PlaybackConnection
import com.libreplayer.media.playback.PlaybackSnapshotStore
import com.libreplayer.settings.SettingsRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "libreplayer.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    private val metadataReader by lazy { AudioMetadataReader(appContext) }
    private val scanner by lazy { DeviceLibraryScanner(appContext, metadataReader) }
    private val audioDetailsReader by lazy { AudioDetailsReader(appContext) }
    private val libraryRefreshStore by lazy { LibraryRefreshStore(appContext) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val playbackSnapshotStore: PlaybackSnapshotStore by lazy { PlaybackSnapshotStore(appContext) }

    val libraryRepository: LibraryRepository by lazy {
        DefaultLibraryRepository(
            database = database,
            scanner = scanner,
            refreshStore = libraryRefreshStore,
        )
    }

    val playlistRepository: PlaylistRepository by lazy {
        DefaultPlaylistRepository(
            playlistQueries = database.playlistDao(),
            songLookupQueries = database.songDao(),
        )
    }

    val audioDetailsRepository: AudioDetailsRepository by lazy {
        DefaultAudioDetailsRepository(
            libraryRepository = libraryRepository,
            playbackConnection = playbackConnection,
            audioDetailsReader = audioDetailsReader,
        )
    }

    val playbackConnection: PlaybackConnection by lazy {
        PlaybackConnection(
            context = appContext,
            libraryRepository = libraryRepository,
        )
    }
}
