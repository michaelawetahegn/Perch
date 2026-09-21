package dev.mkiros.perch.ui.article.document

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

data class TextColumn(val left: Float, val width: Float) {
    companion object {
        fun of(pages: List<Bitmap>): TextColumn? {
            if (pages.isEmpty()) return null

            // Measure pages 2, 3, 4 (indices 1..3) if there are 4+ pages, else all
            val pagesToMeasure = if (pages.size >= 4) {
                pages.subList(1, minOf(4, pages.size))
            } else {
                pages
            }

            var leftmost = 1f
            var rightmost = 0f
            var foundAnyInk = false

            for (bitmap in pagesToMeasure) {
                val ground = findGroundColor(bitmap)
                val (left, right) = findInkBounds(bitmap, ground)

                if (left != null && right != null && left < right) {
                    foundAnyInk = true
                    leftmost = min(leftmost, left)
                    rightmost = max(rightmost, right)
                }
            }

            if (!foundAnyInk || leftmost >= rightmost) {
                return null
            }

            val width = rightmost - leftmost

            // Pad 2% each side
            val padded = width * 0.02f
            val paddedLeft = (leftmost - padded).coerceIn(0f, 1f)
            val paddedRight = (rightmost + padded).coerceIn(0f, 1f)
            val paddedWidth = paddedRight - paddedLeft

            // Return null if span is >= COLUMN_MAX_WIDTH
            if (paddedWidth >= DocumentZoom.COLUMN_MAX_WIDTH) {
                return null
            }

            return TextColumn(left = paddedLeft, width = paddedWidth)
        }

        private fun findGroundColor(bitmap: Bitmap): Int {
            // Sample the border to find the most common color (the ground)
            val colorCounts = mutableMapOf<Int, Int>()
            val width = bitmap.width
            val height = bitmap.height

            // Sample top and bottom edges
            for (x in 0 until width) {
                val topPixel = bitmap.getPixel(x, 0)
                val bottomPixel = bitmap.getPixel(x, height - 1)
                colorCounts[topPixel] = colorCounts.getOrDefault(topPixel, 0) + 1
                colorCounts[bottomPixel] = colorCounts.getOrDefault(bottomPixel, 0) + 1
            }

            // Sample left and right edges
            for (y in 0 until height) {
                val leftPixel = bitmap.getPixel(0, y)
                val rightPixel = bitmap.getPixel(width - 1, y)
                colorCounts[leftPixel] = colorCounts.getOrDefault(leftPixel, 0) + 1
                colorCounts[rightPixel] = colorCounts.getOrDefault(rightPixel, 0) + 1
            }

            // Return the most common color (default to white if map is empty)
            return colorCounts.maxByOrNull { it.value }?.key ?: 0xFFFFFFFF.toInt()
        }

        private fun findInkBounds(bitmap: Bitmap, ground: Int): Pair<Float?, Float?> {
            val width = bitmap.width
            val height = bitmap.height
            val groundLuminance = getLuminance(ground)
            val inkThreshold = 48f / 255f

            // For each column x, count how many sampled rows have ink
            var leftmost: Int? = null
            var rightmost: Int? = null

            for (x in 0 until width) {
                var inkCount = 0

                // Sample every second row
                for (y in 1 until height step 2) {
                    val pixel = bitmap.getPixel(x, y)
                    val pixelLuminance = getLuminance(pixel)
                    if (kotlin.math.abs(pixelLuminance - groundLuminance) > inkThreshold) {
                        inkCount++
                    }
                }

                // Column has ink if at least 2 sampled rows are ink
                if (inkCount >= 2) {
                    if (leftmost == null) {
                        leftmost = x
                    }
                    rightmost = x
                }
            }

            if (leftmost == null || rightmost == null) {
                return Pair(null, null)
            }

            // Convert pixel positions to [0, 1] range
            val left = leftmost.toFloat() / width
            val right = (rightmost + 1).toFloat() / width

            return Pair(left, right)
        }

        private fun getLuminance(color: Int): Float {
            val r = Color.red(color) / 255f
            val g = Color.green(color) / 255f
            val b = Color.blue(color) / 255f
            // Standard luminance formula
            return 0.299f * r + 0.587f * g + 0.114f * b
        }
    }
}
