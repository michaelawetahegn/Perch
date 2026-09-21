package dev.mkiros.perch.data.document

import android.graphics.Bitmap
import java.io.Closeable
import java.io.File

/** Page size in PDF points (not pixels). */
data class PageSize(val width: Int, val height: Int)

/** Renders pages from a document file. */
interface PageRasterizer {
    /** Open a document file. Returns null if the file is not a readable document. */
    fun open(file: File): PageSource?
}

/** An open document. A source is not thread-safe and allows one open page at a time. */
interface PageSource : Closeable {
    /** The number of pages in the document. */
    val pageCount: Int

    /** The size of a page in PDF points. */
    fun size(index: Int): PageSize

    /** Render a page to a bitmap at a specific width in pixels. ARGB_8888, height = widthPx / aspect, white ground. */
    fun render(index: Int, widthPx: Int): Bitmap
}
