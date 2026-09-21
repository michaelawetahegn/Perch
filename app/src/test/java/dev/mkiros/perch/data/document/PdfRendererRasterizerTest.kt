package dev.mkiros.perch.data.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfRendererRasterizerTest {

    private val rasterizer = PdfRendererRasterizer()

    @Test
    fun `a file that is not a PDF opens as null`() {
        val htmlFile = DocumentFixtures.manifest().first { it.slug == "html-in-disguise" }.file
        val source = rasterizer.open(htmlFile)
        assertThat(source).isNull()
    }
}
