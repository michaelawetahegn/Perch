package dev.mkiros.perch.data.extract

import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.ArticleLowering
import dev.mkiros.perch.data.parse.HtmlSanitizer
import org.jsoup.Jsoup
import org.junit.Test

/**
 * U10's contract: reading an article must never require visiting the site.
 *
 * Every assertion here runs against a page harvested into `fixtures/articles/`, so the
 * test is offline and the thing it measures is a real CMS rather than a hand-written
 * approximation of one.
 *
 * The pairing of [ArticleFixture.mid] and [ArticleFixture.last] is deliberate. A "contains
 * the article's opening" check passes for an extractor that stops at the first sidebar, so
 * a mid-article sentence proves it found the body and a final sentence proves it reached
 * the end of it.
 */
class ArticleExtractorTest {

    @Test
    fun `every harvested page extracts prose from the middle and the end of the article`() {
        val failures = mutableListOf<String>()

        for (fixture in ArticleFixtures.all) {
            val text = extractedText(fixture)
            if (text == null) {
                failures += "${fixture.slug}: extracted nothing"
                continue
            }
            if (!text.contains(fixture.mid)) failures += "${fixture.slug}: missing mid-article prose"
            if (!text.contains(fixture.last)) failures += "${fixture.slug}: missing final prose"
        }

        assertThat(failures).isEmpty()
    }

    @Test
    fun `extraction drops the nav, footer, cookie banner and related-posts chrome`() {
        val failures = mutableListOf<String>()

        for (fixture in ArticleFixtures.all) {
            val text = extractedText(fixture) ?: continue
            for (chrome in fixture.excludes) {
                if (text.contains(chrome)) failures += "${fixture.slug}: kept chrome \"$chrome\""
            }
        }

        assertThat(failures).isEmpty()
    }

    /**
     * §0's second shape: gpuopen.com ships a ~200-character `<description>` and no
     * `content:encoded`, so the article renders as its own blurb. The excerpt is read out
     * of the harvested feed rather than hard-coded, so the ratio is measured against what
     * the reader would actually have been left with.
     */
    @Test
    fun `an excerpt-only page recovers a body at least ten times the feed excerpt`() {
        val excerpts = feedExcerpts()
        val report = mutableListOf<String>()
        val short = mutableListOf<String>()

        for (fixture in ArticleFixtures.excerptOnly) {
            val excerpt = requireNotNull(excerpts[fixture.url]) { "no feed excerpt for ${fixture.url}" }
            val extracted = requireNotNull(extractedText(fixture)) { "${fixture.slug} extracted nothing" }
            val ratio = extracted.length.toDouble() / excerpt.length
            report += "${fixture.slug}: ${excerpt.length} → ${extracted.length} chars (${"%.1f".format(ratio)}×)"
            if (ratio < MIN_EXCERPT_RATIO) short += report.last()
        }

        println(report.joinToString("\n"))
        assertThat(short).isEmpty()
    }

    /**
     * Extracted HTML goes through the *existing* sanitize → lower pipeline, so it gets no
     * special treatment downstream. `ArticleLoweringCorpusTest`'s standing rule applies
     * unchanged: an [ArticleBlock.Unsupported] anywhere is a lowering bug, and an extractor
     * that hands the pipeline markup it has never seen would show up here first.
     */
    @Test
    fun `lowering an extracted article yields no unsupported blocks`() {
        val unsupported = mutableListOf<String>()

        for (fixture in ArticleFixtures.all) {
            val html = ArticleExtractor.extract(fixture.html(), fixture.url) ?: continue
            val blocks = ArticleLowering.toBlocks(HtmlSanitizer.sanitize(html, fixture.url))
            assertThat(blocks).isNotEmpty()
            unsupported += flatten(blocks)
                .filterIsInstance<ArticleBlock.Unsupported>()
                .map { "${fixture.slug}: ${it.label}" }
        }

        assertThat(unsupported).isEmpty()
    }

    /**
     * V09/#4: on a ZDI post the table *is* the post — a month's CVEs, one per row — and
     * dropping it leaves two paragraphs saying "here's a look at all the bugs" above
     * nothing. Squarespace gives every block its own `sqs-block` div, so the table is a
     * *sibling* of the winning subtree, and a sibling sweep keyed on text density will
     * never keep it: a table is mostly markup.
     *
     * The count is taken off the page rather than written down, so this asserts the table
     * survived whole rather than that some table survived.
     */
    @Test
    fun `a Squarespace page keeps the table its article is made of`() {
        val fixture = ArticleFixtures.squarespaceTable
        val onPage = cells(Jsoup.parse(fixture.html(), fixture.url))
        assertThat(onPage).isEqualTo(EXPECTED_ZDI_CELLS)

        val extracted = requireNotNull(ArticleExtractor.extract(fixture.html(), fixture.url))
        val doc = Jsoup.parse(extracted, fixture.url)

        assertThat(doc.select("table")).hasSize(1)
        assertThat(cells(doc)).isEqualTo(onPage)
        assertThat(doc.text()).contains("CVE-2026-43743")
    }

    /**
     * The recovered table has to survive the *rest* of the pipeline too — U15's gate 6b
     * asks the live corpus for exactly this, and extraction is a second way into it, so
     * the property is worth having offline as well as on the wire: one table, rectangular
     * rows, the header the markup declared, and every written cell still written.
     */
    @Test
    fun `the recovered Squarespace table lowers rectangular with its header intact`() {
        val fixture = ArticleFixtures.squarespaceTable
        val written = Jsoup.parse(fixture.html(), fixture.url)
            .select("table td, table th")
            .count { it.text().isNotBlank() }

        val extracted = requireNotNull(ArticleExtractor.extract(fixture.html(), fixture.url))
        val tables = flatten(ArticleLowering.toBlocks(HtmlSanitizer.sanitize(extracted, fixture.url)))
            .filterIsInstance<ArticleBlock.Table>()

        assertThat(tables).hasSize(1)
        val table = tables.single()
        assertThat(table.rows.map { it.size }.distinct()).containsExactly(table.header.size)
        assertThat(table.header.map { it.text }).containsExactly(
            "CVE ID", "Component", "Impact",
            "iOS 26.5.2 / iPadOS 26.5.2", "macOS Tahoe 26.5.2", "Safari 26.5.2",
        ).inOrder()

        val lowered = table.header.count { it.text.isNotBlank() } +
            table.rows.sumOf { row -> row.count { it.text.isNotBlank() } }
        assertThat(lowered).isEqualTo(written)
    }

    @Test
    fun `a page with no article on it extracts nothing rather than its navigation`() {
        val html = """
            <html><body>
              <nav><ul><li><a href="/">Home</a></li><li><a href="/about">About</a></li></ul></nav>
              <footer><p>© 2026 Example</p></footer>
            </body></html>
        """.trimIndent()

        assertThat(ArticleExtractor.extract(html, "https://example.com/")).isNull()
    }

    @Test
    fun `malformed input yields null rather than an exception`() {
        assertThat(ArticleExtractor.extract("", "https://example.com/")).isNull()
        assertThat(ArticleExtractor.extract("<<<>", null)).isNull()
    }

    @Test
    fun `relative links and images in the extracted body are absolute`() {
        val html = """
            <html><body><article>
              <p>${"Long enough to score. ".repeat(20)}</p>
              <p>See <a href="/next/">the next part</a> and this diagram:</p>
              <p><img src="../img/diagram.png" alt="diagram"></p>
            </article></body></html>
        """.trimIndent()

        val extracted = requireNotNull(ArticleExtractor.extract(html, "https://example.com/posts/one/"))
        val doc = Jsoup.parse(extracted, "https://example.com/posts/one/")

        assertThat(doc.select("a").attr("abs:href")).isEqualTo("https://example.com/next/")
        assertThat(doc.select("img").attr("abs:src")).isEqualTo("https://example.com/posts/img/diagram.png")
    }

    /** Prose from the extracted subtree, normalised the way the reader would see it. */
    private fun extractedText(fixture: ArticleFixture): String? =
        ArticleExtractor.extract(fixture.html(), fixture.url)
            ?.let { Jsoup.parse(it, fixture.url).text() }

    /** `entry link → description text` straight out of the harvested gpuopen feed. */
    private fun feedExcerpts(): Map<String, String> =
        Jsoup.parse(ArticleFixtures.gpuopenFeed(), "", org.jsoup.parser.Parser.xmlParser())
            .select("item")
            .associate { item ->
                item.selectFirst("link")!!.text().trim() to
                    Jsoup.parse(item.selectFirst("description")!!.text()).text().trim()
            }

    private fun flatten(blocks: List<ArticleBlock>): List<ArticleBlock> =
        blocks.flatMap { if (it is ArticleBlock.Quote) listOf(it) + flatten(it.blocks) else listOf(it) }

    /** Every cell in every table of [doc], header cells included. */
    private fun cells(doc: org.jsoup.nodes.Document): Int = doc.select("table td, table th").size

    private companion object {
        const val MIN_EXCERPT_RATIO = 10.0

        /** 6 header cells + 37 CVEs × 6 columns, as harvested. */
        const val EXPECTED_ZDI_CELLS = 228
    }
}
