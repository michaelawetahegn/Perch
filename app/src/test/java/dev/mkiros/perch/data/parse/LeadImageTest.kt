package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `entries.imageUrl`, per PLAN-2.md §0 and U05: the ordered fallback chain
 * `media:thumbnail` → `media:content medium="image"` → an `<enclosure>` typed as an image →
 * the first real `<img>` in the body, with relative URLs resolved against the entry link.
 *
 * Driven through the parsers rather than the helper, because the rung that matters is the
 * one a feed actually reaches. The corpus-wide coverage number is `ThumbnailCorpusTest`.
 *
 * **A missing image is a designed state, not a failure.** Every rung here resolves to
 * null rather than to a guessed or broken URL — U08's row draws the placeholder.
 */
class LeadImageTest {

    private val rss = RssParser()
    private val atom = AtomParser()

    private fun item(body: String, requestUrl: String? = FEED_URL) =
        rss.parse(parseFeedXml(rssAround(body), requestUrl), requestUrl)!!.entries.single()

    private fun entry(body: String, requestUrl: String? = FEED_URL) =
        atom.parse(parseFeedXml(atomAround(body), requestUrl), requestUrl)!!.entries.single()

    // ---- 1. the declared rungs, in order -----------------------------------------

    @Test
    fun `media thumbnail outranks media content and an image enclosure`() {
        val entry = item(
            """
            <enclosure url="https://cdn.example/enclosure.jpg" type="image/jpeg" length="9"/>
            <media:content url="https://cdn.example/content.jpg" medium="image"/>
            <media:thumbnail url="https://cdn.example/thumb.jpg"/>
            """,
        )

        assertThat(entry.imageUrl).isEqualTo("https://cdn.example/thumb.jpg")
    }

    @Test
    fun `media content outranks an image enclosure and needs to declare itself an image`() {
        val video = item(
            """
            <enclosure url="https://cdn.example/enclosure.jpg" type="image/jpeg" length="9"/>
            <media:content url="https://cdn.example/clip.mp4" medium="video"/>
            """,
        )
        val image = item(
            """
            <enclosure url="https://cdn.example/enclosure.jpg" type="image/jpeg" length="9"/>
            <media:content url="https://cdn.example/content.jpg" medium="image"/>
            """,
        )

        assertThat(video.imageUrl).isEqualTo("https://cdn.example/enclosure.jpg")
        assertThat(image.imageUrl).isEqualTo("https://cdn.example/content.jpg")
    }

    @Test
    fun `a media element nested in a media group is still found`() {
        val entry = item(
            """
            <media:group>
              <media:thumbnail url="https://cdn.example/grouped.jpg"/>
            </media:group>
            """,
        )

        assertThat(entry.imageUrl).isEqualTo("https://cdn.example/grouped.jpg")
    }

    @Test
    fun `an enclosure is taken only when its media type says image`() {
        val podcast = item("""<enclosure url="https://cdn.example/ep.mp3" type="audio/mpeg" length="9"/>""")
        val picture = item("""<enclosure url="https://cdn.example/lead.png" type="image/png" length="9"/>""")

        assertThat(podcast.imageUrl).isNull()
        assertThat(picture.imageUrl).isEqualTo("https://cdn.example/lead.png")
    }

    // ---- 2. the body rung ---------------------------------------------------------

    @Test
    fun `falls back to the first image in the body when the feed declares none`() {
        val entry = item(
            """
            <description>&lt;p&gt;Words&lt;/p&gt;
              &lt;img src="https://cdn.example/inline.jpg"/&gt;
              &lt;img src="https://cdn.example/second.jpg"/&gt;</description>
            """,
        )

        assertThat(entry.imageUrl).isEqualTo("https://cdn.example/inline.jpg")
    }

    @Test
    fun `resolves a relative body image against the entry link, not the feed url`() {
        val entry = item(
            """
            <link>https://example.com/posts/2025/hello</link>
            <description>&lt;img src="../art/cover.png"/&gt;</description>
            """,
        )

        assertThat(entry.imageUrl).isEqualTo("https://example.com/posts/art/cover.png")
    }

    @Test
    fun `reads an image out of real XHTML content, not only escaped markup`() {
        val entry = entry(
            """
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">
              <p>Words</p><img src="https://cdn.example/xhtml.jpg"/>
            </div></content>
            """,
        )

        assertThat(entry.imageUrl).isEqualTo("https://cdn.example/xhtml.jpg")
    }

    // ---- 3. what is furniture rather than content ---------------------------------

    @Test
    fun `skips a tracking pixel and takes the next real image`() {
        val entry = item(
            """
            <description>&lt;img src="https://stats.example/p.gif" width="1" height="1"/&gt;
              &lt;img src="https://cdn.example/real.jpg"/&gt;</description>
            """,
        )

        assertThat(entry.imageUrl).isEqualTo("https://cdn.example/real.jpg")
    }

    @Test
    fun `skips an image whose declared dimensions are below the thumbnail floor`() {
        val entry = item(
            """
            <description>&lt;img src="https://cdn.example/badge.png" width="32" height="32"/&gt;
              &lt;img src="https://cdn.example/real.jpg" width="640" height="480"/&gt;</description>
            """,
        )

        assertThat(entry.imageUrl).isEqualTo("https://cdn.example/real.jpg")
    }

    @Test
    fun `skips an image named like a tracker even when it declares no size`() {
        val entry = item(
            """
            <description>&lt;img src="https://cdn.example/spacer.gif"/&gt;
              &lt;img src="https://feeds.feedburner.com/~ff/blog?a=1"/&gt;
              &lt;img src="https://cdn.example/real.jpg"/&gt;</description>
            """,
        )

        assertThat(entry.imageUrl).isEqualTo("https://cdn.example/real.jpg")
    }

    @Test
    fun `a declared thumbnail below the floor is refused rather than shown`() {
        val entry = item("""<media:thumbnail url="https://cdn.example/tiny.jpg" width="16" height="16"/>""")

        assertThat(entry.imageUrl).isNull()
    }

    // ---- 4. absence is a normal answer --------------------------------------------

    @Test
    fun `a text-only entry resolves to null rather than a guess`() {
        val entry = item("<description>&lt;p&gt;Just words, no pictures.&lt;/p&gt;</description>")

        assertThat(entry.imageUrl).isNull()
    }

    @Test
    fun `a body image with an unusable scheme resolves to null`() {
        val entry = item(
            """<description>&lt;img src="data:image/gif;base64,R0lGOD"/&gt;</description>""",
        )

        assertThat(entry.imageUrl).isNull()
    }

    @Test
    fun `a lazily loaded image contributes its data src`() {
        val entry = item(
            """
            <description>&lt;img data-src="https://cdn.example/lazy.jpg"/&gt;</description>
            """,
        )

        assertThat(entry.imageUrl).isEqualTo("https://cdn.example/lazy.jpg")
    }

    @Test
    fun `an image inside dropped markup is not mistaken for content`() {
        val entry = item(
            """
            <description>&lt;noscript&gt;&lt;img src="https://cdn.example/noscript.jpg"/&gt;&lt;/noscript&gt;
              &lt;img src="https://cdn.example/real.jpg"/&gt;</description>
            """,
        )

        assertThat(entry.imageUrl).isEqualTo("https://cdn.example/real.jpg")
    }

    private companion object {
        const val FEED_URL = "https://example.com/feed.xml"

        fun rssAround(body: String) = """
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <title>Corpus</title>
                <link>https://example.com/</link>
                <item>
                  <title>An entry</title>
                  <guid>urn:entry:1</guid>
                  <pubDate>Mon, 03 Mar 2025 12:30:00 GMT</pubDate>
                  $body
                </item>
              </channel>
            </rss>
        """.trimIndent()

        fun atomAround(body: String) = """
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
              <title>Corpus</title>
              <link rel="alternate" href="https://example.com/"/>
              <entry>
                <title>An entry</title>
                <id>urn:entry:1</id>
                <updated>2025-03-03T12:30:00Z</updated>
                $body
              </entry>
            </feed>
        """.trimIndent()
    }
}
