package dev.mkiros.perch.data.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.Mutex

/** Production PDF rasterizer using Android's PdfRenderer. */
class PdfRendererRasterizer : PageRasterizer {
    override fun open(file: File): PageSource? {
        return try {
            val fd = android.os.ParcelFileDescriptor.open(
                file,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            try {
                val renderer = PdfRenderer(fd)
                PdfRendererSource(renderer, fd)
            } catch (e: Throwable) {
                // PdfRenderer may fail for non-PDF files or under Robolectric (NoSuchMethodError)
                fd.close()
                null
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: Throwable) {
            // Catch any other errors
            null
        }
    }
}

@RequiresApi(21)
private class PdfRendererSource(
    private val renderer: PdfRenderer,
    private val fd: android.os.ParcelFileDescriptor,
) : PageSource {
    override val pageCount: Int = renderer.pageCount
    private val mutex = Mutex()

    override fun size(index: Int): PageSize {
        val page = renderer.openPage(index)
        val size = PageSize(page.width, page.height)
        page.close()
        return size
    }

    override fun render(index: Int, widthPx: Int): Bitmap {
        val page = renderer.openPage(index)
        val sizePoints = PageSize(page.width, page.height)
        val aspect = sizePoints.width.toFloat() / sizePoints.height.toFloat()
        val heightPx = (widthPx / aspect).toInt()

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        // Fill with white background
        bitmap.eraseColor(Color.WHITE)
        // Render to bitmap (scaled to fit)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        return bitmap
    }

    override fun close() {
        renderer.close()
        fd.close()
    }
}
