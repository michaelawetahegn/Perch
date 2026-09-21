package dev.mkiros.perch.ui.article.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentZoomTest {
    @Test
    fun `a pinch scales about the centroid and keeps the point under the fingers`() {
        val current = DocTransform()
        val scale = DocumentZoom.pinch(current, viewportWidth = 1000f, centroidX = 750f, panX = 0f, zoom = 2f)

        assertThat(scale.scale).isWithin(0.01f).of(2f)
        assertThat(scale.offsetX).isWithin(0.01f).of(-250f)
    }

    @Test
    fun `scale is clamped to 1×–4×`() {
        val current = DocTransform()
        val tooHigh = DocumentZoom.pinch(current, viewportWidth = 1000f, centroidX = 500f, panX = 0f, zoom = 10f)
        val tooLow = DocumentZoom.pinch(current, viewportWidth = 1000f, centroidX = 500f, panX = 0f, zoom = 0.1f)

        assertThat(tooHigh.scale).isWithin(0.01f).of(DocumentZoom.MAX_SCALE)
        assertThat(tooLow.scale).isWithin(0.01f).of(DocumentZoom.MIN_SCALE)
    }

    @Test
    fun `offsetX never shows the left or right edge past the viewport`() {
        // At 2× scale, a 1000px viewport shows 500px of content;
        // the offset can be at most 0 (right edge) or -500 (left edge off-screen)
        val at2x = DocTransform(scale = 2f, offsetX = 0f)
        val panned = DocumentZoom.drag(at2x, viewportWidth = 1000f, dx = 1000f)

        assertThat(panned.offsetX).isAtMost(0f)
        assertThat(panned.offsetX).isAtLeast(-500f)
    }

    @Test
    fun `a drag pans only while zoomed and stays clamped`() {
        val atFit = DocTransform()
        val dragged = DocumentZoom.drag(atFit, viewportWidth = 1000f, dx = 100f)
        assertThat(dragged).isEqualTo(atFit)

        val zoomed = DocTransform(scale = 2f, offsetX = 0f)
        val zoomedDragged = DocumentZoom.drag(zoomed, viewportWidth = 1000f, dx = -100f)
        assertThat(zoomedDragged.offsetX).isWithin(0.01f).of(-100f)
    }

    @Test
    fun `double tap at fit goes to the text column when there is one`() {
        val current = DocTransform()
        val column = TextColumn(left = 0.10f, width = 0.80f)
        val result = DocumentZoom.doubleTap(current, viewportWidth = 1000f, atX = 500f, column = column)

        // Column spans 100px (0.10 * 1000) to 900px (0.90 * 1000), width 800px
        // To fit 800px wide: scale = min(1000 / 800, MAX_SCALE) = min(1.25, 4) = 1.25
        // offsetX = -100 * 1.25 = -125 (clamped)
        assertThat(result.scale).isWithin(0.01f).of(1.25f)
        assertThat(result.offsetX).isWithin(0.01f).of(-125f)
    }

    @Test
    fun `double tap with a column wider than nine tenths is a 2× about the tap`() {
        val current = DocTransform()
        val wideColumn = TextColumn(left = 0.05f, width = 0.95f)
        val result = DocumentZoom.doubleTap(current, viewportWidth = 1000f, atX = 500f, column = wideColumn)

        // Column is too wide (95%), so use fallback 2×
        assertThat(result.scale).isWithin(0.01f).of(DocumentZoom.FALLBACK_SCALE)
    }

    @Test
    fun `double tap while zoomed returns to fit`() {
        val zoomed = DocTransform(scale = 2f, offsetX = -100f)
        val result = DocumentZoom.doubleTap(zoomed, viewportWidth = 1000f, atX = 500f, column = null)

        assertThat(result.scale).isWithin(0.01f).of(1f)
        assertThat(result.offsetX).isWithin(0.01f).of(0f)
    }

    @Test
    fun `the column's scale is capped at the maximum`() {
        val current = DocTransform()
        val narrowColumn = TextColumn(left = 0f, width = 0.1f)
        val result = DocumentZoom.doubleTap(current, viewportWidth = 1000f, atX = 500f, column = narrowColumn)

        // Column is 100px wide; to fit would need 10× but capped at MAX_SCALE
        assertThat(result.scale).isAtMost(DocumentZoom.MAX_SCALE)
    }

    @Test
    fun `isZoomed returns true only above fit`() {
        assertThat(DocumentZoom.isZoomed(1f)).isFalse()
        assertThat(DocumentZoom.isZoomed(1.1f)).isTrue()
        assertThat(DocumentZoom.isZoomed(4f)).isTrue()
    }
}
