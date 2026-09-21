package dev.mkiros.perch.data.document

import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.support.FixtureRasterizer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FixtureRasterizerTest {
    @get:Rule(order = 1)
    val perch = PerchRule()

    private val rasterizer = FixtureRasterizer()

    @Test
    fun `a known file answers the manifest page count and sizes`() {
        val ssrnFile = DocumentFixtures.manifest().first { it.slug == "ssrn-6191618" }.file
        val source = rasterizer.open(ssrnFile)
        assertThat(source).isNotNull()
        assertThat(source!!.pageCount).isEqualTo(112)

        val page1 = source.size(0)
        assertThat(page1.width).isEqualTo(612)
        assertThat(page1.height).isEqualTo(792)

        val mixedFile = DocumentFixtures.manifest().first { it.slug == "mixed-sizes" }.file
        val mixedSource = rasterizer.open(mixedFile)
        assertThat(mixedSource).isNotNull()
        assertThat(mixedSource!!.pageCount).isEqualTo(3)
        assertThat(mixedSource.size(0).width).isEqualTo(595)
        assertThat(mixedSource.size(0).height).isEqualTo(842)
        assertThat(mixedSource.size(1).width).isEqualTo(300)
        assertThat(mixedSource.size(1).height).isEqualTo(200)
        assertThat(mixedSource.size(2).width).isEqualTo(595)
        assertThat(mixedSource.size(2).height).isEqualTo(842)
    }

    @Test
    fun `a rendered page decodes at the width asked for`() {
        val ssrnFile = DocumentFixtures.manifest().first { it.slug == "ssrn-6191618" }.file
        val source = rasterizer.open(ssrnFile)
        assertThat(source).isNotNull()

        val bitmap = source!!.render(0, 300)
        assertThat(bitmap.width).isEqualTo(300)

        val aspect = 612.0f / 792.0f
        val expectedHeight = (300.0f / aspect).toInt()
        assertThat(bitmap.height).isEqualTo(expectedHeight)
    }

    @Test
    fun `a page with no PNG still renders`() {
        val ssrnFile = DocumentFixtures.manifest().first { it.slug == "ssrn-6191618" }.file
        val source = rasterizer.open(ssrnFile)
        assertThat(source).isNotNull()

        // Page 112 has no PNG, but render should still return a white bitmap with a grey rule
        val bitmap = source!!.render(111, 300)
        assertThat(bitmap.width).isEqualTo(300)
        assertThat(bitmap).isNotNull()
    }

    @Test
    fun `an unknown file is null`() {
        val unknownFile = File("/tmp/unknown-file-12345.pdf")
        val source = rasterizer.open(unknownFile)
        assertThat(source).isNull()
    }
}
