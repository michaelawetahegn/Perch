package dev.mkiros.perch.ui.article.document

import android.graphics.Bitmap
import android.util.LruCache
import dev.mkiros.perch.data.document.PageSource
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The rendered pages of one open document (PLAN-13 §0.5): the last [CAPACITY] bitmaps,
 * keyed by page and pixel width, over the one [PageSource] the screen opened.
 *
 * A source allows one open page at a time and is not thread-safe, so every render runs
 * under one [Mutex], on [Dispatchers.IO]. The cache itself is touched only from the main
 * thread. An evicted bitmap is recycled unless a page on screen still draws it — [show]
 * pins it — because drawing a recycled bitmap is a crash, not a blank.
 */
class PageCache(private val source: PageSource) : Closeable {

    private val mutex = Mutex()
    private var closed = false
    private val shown = mutableMapOf<Key, Int>()
    private val orphans = mutableMapOf<Key, Bitmap>()
    private val bitmaps = object : LruCache<Key, Bitmap>(CAPACITY) {
        override fun entryRemoved(evicted: Boolean, key: Key, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue === newValue) return
            if (key in shown) orphans[key] = oldValue else oldValue.recycle()
        }
    }

    /** The page already rendered at [widthPx], if the cache still holds it. */
    fun cached(index: Int, widthPx: Int): Bitmap? = bitmaps.get(Key(index, widthPx))

    /** Page [index] at [widthPx] — from the cache, or rendered; null once the document has closed. */
    suspend fun page(index: Int, widthPx: Int): Bitmap? {
        val key = Key(index, widthPx)
        bitmaps.get(key)?.let { return it }
        val rendered = mutex.withLock {
            if (closed) null else withContext(Dispatchers.IO) { source.render(index, widthPx) }
        } ?: return null
        if (closed) return null
        bitmaps.put(key, rendered)
        return rendered
    }

    /** Pins a bitmap a page is drawing; the returned function unpins it. */
    fun show(index: Int, widthPx: Int): () -> Unit {
        val key = Key(index, widthPx)
        shown[key] = (shown[key] ?: 0) + 1
        return {
            val left = (shown[key] ?: 1) - 1
            if (left == 0) {
                shown.remove(key)
                // Evicted while it was on screen: nothing draws it any more.
                orphans.remove(key)?.recycle()
            } else {
                shown[key] = left
            }
        }
    }

    override fun close() {
        closed = true
        bitmaps.evictAll()
        // A render in flight holds the mutex; the source closes when it is done.
        CoroutineScope(Dispatchers.IO).launch { mutex.withLock { source.close() } }
    }

    private data class Key(val index: Int, val widthPx: Int)

    private companion object {
        const val CAPACITY = 6
    }
}
