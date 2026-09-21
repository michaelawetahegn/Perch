package dev.mkiros.perch.data.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import java.io.File
import java.io.IOException

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

/** Not thread-safe, one open page at a time: [dev.mkiros.perch.ui.article.document.PageCache] serialises renders. */
private class PdfRendererSource(
    private val renderer: PdfRenderer,
    private val fd: android.os.ParcelFileDescriptor,
) : PageSource {
    override val pageCount: Int = renderer.pageCount

    override fun size(index: Int): PageSize {
        return renderer.openPage(index).use { page -> PageSize(page.width, page.height) }
    }

    override fun render(index: Int, widthPx: Int): Bitmap {
        return renderer.openPage(index).use { page ->
            val heightPx = (widthPx * page.height.toFloat() / page.width).toInt()
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    override fun close() {
        renderer.close()
        fd.close()
    }
}
