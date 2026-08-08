package dev.mkiros.perch.data.opml

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import java.time.LocalDate
import org.junit.Test

/**
 * The OPML document format itself: what Perch writes, and what it is willing to read back
 * from every other reader on earth.
 *
 * Reading is where the leniency lives. Exports in the wild nest their sources several
 * folders deep, disagree about whether the label is `text` or `title`, and sometimes carry
 * outlines that point at nothing at all — none of which is a reason to reject the file.
 * Only a document that is not an OPML document at all is an error, and even that is a
 * value, never a throw.
 *
 * Since U13 a folder is carried rather than discarded, which puts one new obligation on
 * both halves: whatever nesting a file arrives with, a source comes back out of [Opml.read]
 * under exactly one folder name, and [Opml.write] puts it back under exactly one container.
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
    fun `export writes an unfiled source as a top-level rss outline`() {
        val xml = Opml.write(outlines)

        assertThat(xml).contains(
            """<outline type="rss" text="Null Program" title="Null Program" """ +
                """xmlUrl="https://nullprogram.com/feed/" htmlUrl="https://nullprogram.com/" />""",
        )
        assertThat(xml).doesNotContain("htmlUrl=\"\"")
    }

    @Test
    fun `export nests each folder as a container holding its sources`() {
        val xml = Opml.write(
            listOf(
                OpmlOutline("A", "https://a.example/feed", folder = "AI/LLM"),
                OpmlOutline("B", "https://b.example/feed", folder = "AI/LLM"),
            ),
        )

        assertThat(xml).contains("""<outline text="AI/LLM" title="AI/LLM">""")
        assertThat(xml).contains("""<outline type="rss" text="A" title="A" xmlUrl="https://a.example/feed" />""")
        assertThat(xml).contains("""<outline type="rss" text="B" title="B" xmlUrl="https://b.example/feed" />""")
        // One container, not one per source.
        assertThat(xml.windowed("AI/LLM".length).count { it == "AI/LLM" }).isEqualTo(2)
    }

    @Test
    fun `export writes folders in order and unfiled sources at top level after them`() {
        val xml = Opml.write(
            listOf(
                OpmlOutline("A", "https://a.example/feed", folder = "First"),
                OpmlOutline("C", "https://c.example/feed"),
                OpmlOutline("B", "https://b.example/feed", folder = "Second"),
            ),
        )

        val first = xml.indexOf(""""First"""")
        val second = xml.indexOf(""""Second"""")
        val unfiled = xml.indexOf("https://c.example/feed")
        assertThat(first).isLessThan(second)
        assertThat(second).isLessThan(unfiled)
    }

    @Test
    fun `a folder whose sources are not adjacent still writes one container`() {
        val xml = Opml.write(
            listOf(
                OpmlOutline("A", "https://a.example/feed", folder = "Tech"),
                OpmlOutline("B", "https://b.example/feed", folder = "News"),
                OpmlOutline("C", "https://c.example/feed", folder = "Tech"),
            ),
        )

        assertThat(Opml.read(xml)).isEqualTo(
            OpmlParse.Success(
                listOf(
                    OpmlOutline("A", "https://a.example/feed", null, "Tech"),
                    OpmlOutline("C", "https://c.example/feed", null, "Tech"),
                    OpmlOutline("B", "https://b.example/feed", null, "News"),
                ),
                invalid = 0,
            ),
        )
    }

    @Test
    fun `a folder name that would break the document is escaped and survives the round trip`() {
        val filed = listOf(OpmlOutline("A", "https://a.example/feed", null, """R&D <"core">"""))

        assertThat(Opml.read(Opml.write(filed))).isEqualTo(OpmlParse.Success(filed, invalid = 0))
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
    fun `import flattens deep nesting onto the outermost folder name`() {
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
                    OpmlOutline("A", "https://a.example/feed", "https://a.example/", "Tech"),
                    // Not "Deeper": one level of folders is all Perch has, so a source
                    // buried three deep files under the folder the user can actually see.
                    OpmlOutline("B", "https://b.example/feed", null, "Tech"),
                    OpmlOutline("C", "https://c.example/feed", null, null),
                ),
                invalid = 0,
            ),
        )
    }

    @Test
    fun `a container labelled by title rather than text still names a folder`() {
        val parsed = Opml.read(
            """
            <opml version="2.0"><body>
              <outline title="Security"><outline type="rss" text="A" xmlUrl="https://a.example/feed"/></outline>
            </body></opml>
            """.trimIndent(),
        )

        assertThat((parsed as OpmlParse.Success).outlines.single().folder).isEqualTo("Security")
    }

    @Test
    fun `a container with a blank label leaves its sources unfiled`() {
        val parsed = Opml.read(
            """
            <opml version="2.0"><body>
              <outline text="   "><outline type="rss" text="A" xmlUrl="https://a.example/feed"/></outline>
              <outline><outline type="rss" text="B" xmlUrl="https://b.example/feed"/></outline>
            </body></opml>
            """.trimIndent(),
        )

        assertThat((parsed as OpmlParse.Success).outlines.map { it.folder }).containsExactly(null, null)
    }

    @Test
    fun `a real export from another reader imports with its folders intact`() {
        val parsed = Opml.read(File(repoRoot(), "fixtures/opml/other-reader.opml").readText())

        val success = parsed as OpmlParse.Success
        assertThat(success.outlines.map { it.title to it.folder }).containsExactly(
            "Daring Fireball" to null,
            "Null Program" to "Programming",
            "Embedded in Academia" to "Programming",
            "LLVM Project Blog" to "Programming",
            "Project Zero" to "Security",
            "Simon Willison" to null,
        ).inOrder()
        assertThat(success.invalid).isEqualTo(1)
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

    /** Walks up from the working directory, which is `:app` under Gradle and the root elsewhere. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "fixtures/opml").isDirectory) return dir
            dir = dir.parentFile
        }
        error("fixtures/opml not found")
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
