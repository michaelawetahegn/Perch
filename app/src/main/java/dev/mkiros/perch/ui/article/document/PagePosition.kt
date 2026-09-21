package dev.mkiros.perch.ui.article.document

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
