package dev.mkiros.perch.ui.article.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Where a document was left, as the one `scrollPosition` integer the entry row keeps: the
 * item at the top of the screen and how far into it the screen's top edge sits, as a share
 * of that item's height — so it holds when the page is drawn at another size next time.
 */
class DocumentPositionTest {

    @Test
    fun `a mid-page offset survives the round trip to the pixel`() {
        val stored = DocumentPosition.of(item = 2, offset = 347, size = 1_802).encode()

        val restored = DocumentPosition.decode(stored)

        assertThat(restored.item).isEqualTo(2)
        assertThat(restored.offsetIn(1_802)).isEqualTo(347)
    }

    @Test
    fun `every offset into a page restores to the same offset`() {
        val size = 2_417
        for (offset in 0 until size) {
            val restored = DocumentPosition.decode(DocumentPosition.of(item = 7, offset = offset, size = size).encode())
            assertThat(restored.offsetIn(size)).isEqualTo(offset)
        }
    }

    @Test
    fun `re-saving a restored position writes the same value`() {
        val stored = DocumentPosition.of(item = 3, offset = 1_111, size = 1_900).encode()
        val restored = DocumentPosition.decode(stored)

        assertThat(DocumentPosition.of(restored.item, restored.offsetIn(1_900), 1_900).encode()).isEqualTo(stored)
    }

    @Test
    fun `the position is a share of the page, so it holds when the page is drawn larger`() {
        // The bottom fifth of page two at one width is the bottom fifth at twice that width.
        val stored = DocumentPosition.of(item = 2, offset = 800, size = 1_000).encode()

        assertThat(DocumentPosition.decode(stored).offsetIn(2_000)).isEqualTo(1_600)
    }

    @Test
    fun `a value saved by 0_9_0 is the page it named, opened at its top`() {
        // 0.9.0 wrote the list item under the centre of the screen — a small whole number.
        val restored = DocumentPosition.decode(3)

        assertThat(restored.item).isEqualTo(3)
        assertThat(restored.offsetIn(1_800)).isEqualTo(0)
    }

    @Test
    fun `a document never opened is at the very top`() {
        val restored = DocumentPosition.decode(0)

        assertThat(restored.item).isEqualTo(0)
        assertThat(restored.offsetIn(500)).isEqualTo(0)
    }

    @Test
    fun `a new position never reads as an old one, even at the very top`() {
        val stored = DocumentPosition.of(item = 0, offset = 40, size = 400).encode()

        val restored = DocumentPosition.decode(stored)

        assertThat(restored.item).isEqualTo(0)
        assertThat(restored.offsetIn(400)).isEqualTo(40)
    }

    @Test
    fun `a later position always stores a larger value`() {
        // The duplicate merge (PerchDatabase, 9 → 10) keeps the MAX of two rows' positions.
        val earlier = DocumentPosition.of(item = 4, offset = 1_700, size = 1_800).encode()
        val later = DocumentPosition.of(item = 5, offset = 10, size = 1_800).encode()

        assertThat(later).isGreaterThan(earlier)
        assertThat(DocumentPosition.of(item = 1, offset = 0, size = 1_800).encode()).isGreaterThan(112)
    }
}
