package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * The language claim's journey from a publisher's markup to [ArticleBlock.Code.language]
 * (U11).
 *
 * `class` is on the wrong side of DESIGN.md §8's line — the whole point of the safelist is
 * that a feed gets no vote on presentation — so this exists to pin the exactly-one
 * exception: `pre` keeps a `class`, it only ever holds `language-x`, and everything else a
 * CMS wrote there is gone by the time the renderer sees it.
 */
class CodeLanguageClassTest {

    @Test
    fun `a language on the code element reaches the block`() {
        val block = lowerOne("""<pre><code class="language-kotlin">fun main() {}</code></pre>""")

        assertThat(block.language).isEqualTo("kotlin")
        assertThat(block.text).isEqualTo("fun main() {}")
    }

    @Test
    fun `a language on the pre element reaches the block`() {
        assertThat(lowerOne("""<pre class="lang-python">print(1)</pre>""").language)
            .isEqualTo("python")
    }

    @Test
    fun `a language on a wrapper div two levels up is hoisted down`() {
        // Jekyll + Rouge, which is what nullprogram.com ships for every one of its blocks.
        val html = """
            <div class="language-c highlighter-rouge">
              <div class="highlight"><pre class="highlight"><code>int main(void);</code></pre></div>
            </div>
        """.trimIndent()

        assertThat(lowerOne(html).language).isEqualTo("c")
    }

    @Test
    fun `a block with no claim anywhere carries none`() {
        assertThat(lowerOne("<pre><code>hello</code></pre>").language).isNull()
    }

    @Test
    fun `presentational classes never survive the sanitizer`() {
        val out = HtmlSanitizer.sanitize(
            """<pre class="highlight sourceCode wp-block-code"><code>x</code></pre>""",
            null,
        )

        assertThat(out).doesNotContain("class=")
    }

    @Test
    fun `class is kept on pre and nowhere else`() {
        val out = HtmlSanitizer.sanitize(
            """<p class="lead">Lead</p><pre class="language-go"><code class="hljs">x</code></pre>""",
            null,
        )

        assertThat(out).contains("""<pre class="language-go">""")
        assertThat(out).doesNotContain("""<p class=""")
        assertThat(out).doesNotContain("hljs")
    }

    @Test
    fun `the real nullprogram corpus feed yields declared languages`() {
        val snapshot = File(repoRoot(), "fixtures/snapshots/nullprogram-com.xml")
        val parsed = FeedParser().parse(snapshot.readBytes(), null, "https://nullprogram.com/")
        val entries = (parsed as ParseResult.Success).feed.entries

        val languages = entries
            .mapNotNull { HtmlSanitizer.sanitize(it.contentHtml, it.link) }
            .flatMap { ArticleLowering.toBlocks(it) }
            .filterIsInstance<ArticleBlock.Code>()
            .mapNotNull { it.language }
            .toSet()

        // Rouge writes `language-plaintext` for its shell transcripts, which is a claim we
        // deliberately honour by *not* colouring — see `CodeLanguage.of`.
        assertThat(languages).contains("c")
        assertThat(languages).contains("plaintext")
    }

    private fun lowerOne(html: String): ArticleBlock.Code {
        val clean = requireNotNull(HtmlSanitizer.sanitize(html, null)) { "sanitized to nothing" }
        return ArticleLowering.toBlocks(clean).filterIsInstance<ArticleBlock.Code>().single()
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "fixtures/snapshots").isDirectory) return dir
            dir = dir.parentFile
        }
        error("fixtures/snapshots not found")
    }
}
