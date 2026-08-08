package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Whether the body a feed handed us was ever meant to *be* the body (U10, PLAN-2 §0).
 *
 * RSS types its payload by element rather than by attribute: `<content:encoded>` is the
 * article, `<description>` is the blurb, and a great many publishers ship only the second
 * and let readers follow the link. Atom draws the same line between `<content>` and
 * `<summary>`. The distinction is invisible once both have been flattened into
 * `contentHtml`, so the parser records it — that is the one trigger for full-text
 * extraction that cannot be recovered from the body afterwards.
 */
class ExcerptOnlyTest {

    private val parser = FeedParser()

    @Test
    fun `an rss item with only a description is marked as an excerpt`() {
        val entry = parse(
            """
            <rss version="2.0"><channel><title>Example</title>
              <item>
                <title>Adaptive subdivision</title>
                <link>https://gpuopen.com/learn/one/</link>
                <description>Learn how fast, crack-free GPU work graph subdivision works.</description>
              </item>
            </channel></rss>
            """.trimIndent(),
        )

        assertThat(entry.bodyIsExcerpt).isTrue()
    }

    @Test
    fun `an rss item carrying content encoded is not an excerpt`() {
        val entry = parse(
            """
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel><title>Example</title>
              <item>
                <title>Adaptive subdivision</title>
                <link>https://gpuopen.com/learn/one/</link>
                <description>A teaser.</description>
                <content:encoded><![CDATA[<p>The whole article.</p>]]></content:encoded>
              </item>
            </channel></rss>
            """.trimIndent(),
        )

        assertThat(entry.bodyIsExcerpt).isFalse()
    }

    @Test
    fun `an rss item with no body at all is not called an excerpt`() {
        val entry = parse(
            """
            <rss version="2.0"><channel><title>Fabien Sanglard</title>
              <item>
                <title>A dock that finally wakes up reliably</title>
                <link>https://fabiensanglard.net/tb4/index.html</link>
                <pubDate>Sat, 11 Jul 2026 00:00:00 GMT</pubDate>
              </item>
            </channel></rss>
            """.trimIndent(),
        )

        assertThat(entry.contentHtml).isNull()
        assertThat(entry.bodyIsExcerpt).isFalse()
    }

    @Test
    fun `an atom entry with only a summary is marked as an excerpt`() {
        val entry = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Example</title>
              <entry>
                <title>One</title>
                <link href="https://example.com/one"/>
                <id>tag:example.com,2026:1</id>
                <summary type="html">&lt;p&gt;A teaser.&lt;/p&gt;</summary>
              </entry>
            </feed>
            """.trimIndent(),
        )

        assertThat(entry.bodyIsExcerpt).isTrue()
    }

    @Test
    fun `an atom entry carrying content is not an excerpt`() {
        val entry = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Example</title>
              <entry>
                <title>One</title>
                <link href="https://example.com/one"/>
                <id>tag:example.com,2026:1</id>
                <summary type="html">&lt;p&gt;A teaser.&lt;/p&gt;</summary>
                <content type="html">&lt;p&gt;The whole article.&lt;/p&gt;</content>
              </entry>
            </feed>
            """.trimIndent(),
        )

        assertThat(entry.bodyIsExcerpt).isFalse()
    }

    /**
     * The two shapes §0 names, measured against what the sources really ship.
     *
     * §0 describes fabiensanglard.net as title + link + date and nothing else, which is
     * true of 68 of its 144 items; the other 76 carry a one-sentence `<description>`,
     * which is the *other* §0 shape rather than a body. Both are "not an article", and the
     * flag is what lets U10 say so without guessing from a length.
     */
    @Test
    fun `the corpus agrees with what section 0 says about the two named sources`() {
        val fabien = parseSnapshot("fabiensanglard-net", "https://fabiensanglard.net/rss.xml")
        assertThat(fabien).hasSize(144)
        assertThat(fabien.all { it.contentHtml.isNullOrBlank() || it.bodyIsExcerpt }).isTrue()
        assertThat(fabien.count { it.contentHtml.isNullOrBlank() }).isGreaterThan(0)
        assertThat(fabien.count { it.bodyIsExcerpt }).isGreaterThan(0)

        // A feed that ships `content:encoded` is never called an excerpt, whatever its
        // `<description>` says — the flag is about which element the body came out of.
        val fullText = parseSnapshot("lemire-me", "https://lemire.me/blog/feed/")
        assertThat(fullText).isNotEmpty()
        assertThat(fullText.none { it.bodyIsExcerpt }).isTrue()
    }

    private fun parse(xml: String): ParsedEntry {
        val result = parser.parse(
            bytes = xml.toByteArray(),
            contentType = "application/xml",
            requestUrl = "https://example.com/feed",
        )
        return (result as ParseResult.Success).feed.entries.single()
    }

    private fun parseSnapshot(name: String, url: String): List<ParsedEntry> {
        val file = java.io.File(repoRoot(), "fixtures/snapshots/$name.xml")
        val result = parser.parse(file.readBytes(), contentType = null, requestUrl = url)
        return (result as ParseResult.Success).feed.entries
    }

    private fun repoRoot(): java.io.File {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (java.io.File(dir, "fixtures/snapshots").isDirectory) return dir
            dir = dir.parentFile
        }
        error("fixtures/snapshots not found")
    }
}
