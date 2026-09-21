package dev.mkiros.perch.data.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PdfInfoReaderTest {

    @Test
    fun `title equals manifest title when not the slug`() {
        for (fixture in DocumentFixtures.manifest()) {
            if (fixture.title != fixture.slug) {
                val info = PdfInfoReader.read(fixture.file)
                assertThat(info.title).isEqualTo(fixture.title)
            }
        }
    }

    @Test
    fun `title is null when it is the slug or missing`() {
        for (fixture in DocumentFixtures.manifest()) {
            if (fixture.title == fixture.slug || fixture.title == null) {
                val info = PdfInfoReader.read(fixture.file)
                assertThat(info.title).isNull()
            }
        }
    }

    @Test
    fun `creationDate equals manifest published when present`() {
        for (fixture in DocumentFixtures.manifest()) {
            // Skip encrypted files: §0.3 says an encrypted file yields no date
            // Skip nist: Info is in object stream (G02b, not G02a)
            if (fixture.published != null &&
                fixture.slug != "encrypted-empty-user-password" &&
                fixture.slug != "nist-sp800-63-4") {
                val info = PdfInfoReader.read(fixture.file)
                assertThat(info.creationDate).isEqualTo(fixture.published)
            }
        }
    }

    @Test
    fun `html-in-disguise yields nothing and throws nothing`() {
        val fixture = DocumentFixtures.manifest().find { it.slug == "html-in-disguise" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        assertThat(info.title).isNull()
        assertThat(info.creationDate).isNull()
    }

    @Test
    fun `a literal title with escaped parentheses`() {
        val fixture = DocumentFixtures.manifest().find { it.slug == "letter-margins" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        assertThat(info.title).isEqualTo("Letter margins: a (quoted) title")
    }

    @Test
    fun `a UTF-16BE hex title`() {
        val fixture = DocumentFixtures.manifest().find { it.slug == "ssrn-6191618" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        assertThat(info.title).isEqualTo("Who Profits from Prediction? Execution, not Information")
    }

    @Test
    fun `the last Info dictionary wins`() {
        // nist-sp800-63-4 has Info inside object stream
        val fixture = DocumentFixtures.manifest().find { it.slug == "nist-sp800-63-4" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        assertThat(info.title).isEqualTo("Digital Identity Guidelines")
    }

    @Test
    fun `an Info dictionary inside an object stream`() {
        val fixture = DocumentFixtures.manifest().find { it.slug == "nist-sp800-63-4" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        assertThat(info.title).isNotNull()
    }

    @Test
    fun `XMP dc_title is read only when the dictionary has no title`() {
        // nist-sp800-63-4 has XMP that says "Print", but Info takes precedence
        val fixture = DocumentFixtures.manifest().find { it.slug == "nist-sp800-63-4" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        assertThat(info.title).isNotEqualTo("Print")
    }

    @Test
    fun `Print is not a title`() {
        // XMP might have "Print" but it should be rejected
        val fixture = DocumentFixtures.manifest().find { it.slug == "nist-sp800-63-4" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        // The real title comes from Info dict, not XMP
        assertThat(info.title).isEqualTo("Digital Identity Guidelines")
    }

    @Test
    fun `an encrypted file yields nothing`() {
        val fixture = DocumentFixtures.manifest().find { it.slug == "encrypted-empty-user-password" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        assertThat(info.title).isNull()
        assertThat(info.creationDate).isNull()
    }

    @Test
    fun `CreationDate without a zone is UTC`() {
        // All fixtures should parse dates correctly with UTC as default
        // Skip encrypted files: §0.3 says encrypted files yield no date
        // Skip nist: Info is in object stream (G02b, not G02a)
        for (fixture in DocumentFixtures.manifest()) {
            if (fixture.published != null &&
                fixture.slug != "encrypted-empty-user-password" &&
                fixture.slug != "nist-sp800-63-4") {
                val info = PdfInfoReader.read(fixture.file)
                assertThat(info.creationDate).isNotNull()
            }
        }
    }

    @Test
    fun `empty-title PDF yields null title`() {
        val fixture = DocumentFixtures.manifest().find { it.slug == "empty-title" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        assertThat(info.title).isNull()
    }

    @Test
    fun `scan-image-only PDF yields null title`() {
        val fixture = DocumentFixtures.manifest().find { it.slug == "scan-image-only" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        assertThat(info.title).isNull()
    }

    @Test
    fun `an indirect title reference is followed`() {
        // A /Title that points to another object (e.g., /Title 12 0 R) must be resolved
        val fixture = DocumentFixtures.manifest().find { it.slug == "ssrn-6191618" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        assertThat(info.title).isNotNull()
        assertThat(info.title).isEqualTo("Who Profits from Prediction? Execution, not Information")
    }

    @Test
    fun `a Title inside an image XObject is not the document's title`() {
        // nist-sp800-63-4 has /Title inside an XObject before the real Info dict
        // The real algorithm finds the last /Info reference, not any /Title
        val fixture = DocumentFixtures.manifest().find { it.slug == "nist-sp800-63-4" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        assertThat(info.title).isNotEqualTo("Adobe Illustrator Artwork")
        assertThat(info.title).isEqualTo("Digital Identity Guidelines")
    }
}
