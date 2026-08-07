package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import java.io.File
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

    // ---- a real blob from the corpus ---------------------------------------------

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
