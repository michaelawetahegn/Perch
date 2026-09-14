package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.jsoup.Jsoup
import org.junit.Test

/**
 * The sanitizer allowlist and the plain-text snippet, per SPEC.md §5. Everything here is
 * hand-written except the last case, which is a real `content:encoded` blob from the
 * corpus — the shape the app actually has to survive.
 */
class HtmlSanitizerTest {

    private val base = "https://birdwire.example/posts/first"

    private fun sanitize(html: String?) = HtmlSanitizer.sanitize(html, base)

    // ---- what must not survive ---------------------------------------------------

    @Test
    fun `script elements are removed with their code`() {
        val out = sanitize("<p>Before</p><script>alert('pwned')</script><p>After</p>")

        assertThat(out).doesNotContain("script")
        assertThat(out).doesNotContain("alert")
        assertThat(out).contains("Before")
        assertThat(out).contains("After")
    }

    @Test
    fun `event handler attributes are stripped from allowed elements`() {
        val out = sanitize("""<p onclick="steal()">Tap me</p>""")

        assertThat(out).doesNotContain("onclick")
        assertThat(out).doesNotContain("steal")
        assertThat(out).contains("Tap me")
    }

    @Test
    fun `a javascript href is dropped but its text is kept`() {
        val out = sanitize("""<p><a href="javascript:steal()">Click</a></p>""")

        assertThat(out).doesNotContain("javascript")
        assertThat(out).doesNotContain("href")
        assertThat(out).contains("Click")
    }

    @Test
    fun `styles iframes and forms are removed`() {
        val out = sanitize(
            """
            <style>p{display:none}</style>
            <iframe src="https://ads.example/frame"></iframe>
            <form action="/subscribe"><input name="email"></form>
            <p style="color:red" class="lede">Body</p>
            """
        )

        assertThat(out).doesNotContain("iframe")
        assertThat(out).doesNotContain("ads.example")
        assertThat(out).doesNotContain("display:none")
        assertThat(out).doesNotContain("<form")
        assertThat(out).doesNotContain("style=")
        assertThat(out).doesNotContain("class=")
        assertThat(out).contains("Body")
    }

    @Test
    fun `tracking pixels are dropped and real images are kept`() {
        val out = sanitize(
            """
            <p>Text</p>
            <img src="https://track.example/px.gif" width="1" height="1" alt="">
            <img src="https://birdwire.example/hero.png" width="800" height="600" alt="A hero">
            """
        )

        assertThat(out).doesNotContain("track.example")
        assertThat(out).contains("https://birdwire.example/hero.png")
        assertThat(out).contains("A hero")
    }

    @Test
    fun `an image with no usable source is dropped`() {
        val out = sanitize("""<p>Text</p><img alt="broken"><img src="data:image/gif;base64,R0lGOD">""")

        assertThat(out).doesNotContain("<img")
        assertThat(out).doesNotContain("data:")
        assertThat(out).contains("Text")
    }

    /**
     * F03/#70: a lazy-loaded image's `src` is a placeholder and the picture is in a
     * `data-*` attribute. The page path promoted it (`ArticleExtractor`); the feed path
     * did not, so the same markup in `content:encoded` lost every figure.
     */
    @Test
    fun `a lazy image's data-src becomes its src`() {
        val out = sanitize(
            """
            <p>Text</p>
            <img src="data:image/gif;base64,R0lGOD" data-src="/img/real.jpg" width="800" height="600" alt="real">
            <img data-lazy-src="https://cdn.example/b.jpg" alt="also real">
            """
        )

        assertThat(out).contains("https://birdwire.example/img/real.jpg")
        assertThat(out).contains("https://cdn.example/b.jpg")
        assertThat(out).doesNotContain("data:")
    }

    @Test
    fun `an image with only a srcset takes the widest candidate`() {
        val out = sanitize(
            """
            <p>Text</p>
            <img srcset="/img/a-300.jpg 300w, /img/a-1200.jpg 1200w, /img/a-768.jpg 768w" alt="a">
            <img src="/img/b.jpg" srcset="/img/b-2000.jpg 2000w" alt="b">
            """
        )

        assertThat(out).contains("https://birdwire.example/img/a-1200.jpg")
        assertThat(out).doesNotContain("a-300")
        assertThat(out).doesNotContain("a-768")
        // A real `src` is the publisher's choice; the srcset is only a fallback for its absence.
        assertThat(out).contains("https://birdwire.example/img/b.jpg")
        assertThat(out).doesNotContain("b-2000")
    }

    // ---- what must survive --------------------------------------------------------

    @Test
    fun `the allowlisted structural elements survive`() {
        val out = sanitize(
            """
            <h2>Heading</h2>
            <p>Body with <em>emphasis</em>, <strong>weight</strong> and <code>code()</code>.</p>
            <ul><li>one</li><li>two</li></ul>
            <ol><li>first</li></ol>
            <blockquote><p>Quoted</p></blockquote>
            <pre><code>fun main() {}</code></pre>
            <figure><img src="/img/a.png" alt="A"><figcaption>Caption</figcaption></figure>
            <table><thead><tr><th>H</th></tr></thead><tbody><tr><td>D</td></tr></tbody></table>
            <p>x<sub>1</sub> y<sup>2</sup></p>
            <hr>
            """
        )

        for (tag in listOf(
            "h2", "p", "em", "strong", "code", "ul", "ol", "li", "blockquote", "pre",
            "figure", "figcaption", "table", "thead", "tbody", "tr", "th", "td",
            "sub", "sup", "hr",
        )) {
            assertThat(out).contains("<$tag")
        }
    }

    @Test
    fun `cell spans survive because they are structure, not the source voting on style`() {
        val out = sanitize(
            """<table><tr><td colspan="2" style="color:red" align="center">wide</td>
               <td rowspan="3">tall</td></tr></table>""",
        )

        assertThat(out).contains("colspan=\"2\"")
        assertThat(out).contains("rowspan=\"3\"")
        assertThat(out).doesNotContain("style")
        assertThat(out).doesNotContain("align")
    }

    @Test
    fun `a disallowed wrapper is unwrapped rather than deleted`() {
        val out = sanitize("""<div class="post"><section><p>Kept</p></section></div>""")

        assertThat(out).doesNotContain("<div")
        assertThat(out).doesNotContain("<section")
        assertThat(out).contains("<p>Kept</p>")
    }

    @Test
    fun `relative hrefs and sources are resolved against the entry link`() {
        val out = sanitize(
            """<p><a href="../second">Next</a></p><img src="/img/hero.png" alt="Hero">"""
        )

        assertThat(out).contains("https://birdwire.example/second")
        assertThat(out).contains("https://birdwire.example/img/hero.png")
    }

    @Test
    fun `relative urls are dropped when there is no base to resolve against`() {
        val out = HtmlSanitizer.sanitize("""<p><a href="/second">Next</a></p>""", null)

        assertThat(out).doesNotContain("href")
        assertThat(out).contains("Next")
    }

    @Test
    fun `entities including nested quotes are decoded once and re-escaped safely`() {
        val out = sanitize("""<p>He said &#8220;yes&quot; &amp; left &lt;the room&gt;</p>""")

        assertThat(HtmlSanitizer.summarize(out)).isEqualTo("""He said “yes" & left <the room>""")
        assertThat(out).contains("&amp;")
        assertThat(out).contains("&lt;the room&gt;")
    }

    @Test
    fun `truncated and empty markup never throws`() {
        assertThat(sanitize("<p>open <b>bold <img src=\"/a.png\"")).isNotNull()
        assertThat(sanitize("")).isNull()
        assertThat(sanitize(null)).isNull()
        assertThat(sanitize("<p>   </p>")).isNull()
    }

    // ---- the plain-text snippet ---------------------------------------------------

    @Test
    fun `a short summary is the whole text with tags gone and whitespace collapsed`() {
        val summary = HtmlSanitizer.summarize("<h2>Title</h2>\n<p>Two   words.</p>\n<p>More.</p>")

        assertThat(summary).isEqualTo("Title Two words. More.")
    }

    @Test
    fun `a long summary is cut on a word boundary within the limit`() {
        val body = "<p>" + "wren ".repeat(200) + "</p>"

        val summary = HtmlSanitizer.summarize(body)!!

        assertThat(summary.length).isAtMost(300)
        assertThat(summary).endsWith("…")
        assertThat(summary.removeSuffix("…")).endsWith("wren")
        assertThat(summary).startsWith("wren wren")
    }

    @Test
    fun `an empty or text-free document has no summary`() {
        assertThat(HtmlSanitizer.summarize(null)).isNull()
        assertThat(HtmlSanitizer.summarize("<p> </p><img src=\"/a.png\">")).isNull()
    }

    // ---- promotional blocks (F04, #70) ------------------------------------------

    @Test
    fun `a donate block with a button and a sentence is dropped`() {
        // Bellingcat's Gutenberg shape: no aside, no role, no form — only class tokens name it.
        val out = sanitize(
            """
            <p>The visas were issued in 2019.</p>
            <div class="wp-block-bellingcat-donate-block"><div>
              <div class="wp-block-image alignleft"><figure><div class="media">
                <img src="/q.png" alt="" width="27" height="27"></div></figure></div>
              <h2>Support Bellingcat</h2>
              <p>Your donations directly contribute to our ability to publish investigations.</p>
              <div class="wp-block-buttons"><div class="wp-block-button">
                <a class="wp-block-button__link" href="https://bellingcat.com/donate">Donate</a>
              </div></div>
            </div></div>
            <p>The cartel's members arrived on them.</p>
            """.trimIndent(),
        )!!

        assertThat(out).doesNotContain("Support")
        assertThat(out).doesNotContain("Your donations")
        assertThat(out).doesNotContain("q.png")
        assertThat(out).contains("<p>The visas were issued in 2019.</p>")
        assertThat(out).contains("<p>The cartel's members arrived on them.</p>")
    }

    @Test
    fun `a section whose id merely contains donation but holds real prose survives`() {
        val paragraph = "<p>" + "Every year the records are audited by an outside firm. ".repeat(4) + "</p>"
        val out = sanitize("""<section id="donation-records">$paragraph$paragraph$paragraph</section>""")!!

        assertThat(out).contains("audited by an outside firm")
        assertThat(Regex("<p>").findAll(out).count()).isEqualTo(3)
    }

    @Test
    fun `a promo paragraph with no link survives`() {
        val out = sanitize("""<p class="promo">Our next issue ships in March.</p>""")!!

        assertThat(out).isEqualTo("<p>Our next issue ships in March.</p>")
    }

    // ---- a real blob from the corpus ---------------------------------------------

    // ---- captions (F05, #67) --------------------------------------------------------

    /**
     * F05/#67: GIJN's captions are WordPress's legacy shortcode — no `<figure>`, only a
     * `div.wp-caption` holding an `img[aria-describedby]` and the `p` it names — so the
     * caption lowered to an ordinary paragraph and read as body text. WAI-ARIA is the
     * standard; the rewrite turns the described image into the figure the CMS meant.
     */
    @Test
    fun `an image whose aria-describedby names a paragraph becomes a figure with that paragraph as its figcaption`() {
        val out = sanitize(
            """
            <p>Access is negotiated through local trust.</p>
            <div id="attachment_3218477" style="width: 312px" class="wp-caption alignright">
              <img loading="lazy" aria-describedby="caption-attachment-3218477" class=" wp-image-3218477"
                   src="https://gijn.org/wp-content/uploads/2026/09/Zubaida-Ibrahim-771x618.png"
                   alt="Zubaida records an interview" width="302" height="242">
              <p id="caption-attachment-3218477" class="wp-caption-text">Zubaida Baba Ibrahim records
                 an interview. Image: <em>Courtesy of Ibrahim</em></p>
            </div>
            <p>Dembélé faced a similar situation in Menaka.</p>
            """.trimIndent(),
        )!!

        val figures = Jsoup.parse(out).select("figure")
        assertThat(figures).hasSize(1)
        assertThat(figures.single().select("img[src$=Zubaida-Ibrahim-771x618.png]")).hasSize(1)
        assertThat(figures.single().selectFirst("figcaption")!!.text())
            .isEqualTo("Zubaida Baba Ibrahim records an interview. Image: Courtesy of Ibrahim")
        assertThat(figures.single().selectFirst("figcaption em")!!.text()).isEqualTo("Courtesy of Ibrahim")
        // The described paragraph moved into the figure; it is not also left behind as prose.
        assertThat(Jsoup.parse(out).select("p").eachText())
            .containsExactly("Access is negotiated through local trust.", "Dembélé faced a similar situation in Menaka.")
    }

    /** The same shape without ARIA: the caption is named only by a class token on the sibling. */
    @Test
    fun `a container of one image and one caption-classed paragraph becomes a figure`() {
        val out = sanitize(
            """
            <div class="image-block">
              <img src="/harbour.jpg" alt="The harbour">
              <p class="photo-credit">The harbour at dawn. Photo: A. Reader</p>
            </div>
            <p>The tide was out.</p>
            """.trimIndent(),
        )!!

        val figure = Jsoup.parse(out).selectFirst("figure")!!
        assertThat(figure.select("img[src=https://birdwire.example/harbour.jpg]")).hasSize(1)
        assertThat(figure.selectFirst("figcaption")!!.text()).isEqualTo("The harbour at dawn. Photo: A. Reader")
        assertThat(Jsoup.parse(out).select("p").eachText()).containsExactly("The tide was out.")
    }

    /**
     * Nothing looser: "a different font and colour under an image" cannot be seen once `style`
     * is gone, and an emphasised paragraph after a lead image is as often a pull-quote or an
     * editor's note as a caption. Without ARIA or a caption-classed sibling it stays prose.
     */
    @Test
    fun `an italic paragraph after an image stays a paragraph`() {
        val out = sanitize(
            """
            <p><img src="/lead.jpg" alt="Lead"></p>
            <p><em>Editor's note: this story is the second in a series.</em></p>
            <p>Access is negotiated through local trust.</p>
            """.trimIndent(),
        )!!

        val doc = Jsoup.parse(out)
        assertThat(doc.select("figure")).isEmpty()
        assertThat(doc.select("img")).hasSize(1)
        assertThat(doc.select("p em").eachText()).containsExactly("Editor's note: this story is the second in a series.")
    }

    @Test
    fun `a real content encoded blob keeps its prose and loses its markup cruft`() {
        val entry = krebsFirstEntry()
        val raw = entry.contentHtml!!

        val out = HtmlSanitizer.sanitize(raw, entry.link)!!

        // The source has WordPress caption wrappers, inline styles and sized images.
        assertThat(raw).contains("<div")
        assertThat(out).doesNotContain("<div")
        assertThat(out).doesNotContain("style=")
        assertThat(out).doesNotContain("class=")
        assertThat(out).contains("<p>")
        assertThat(out).contains("Snowflake")
        assertThat(out).contains("<img")
        // Every surviving src is absolute http(s).
        val sources = Regex("""src="([^"]*)"""").findAll(out).map { it.groupValues[1] }.toList()
        assertThat(sources).isNotEmpty()
        assertThat(sources.all { it.startsWith("http://") || it.startsWith("https://") }).isTrue()

        val summary = HtmlSanitizer.summarize(out)!!
        assertThat(summary.length).isAtMost(300)
        assertThat(summary).doesNotContain("<")
        assertThat(summary).startsWith("A 26-year-old Canadian man")
    }

    private fun krebsFirstEntry(): ParsedEntry {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "fixtures/snapshots").isDirectory) dir = dir.parentFile
        val file = File(checkNotNull(dir), "fixtures/snapshots/krebsonsecurity-com.xml")
        val url = "https://krebsonsecurity.com/feed/"
        val result = FeedParser().parse(file.readBytes(), null, url)
        return (result as ParseResult.Success).feed.entries.first()
    }
}
