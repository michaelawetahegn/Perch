package dev.mkiros.perch.data.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import java.io.File
import android.os.ParcelFileDescriptor

/** Production PDF rasterizer using Android's PdfRenderer. */
class PdfRendererRasterizer : PageRasterizer {
    override fun open(file: File): PageSource? {
        val fd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            return null // gone, or not ours to read
        }
        return try {
            PdfRendererSource(PdfRenderer(fd), fd)
        } catch (e: Exception) {
            // Not a PDF, damaged, or password-protected: PdfRenderer says so with IOException
            // or SecurityException.
            fd.close()
            null
        } catch (e: NoSuchMethodError) {
            // Robolectric has no PdfRenderer (PLAN-13 §0.5); on a device this never happens.
            fd.close()
            null
        }
    }
}

/** Not thread-safe, one open page at a time: [dev.mkiros.perch.ui.article.document.PageCache] serialises renders. */
private class PdfRendererSource(
    private val renderer: PdfRenderer,
    private val fd: ParcelFileDescriptor,
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
