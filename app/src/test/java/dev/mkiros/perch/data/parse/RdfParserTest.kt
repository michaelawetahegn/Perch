package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/**
 * RDF (RSS 1.0) extraction, per SPEC.md §5. RSS 1.0's shape differs from RSS 2.0 in the
 * one way that matters: `<item>` elements are siblings of `<channel>`, not its children,
 * and identity lives in an `rdf:about` attribute rather than a `<guid>` element.
 *
 * Every document here is hand-written — the harvested corpus (T04) contains no RSS 1.0
 * feed at all, which is exactly why this format needs its own deliberate tests.
 */
class RdfParserTest {

    private val parser = RdfParser()

    private fun parse(xml: String, requestUrl: String? = "https://example.com/rdf.xml") =
        parser.parse(parseFeedXml(xml, requestUrl), requestUrl)

    // ---- 1. a well-formed RSS 1.0 document ---------------------------------------

    private val wellFormed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                 xmlns="http://purl.org/rss/1.0/"
                 xmlns:dc="http://purl.org/dc/elements/1.1/"
                 xmlns:content="http://purl.org/rss/1.0/modules/content/">
          <channel rdf:about="https://birdwire.example/rdf.xml">
            <title>Bird &amp; Wire</title>
            <link>https://birdwire.example/</link>
            <description>Notes on birds and wires</description>
            <dc:date>2025-03-04T09:00:00Z</dc:date>
            <items>
              <rdf:Seq>
                <rdf:li rdf:resource="https://birdwire.example/first" />
                <rdf:li rdf:resource="https://birdwire.example/second" />
              </rdf:Seq>
            </items>
          </channel>
          <image rdf:about="https://birdwire.example/logo.png">
            <title>Bird &amp; Wire</title>
            <url>https://birdwire.example/logo.png</url>
            <link>https://birdwire.example/</link>
          </image>
          <item rdf:about="https://birdwire.example/first">
            <title>The &lt;b&gt;first&lt;/b&gt; post</title>
            <link>https://birdwire.example/first</link>
            <description>A short summary.</description>
            <dc:date>2025-03-03T12:30:00Z</dc:date>
            <dc:creator>Rook</dc:creator>
            <content:encoded><![CDATA[<p>The whole <em>post</em>.</p>]]></content:encoded>
          </item>
          <item rdf:about="https://birdwire.example/second">
            <title>Second</title>
            <link>https://birdwire.example/second</link>
            <description>Another one.</description>
            <dc:date>2025-03-02T08:00:00Z</dc:date>
          </item>
        </rdf:RDF>
    """.trimIndent()

    @Test
    fun `reads channel title, site link and feed date`() {
        val feed = parse(wellFormed)!!

        assertThat(feed.title).isEqualTo("Bird & Wire")
        assertThat(feed.siteUrl).isEqualTo("https://birdwire.example/")
        assertThat(feed.updatedAt).isEqualTo(Instant.parse("2025-03-04T09:00:00Z"))
    }

    @Test
    fun `collects items that sit beside the channel rather than inside it`() {
        val feed = parse(wellFormed)!!

        assertThat(feed.entries.map { it.title })
            .containsExactly("The first post", "Second")
            .inOrder()
    }

    @Test
    fun `does not mistake the channel or the image for an entry`() {
        val feed = parse(wellFormed)!!

        assertThat(feed.entries).hasSize(2)
    }

    @Test
    fun `prefers content encoded over description`() {
        val first = parse(wellFormed)!!.entries[0]

        assertThat(first.contentHtml).isEqualTo("<p>The whole <em>post</em>.</p>")
    }

    @Test
    fun `falls back to description when there is no content encoded`() {
        val second = parse(wellFormed)!!.entries[1]

        assertThat(second.contentHtml).isEqualTo("Another one.")
    }

    @Test
    fun `reads the publication date from dc date`() {
        val first = parse(wellFormed)!!.entries[0]

        assertThat(first.publishedAt).isEqualTo(Instant.parse("2025-03-03T12:30:00Z"))
        assertThat(first.publishedIsEstimated).isFalse()
    }

    @Test
    fun `reads the author from dc creator`() {
        val entries = parse(wellFormed)!!.entries

        assertThat(entries[0].author).isEqualTo("Rook")
        assertThat(entries[1].author).isNull()
    }

    // ---- 2. identity -------------------------------------------------------------

    @Test
    fun `uses rdf about as the entry guid`() {
        val first = parse(wellFormed)!!.entries[0]

        assertThat(first.guid).isEqualTo("https://birdwire.example/first")
    }

    @Test
    fun `falls back to the link when the item has no rdf about`() {
        val feed = parse(
            """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <channel><title>Anon</title></channel>
              <item>
                <title>No about here</title>
                <link>https://birdwire.example/anon</link>
              </item>
            </rdf:RDF>
            """.trimIndent(),
        )!!

        assertThat(feed.entries.single().guid).isEqualTo("https://birdwire.example/anon")
    }

    @Test
    fun `derives a stable guid when the item has neither rdf about nor link`() {
        val xml = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel><title>Anon</title></channel>
              <item>
                <title>Nameless</title>
                <dc:date>2025-03-03T12:30:00Z</dc:date>
              </item>
            </rdf:RDF>
        """.trimIndent()

        val once = parse(xml)!!.entries.single().guid
        val twice = parse(xml)!!.entries.single().guid

        assertThat(once).isNotEmpty()
        assertThat(once).isEqualTo(twice)
    }

    // ---- 3. dates and links ------------------------------------------------------

    @Test
    fun `falls back to the feed date when an item carries none`() {
        val feed = parse(
            """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title>Undated</title>
                <dc:date>2025-03-04T09:00:00Z</dc:date>
              </channel>
              <item rdf:about="https://birdwire.example/x"><title>X</title></item>
            </rdf:RDF>
            """.trimIndent(),
        )!!

        val entry = feed.entries.single()
        assertThat(entry.publishedAt).isEqualTo(Instant.parse("2025-03-04T09:00:00Z"))
        assertThat(entry.publishedIsEstimated).isTrue()
    }

    @Test
    fun `resolves relative item links against the site url`() {
        val feed = parse(
            """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <channel>
                <title>Relative</title>
                <link>https://birdwire.example/blog/</link>
              </channel>
              <item><title>X</title><link>/posts/x</link></item>
            </rdf:RDF>
            """.trimIndent(),
        )!!

        assertThat(feed.entries.single().link).isEqualTo("https://birdwire.example/posts/x")
    }

    @Test
    fun `drops a javascript item link`() {
        val feed = parse(
            """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <channel><title>Hostile</title></channel>
              <item><title>X</title><link>javascript:alert(1)</link></item>
            </rdf:RDF>
            """.trimIndent(),
        )!!

        assertThat(feed.entries.single().link).isNull()
    }

    @Test
    fun `titles the feed after its host when the channel has no title`() {
        val feed = parse(
            """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <channel><link>https://www.birdwire.example/</link></channel>
              <item><title>X</title></item>
            </rdf:RDF>
            """.trimIndent(),
        )!!

        assertThat(feed.title).isEqualTo("birdwire.example")
    }

    // ---- 4. shapes real feeds get wrong ------------------------------------------

    @Test
    fun `also collects items nested inside the channel`() {
        val feed = parse(
            """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <channel>
                <title>Nested</title>
                <item rdf:about="https://birdwire.example/a"><title>A</title></item>
                <item rdf:about="https://birdwire.example/b"><title>B</title></item>
              </channel>
            </rdf:RDF>
            """.trimIndent(),
        )!!

        assertThat(feed.entries.map { it.title }).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `accepts a document that binds the rdf namespace to another prefix`() {
        val feed = parse(
            """
            <r:RDF xmlns:r="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                   xmlns="http://purl.org/rss/1.0/">
              <channel><title>Odd prefix</title></channel>
              <item r:about="https://birdwire.example/a"><title>A</title></item>
            </r:RDF>
            """.trimIndent(),
        )

        assertThat(feed).isNotNull()
        assertThat(feed!!.title).isEqualTo("Odd prefix")
        assertThat(feed.entries.single().guid).isEqualTo("https://birdwire.example/a")
    }

    @Test
    fun `does not throw on a truncated document and keeps the items it closed`() {
        val feed = parse(
            """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <channel><title>Cut short</title></channel>
              <item rdf:about="https://birdwire.example/a"><title>A</title></item>
              <item rdf:about="https://birdwire.example/b"><title>B</ti
            """.trimIndent(),
        )!!

        assertThat(feed.title).isEqualTo("Cut short")
        assertThat(feed.entries.first().title).isEqualTo("A")
    }

    @Test
    fun `titles an untitled item rather than leaving it blank`() {
        val feed = parse(
            """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <channel><title>Blank</title></channel>
              <item rdf:about="https://birdwire.example/a"><description>Body only.</description></item>
            </rdf:RDF>
            """.trimIndent(),
        )!!

        assertThat(feed.entries.single().title).isEqualTo("(untitled)")
    }

    // ---- 5. not RDF at all -------------------------------------------------------

    @Test
    fun `returns null for an RSS 2 document`() {
        val document = parseFeedXml("<rss version=\"2.0\"><channel><title>T</title></channel></rss>")

        assertThat(parser.parse(document)).isNull()
    }

    @Test
    fun `returns null for an Atom document`() {
        val document = parseFeedXml("<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>T</title></feed>")

        assertThat(parser.parse(document)).isNull()
    }

    @Test
    fun `returns null for a document with no root element at all`() {
        assertThat(parser.parse(parseFeedXml(""))).isNull()
    }
}
