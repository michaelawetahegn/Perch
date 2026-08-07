package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/**
 * Atom 1.0 extraction, per SPEC.md §5. Every document here is hand-written so the failure
 * it provokes is obvious; the real-corpus sweep is T09's `FeedCorpusTest`.
 *
 * The shapes exercised are the ones the corpus actually contains: `rel` before `href` and
 * after it, `<link>` with no `rel` at all, `self`/`replies`/`edit` links that must never
 * win, `content` typed `html` / `xhtml` / `text`, and `xml:base` on the entry.
 */
class AtomParserTest {

    private val parser = AtomParser()

    private fun parse(xml: String, requestUrl: String? = "https://example.com/atom.xml") =
        parser.parse(parseFeedXml(xml, requestUrl), requestUrl)

    // ---- 1. a well-formed Atom 1.0 document --------------------------------------

    private val wellFormed = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Bird &amp; Wire</title>
          <link href="https://birdwire.example/feed.atom" rel="self" type="application/atom+xml"/>
          <link rel="alternate" type="text/html" href="https://birdwire.example/"/>
          <updated>2025-03-04T09:00:00Z</updated>
          <author><name>Rook</name></author>
          <entry>
            <title>The &lt;b&gt;first&lt;/b&gt; post</title>
            <link rel="alternate" href="https://birdwire.example/first"/>
            <id>tag:birdwire.example,2025:1</id>
            <published>2025-03-03T12:30:00Z</published>
            <updated>2025-03-05T18:00:00Z</updated>
            <author><name>Jackdaw</name></author>
            <content type="html">&lt;p&gt;A short body.&lt;/p&gt;</content>
          </entry>
          <entry>
            <title>Second</title>
            <link href="https://birdwire.example/second"/>
            <id>tag:birdwire.example,2025:2</id>
            <updated>2025-03-02T08:00:00Z</updated>
            <summary>Only a summary.</summary>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `reads feed title, alternate site link and every entry`() {
        val feed = parse(wellFormed)!!

        assertThat(feed.title).isEqualTo("Bird & Wire")
        assertThat(feed.siteUrl).isEqualTo("https://birdwire.example/")
        assertThat(feed.updatedAt).isEqualTo(Instant.parse("2025-03-04T09:00:00Z"))
        assertThat(feed.entries).hasSize(2)
    }

    @Test
    fun `strips markup and decodes entities in entry titles`() {
        assertThat(parse(wellFormed)!!.entries[0].title).isEqualTo("The first post")
    }

    @Test
    fun `prefers published over updated as the publication date`() {
        val first = parse(wellFormed)!!.entries[0]

        assertThat(first.publishedAt).isEqualTo(Instant.parse("2025-03-03T12:30:00Z"))
        assertThat(first.publishedIsEstimated).isFalse()
    }

    @Test
    fun `falls back to updated when the entry has no published date`() {
        val second = parse(wellFormed)!!.entries[1]

        assertThat(second.publishedAt).isEqualTo(Instant.parse("2025-03-02T08:00:00Z"))
        assertThat(second.publishedIsEstimated).isFalse()
    }

    @Test
    fun `takes the id as the guid`() {
        assertThat(parse(wellFormed)!!.entries[0].guid).isEqualTo("tag:birdwire.example,2025:1")
    }

    @Test
    fun `reads the entry author name, not the whole author element`() {
        assertThat(parse(wellFormed)!!.entries[0].author).isEqualTo("Jackdaw")
    }

    @Test
    fun `inherits the feed author when the entry names none`() {
        assertThat(parse(wellFormed)!!.entries[1].author).isEqualTo("Rook")
    }

    @Test
    fun `unescapes html content`() {
        assertThat(parse(wellFormed)!!.entries[0].contentHtml).isEqualTo("<p>A short body.</p>")
    }

    @Test
    fun `falls back to summary when the entry has no content`() {
        assertThat(parse(wellFormed)!!.entries[1].contentHtml).isEqualTo("Only a summary.")
    }

    // ---- 2. link selection -------------------------------------------------------

    private val awkwardLinks = """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Links</title>
          <link href="https://links.example/feed" rel="self"/>
          <link href="https://links.example/" rel="alternate"/>
          <entry>
            <title>Ordering</title>
            <id>1</id>
            <link href="https://links.example/edit/1" rel="edit" type="application/atom+xml"/>
            <link href="https://links.example/1/comments" rel="replies" type="text/html"/>
            <link href="https://links.example/1.atom" rel="alternate" type="application/atom+xml"/>
            <link href="https://links.example/1" rel="alternate" type="text/html"/>
            <updated>2025-03-01T00:00:00Z</updated>
          </entry>
          <entry>
            <title>Bare link</title>
            <id>2</id>
            <link href="/relative/two"/>
            <updated>2025-03-01T00:00:00Z</updated>
          </entry>
          <entry>
            <title>Self only</title>
            <id>3</id>
            <link href="https://links.example/3.atom" rel="self"/>
            <updated>2025-03-01T00:00:00Z</updated>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `prefers the html alternate link over other alternates`() {
        assertThat(parse(awkwardLinks)!!.entries[0].link).isEqualTo("https://links.example/1")
    }

    @Test
    fun `treats a link with no rel as the alternate and resolves it`() {
        assertThat(parse(awkwardLinks)!!.entries[1].link).isEqualTo("https://links.example/relative/two")
    }

    @Test
    fun `never takes a self, edit or replies link as the entry link`() {
        assertThat(parse(awkwardLinks)!!.entries[2].link).isNull()
    }

    @Test
    fun `resolves relative entry links against the feed site url, not the request url`() {
        // The feed lives on example.com but publishes from links.example — the alternate
        // link is the base, otherwise every relative href points at the wrong host.
        assertThat(parse(awkwardLinks)!!.siteUrl).isEqualTo("https://links.example/")
    }

    // ---- 3. content typing -------------------------------------------------------

    private val typedContent = """
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <title>Typed</title>
          <link rel="alternate" href="https://typed.example/"/>
          <entry>
            <title>XHTML</title>
            <id>x</id>
            <updated>2025-03-01T00:00:00Z</updated>
            <content type="xhtml">
              <div xmlns="http://www.w3.org/1999/xhtml"><p>Real <em>elements</em>.</p></div>
            </content>
          </entry>
          <entry>
            <title>Plain</title>
            <id>t</id>
            <updated>2025-03-01T00:00:00Z</updated>
            <content type="text">1 &lt; 2 &amp; 3 &gt; 2</content>
          </entry>
          <entry xml:base="https://typed.example/posts/">
            <title>Based</title>
            <id>b</id>
            <dc:creator>Magpie</dc:creator>
            <updated>2025-03-01T00:00:00Z</updated>
            <link href="third"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `unwraps the xhtml div and keeps its markup`() {
        val content = parse(typedContent)!!.entries[0].contentHtml!!

        assertThat(content).contains("<p>Real <em>elements</em>.</p>")
        assertThat(content).doesNotContain("<div")
    }

    @Test
    fun `escapes text content so it is not rendered as markup`() {
        assertThat(parse(typedContent)!!.entries[1].contentHtml)
            .isEqualTo("1 &lt; 2 &amp; 3 &gt; 2")
    }

    @Test
    fun `honours xml colon base when resolving an entry link`() {
        assertThat(parse(typedContent)!!.entries[2].link)
            .isEqualTo("https://typed.example/posts/third")
    }

    @Test
    fun `accepts dc colon creator as the author`() {
        assertThat(parse(typedContent)!!.entries[2].author).isEqualTo("Magpie")
    }

    // ---- 4. damaged and degenerate documents -------------------------------------

    @Test
    fun `never throws on a truncated document and keeps the entries it closed`() {
        val truncated = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Cut short</title>
              <entry><title>Whole</title><id>1</id><updated>2025-03-01T00:00:00Z</updated></entry>
              <entry><title>Half</title><id>2</id><updated>2025-03
        """.trimIndent()

        val feed = parse(truncated)!!

        assertThat(feed.title).isEqualTo("Cut short")
        assertThat(feed.entries.map { it.title }).containsExactly("Whole", "Half").inOrder()
    }

    @Test
    fun `never throws on mismatched tags`() {
        val mismatched = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Mismatched</entry>
              <entry><title>One</title><id>1</id></feed>
        """.trimIndent()

        assertThat(parse(mismatched)!!.entries).isNotEmpty()
    }

    @Test
    fun `titles an untitled feed after its host`() {
        val untitled = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <link rel="alternate" href="https://www.untitled.example/blog"/>
              <entry><title>One</title><id>1</id></entry>
            </feed>
        """.trimIndent()

        assertThat(parse(untitled)!!.title).isEqualTo("untitled.example")
    }

    @Test
    fun `falls back to the feed updated date for an entry with no date of its own`() {
        val undated = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Undated</title>
              <updated>2025-03-04T09:00:00Z</updated>
              <entry><title>One</title><id>1</id></entry>
            </feed>
        """.trimIndent()

        val entry = parse(undated)!!.entries.single()

        assertThat(entry.publishedAt).isEqualTo(Instant.parse("2025-03-04T09:00:00Z"))
        assertThat(entry.publishedIsEstimated).isTrue()
    }

    @Test
    fun `derives a stable guid when the entry has neither id nor link`() {
        val anonymous = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Anonymous</title>
              <entry><title>One</title><updated>2025-03-01T00:00:00Z</updated></entry>
            </feed>
        """.trimIndent()

        val first = parse(anonymous)!!.entries.single().guid
        val second = parse(anonymous)!!.entries.single().guid

        assertThat(first).isEqualTo(second)
        assertThat(first).isNotEmpty()
    }

    @Test
    fun `returns null for a document that is not atom`() {
        val rss = """
            <rss version="2.0"><channel><title>Not Atom</title></channel></rss>
        """.trimIndent()

        assertThat(parse(rss)).isNull()
        assertThat(parse("<html><body>hello</body></html>")).isNull()
        assertThat(parse("")).isNull()
    }

    // ---- 5. lead image -----------------------------------------------------------

    @Test
    fun `takes an image enclosure as the lead image but ignores a podcast one`() {
        val enclosures = """
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
              <title>Media</title>
              <link rel="alternate" href="https://media.example/"/>
              <entry>
                <title>Podcast</title><id>1</id>
                <link rel="enclosure" type="audio/mpeg" href="https://media.example/ep1.mp3"/>
                <link rel="enclosure" type="image/png" href="/cover.png"/>
              </entry>
              <entry>
                <title>Thumbnail</title><id>2</id>
                <media:thumbnail url="https://media.example/thumb.jpg"/>
              </entry>
            </feed>
        """.trimIndent()

        val entries = parse(enclosures)!!.entries

        assertThat(entries[0].imageUrl).isEqualTo("https://media.example/cover.png")
        assertThat(entries[1].imageUrl).isEqualTo("https://media.example/thumb.jpg")
    }
}
