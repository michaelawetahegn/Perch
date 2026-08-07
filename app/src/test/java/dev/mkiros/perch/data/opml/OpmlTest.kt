package dev.mkiros.perch.data.opml

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import org.junit.Test

/**
 * The OPML document format itself: what Perch writes, and what it is willing to read back
 * from every other reader on earth.
 *
 * Reading is where the leniency lives. Exports in the wild nest their sources in folders,
 * disagree about whether the label is `text` or `title`, and sometimes carry outlines that
 * point at nothing at all — none of which is a reason to reject the file. Only a document
 * that is not an OPML document at all is an error, and even that is a value, never a throw.
 */
class OpmlTest {

    private val outlines = listOf(
        OpmlOutline("Null Program", "https://nullprogram.com/feed/", "https://nullprogram.com/"),
        OpmlOutline("Embedded in Academia", "https://blog.regehr.org/feed", null),
    )

    // ---- writing ---------------------------------------------------------------

    @Test
    fun `export declares OPML 2 0`() {
        val xml = Opml.write(outlines)

        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        assertThat(xml).contains("<opml version=\"2.0\">")
    }

    @Test
    fun `export writes every source as a flat rss outline`() {
        val xml = Opml.write(outlines)

        assertThat(xml).contains(
            """<outline type="rss" text="Null Program" title="Null Program" """ +
                """xmlUrl="https://nullprogram.com/feed/" htmlUrl="https://nullprogram.com/" />""",
        )
        assertThat(xml).doesNotContain("htmlUrl=\"\"")
    }

    @Test
    fun `export escapes titles that would otherwise break the document`() {
        val awkward = listOf(OpmlOutline("""Ampersands & "quotes" <tags>""", "https://x.example/f"))

        val reread = Opml.read(Opml.write(awkward))

        assertThat(reread).isEqualTo(OpmlParse.Success(awkward, invalid = 0))
    }

    @Test
    fun `export stamps the creation date when one is given`() {
        val xml = Opml.write(outlines, createdAt = Instant.parse("2026-08-07T12:00:00Z"))

        assertThat(xml).contains("<dateCreated>Fri, 7 Aug 2026 12:00:00 GMT</dateCreated>")
    }

    @Test
    fun `export of no sources is still a readable document`() {
        val reread = Opml.read(Opml.write(emptyList()))

        assertThat(reread).isEqualTo(OpmlParse.Success(emptyList(), invalid = 0))
    }

    @Test
    fun `the suggested file name carries the date`() {
        assertThat(Opml.fileName(LocalDate.of(2026, 8, 7))).isEqualTo("perch-20260807.opml")
    }

    // ---- reading ---------------------------------------------------------------

    @Test
    fun `import flattens folders however deeply they nest`() {
        val parsed = Opml.read(
            """
            <?xml version="1.0"?>
            <opml version="1.0"><head><title>subs</title></head><body>
              <outline text="Tech">
                <outline type="rss" text="A" xmlUrl="https://a.example/feed" htmlUrl="https://a.example/"/>
                <outline text="Deeper">
                  <outline type="rss" text="B" xmlUrl="https://b.example/feed"/>
                </outline>
              </outline>
              <outline type="rss" text="C" xmlUrl="https://c.example/feed"/>
            </body></opml>
            """.trimIndent(),
        )

        assertThat(parsed).isEqualTo(
            OpmlParse.Success(
                listOf(
                    OpmlOutline("A", "https://a.example/feed", "https://a.example/"),
                    OpmlOutline("B", "https://b.example/feed", null),
                    OpmlOutline("C", "https://c.example/feed", null),
                ),
                invalid = 0,
            ),
        )
    }

    @Test
    fun `import falls back from text to title to the feed host for a label`() {
        val parsed = Opml.read(
            """
            <opml version="2.0"><body>
              <outline type="rss" title="From title" xmlUrl="https://a.example/feed"/>
              <outline type="rss" xmlUrl="https://b.example/feed"/>
            </body></opml>
            """.trimIndent(),
        )

        assertThat((parsed as OpmlParse.Success).outlines.map { it.title })
            .containsExactly("From title", "b.example")
    }

    @Test
    fun `an outline that points at nothing is counted invalid not imported`() {
        val parsed = Opml.read(
            """
            <opml version="2.0"><body>
              <outline type="rss" text="Fine" xmlUrl="https://a.example/feed"/>
              <outline type="rss" text="No address"/>
              <outline type="rss" text="Not a feed address" xmlUrl="javascript:alert(1)"/>
              <outline type="rss" text="Relative" xmlUrl="/feed"/>
            </body></opml>
            """.trimIndent(),
        )

        assertThat(parsed).isEqualTo(
            OpmlParse.Success(listOf(OpmlOutline("Fine", "https://a.example/feed", null)), invalid = 3),
        )
    }

    @Test
    fun `an empty folder is a container not an invalid source`() {
        val parsed = Opml.read(
            """<opml version="2.0"><body><outline text="Empty folder"></outline></body></opml>""",
        )

        assertThat(parsed).isEqualTo(OpmlParse.Success(emptyList(), invalid = 0))
    }

    @Test
    fun `a document that is not OPML is a typed error`() {
        val parsed = Opml.read("<rss version=\"2.0\"><channel><title>Nope</title></channel></rss>")

        assertThat(parsed).isInstanceOf(OpmlParse.Malformed::class.java)
    }

    @Test
    fun `HTML is a typed error`() {
        val parsed = Opml.read("<!doctype html><html><body><p>subscriptions</p></body></html>")

        assertThat(parsed).isInstanceOf(OpmlParse.Malformed::class.java)
    }

    @Test
    fun `an empty file is a typed error`() {
        assertThat(Opml.read("   ")).isInstanceOf(OpmlParse.Malformed::class.java)
    }

    @Test
    fun `truncated OPML never throws`() {
        val parsed = Opml.read(
            """<opml version="2.0"><body><outline type="rss" text="A" xmlUrl="https://a.example/feed">""",
        )

        assertThat(parsed).isEqualTo(
            OpmlParse.Success(listOf(OpmlOutline("A", "https://a.example/feed", null)), invalid = 0),
        )
    }
}
