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

    private companion object {
        const val MIN_EXCERPT_RATIO = 10.0
    }
}
