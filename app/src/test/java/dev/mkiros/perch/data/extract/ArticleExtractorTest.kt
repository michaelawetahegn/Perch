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

    /**
     * H01/#83: Paul Graham's pages are laid out with `<table>` — the essay sits in one cell of
     * a nested one-cell table, its paragraphs split by `<br><br>`. Lowered as data, the whole
     * essay became one flattened cell of a one-row grid; lowered as layout it is paragraphs.
     */
    @Test
    fun `a page laid out in tables lowers to its paragraphs, not a grid`() {
        val fixture = ArticleFixtures.paulgraham
        val extracted = requireNotNull(ArticleExtractor.extract(fixture.html(), fixture.url))
        val blocks = flatten(ArticleLowering.toBlocks(HtmlSanitizer.sanitize(extracted, fixture.url)))
        val paragraphs = blocks.filterIsInstance<ArticleBlock.Paragraph>().map { it.text.text }

        assertThat(blocks.filterIsInstance<ArticleBlock.Table>()).isEmpty()
        assertThat(paragraphs.size).isAtLeast(20)
        assertThat(paragraphs.count { fixture.mid in it }).isEqualTo(1)
        assertThat(paragraphs.count { fixture.last in it }).isEqualTo(1)
        assertThat(paragraphs.single { fixture.mid in it }).doesNotContain(fixture.last)
    }

    /**
     * J01/#85: IEEE Spectrum's three in-body photos sit behind `data-runner-src`, with a
     * `data:` placeholder in `src` that the allowlist drops; they used to vanish.
     */
    @Test
    fun `a page whose photos load lazily keeps them`() {
        val fixture = ArticleFixtures.ieeeSpectrum
        val extracted = requireNotNull(ArticleExtractor.extract(fixture.html(), fixture.url))
        val images = flatten(ArticleLowering.toBlocks(HtmlSanitizer.sanitize(extracted, fixture.url)))
            .filterIsInstance<ArticleBlock.Image>()

        assertThat(images).hasSize(3)
        assertThat(images.first().url).endsWith("background.jpg?id=67857167&width=980")
    }

    /**
     * J02/#85: each IEEE photo is followed by a caption-classed `small` and a credit-classed
     * one. They used to fall through as a loose paragraph, run together with no space.
     */
    @Test
    fun `a photo's caption and credit read as one caption under it`() {
        val fixture = ArticleFixtures.ieeeSpectrum
        val extracted = requireNotNull(ArticleExtractor.extract(fixture.html(), fixture.url))
        val blocks = flatten(ArticleLowering.toBlocks(HtmlSanitizer.sanitize(extracted, fixture.url)))
        val images = blocks.filterIsInstance<ArticleBlock.Image>()
        val paragraphs = blocks.filterIsInstance<ArticleBlock.Paragraph>().map { it.text.text }

        assertThat(images).hasSize(3)
        assertThat(images.map { it.caption?.text }).doesNotContain(null)
        assertThat(images.first().caption!!.text.trim()).isEqualTo(
            "Michael Bloomberg believed Wall Street would pay a premium for access to specialized financial data. — Karjean Levine/Getty Images",
        )
        assertThat(paragraphs.filter { "Getty Images" in it }).isEmpty()
        assertThat(paragraphs.filter { "National Museum of American History/Smithsonian" in it }).isEmpty()
    }

    /** H02/#83: an image map is a navigation widget — its links live in `<area>`, not in the picture. */
    @Test
    fun `an image-map navigation picture does not survive extraction`() {
        val html = """
            <html><body><article>
              <img usemap="#m" src="/nav.gif"><map name="m"><area href="/a"></map>
              <p>${"Long enough to score. ".repeat(20)}</p>
            </article></body></html>
        """.trimIndent()

        val extracted = requireNotNull(ArticleExtractor.extract(html, "https://example.com/posts/one.html"))

        assertThat(extracted).doesNotContain("nav.gif")
    }

    /**
     * H02/#83: a picture whose only job is linking to the site's home page is the logo. The
     * href is relative so resolution is what is tested: `../index.html` from `/posts/` is the
     * root, where a bare `index.html` would be `/posts/index.html`, a section and not home.
     */
    @Test
    fun `a picture that only links home is the site logo, while a linked figure stays`() {
        val html = """
            <html><body><article>
              <a href="../index.html"><img src="/logo.gif"></a>
              <p>${"Long enough to score. ".repeat(20)}</p>
              <p><a href="/posts/two.html"><img src="/figure.png"></a></p>
            </article></body></html>
        """.trimIndent()

        val extracted = requireNotNull(ArticleExtractor.extract(html, "https://example.com/posts/one.html"))

        assertThat(extracted).doesNotContain("logo.gif")
        assertThat(extracted).contains("figure.png")
    }

    /**
     * H02/#83: of PG's four pictures only the author's own title GIF is the article's. The
     * 1×26 spacer is the sanitizer's to drop, so pixels are left out as it would; [images]
     * is not used because its icon ceiling would also drop the 18-pixel-high title.
     */
    @Test
    fun `a table-laid page keeps its title picture and loses its navigation and logo`() {
        val fixture = ArticleFixtures.paulgraham
        val extracted = requireNotNull(ArticleExtractor.extract(fixture.html(), fixture.url))
        val pictures = Jsoup.parse(extracted, fixture.url).select("img")
            .filterNot { HtmlSanitizer.isTrackingPixel(it) }
            .map { it.attr("abs:src") }

        assertThat(pictures)
            .containsExactly("https://s.turbifycdn.com/aah/paulgraham/making-startups-powerful-1.gif")
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

    /**
     * #70: Bellingcat wraps every figure in `<div class="media">`, and `media` is one of
     * the extractor's chrome tokens, so the unlikely-candidate sweep deleted each image
     * *with its container* and the reader got an article with no pictures. A container
     * named for its role that is mostly an image is the content, not the chrome.
     */
    @Test
    fun `a container named media keeps the image it wraps`() {
        val paragraphs = (1..10).joinToString("\n") { "<p>Paragraph $it, ${"long enough to score, ".repeat(4)}</p>" }
        val html = """
            <html><body><article>
              ${paragraphs.substringBefore("<p>Paragraph 6")}
              <div class="media"><img src="https://x/a.jpg" alt="a figure"></div>
              ${"<p>Paragraph 6" + paragraphs.substringAfter("<p>Paragraph 6")}
            </article></body></html>
        """.trimIndent()

        val extracted = requireNotNull(ArticleExtractor.extract(html, "https://example.com/post/"))

        assertThat(Jsoup.parse(extracted).select("img[src=https://x/a.jpg]")).hasSize(1)
    }

    /**
     * The same defect measured on the page the reader reported. The reference is the page
     * with every `<div class="media">` unwrapped — the markup a theme without that wrapper
     * would have shipped — and the claim is that the wrapper changes nothing about which
     * images survive. What the reference drops (a related-articles thumbnail, the
     * scroll-driven interactive's step icons) the extraction may drop too; what it keeps
     * the extraction must keep. The count is pinned so the assertion cannot pass on nothing.
     */
    @Test
    fun `a container named media around every figure changes nothing about which images survive`() {
        val fixture = ArticleFixtures.bellingcat
        val unwrapped = Jsoup.parse(fixture.html(), fixture.url)
            .apply { select("div.media").forEach { it.unwrap() } }
            .outerHtml()
        val reference = images(requireNotNull(ArticleExtractor.extract(unwrapped, fixture.url)), fixture.url)
        assertThat(reference).hasSize(EXPECTED_BELLINGCAT_IMAGES)

        val kept = images(requireNotNull(ArticleExtractor.extract(fixture.html(), fixture.url)), fixture.url)

        assertThat(kept).containsExactlyElementsIn(reference)
    }

    /**
     * #67, on the page the reader reported: three legacy-shortcode captions, each a
     * `div.wp-caption` with an `img[aria-describedby]` and the `p.wp-caption-text` it names.
     * Extracted, sanitized and lowered, each caption must ride under its image, and none may
     * be left standing as a paragraph of body text.
     */
    @Test
    fun `a wordpress caption page lowers its captions under their images, not as paragraphs`() {
        val fixture = ArticleFixtures.gijn
        val html = requireNotNull(ArticleExtractor.extract(fixture.html(), fixture.url))
        val blocks = flatten(ArticleLowering.toBlocks(HtmlSanitizer.sanitize(html, fixture.url)))

        assertThat(blocks.filterIsInstance<ArticleBlock.Image>().count { it.caption != null }).isAtLeast(3)
        assertThat(
            blocks.filterIsInstance<ArticleBlock.Paragraph>().map { it.text.text }
                .filter { it.startsWith("Zubaida Baba Ibrahim records") },
        ).isEmpty()
    }

    /**
     * The distinct figure URLs in [html], icons aside: the 27-pixel glyph in the donate
     * block is F04's to remove, and this count must not move when it does.
     */
    private fun images(html: String, baseUrl: String): Set<String> =
        Jsoup.parse(html, baseUrl).select("img")
            .filter { img -> listOf("width", "height").none { (img.attr(it).toIntOrNull() ?: Int.MAX_VALUE) <= ICON_PX } }
            .map { it.attr("abs:src") }
            .toSet()

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

        /** An image declared this small or smaller is an icon or a pixel, not a figure. */
        const val ICON_PX = 48

        /** Distinct figure URLs the Bellingcat body keeps without its `media` wrappers. */
        const val EXPECTED_BELLINGCAT_IMAGES = 8
    }
}
