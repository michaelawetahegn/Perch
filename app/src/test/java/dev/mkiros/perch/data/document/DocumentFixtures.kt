package dev.mkiros.perch.data.document

import java.io.File
import java.time.Instant

data class DocumentFixture(
    val slug: String,
    val file: File,
    val sha256: String,
    val pages: Int,
    val page1Pt: String,
    val title: String?,
    val published: Instant?,
    val producer: String,
    val origin: String,
)

object DocumentFixtures {
    private val fixtures = mutableMapOf<String, DocumentFixture>()

    fun manifest(): List<DocumentFixture> {
        if (fixtures.isNotEmpty()) return fixtures.values.toList()

        val root = repoRoot()
        val manifestFile = File(root, "fixtures/documents/manifest.tsv")
        val lines = manifestFile.readLines()

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split("\t")
            if (parts.size < 9) continue

            val slug = parts[0]
            val bytes = parts[1].toIntOrNull() ?: -1
            val sha256 = parts[2]
            val pages = if (parts[3] == "-") -1 else parts[3].toIntOrNull() ?: -1
            val page1Pt = parts[4]
            val title = parts[5].takeIf { it != "-" }
            val published = if (parts[6] == "-") null else Instant.parse(parts[6])
            val producer = parts[7]
            val origin = parts[8]

            val file = File(root, "fixtures/documents/$slug.pdf")
            if (file.exists()) {
                val fixture = DocumentFixture(
                    slug = slug,
                    file = file,
                    sha256 = sha256,
                    pages = pages,
                    page1Pt = page1Pt,
                    title = title,
                    published = published,
                    producer = producer,
                    origin = origin,
                )
                fixtures[slug] = fixture
            }
        }

        return fixtures.values.toList()
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("Could not find repo root")
    }
}
