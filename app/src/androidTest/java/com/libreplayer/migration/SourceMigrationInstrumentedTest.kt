package com.libreplayer.migration

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.data.database.AppDatabase
import com.libreplayer.data.database.MIGRATION_1_2
import com.libreplayer.data.repository.*
import com.libreplayer.data.repository.ProvenanceFixtures as F
import com.libreplayer.library.scanner.*
import com.libreplayer.library.metadata.AudioMetadataReader
import com.libreplayer.media.playback.PlaybackSnapshot
import com.libreplayer.media.playback.PlaybackSnapshotStore
import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class SourceMigrationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LibrePlayerApplication
    private val oldTables = listOf("songs", "albums", "artists", "imported_roots", "playlists", "playlist_songs", "recently_played")

    @Test fun migrationReferencesRollbackAndBoundedRefresh() = runBlocking<Unit> {
        assertTrue(app.filesDir.path.contains("/q37-migration-"))
        val path = app.getDatabasePath("libreplayer.db")
        assertFalse(path.exists())
        createV1(path)
        val store = PlaybackSnapshotStore(app)
        val snapshot = PlaybackSnapshot(listOf("media:43", "media:42", "missing:old", "media:42"), 3, 12345, 2, true, false)
        store.save(snapshot)
        val preferenceFile = File(app.filesDir, "datastore/libreplayer_preferences.preferences_pb")
        val preferencesBefore = preferenceFile.readBytes()
        val before = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            capture(oldTables) { sql -> sqlite.rawQuery(sql, null) }
        }
        var db = app.appContainer.database // Uses the production builder and registered migration.
        val migrated = capture(oldTables) { db.openHelper.writableDatabase.query(it) }
        assertEquals(before, migrated)
        assertEquals(snapshot, store.snapshot.first())
        assertArrayEquals(preferencesBefore, preferenceFile.readBytes())
        assertEquals(2, db.openHelper.writableDatabase.version)
        assertEquals(listOf("media:43"), db.sourceDao().getProtections().map { it.songId })
        assertEquals(4, db.sourceDao().getMemberships().size)
        assertTrue(db.sourceDao().getSources().all { it.version == null && it.generation == null })
        db = Room.databaseBuilder(app, AppDatabase::class.java, path.path).addMigrations(MIGRATION_1_2).build()
        assertEquals(before, capture(oldTables) { db.openHelper.writableDatabase.query(it) })
        val userTables = listOf("playlists", "playlist_songs", "recently_played")
        val userBefore = capture(userTables) { db.openHelper.writableDatabase.query(it) }

        // Exact legacy identity and a conflicting secondary occurrence: actual Room lookup joins.
        db.reconcileSources(listOf(F.complete(F.secondary, F.media("1234-abcd"))))
        val replacement = db.songDao().getAllSongs().single { it.id.startsWith("media-scoped:") }
        assertFalse(replacement.isFavorite)
        assertTrue(db.songDao().getSongById("media:42")!!.isFavorite)
        assertEquals(userBefore, capture(userTables) { db.openHelper.writableDatabase.query(it) })
        assertEquals(snapshot, store.snapshot.first())
        assertNull(db.songDao().getSongById("media:43")) // Retained ambiguous rows cannot play a reused aggregate URI.
        assertTrue(db.songDao().getAllSongs().any { it.id == "media:43" })
        assertFalse(db.playlistDao().getPlaylistSongsNow(7).any { it.song.id == replacement.id })

        // Q2 calls the existing production media-item conversion without a production test hook.
        val conversion = Class.forName("com.libreplayer.media.playback.PlaybackConnectionKt")
            .getDeclaredMethod("toMediaItem", Song::class.java).apply { isAccessible = true }
        listOf(db.songDao().getSongById("media:42")!!, replacement).forEach { entity ->
            val song = entity.asModel()
            val item = conversion.invoke(null, song) as MediaItem
            assertEquals(song.id, item.mediaId)
            assertEquals(song.contentUri, item.localConfiguration!!.uri.toString())
        }

        // A real SQLite abort after song writes, at the source/checkpoint write, must roll back Room.
        val allTables = oldTables + listOf("library_sources", "song_sources", "legacy_song_protection")
        val beforeFailure = capture(allTables) { db.openHelper.writableDatabase.query(it) }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_checkpoint BEFORE UPDATE ON library_sources BEGIN SELECT RAISE(ABORT, 'injected checkpoint failure'); END")
        var failed = false
        try { db.reconcileSources(listOf(F.complete(F.secondary, F.media("1234-abcd", "99"), generation = 11))) }
        catch (_: android.database.sqlite.SQLiteException) { failed = true }
        assertTrue("Room failure injection did not execute", failed)
        assertEquals(beforeFailure, capture(allTables) { db.openHelper.writableDatabase.query(it) })
        assertEquals(snapshot, store.snapshot.first())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_checkpoint")

        // Bounded Q1.1e: unchanged, add, authoritative delete, unavailable, return, checkpoint.
        db.reconcileSources(listOf(F.complete(F.primary, F.media(), F.media(item = "44"), F.media(item = "45"))))
        val stable = db.songDao().getAllSongs()
        db.reconcileSources(listOf(F.complete(F.primary, F.media(), F.media(item = "44"), F.media(item = "45"))))
        assertEquals(stable, db.songDao().getAllSongs())
        db.reconcileSources(listOf(F.complete(F.primary, F.media(), F.media(item = "99"), generation = 11)))
        assertEquals(2, db.sourceDao().getMemberships().count { it.sourceId == F.primary.id && it.present })
        db.reconcileSources(listOf(F.complete(F.primary, F.media(item = "99"), generation = 12)))
        assertNull(db.songDao().getSongById("media:42"))
        assertFalse(db.playlistDao().getPlaylistSongsNow(7).any { it.song.id == replacement.id })
        val failedSource = db.sourceDao().getSources().single { it.id == F.primary.id }
        val failedMembers = db.sourceDao().getMemberships().filter { it.sourceId == F.primary.id }
        db.reconcileSources(listOf(SourceScan.Unavailable(F.primary, "unavailable"), F.complete(F.secondary, F.media("1234-abcd"), generation = 11)))
        assertEquals(failedSource, db.sourceDao().getSources().single { it.id == F.primary.id })
        assertEquals(failedMembers, db.sourceDao().getMemberships().filter { it.sourceId == F.primary.id })
        db.reconcileSources(listOf(F.complete(F.primary, F.media(), generation = 13), SourceScan.Unavailable(F.secondary, "unavailable")))
        assertNotNull(db.songDao().getSongById("media:42"))
        assertEquals(13L, db.sourceDao().getSources().single { it.id == F.primary.id }.generation)
        assertEquals(userBefore, capture(userTables) { db.openHelper.writableDatabase.query(it) })
        assertEquals(snapshot, store.snapshot.first())
        assertTrue(db.playlistDao().getPlaylistSongsNow(7).any { it.song.id == "media:42" })

        // Q1.1d: raw protected storage stays out of every playable projection and group count.
        val visible = db.songDao().observeSongs().first()
        assertEquals(visible.map { it.id }.toSet(), db.songDao().getAvailableSongs().map { it.id }.toSet())
        assertFalse(visible.any { it.id == "media:43" })
        assertEquals(visible.size, db.albumDao().getAllAlbums().sumOf { it.songCount })
        assertEquals(visible.size, db.artistDao().getAllArtists().sumOf { it.songCount })
        assertFalse(db.songDao().observeFavoriteSongs().first().any { it.id == replacement.id || it.id == "media:43" })

        val evidence = JSONObject().put("result", "PASS").put("existingSongIdsChanged", false)
            .put("preMigrationRows", JSONObject(before)).put("postMigrationRows", JSONObject(migrated))
            .put("snapshot", snapshot.toString()).put("preferenceBytesSha256", sha256(preferencesBefore))
            .put("actualRoomRollback", true).put("q11e", "PASS").put("q11d", "PASS").put("q36", "PASS").put("q2", "PASS")
        File(app.filesDir, "migration-proof.json").writeText(evidence.toString(2))
        println("Q37_MIGRATION_PROOF=${File(app.filesDir, "migration-proof.json")}")
        db.close()
        ActivityScenario.launch(com.libreplayer.app.MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(app.filesDir, "migration-app-open.png").outputStream().use { output ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun normalPrimaryRefreshAndPartialSourceFailure() = runBlocking {
        val db = Room.databaseBuilder(app, AppDatabase::class.java, "primary-smoke.db").addMigrations(MIGRATION_1_2).build()
        val scanner = DeviceLibraryScanner(app, AudioMetadataReader(app))
        val first = scanner.scan(emptyList(), emptyList(), emptyList(), emptyList(), LibraryScanMode.FULL_REBUILD)
        assertFalse(first.sources.any { it is SourceScan.Unavailable })
        val primary = first.sources.filterIsInstance<SourceScan.Complete>().single { it.source.locator == "external_primary" }
        assertTrue("Bounded smoke requires an existing small/medium primary catalog", primary.observations.size in 1..2500)
        db.reconcileSources(first.sources)
        val firstSongs = db.songDao().getAllSongs()
        assertEquals(primary.observations.size, firstSongs.size)
        assertEquals(firstSongs.size, firstSongs.map { it.id }.distinct().size)
        val unchanged = scanner.scan(emptyList(), firstSongs.map { it.asScannedSong() }, db.sourceDao().getSources(), db.sourceDao().getMemberships(), LibraryScanMode.INCREMENTAL)
        assertFalse(unchanged.sources.any { it is SourceScan.Unavailable })
        assertEquals(0, unchanged.statistics.mediaStoreRowsRead)
        db.reconcileSources(unchanged.sources)
        assertEquals(firstSongs, db.songDao().getAllSongs())

        val root = SourceIdentity.root("content://com.libreplayer.test.source/tree/rootA")
        val document = F.document("com.libreplayer.test.source").let { it.copy(relativePath = android.net.Uri.parse(it.contentUri).path) }
        db.reconcileSources(listOf(F.complete(root, document)))
        val beforeRoot = db.sourceDao().getSources().single { it.id == root.id }
        val beforeMembership = db.sourceDao().getMemberships().filter { it.sourceId == root.id }
        val resolver = app.contentResolver
        for (mode in listOf("partial", "null", "loading")) {
            resolver.call(android.net.Uri.parse(root.locator), "configure", mode, null)
            val result = scanner.scan(listOf(root.locator), db.songDao().getAllSongs().map { it.asScannedSong() },
                db.sourceDao().getSources(), db.sourceDao().getMemberships(), LibraryScanMode.INCREMENTAL)
            assertTrue(result.sources.single { it.source.id == root.id } is SourceScan.Unavailable)
            if (mode == "partial") {
                assertEquals(2, resolver.call(android.net.Uri.parse(root.locator), "status", null, null)!!.getInt("childQueries"))
                assertEquals(1, result.statistics.cachedSongsReused - firstSongs.size)
            }
            db.reconcileSources(result.sources)
            assertEquals(beforeRoot, db.sourceDao().getSources().single { it.id == root.id })
            assertEquals(beforeMembership, db.sourceDao().getMemberships().filter { it.sourceId == root.id })
        }
        resolver.call(android.net.Uri.parse(root.locator), "configure", "success", null)
        val returned = scanner.scan(listOf(root.locator), db.songDao().getAllSongs().map { it.asScannedSong() },
            db.sourceDao().getSources(), db.sourceDao().getMemberships(), LibraryScanMode.INCREMENTAL)
        assertTrue(returned.sources.single { it.source.id == root.id } is SourceScan.Complete)
        db.reconcileSources(returned.sources)
        assertEquals(firstSongs.size + 1, db.songDao().getAllSongs().size)
        val evidence = JSONObject().put("result", "PASS").put("primaryRows", firstSongs.size)
            .put("unchangedMetadataReads", unchanged.statistics.mediaStoreRowsRead)
            .put("partialEnumeration", "PASS").put("nullCursor", "PASS").put("loadingCursor", "PASS")
        File(app.filesDir, "scanner-proof.json").writeText(evidence.toString(2))
        println("Q37_SCANNER_PROOF=${File(app.filesDir, "scanner-proof.json")}")
        db.close()
    }

    private fun createV1(path: File) {
        val schema = JSONObject(instrumentation.context.assets.open("com.libreplayer.data.database.AppDatabase/1.json").bufferedReader().readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val name = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                val indexes = entity.optJSONArray("indices")
                if (indexes != null) for (i in 0 until indexes.length()) sqlite.execSQL(indexes.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", name))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            F.legacy().songs.forEach { song ->
                val values = ContentValues()
                song.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach { field ->
                    field.isAccessible = true
                    when (val value = field.get(song)) {
                        null -> values.putNull(field.name)
                        is String -> values.put(field.name, value)
                        is Boolean -> values.put(field.name, value)
                        is Int -> values.put(field.name, value)
                        is Long -> values.put(field.name, value)
                    }
                }
                sqlite.insertOrThrow("songs", null, values)
            }
            sqlite.execSQL("INSERT INTO imported_roots VALUES (?, 'Root A', 10)", arrayOf(F.rootA.locator))
            sqlite.execSQL("INSERT INTO playlists VALUES (7, 'Ordered', 11, 12)")
            listOf("media:43", "media:42", "missing:old", F.document().id).forEachIndexed { position, id ->
                sqlite.execSQL("INSERT INTO playlist_songs VALUES (7, ?, ?, 13)", arrayOf<Any>(id, position))
            }
            sqlite.execSQL("INSERT INTO recently_played VALUES ('media:42', 14)")
            sqlite.version = 1
        }
    }

    private fun capture(tables: List<String>, query: (String) -> android.database.Cursor): Map<String, List<List<String?>>> =
        tables.associateWith { table -> query("SELECT * FROM $table ORDER BY 1,2").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        } }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
