package dev.mkiros.perch.ui.article.document

import kotlin.math.roundToInt

interface LayoutItem {
    val index: Int
    val offset: Int
    val size: Int
}

fun pageUnderCentre(visible: List<LayoutItem>, viewportHeight: Int): Int? {
    val centre = viewportHeight / 2f

    // Find the item that straddles the centre, skipping the header (item 0)
    for (item in visible) {
        if (item.index == 0) continue

        val itemTop = item.offset
        val itemBottom = item.offset + item.size

        if (itemTop <= centre && centre < itemBottom) {
            return item.index
        }
    }

    return null
}

/**
 * Where a document was left (E01, #65): the list [item] at the top of the screen — item 0 is
 * the header, item N page N — and how far into it the screen's top edge sits, as [depth]
 * ten-thousandths of the item's height. A share rather than pixels, because a page's height
 * is its width over its aspect and the width is the window's, which can differ next time.
 *
 * It is stored in the entry's one `scrollPosition` integer as `(item + 1) × SCALE + depth`,
 * so every value written this way is at least [SCALE]. 0.9.0 wrote the bare item under the
 * centre of the screen, a number below any document's page count and so below [SCALE]:
 * [decode] reads one of those as that item's top, exactly where 0.9.0 reopened it.
 * The encoding grows with the position, which the duplicate merge's `MAX` relies on.
 */
data class DocumentPosition(val item: Int, val depth: Int) {

    /** The offset into an item [size] pixels tall; exact for any page under [SCALE] pixels. */
    fun offsetIn(size: Int): Int = (depth.toLong() * size / SCALE.toDouble()).roundToInt()

    fun encode(): Int = (item + 1) * SCALE + depth

    companion object {
        const val SCALE = 10_000

        fun of(item: Int, offset: Int, size: Int): DocumentPosition =
            DocumentPosition(item, if (size <= 0) 0 else (offset * SCALE.toDouble() / size).roundToInt().coerceIn(0, SCALE - 1))

        fun decode(stored: Int): DocumentPosition =
            if (stored < SCALE) DocumentPosition(item = stored.coerceAtLeast(0), depth = 0)
            else DocumentPosition(item = stored / SCALE - 1, depth = stored % SCALE)
    }
}
