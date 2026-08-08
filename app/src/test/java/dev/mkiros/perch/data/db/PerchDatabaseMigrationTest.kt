package dev.mkiros.perch.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration baseline gate (T31).
 *
 * There is no `fallbackToDestructiveMigration()` any more, so from the first daily-use
 * install onwards a schema change that ships without a migration crashes on upgrade and
 * takes the user's feeds with it. These assertions are what stands between a version bump
 * and that outcome: they run in `./gradlew test`, off-device, with no Room instance.
 */
class PerchDatabaseMigrationTest {

    @Test
    fun `every shipped schema version has an exported baseline json`() {
        val exported = exportedVersions()

        assertEquals(
            "schemas/ must hold one exported json per version up to ${PerchDatabase.VERSION}",
            (1..PerchDatabase.VERSION).toList(),
            exported,
        )
    }

    @Test
    fun `the exported schema for the current version declares that version`() {
        val json = schemaDir().resolve("${PerchDatabase.VERSION}.json").readText()
        val declared = Regex("\"version\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)

        assertEquals(
            "${PerchDatabase.VERSION}.json is stale — re-run KSP to re-export it",
            PerchDatabase.VERSION.toString(),
            declared,
        )
    }

    @Test
    fun `migrations form an unbroken path from the first version to the current one`() {
        val edges = PerchDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }

        assertEquals(
            "each migration must step exactly one version",
            emptyList<Pair<Int, Int>>(),
            edges.filter { (from, to) -> to != from + 1 },
        )
        assertEquals(
            "MIGRATIONS must cover every step from 1 to ${PerchDatabase.VERSION}",
            (1 until PerchDatabase.VERSION).map { it to it + 1 },
            edges.sortedBy { it.first },
        )
    }

    @Test
    fun `the database is built without a destructive fallback`() {
        val source = ExportedSchemas.repoRoot()
            .resolve("app/src/main/java/dev/mkiros/perch/data/db/PerchDatabase.kt")
            .readText()

        assertTrue(
            "fallbackToDestructiveMigration() wipes shipped installs — it may not come back",
            "fallbackToDestructiveMigration" !in source,
        )
    }

    private fun exportedVersions(): List<Int> = ExportedSchemas.exportedVersions()

    private fun schemaDir(): File {
        val dir = ExportedSchemas.dir()
        assertTrue("no exported schemas at $dir", dir.isDirectory)
        return dir
    }
}
