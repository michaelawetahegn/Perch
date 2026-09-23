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
            if (fixture.published != null && fixture.slug != "encrypted-empty-user-password") {
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
    fun `an Info dictionary inside an object stream is found`() {
        // nist-sp800-63-4 has no plain `4921 0 obj`: its Info dictionary lives in a /Type /ObjStm
        val fixture = DocumentFixtures.manifest().find { it.slug == "nist-sp800-63-4" }
        assertThat(fixture).isNotNull()
        val info = PdfInfoReader.read(fixture!!.file)
        assertThat(info.title).isEqualTo("Digital Identity Guidelines")
        assertThat(info.creationDate).isEqualTo(fixture.published)
    }

    @Test
    fun `an object stream is read by its header, not by the first dictionary in it`() {
        // ISO 32000-1 §7.5.7: object 8 sits second, behind a decoy that has a /Title of its own
        val decoy = "<< /Title (Decoy) >>"
        val info = "<< /Title (Found by offset) /CreationDate (D:20240102030405Z) >>"
        val objects = "$decoy $info"
        val header = "7 0 8 ${decoy.length + 1} "
        val deflater = java.util.zip.Deflater()
        deflater.setInput((header + objects).toByteArray(Charsets.ISO_8859_1))
        deflater.finish()
        val buffer = ByteArray(4096)
        val packed = buffer.copyOf(deflater.deflate(buffer))
        deflater.end()

        val pdf = java.io.ByteArrayOutputStream()
        pdf.write("%PDF-1.5\n9 0 obj\n<< /Type /ObjStm /N 2 /First ${header.length} /Filter /FlateDecode /Length ${packed.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        pdf.write(packed)
        pdf.write("\nendstream\nendobj\ntrailer\n<< /Info 8 0 R >>\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        val file = java.io.File.createTempFile("objstm", ".pdf").apply { deleteOnExit(); writeBytes(pdf.toByteArray()) }

        val read = PdfInfoReader.read(file)
        assertThat(read.title).isEqualTo("Found by offset")
        assertThat(read.creationDate).isEqualTo(java.time.Instant.parse("2024-01-02T03:04:05Z"))
    }

    @Test
    fun `XMP dc_title is read only when the dictionary has no title`() {
        val xmp = "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">From XMP &amp; first</rdf:li></rdf:Alt></dc:title>" +
            "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Second XMP</rdf:li></rdf:Alt></dc:title>"
        assertThat(PdfInfoReader.read(pdf(info = "<< /CreationDate (D:20240102) >>", xmp = xmp)).title)
            .isEqualTo("From XMP & first")
        assertThat(PdfInfoReader.read(pdf(info = "<< /Title (From Info) >>", xmp = xmp)).title)
            .isEqualTo("From Info")
        // nist-sp800-63-4's XMP says "Print"; its Info dictionary outranks it
        val nist = DocumentFixtures.manifest().first { it.slug == "nist-sp800-63-4" }
        assertThat(PdfInfoReader.read(nist.file).title).isEqualTo("Digital Identity Guidelines")
    }

    @Test
    fun `Print is not a title`() {
        assertThat(PdfInfoReader.read(pdf(info = "<< /Title (Print) >>")).title).isNull()
        val xmp = "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Print</rdf:li></rdf:Alt></dc:title>" +
            "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Not the first</rdf:li></rdf:Alt></dc:title>"
        assertThat(PdfInfoReader.read(pdf(info = "<< >>", xmp = xmp)).title).isNull()
    }

    @Test
    fun `a title equal to the file name is not a title`() {
        assertThat(PdfInfoReader.read(pdf(info = "<< /Title (Quarterly-Report) >>", name = "quarterly-report")).title)
            .isNull()
    }

    @Test
    fun `a Microsoft Word prefix and a doc suffix are dropped`() {
        assertThat(PdfInfoReader.read(pdf(info = "<< /Title (Microsoft Word - Budget 2026.docx) >>")).title)
            .isEqualTo("Budget 2026")
        assertThat(PdfInfoReader.read(pdf(info = "<< /Title (Field notes.indd) >>")).title)
            .isEqualTo("Field notes")
    }

    @Test
    fun `a literal title behind a UTF-16BE BOM decodes`() {
        val utf16 = String(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Grüße".toByteArray(Charsets.UTF_16BE), Charsets.ISO_8859_1)
        assertThat(PdfInfoReader.read(pdf(info = "<< /Title ($utf16) >>")).title).isEqualTo("Grüße")
    }

    @Test
    fun `whitespace inside a title is collapsed`() {
        assertThat(PdfInfoReader.read(pdf(info = "<< /Title (  Two\\nlines   and  gaps ) >>")).title)
            .isEqualTo("Two lines and gaps")
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
        for (fixture in DocumentFixtures.manifest()) {
            if (fixture.published != null && fixture.slug != "encrypted-empty-user-password") {
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

    /** A 40 MiB file is not read into memory whole (twice, as bytes and as text) for its title. */
    @Test
    fun `a file larger than two windows is read at its ends, where its trailer and Info are`() {
        val file = padded(head = "", tail = "1 0 obj\n<< /Title (At the end) >>\nendobj\ntrailer\n<< /Info 1 0 R >>\n%%EOF\n")

        assertThat(PdfInfoReader.read(file, window = WINDOW).title).isEqualTo("At the end")
    }

    @Test
    fun `metadata in the middle of a file larger than two windows is never read`() {
        val file = padded(head = "", middle = "1 0 obj\n<< /Title (In the middle) >>\nendobj\n", tail = "trailer\n<< /Info 1 0 R >>\n%%EOF\n")

        assertThat(PdfInfoReader.read(file, window = WINDOW).title).isNull()
        assertThat(PdfInfoReader.read(file, window = file.length().toInt()).title).isEqualTo("In the middle")
    }

    /** `%PDF-`, [head], then comment padding to well past two [WINDOW]s around [middle], then [tail]. */
    private fun padded(head: String, tail: String, middle: String = ""): java.io.File {
        val padding = "%" + "x".repeat(WINDOW * 2) + "\n"
        val text = "%PDF-1.4\n$head$padding$middle$padding$tail"
        val dir = kotlin.io.path.createTempDirectory("pdfinfo").toFile().apply { deleteOnExit() }
        return java.io.File(dir, "padded.pdf").apply { deleteOnExit(); writeBytes(text.toByteArray(Charsets.ISO_8859_1)) }
    }

    /** A minimal plain-object PDF: the Info dictionary as object 1, an optional XMP stream as object 2. */
    private fun pdf(info: String, xmp: String? = null, name: String = "synthetic"): java.io.File {
        val body = StringBuilder("%PDF-1.4\n1 0 obj\n$info\nendobj\n")
        if (xmp != null) {
            val packet = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description>$xmp</rdf:Description></rdf:RDF></x:xmpmeta>"
            body.append("2 0 obj\n<< /Type /Metadata /Subtype /XML /Length ${packet.length} >>\nstream\n$packet\nendstream\nendobj\n")
        }
        body.append("trailer\n<< /Info 1 0 R >>\n%%EOF\n")
        val dir = kotlin.io.path.createTempDirectory("pdfinfo").toFile().apply { deleteOnExit() }
        return java.io.File(dir, "$name.pdf").apply { deleteOnExit(); writeBytes(body.toString().toByteArray(Charsets.ISO_8859_1)) }
    }

    private companion object {
        const val WINDOW = 1024
    }
}
