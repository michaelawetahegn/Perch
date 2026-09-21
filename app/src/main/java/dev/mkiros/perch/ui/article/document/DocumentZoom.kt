package dev.mkiros.perch.ui.article.document

import kotlin.math.max
import kotlin.math.min

data class DocTransform(val scale: Float = 1f, val offsetX: Float = 0f)

object DocumentZoom {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f
    const val FALLBACK_SCALE = 2f
    const val COLUMN_MAX_WIDTH = 0.9f

    fun pinch(current: DocTransform, viewportWidth: Float, centroidX: Float, panX: Float, zoom: Float): DocTransform {
        val newScale = (current.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val viewportCenter = viewportWidth / 2f

        // Centroid offset from viewport center
        val centroidOffset = centroidX - viewportCenter
        // Content offset from viewport center at current scale
        val contentOffsetX = (centroidOffset - current.offsetX) / current.scale
        // Where that content point should be at new scale
        // viewport_offset = offset_x + content_offset * scale
        val newOffsetX = centroidOffset - (contentOffsetX * newScale)
        val clampedOffsetX = newOffsetX.coerceIn(-(viewportWidth * (newScale - 1f)), 0f)

        return DocTransform(scale = newScale, offsetX = clampedOffsetX)
    }

    fun drag(current: DocTransform, viewportWidth: Float, dx: Float): DocTransform {
        if (current.scale <= 1f) return current

        val newOffsetX = (current.offsetX + dx).coerceIn(-(viewportWidth * (current.scale - 1f)), 0f)
        return current.copy(offsetX = newOffsetX)
    }

    fun doubleTap(current: DocTransform, viewportWidth: Float, atX: Float, column: TextColumn?): DocTransform {
        // If already zoomed, return to fit
        if (isZoomed(current.scale)) {
            return DocTransform()
        }

        // If column exists and is not too wide, zoom to fit it
        if (column != null && column.width < COLUMN_MAX_WIDTH) {
            val targetScale = min(1f / column.width, MAX_SCALE.toFloat())
            val offsetX = -(column.left * viewportWidth * targetScale)
            val clampedOffsetX = offsetX.coerceIn(-(viewportWidth * (targetScale - 1f)), 0f)
            return DocTransform(scale = targetScale, offsetX = clampedOffsetX)
        }

        // Otherwise, use fallback 2× zoom about the tap point
        return pinch(current, viewportWidth, centroidX = atX, panX = 0f, zoom = FALLBACK_SCALE)
    }

    fun isZoomed(scale: Float): Boolean = scale > 1f
}
