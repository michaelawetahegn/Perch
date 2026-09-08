package dev.mkiros.perch.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import org.json.JSONObject

/**
 * The `app/schemas/` exports, read as data.
 *
 * Two tests depend on them for different reasons. `PerchDatabaseMigrationTest` asserts
 * that one exists per shipped version; the per-migration tests *build* the old database
 * from [createStatements] rather than from a hand-copied `CREATE TABLE`, so an upgrade is
 * always exercised against the schema that actually shipped and cannot drift from it.
 *
 * [seedVersion] and [openAtCurrentVersion] are the two halves of every migration test:
 * write a populated old database, then open it through the shipped migrations.
 */
object ExportedSchemas {

    /** Every DDL statement that makes up version [version], tables before their indices. */
    fun createStatements(version: Int): List<String> {
        val database = JSONObject(json(version)).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        return (0 until entities.length()).flatMap { i ->
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            val indices = entity.optJSONArray("indices")
            val indexSql = (0 until (indices?.length() ?: 0)).map {
                indices!!.getJSONObject(it).getString("createSql")
            }
            (listOf(entity.getString("createSql")) + indexSql)
                .map { it.replace("\${TABLE_NAME}", table) }
        }
    }

    /**
     * Writes a real, populated database at [version] to [file]: the schema that shipped as
     * `<version>.json`, the rows [rows] inserts, and the version stamp an upgrade reads.
     */
    fun seedVersion(file: File, version: Int, rows: (SQLiteDatabase) -> Unit) {
        file.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(file)
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        createStatements(version).forEach(old::execSQL)
        rows(old)
        old.version = version
        old.close()
    }

    /**
     * Opens the seeded [file] with the shipped migrations. Room validates the migrated
     * schema against the current entities here, so a migration that produces the wrong
     * columns, indices or foreign keys fails inside this call rather than on a phone.
     */
    fun openAtCurrentVersion(context: Context, file: File): PerchDatabase =
        Room.databaseBuilder(context, PerchDatabase::class.java, file.name)
            .addMigrations(*PerchDatabase.MIGRATIONS)
            .build()

    fun json(version: Int): String = dir().resolve("$version.json").readText()

    fun exportedVersions(): List<Int> =
        dir().listFiles()
            ?.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            ?.sorted()
            .orEmpty()

    fun dir(): File = repoRoot().resolve("app/schemas/${PerchDatabase::class.qualifiedName}")

    /** Walks up from the working directory, which is `:app` under Gradle and the root elsewhere. */
    fun repoRoot(): File {
        val start = System.getProperty("user.dir") ?: "."
        var dir: File? = File(start).absoluteFile
        while (dir != null) {
            if (File(dir, "app/schemas").isDirectory) return dir
            dir = dir.parentFile
        }
        error("app/schemas not found above $start")
    }
}
