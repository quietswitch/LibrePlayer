package com.libreplayer.migration

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.DatabaseErrorHandler
import androidx.test.runner.AndroidJUnitRunner
import java.io.File
import java.util.UUID

/** Test-only application sandbox. Production database and preference paths are never opened. */
class SourceMigrationTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        val root = File(context.cacheDir, "q37-migration-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getDatabasePath(name: String): File =
                (if (File(name).isAbsolute) File(name) else File(root, "databases/$name")).apply {
                    check(canonicalPath.startsWith(root.canonicalPath + File.separator))
                    parentFile!!.mkdirs()
                }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        }
        return super.newApplication(cl, className, isolated)
    }
}
