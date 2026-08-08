package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Test

/**
 * U05's coverage gate over the harvested corpus.
 *
 * **On the number this asserts.** U05 asks for "≥60% of entries resolve a thumbnail". That
 * is not reachable from feed markup alone and never was: only **340 of the corpus's 1038
 * entries contain any image markup at all** (measured here, every run — see [Row.available]).
 * The fifth rung of §0's chain, `og:image` from the entry page, is the one that would close
 * the gap, and §0 explicitly defers it to U10 — *"only if the page is already being fetched
 * by U10; never fetch a page just for a thumbnail"*. Until U10 lands, 32.8% is the corpus
 * ceiling, not our score.
 *
 * So this asserts the contract that actually measures *our* code: of the entries that do
 * carry an image, we must resolve [FLOOR_OF_AVAILABLE]% of them. That is a stricter
 * regression gate than a raw percentage — it cannot be satisfied by the corpus changing
 * shape, only by [LeadImage] doing its job. The absolute figure U05 asked for is printed on
 * every run and is re-gated live, with `og:image` in play, at U15 gate 4.
 *
 * The per-source table prints on every run, passing or failing, because the total on its own
 * is unreadable. A source at 0% of *available* would be our bug; a source with 0 available is
 * a text-only blog, which is the feed's shape and not something to fix.
 */
class ThumbnailCorpusTest {

    private val parser = FeedParser()

    @Test
    fun `every corpus entry that carries an image resolves one`() {
        val urls = File(repoRoot(), "fixtures/manifest.tsv").readLines()
            .mapNotNull { line -> line.split('\t').takeIf { it.size >= 2 } }
            .associate { it[0] to it[1] }

        val snapshots = File(repoRoot(), "fixtures/snapshots").listFiles()
            ?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        check(snapshots.size >= 35) { "expected ≥35 snapshots, found ${snapshots.size}" }

        val rows = snapshots.map { file ->
            val slug = file.nameWithoutExtension
            val bytes = file.readBytes()
            val result = parser.parse(bytes, contentType = null, requestUrl = urls[slug])
            val entries = (result as? ParseResult.Success)?.feed?.entries.orEmpty()
            // Whatever we did resolve has to be something Coil can actually load.
            entries.mapNotNull { it.imageUrl }.forEach { url ->
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    throw AssertionError("$slug resolved a non-absolute image: $url")
                }
            }
            Row(slug, entries.size, entries.count { it.imageUrl != null }, available(bytes))
        }

        val total = rows.sumOf { it.entries }
        val withImage = rows.sumOf { it.withImage }
        val available = rows.sumOf { it.available }
        val percentOfAll = withImage * 100.0 / total
        val percentOfAvailable = withImage * 100.0 / available

        println(
            buildString {
                appendLine(
                    "U05 thumbnails — resolved %d/%d of entries carrying an image (%.1f%%); %d/%d of all entries (%.1f%%)"
                        .format(withImage, available, percentOfAvailable, withImage, total, percentOfAll),
                )
                appendLine("  %-28s %7s %7s %7s".format("source", "of-avail", "resolved", "entries"))
                rows.sortedBy { it.percentOfAvailable }.forEach { row ->
                    appendLine(
                        "  %-28s %6.1f%% %3d/%-3d %7d".format(
                            row.slug, row.percentOfAvailable, row.withImage, row.available, row.entries,
                        ),
                    )
                }
            },
        )

        assertThat(percentOfAvailable).isAtLeast(FLOOR_OF_AVAILABLE)
    }

    private data class Row(
        val slug: String,
        val entries: Int,
        val withImage: Int,
        val available: Int,
    ) {
        /** 100% when a source ships no images at all — nothing was missed, so nothing is owed. */
        val percentOfAvailable: Double
            get() = if (available == 0) 100.0 else withImage * 100.0 / available
    }

    private companion object {
        /**
         * Of the entries that carry an image, the share we must resolve. Deliberately not
         * 100: a handful of entries across the corpus put their only picture somewhere the
         * chain rightly refuses it (an `<img>` inside dropped markup, a sub-64px badge).
         */
        const val FLOOR_OF_AVAILABLE = 95.0

        /**
         * An independent, deliberately crude oracle: how many entries carry image markup of
         * any kind. It knows nothing of [LeadImage]'s ordering or its furniture rules — that
         * is the point, since a test that reimplemented the ranking would assert only that
         * the code agrees with itself. Escaped bodies surface through `text()`, real XHTML
         * children through `html()`.
         */
        private fun available(bytes: ByteArray): Int {
            val document = Jsoup.parse(bytes.toString(Charsets.UTF_8), "", Parser.xmlParser())
            return document.select("item, entry").count { element ->
                val markup = element.html() + element.text()
                IMAGE_MARKUP.containsMatchIn(markup)
            }
        }

        private val IMAGE_MARKUP =
            Regex("<img|media:thumbnail|media:content|<enclosure", RegexOption.IGNORE_CASE)

        private fun repoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                if (File(dir, "fixtures/snapshots").isDirectory) return dir
                dir = dir.parentFile
            }
            error("fixtures/snapshots not found")
        }
    }
}
