package com.libreplayer.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.libreplayer.data.database.dao.AlbumDao
import com.libreplayer.data.database.dao.ArtistDao
import com.libreplayer.data.database.dao.ImportedRootDao
import com.libreplayer.data.database.dao.PlaylistDao
import com.libreplayer.data.database.dao.RecentlyPlayedDao
import com.libreplayer.data.database.dao.SongDao
import com.libreplayer.data.database.entity.AlbumEntity
import com.libreplayer.data.database.entity.ArtistEntity
import com.libreplayer.data.database.entity.ImportedRootEntity
import com.libreplayer.data.database.entity.PlaylistEntity
import com.libreplayer.data.database.entity.PlaylistSongEntity
import com.libreplayer.data.database.entity.RecentlyPlayedEntity
import com.libreplayer.data.database.entity.SongEntity

@Database(
    entities = [
        SongEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        ImportedRootEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        RecentlyPlayedEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun importedRootDao(): ImportedRootDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun recentlyPlayedDao(): RecentlyPlayedDao
}
