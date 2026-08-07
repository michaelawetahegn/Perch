package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The standing contract, per PLAN.md T09.
 *
 * Every snapshot harvested by T04 is parsed exactly as the app will parse it — same
 * bytes, same request URL as the manifest recorded — and held to the guarantees the rest
 * of the app is written against: a feed always has a name and at least one entry, and an
 * entry always has a title, a plausible date, a usable-or-absent link, and an identity
 * that is unique inside its feed.
 *
 * **This test may never be weakened.** If a later task makes it fail, the later task is
 * wrong until proven otherwise. One parameterized case per feed, so a failure names the
 * feed that broke rather than "the corpus".
 */
@RunWith(Parameterized::class)
class FeedCorpusTest(private val slug: String, private val snapshot: File, private val url: String?) {

    private val parser = FeedParser()

    @Test
    fun `every corpus feed parses into a usable feed`() {
        val result = parser.parse(snapshot.readBytes(), contentType = null, requestUrl = url)

        val feed = (result as? ParseResult.Success)?.feed
            ?: fail("did not parse: ${(result as ParseResult.Failure).reason}")

        assertThat(feed.title.isBlank()).isFalse()
        assertThat(feed.entries).isNotEmpty()
    }

    @Test
    fun `every corpus entry is titled, dated, linkable and uniquely identified`() {
        val feed = (parser.parse(snapshot.readBytes(), null, url) as ParseResult.Success).feed
        val seenGuids = mutableSetOf<String>()

        feed.entries.forEachIndexed { index, entry ->
            val where = "$slug entry #$index (${entry.guid.take(80)})"

            if (entry.title.isBlank()) fail("$where has a blank title")
            if (entry.guid.isBlank()) fail("$where has a blank guid")
            if (!seenGuids.add(entry.guid)) fail("$where repeats a guid already used in this feed")

            entry.link?.let { link ->
                if (!link.startsWith("http://") && !link.startsWith("https://")) {
                    fail("$where has a non-absolute link: $link")
                }
            }

            val published = entry.publishedAt ?: fail("$where has no publication date")
            if (published < FLOOR || published > ceiling()) {
                fail("$where is dated $published, outside [$FLOOR, ${ceiling()}]")
            }
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    companion object {
        /** SPEC.md §5: never 1970, and never more than a day into the future. */
        private val FLOOR: Instant = Instant.parse("2000-01-01T00:00:00Z")

        private fun ceiling(): Instant = Instant.now().plus(Duration.ofHours(24))

        /**
         * Walks up from the test's working directory (the `:app` project dir under
         * Gradle, the repo root under some IDE runners) so the corpus is found either way.
         */
        private fun repoRoot(): File {
            val start = System.getProperty("user.dir") ?: "."
            var dir: File? = File(start).absoluteFile
            while (dir != null) {
                if (File(dir, "fixtures/snapshots").isDirectory) return dir
                dir = dir.parentFile
            }
            error("fixtures/snapshots not found above $start")
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun corpus(): List<Array<Any?>> {
            val root = repoRoot()
            val urls = File(root, "fixtures/manifest.tsv").readLines()
                .mapNotNull { line -> line.split('\t').takeIf { it.size >= 2 } }
                .associate { it[0] to it[1] }

            val snapshots = File(root, "fixtures/snapshots").listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name }
                .orEmpty()

            // A corpus that quietly emptied itself would make every assertion below vacuous.
            check(snapshots.size >= 35) { "expected ≥35 snapshots, found ${snapshots.size}" }

            return snapshots.map { file ->
                val slug = file.nameWithoutExtension
                arrayOf(slug, file, urls[slug])
            }
        }
    }
}
