package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/**
 * RSS 2.0 / 0.9x extraction, per SPEC.md §5. Every document here is hand-written so the
 * failure it provokes is obvious; the real-corpus sweep is T09's `FeedCorpusTest`.
 */
class RssParserTest {

    private val parser = RssParser()

    private fun parse(xml: String, requestUrl: String? = "https://example.com/feed.xml") =
        parser.parse(parseFeedXml(xml, requestUrl), requestUrl)

    // ---- 1. a well-formed RSS 2.0 document ---------------------------------------

    private val wellFormed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <channel>
            <title>Bird &amp; Wire</title>
            <link>https://birdwire.example/</link>
            <description>Notes on birds and wires</description>
            <lastBuildDate>Tue, 04 Mar 2025 09:00:00 GMT</lastBuildDate>
            <item>
              <title>The &lt;b&gt;first&lt;/b&gt; post</title>
              <link>https://birdwire.example/first</link>
              <guid isPermaLink="false">tag:birdwire,2025:1</guid>
              <pubDate>Mon, 03 Mar 2025 12:30:00 GMT</pubDate>
              <author>rook@birdwire.example (Rook)</author>
              <description>A short summary.</description>
            </item>
            <item>
              <title>Second</title>
              <link>https://birdwire.example/second</link>
              <guid>https://birdwire.example/second</guid>
              <pubDate>Sun, 02 Mar 2025 08:00:00 GMT</pubDate>
              <dc:creator>Jackdaw</dc:creator>
              <description>Another one.</description>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `reads channel title, site link and every item`() {
        val feed = parse(wellFormed)!!

        assertThat(feed.title).isEqualTo("Bird & Wire")
        assertThat(feed.siteUrl).isEqualTo("https://birdwire.example/")
        assertThat(feed.updatedAt).isEqualTo(Instant.parse("2025-03-04T09:00:00Z"))
        assertThat(feed.entries).hasSize(2)
    }

    @Test
    fun `strips markup and decodes entities in item titles`() {
        val first = parse(wellFormed)!!.entries[0]

        assertThat(first.title).isEqualTo("The first post")
    }

    @Test
    fun `reads link, guid, date and description from an item`() {
        val first = parse(wellFormed)!!.entries[0]

        assertThat(first.link).isEqualTo("https://birdwire.example/first")
        assertThat(first.guid).isEqualTo("tag:birdwire,2025:1")
        assertThat(first.publishedAt).isEqualTo(Instant.parse("2025-03-03T12:30:00Z"))
        assertThat(first.publishedIsEstimated).isFalse()
        assertThat(first.contentHtml).isEqualTo("A short summary.")
    }

    @Test
    fun `prefers dc creator over an rfc822 author mailbox`() {
        val entries = parse(wellFormed)!!.entries

        assertThat(entries[0].author).isEqualTo("Rook")
        assertThat(entries[1].author).isEqualTo("Jackdaw")
    }

    // ---- 2. content:encoded, CDATA and enclosures ---------------------------------

    @Test
    fun `prefers content encoded over description and keeps its markup`() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Contentful</title>
                <item>
                  <title>Post</title>
                  <link>https://c.example/p</link>
                  <description>Truncated teaser…</description>
                  <content:encoded><![CDATA[<p>Full <em>body</em>.</p>]]></content:encoded>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )!!

        assertThat(feed.entries.single().contentHtml).isEqualTo("<p>Full <em>body</em>.</p>")
    }

    @Test
    fun `unescapes html that was entity-encoded rather than wrapped in cdata`() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Escaped</title>
                <item>
                  <title>Post</title>
                  <content:encoded>&lt;p&gt;Body &amp;amp; more&lt;/p&gt;</content:encoded>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )!!

        assertThat(feed.entries.single().contentHtml).isEqualTo("<p>Body &amp; more</p>")
    }

    @Test
    fun `takes the lead image from an image enclosure and ignores other media types`() {
        val feed = parse(
            """
            <rss version="2.0">
              <channel>
                <title>Enclosures</title>
                <item>
                  <title>Podcast</title>
                  <enclosure url="https://e.example/ep.mp3" type="audio/mpeg" length="9"/>
                </item>
                <item>
                  <title>Illustrated</title>
                  <enclosure url="https://e.example/lead.jpg" type="image/jpeg" length="9"/>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )!!

        assertThat(feed.entries[0].imageUrl).isNull()
        assertThat(feed.entries[1].imageUrl).isEqualTo("https://e.example/lead.jpg")
    }

    // ---- 3. RSS 0.91: dc:date, relative links, feed-level date fallback -----------

    @Test
    fun `falls back to dc date when pubDate is absent`() {
        val feed = parse(
            """
            <rss version="0.91" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title>Old school</title>
                <item>
                  <title>Dated by dublin core</title>
                  <link>/relative/post</link>
                  <dc:date>2019-07-04T18:00:00Z</dc:date>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
            requestUrl = "https://old.example/rss",
        )!!

        val entry = feed.entries.single()
        assertThat(entry.publishedAt).isEqualTo(Instant.parse("2019-07-04T18:00:00Z"))
        assertThat(entry.publishedIsEstimated).isFalse()
        assertThat(entry.link).isEqualTo("https://old.example/relative/post")
    }

    @Test
    fun `falls back to the channel date and flags the entry as estimated`() {
        val feed = parse(
            """
            <rss version="2.0">
              <channel>
                <title>Undated items</title>
                <lastBuildDate>Tue, 04 Mar 2025 09:00:00 GMT</lastBuildDate>
                <item><title>No date at all</title><link>https://u.example/a</link></item>
              </channel>
            </rss>
            """.trimIndent(),
        )!!

        val entry = feed.entries.single()
        assertThat(entry.publishedAt).isEqualTo(Instant.parse("2025-03-04T09:00:00Z"))
        assertThat(entry.publishedIsEstimated).isTrue()
    }

    @Test
    fun `drops a link that is neither http nor resolvable`() {
        val feed = parse(
            """
            <rss version="2.0">
              <channel>
                <title>Bad links</title>
                <item><title>Scripted</title><link>javascript:alert(1)</link></item>
              </channel>
            </rss>
            """.trimIndent(),
        )!!

        assertThat(feed.entries.single().link).isNull()
    }

    // ---- 4. the GUID fallback chain -----------------------------------------------

    private val guidless = """
        <rss version="2.0">
          <channel>
            <title>Guidless</title>
            <item><title>Has a link</title><link>https://g.example/one</link></item>
            <item><title>Has nothing</title><pubDate>Mon, 03 Mar 2025 12:30:00 GMT</pubDate></item>
            <item><title>Has nothing</title><pubDate>Sun, 02 Mar 2025 08:00:00 GMT</pubDate></item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `falls back to the link and then to a hash of title and raw date for the guid`() {
        val entries = parse(guidless)!!.entries

        assertThat(entries[0].guid).isEqualTo("https://g.example/one")
        assertThat(entries[1].guid).isNotEmpty()
        assertThat(entries.map { it.guid }.toSet()).hasSize(3)
    }

    @Test
    fun `derives the same guid every time so a refetch does not duplicate entries`() {
        val first = parse(guidless)!!.entries.map { it.guid }
        val second = parse(guidless)!!.entries.map { it.guid }

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `substitutes a placeholder for a missing item title`() {
        val feed = parse(
            """
            <rss version="2.0">
              <channel>
                <title>Untitled items</title>
                <item><link>https://n.example/x</link><description>Body</description></item>
              </channel>
            </rss>
            """.trimIndent(),
        )!!

        assertThat(feed.entries.single().title).isEqualTo("(untitled)")
    }

    @Test
    fun `names the feed after its site when the channel title is blank`() {
        val feed = parse(
            """
            <rss version="2.0">
              <channel>
                <title>   </title>
                <link>https://anon.example/</link>
                <item><title>One</title></item>
              </channel>
            </rss>
            """.trimIndent(),
        )!!

        assertThat(feed.title).isEqualTo("anon.example")
    }

    // ---- 5. documents that must not throw ------------------------------------------

    @Test
    fun `returns what it can from a document truncated mid-tag`() {
        val feed = parse(
            """
            <rss version="2.0">
              <channel>
                <title>Cut short</title>
                <item><title>Survivor</title><link>https://t.example/a</link></item>
                <item><title>Half a
            """.trimIndent(),
        )

        assertThat(feed).isNotNull()
        assertThat(feed!!.title).isEqualTo("Cut short")
        assertThat(feed.entries.map { it.title }).contains("Survivor")
    }

    @Test
    fun `survives mismatched tags and undeclared entities`() {
        val feed = parse(
            """
            <rss version="2.0">
              <channel>
                <title>Sloppy &nbsp; &bogus; markup</title>
                <item>
                  <title>Unclosed <b>bold
                  <link>https://s.example/a</link>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )

        assertThat(feed).isNotNull()
        assertThat(feed!!.entries).isNotEmpty()
    }

    @Test
    fun `returns null for a channel-less document so the dispatcher can try another parser`() {
        val feed = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>An Atom feed</title>
              <entry><title>Not mine</title></entry>
            </feed>
            """.trimIndent(),
        )

        assertThat(feed).isNull()
    }

    @Test
    fun `returns null for an empty document`() {
        assertThat(parse("")).isNull()
    }

    @Test
    fun `keeps a channel that carries no items at all`() {
        val feed = parse(
            """
            <rss version="2.0"><channel><title>Silent</title></channel></rss>
            """.trimIndent(),
        )!!

        assertThat(feed.title).isEqualTo("Silent")
        assertThat(feed.entries).isEmpty()
    }
}
