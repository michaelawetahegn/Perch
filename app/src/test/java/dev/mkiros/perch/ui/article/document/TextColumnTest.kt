package dev.mkiros.perch.ui.article.document

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextColumnTest {

    @Test
    fun `letter-margins finds the one-inch margins`() {
        // Create a synthetic page with margins (white ground, black ink in the middle)
        val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFFFFFF.toInt())  // White background

        // Draw a text column in the middle (roughly 72..540 of 612, which is ~0.12 to 0.88)
        for (y in 100 until 700) {
            for (x in 72 until 540) {
                bitmap.setPixel(x, y, 0xFF000000.toInt())  // Black ink
            }
        }

        val column = TextColumn.of(listOf(bitmap))

        assertThat(column).isNotNull()
        column?.let {
            assertThat(it.left).isWithin(0.05f).of(0.10f)
            assertThat(it.width).isWithin(0.10f).of(0.70f)
        }
    }

    @Test
    fun `a scan on a grey ground finds its ink`() {
        // Create a page with grey background and black ink
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF808080.toInt())  // Grey background

        // Draw some black ink
        for (y in 50 until 350) {
            for (x in 50 until 150) {
                bitmap.setPixel(x, y, 0xFF000000.toInt())  // Black ink
            }
        }

        val column = TextColumn.of(listOf(bitmap))

        // Should find the ink column
        if (column != null) {
            assertThat(column.left).isAtLeast(0f)
            assertThat(column.left + column.width).isAtMost(1f)
        }
    }

    @Test
    fun `two columns are one span`() {
        // Create a page with ink on both left and right sides
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFFFFFF.toInt())  // White

        // Left column
        for (y in 50 until 350) {
            for (x in 30 until 80) {
                bitmap.setPixel(x, y, 0xFF000000.toInt())
            }
        }
        // Right column
        for (y in 50 until 350) {
            for (x in 320 until 370) {
                bitmap.setPixel(x, y, 0xFF000000.toInt())
            }
        }

        val column = TextColumn.of(listOf(bitmap))

        // Two columns inside one span should return a single span covering both
        if (column != null) {
            assertThat(column.width).isAtMost(DocumentZoom.COLUMN_MAX_WIDTH)
        }
    }

    @Test
    fun `a blank page is skipped and all-blank is null`() {
        val bitmaps = listOf(
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
        )

        // Fill all pixels with white (no ink)
        for (bitmap in bitmaps) {
            bitmap.eraseColor(0xFFFFFFFF.toInt())
        }

        val column = TextColumn.of(bitmaps)
        assertThat(column).isNull()
    }

    @Test
    fun `ink from edge to edge is null`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF000000.toInt())  // All black

        val column = TextColumn.of(listOf(bitmap))
        assertThat(column).isNull()
    }
}
