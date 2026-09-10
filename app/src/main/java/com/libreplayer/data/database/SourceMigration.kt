package com.libreplayer.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.libreplayer.data.database.entity.LegacySongProtectionEntity
import com.libreplayer.data.database.entity.LibrarySourceEntity
import com.libreplayer.data.database.entity.SongSourceEntity
import com.libreplayer.library.scanner.SourceIdentity
import com.libreplayer.library.scanner.sourceKey

internal data class LegacySourceRow(val id: String, val type: String, val uri: String)
internal data class LegacyProvenance(
    val sources: List<LibrarySourceEntity>,
    val memberships: List<SongSourceEntity>,
    val protections: List<LegacySongProtectionEntity>,
)

internal fun classifyLegacySources(rows: List<LegacySourceRow>, roots: List<String>): LegacyProvenance {
    val candidates = rows.flatMap { row ->
        when (row.type) {
            SourceIdentity.MEDIA -> SourceIdentity.mediaItem(row.uri)?.let { (source, item) ->
                listOf(source to SongSourceEntity(source.id, 0, item, row.id, row.uri, null))
            }.orEmpty()
            SourceIdentity.DOCUMENT -> SourceIdentity.documentKey(row.uri)?.let { item ->
                roots.filter { SourceIdentity.hasExactRoot(row.uri, it) }.map { root ->
                    val source = SourceIdentity.root(root)
                    source to SongSourceEntity(source.id, 0, item, row.id, row.uri, null)
                }
            }.orEmpty()
            else -> emptyList()
        }
    }
    // Duplicate legacy evidence cannot choose an owner. Preserve both old rows, unclaimed.
    val unique = candidates.groupBy { (source, member) ->
        if (source.kind == SourceIdentity.DOCUMENT) sourceKey(source.kind, member.itemKey)
        else sourceKey(source.id, member.itemKey)
    }
        .values.filter { group -> group.map { it.second.songId }.distinct().size == 1 }.flatten()
    val claimed = unique.map { it.second.songId }.toSet()
    return LegacyProvenance(
        unique.map { it.first }.distinctBy { it.id },
        unique.map { it.second }.distinct(),
        rows.filter { it.id !in claimed }.map { LegacySongProtectionEntity(it.id, "unscoped") },
    )
}

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS library_sources (id TEXT NOT NULL, kind TEXT NOT NULL, authority TEXT NOT NULL, locator TEXT NOT NULL, incarnation INTEGER NOT NULL, version TEXT, generation INTEGER, reconciledAtEpochMillis INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS song_sources (sourceId TEXT NOT NULL, incarnation INTEGER NOT NULL, itemKey TEXT NOT NULL, songId TEXT NOT NULL, contentUri TEXT NOT NULL, physicalKey TEXT, present INTEGER NOT NULL, PRIMARY KEY(sourceId, incarnation, itemKey), FOREIGN KEY(sourceId) REFERENCES library_sources(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_song_sources_songId ON song_sources(songId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_song_sources_physicalKey ON song_sources(physicalKey)")
        db.execSQL("CREATE TABLE IF NOT EXISTS legacy_song_protection (songId TEXT NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(songId))")
        val rows = db.query("SELECT id, sourceType, contentUri FROM songs").use { cursor ->
            buildList { while (cursor.moveToNext()) add(LegacySourceRow(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
        }
        val roots = db.query("SELECT uri FROM imported_roots").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val provenance = classifyLegacySources(rows, roots)
        provenance.sources.forEach { s ->
            db.execSQL("INSERT INTO library_sources VALUES (?, ?, ?, ?, 0, NULL, NULL, 0)", arrayOf(s.id, s.kind, s.authority, s.locator))
        }
        provenance.memberships.forEach { m ->
            db.execSQL("INSERT INTO song_sources VALUES (?, 0, ?, ?, ?, NULL, 1)", arrayOf(m.sourceId, m.itemKey, m.songId, m.contentUri))
        }
        provenance.protections.forEach { p ->
            db.execSQL("INSERT INTO legacy_song_protection VALUES (?, ?)", arrayOf(p.songId, p.reason))
        }
    }
}
