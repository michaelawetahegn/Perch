package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * T25a — the normalization layer, per SPEC.md §5 and DESIGN.md §8.
 *
 * Forty-two sources ship forty-two HTML dialects; this is where they stop being
 * different. The renderer (T25) sees only [ArticleBlock], so every rule about what an
 * article may look like is asserted here, on the JVM, with no Compose in sight.
 */
class ArticleLoweringTest {

    private fun lower(html: String?) = ArticleLowering.toBlocks(html)

    private fun paragraphs(html: String?) =
        lower(html).filterIsInstance<ArticleBlock.Paragraph>().map { it.text.text }

    // --- Paragraphs and inline spans -------------------------------------------------

    @Test
    fun `a paragraph keeps its words and collapses the CMS whitespace`() {
        assertThat(paragraphs("<p>Hello   there,\n  reader.</p>"))
            .containsExactly("Hello there, reader.")
    }

    @Test
    fun `emphasis strong code sub and sup survive as marks over their own ranges`() {
        val span = (lower(
            "<p>a <em>em</em> <strong>str</strong> <code>cd</code> <sub>sb</sub> <sup>sp</sup></p>",
        ).single() as ArticleBlock.Paragraph).text

        assertThat(span.text).isEqualTo("a em str cd sb sp")
        assertThat(span.marks).containsExactly(
            RichSpan.Mark(SpanStyle.Em, 2, 4),
            RichSpan.Mark(SpanStyle.Strong, 5, 8),
            RichSpan.Mark(SpanStyle.InlineCode, 9, 11),
            RichSpan.Mark(SpanStyle.Sub, 12, 14),
            RichSpan.Mark(SpanStyle.Sup, 15, 17),
        )
    }

    @Test
    fun `an anchor becomes a link mark carrying its href`() {
        val span = (lower("<p>See <a href=\"https://x.example/p\">here</a>.</p>").single()
            as ArticleBlock.Paragraph).text

        assertThat(span.text).isEqualTo("See here.")
        assertThat(span.marks).containsExactly(
            RichSpan.Mark(SpanStyle.Link("https://x.example/p"), 4, 8),
        )
    }

    @Test
    fun `the source gets no vote on typography`() {
        val span = (lower(
            """<p style="color:red" align="center">Red <font color="blue" size="7">blue</font>
               <span style="font-size:40px">big</span></p>""",
        ).single() as ArticleBlock.Paragraph).text

        assertThat(span.text).isEqualTo("Red blue big")
        assertThat(span.marks).isEmpty()
    }

    @Test
    fun `wrapper soup collapses into one paragraph`() {
        assertThat(paragraphs("<div><div><span>Hi</span> <span>there</span></div></div>"))
            .containsExactly("Hi there")
    }

    // --- Breaks ----------------------------------------------------------------------

    @Test
    fun `a single br stays inside its paragraph`() {
        assertThat(paragraphs("<p>Roses are red<br>violets are blue</p>"))
            .containsExactly("Roses are red\nviolets are blue")
    }

    @Test
    fun `a double br is one paragraph break, not an empty paragraph`() {
        assertThat(paragraphs("<p>First<br><br><br>Second</p>"))
            .containsExactly("First", "Second").inOrder()
    }

    @Test
    fun `runs of empty paragraphs fold away entirely`() {
        assertThat(paragraphs("<p>A</p><p></p><p>&nbsp;</p><p><br></p><p>  </p><p>B</p>"))
            .containsExactly("A", "B").inOrder()
    }

    // --- Headings --------------------------------------------------------------------

    @Test
    fun `headings flatten to level two and three only`() {
        val levels = lower("<h1>a</h1><h2>b</h2><h3>c</h3><h4>d</h4><h5>e</h5><h6>f</h6>")
            .filterIsInstance<ArticleBlock.Heading>()
            .map { it.level }

        assertThat(levels).containsExactly(2, 2, 3, 3, 3, 3).inOrder()
    }

    // --- Code ------------------------------------------------------------------------

    @Test
    fun `a pre block keeps its code verbatim`() {
        val code = lower("<pre><code>fun main() {\n    println(\"hi &amp; bye\")\n}\n</code></pre>")
            .single() as ArticleBlock.Code

        assertThat(code.text).isEqualTo("fun main() {\n    println(\"hi & bye\")\n}")
    }

    // --- Images ----------------------------------------------------------------------

    @Test
    fun `a bare img becomes an image with its alt text and no caption`() {
        val image = lower("<img src=\"https://x.example/a.png\" alt=\"A bird\">").single()
            as ArticleBlock.Image

        assertThat(image.url).isEqualTo("https://x.example/a.png")
        assertThat(image.alt).isEqualTo("A bird")
        assertThat(image.caption).isNull()
    }

    @Test
    fun `a figure unwraps into an image carrying its figcaption`() {
        val image = lower(
            """<figure><img src="https://x.example/a.png"><figcaption>A <em>bird</em>
               </figcaption></figure>""",
        ).single() as ArticleBlock.Image

        assertThat(image.alt).isNull()
        assertThat(image.caption?.text).isEqualTo("A bird")
        assertThat(image.caption?.marks).containsExactly(RichSpan.Mark(SpanStyle.Em, 2, 6))
    }

    @Test
    fun `an image inside a paragraph ends the paragraph rather than nesting`() {
        val blocks = lower("<p>Before <img src=\"https://x.example/a.png\"> after</p>")

        assertThat(blocks.map { it::class.simpleName })
            .containsExactly("Paragraph", "Image", "Paragraph").inOrder()
        assertThat((blocks.first() as ArticleBlock.Paragraph).text.text).isEqualTo("Before")
        assertThat((blocks.last() as ArticleBlock.Paragraph).text.text).isEqualTo("after")
    }

    // --- Quotes, lists, tables, rules -------------------------------------------------

    @Test
    fun `a blockquote holds its own blocks`() {
        val quote = lower("<blockquote><p>Quoted</p><p>More</p></blockquote>").single()
            as ArticleBlock.Quote

        assertThat(quote.blocks.filterIsInstance<ArticleBlock.Paragraph>().map { it.text.text })
            .containsExactly("Quoted", "More").inOrder()
    }

    @Test
    fun `lists keep their order and flatten nesting into further items`() {
        val unordered = lower("<ul><li>one</li><li>two<ul><li>two a</li></ul></li></ul>").single()
            as ArticleBlock.ListBlock
        val ordered = lower("<ol><li>first</li><li>second</li></ol>").single()
            as ArticleBlock.ListBlock

        assertThat(unordered.ordered).isFalse()
        assertThat(unordered.items.map { it.text }).containsExactly("one", "two", "two a").inOrder()
        assertThat(ordered.ordered).isTrue()
        assertThat(ordered.items.map { it.text }).containsExactly("first", "second").inOrder()
    }

    @Test
    fun `a table splits its header row from its body rows`() {
        val table = lower(
            """<table><thead><tr><th>Year</th><th>Bird</th></tr></thead>
               <tbody><tr><td>2024</td><td>Swift</td></tr>
               <tr><td>2025</td><td>Perch</td></tr></tbody></table>""",
        ).single() as ArticleBlock.Table

        assertThat(table.header.map { it.text }).containsExactly("Year", "Bird").inOrder()
        assertThat(table.rows.map { row -> row.map { it.text } })
            .containsExactly(listOf("2024", "Swift"), listOf("2025", "Perch")).inOrder()
    }

    @Test
    fun `a headerless table puts every row in the body`() {
        val table = lower("<table><tr><td>a</td><td>b</td></tr></table>").single()
            as ArticleBlock.Table

        assertThat(table.header).isEmpty()
        assertThat(table.rows).hasSize(1)
    }

    @Test
    fun `one th anywhere in the first row makes it the header`() {
        val table = lower(
            "<table><tr><th>CVE</th><td>Impact</td></tr>" +
                "<tr><td>CVE-2026-1</td><td>RCE</td></tr></table>",
        ).single() as ArticleBlock.Table

        assertThat(table.header.map { it.text }).containsExactly("CVE", "Impact").inOrder()
        assertThat(table.rows).hasSize(1)
    }

    @Test
    fun `a colspan cell is padded out so the columns beside it stay aligned`() {
        val table = lower(
            """<table><tr><th>A</th><th>B</th><th>C</th></tr>
               <tr><td colspan="2">wide</td><td>c</td></tr>
               <tr><td>a</td><td>b</td><td>c</td></tr></table>""",
        ).single() as ArticleBlock.Table

        assertThat(table.header).hasSize(3)
        assertThat(table.rows.map { row -> row.map { it.text } })
            .containsExactly(listOf("wide", "", "c"), listOf("a", "b", "c")).inOrder()
    }

    @Test
    fun `a rowspan cell holds its column open in the rows beneath it`() {
        val table = lower(
            """<table><tr><th>A</th><th>B</th></tr>
               <tr><td rowspan="2">tall</td><td>b1</td></tr>
               <tr><td>b2</td></tr></table>""",
        ).single() as ArticleBlock.Table

        assertThat(table.rows.map { row -> row.map { it.text } })
            .containsExactly(listOf("tall", "b1"), listOf("", "b2")).inOrder()
    }

    @Test
    fun `a short row is padded to the width of the widest one`() {
        val table = lower(
            "<table><tr><td>a</td><td>b</td><td>c</td></tr><tr><td>d</td></tr></table>",
        ).single() as ArticleBlock.Table

        assertThat(table.rows.map { it.size }).containsExactly(3, 3).inOrder()
        assertThat(table.rows[1].map { it.text }).containsExactly("d", "", "").inOrder()
    }

    @Test
    fun `an hr becomes a rule`() {
        assertThat(lower("<p>a</p><hr><p>b</p>")[1]).isEqualTo(ArticleBlock.Rule)
    }

    // --- Totality --------------------------------------------------------------------

    @Test
    fun `an unrecognised element becomes Unsupported carrying its tag, never a silent drop`() {
        assertThat(lower("<p>a</p><video src=\"https://x.example/v.mp4\"></video>"))
            .contains(ArticleBlock.Unsupported("video"))
    }

    @Test
    fun `nothing renderable lowers to no blocks at all`() {
        assertThat(lower(null)).isEmpty()
        assertThat(lower("")).isEmpty()
        assertThat(lower("   \n  ")).isEmpty()
        assertThat(lower("<p></p><div><span> </span></div>")).isEmpty()
    }

    @Test
    fun `truncated markup lowers without throwing`() {
        assertThat(paragraphs("<p>unclosed <em>emphasis <a href=\"https://x.example\">link"))
            .containsExactly("unclosed emphasis link")
    }

    // --- Chrome stripping -------------------------------------------------------------

    @Test
    fun `the wordpress appeared-first-on footer is stripped, and the rule above it with it`() {
        val blocks = lower(
            """<p>The body.</p><hr>
               <p>The post <a href="https://x.example/p">My Post</a> appeared first on
               <a href="https://x.example">Blog</a>.</p>""",
        )

        assertThat(blocks.map { (it as ArticleBlock.Paragraph).text.text })
            .containsExactly("The body.")
    }

    @Test
    fun `read more stubs, share widgets, subscribe CTAs and comment counts are stripped`() {
        val chrome = listOf(
            "<p><a href=\"https://x.example/p\">Read more →</a></p>",
            "<p>Continue reading…</p>",
            "<p>Share this:</p>",
            "<p>Tweet</p>",
            "<p>Follow me on Mastodon</p>",
            "<p>Subscribe to the newsletter for weekly posts.</p>",
            "<p>3 Comments</p>",
            "<p>Leave a comment</p>",
        )

        chrome.forEach { assertThat(lower("<p>Real body text.</p>$it")).hasSize(1) }
    }

    @Test
    fun `chrome stripping does not eat a real sentence that merely starts the same way`() {
        val body = "<p>Share this idea with your team, then read more of the archive to see " +
            "how the practice evolved over the following decade of releases.</p>"

        assertThat(paragraphs(body)).hasSize(1)
    }
}
