package dev.mkiros.perch.ui.article.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageUnderCentreTest {

    private data class TestLayoutItem(override val index: Int, override val offset: Int, override val size: Int) : LayoutItem

    @Test
    fun `the page whose item straddles the centre wins`() {
        // Item 0 (header) at offset 0, size 100
        // Item 1 (page 1) at offset 100, size 500
        // Item 2 (page 2) at offset 600, size 500
        // Viewport height 1000, centre at 500
        // Page 1 straddles the centre (100..600)
        val visible = listOf(
            TestLayoutItem(index = 0, offset = 0, size = 100),
            TestLayoutItem(index = 1, offset = 100, size = 500),
            TestLayoutItem(index = 2, offset = 600, size = 500),
        )

        val result = pageUnderCentre(visible, viewportHeight = 1000)
        assertThat(result).isEqualTo(1)
    }

    @Test
    fun `the header is never a page`() {
        // Item 0 (header) at offset 0, size 100
        // Item 1 (page 1) at offset 100, size 900
        // Viewport height 1000, centre at 500
        // Even if centre is in header range, page 1 is the answer
        val visible = listOf(
            TestLayoutItem(index = 0, offset = 0, size = 100),
            TestLayoutItem(index = 1, offset = 100, size = 900),
        )

        val result = pageUnderCentre(visible, viewportHeight = 1000)
        assertThat(result).isEqualTo(1)
    }

    @Test
    fun `an empty layout is null`() {
        val visible = emptyList<LayoutItem>()
        val result = pageUnderCentre(visible, viewportHeight = 1000)
        assertThat(result).isNull()
    }

    @Test
    fun `a layout with only a header returns null`() {
        val visible = listOf(
            TestLayoutItem(index = 0, offset = 0, size = 100),
        )
        val result = pageUnderCentre(visible, viewportHeight = 1000)
        assertThat(result).isNull()
    }
}
