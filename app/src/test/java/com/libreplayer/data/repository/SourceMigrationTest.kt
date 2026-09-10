package com.libreplayer.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.database.MIGRATION_1_2
import com.libreplayer.data.repository.ProvenanceFixtures as F
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import java.io.File

class SourceMigrationTest {
    @Test fun `actual migration only creates and populates additive tables`() {
        val db = mockk<SupportSQLiteDatabase>()
        val songs = F.legacy().songs
        every { db.query("SELECT id, sourceType, contentUri FROM songs") } returns cursor(songs.map { listOf(it.id, it.sourceType, it.contentUri) })
        every { db.query("SELECT uri FROM imported_roots") } returns cursor(listOf(listOf(F.rootA.locator)))
        val statements = mutableListOf<String>()
        every { db.execSQL(any()) } answers { statements += firstArg<String>() }
        every { db.execSQL(any(), any<Array<out Any?>>()) } answers {
            var sql = firstArg<String>()
            secondArg<Array<out Any?>>().forEach { sql = sql.replaceFirst("?", literal(it)) }
            statements += sql
        }
        MIGRATION_1_2.migrate(db)
        assertThat(statements).hasSize(5 + F.legacy().sources.size + F.legacy().memberships.size + F.legacy().protections.size)
        assertThat(statements.all { it.startsWith("CREATE ") || it.startsWith("INSERT INTO library_sources ") ||
            it.startsWith("INSERT INTO song_sources ") || it.startsWith("INSERT INTO legacy_song_protection ") }).isTrue()
        assertThat(statements.any { it.contains("media:43") && it.startsWith("INSERT INTO song_sources") }).isFalse()
        // Execute these exact recorded production migration statements with host SQLite as a
        // separate pre-device check, using the exported v1 schema and byte-exact row snapshots.
        val dir = File("build/migration-pure").apply { mkdirs() }
        File(dir, "migration.sql").writeText(statements.joinToString(";\n", postfix = ";\n"))
        File(dir, "fixture.sql").writeText(buildString {
            songs.forEach { song ->
                val fields = song.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                append("INSERT INTO songs (")
                append(fields.joinToString(",") { "\"${it.name}\"" })
                append(") VALUES (")
                append(fields.joinToString(",") { it.isAccessible = true; literal(it.get(song)) })
                append(");\n")
            }
            append("INSERT INTO imported_roots VALUES (${literal(F.rootA.locator)}, 'Root A', 10);\n")
            append("INSERT INTO playlists VALUES (7, 'Ordered', 11, 12);\n")
            listOf("media:43", "media:42", "missing:old", F.document().id).forEachIndexed { position, id ->
                append("INSERT INTO playlist_songs VALUES (7, ${literal(id)}, $position, 13);\n")
            }
            append("INSERT INTO recently_played VALUES ('media:42', 14);\n")
        })
    }

    private fun cursor(rows: List<List<String>>): Cursor = mockk<Cursor>().also { cursor ->
        var index = -1
        every { cursor.moveToNext() } answers { ++index < rows.size }
        every { cursor.getString(any()) } answers { rows[index][firstArg()] }
        every { cursor.close() } returns Unit
    }

    private fun literal(value: Any?): String = when (value) {
        null -> "NULL"
        is Boolean -> if (value) "1" else "0"
        is Number -> value.toString()
        else -> "'${value.toString().replace("'", "''")}'"
    }
}
