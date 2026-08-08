package dev.mkiros.perch.ui.article.zoom

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The arithmetic behind U12's viewer, with no composition anywhere near it.
 *
 * Every way a zoomable image ships broken is a bounds bug — a pinch that runs away to 40×,
 * a pan that flings the photo off the screen and leaves the reader looking at a black
 * rectangle, a double tap that zooms somewhere other than where the finger was. Those are
 * all pure functions of (current transform, gesture, viewport), so they are tested as pure
 * functions; [ZoomState] and [ImageViewer] are then only wiring, and the gesture tests over
 * them are about the wiring rather than about the numbers.
 *
 * Coordinates: a gesture arrives in the viewport's own space (origin top-left), while a
 * [Transform]'s offset is a translation applied about the viewport's **centre**, because
 * that is what `graphicsLayer` does with its default transform origin.
 */
class ZoomGeometryTest {

    /** A landscape viewport with a content image of exactly the same shape. */
    private val bounds = ZoomBounds(viewport = Size(1000f, 2000f), contentAspect = 0.5f)

    private val centre = Offset(500f, 1000f)

    @Test
    fun `a pinch scales about the centroid, holding the pinched point still`() {
        val at = Offset(750f, 1500f)

        val after = ZoomGeometry.onGesture(Transform(), bounds, centroid = at, pan = Offset.Zero, zoom = 2f)

        assertThat(after.scale).isWithin(TOLERANCE).of(2f)
        // The pinched point sat 250×500 below-right of centre; at 2× it would drift to
        // 500×1000 unless the offset takes the difference back out.
        assertThat(after.offset.x).isWithin(TOLERANCE).of(-250f)
        assertThat(after.offset.y).isWithin(TOLERANCE).of(-500f)
    }

    @Test
    fun `zooming past the maximum rubber-bands instead of following the fingers`() {
        val far = ZoomGeometry.onGesture(Transform(), bounds, centre, Offset.Zero, zoom = 20f)

        // Well past 5×, so the raw scale is ignored; what is left is a springy overshoot
        // that reads as a wall rather than as a broken gesture.
        assertThat(far.scale).isGreaterThan(ZoomGeometry.MAX_SCALE)
        assertThat(far.scale).isAtMost(ZoomGeometry.MAX_SCALE * ZoomGeometry.OVERSHOOT_LIMIT)
    }

    @Test
    fun `settling returns an overshot pinch to the maximum`() {
        val overshot = ZoomGeometry.onGesture(Transform(), bounds, centre, Offset.Zero, zoom = 20f)

        val settled = ZoomGeometry.settle(overshot, bounds)

        assertThat(settled.scale).isWithin(TOLERANCE).of(ZoomGeometry.MAX_SCALE)
    }

    @Test
    fun `a pinch below the minimum settles back to fit`() {
        val pinchedIn = ZoomGeometry.onGesture(Transform(scale = 2f), bounds, centre, Offset.Zero, zoom = 0.1f)

        assertThat(ZoomGeometry.settle(pinchedIn, bounds).scale)
            .isWithin(TOLERANCE).of(ZoomGeometry.MIN_SCALE)
    }

    @Test
    fun `pan is bounded to the scaled image, so it cannot be flung off screen`() {
        // At 2× the content is 2000×4000 in a 1000×2000 viewport: 500 and 1000 of slack
        // each way. A pan of ten thousand pixels may spend the slack and not one pixel more.
        val panned = ZoomGeometry.onGesture(
            Transform(scale = 2f),
            bounds,
            centroid = centre,
            pan = Offset(10_000f, 10_000f),
            zoom = 1f,
        )

        assertThat(panned.offset.x).isWithin(TOLERANCE).of(500f)
        assertThat(panned.offset.y).isWithin(TOLERANCE).of(1000f)
    }

    @Test
    fun `an image narrower than the viewport cannot be panned sideways at all`() {
        // A tall image letterboxed left and right: there is nothing off-screen to reveal,
        // so a horizontal drag must not slide it out from under the reader.
        val tall = ZoomBounds(viewport = Size(1000f, 2000f), contentAspect = 0.1f)

        val panned = ZoomGeometry.onGesture(Transform(), tall, centre, Offset(400f, 0f), zoom = 1.001f)

        assertThat(panned.offset.x).isWithin(TOLERANCE).of(0f)
    }

    @Test
    fun `zooming back out re-clamps a pan the smaller image can no longer afford`() {
        val zoomedAndPanned = Transform(scale = 4f, offset = Offset(1500f, 3000f))

        val out = ZoomGeometry.onGesture(zoomedAndPanned, bounds, centre, Offset.Zero, zoom = 0.5f)

        // Back at 2× the slack is 500×1000 again; the offset the 4× view had earned is not
        // still owed, or the image parks half off the screen.
        assertThat(out.scale).isWithin(TOLERANCE).of(2f)
        assertThat(out.offset.x).isAtMost(500f + TOLERANCE)
        assertThat(out.offset.y).isAtMost(1000f + TOLERANCE)
    }

    @Test
    fun `double tap toggles between fit and the mid stop, about the tapped point`() {
        val at = Offset(750f, 1500f)

        val zoomedIn = ZoomGeometry.doubleTap(Transform(), bounds, at)

        assertThat(zoomedIn.scale).isWithin(TOLERANCE).of(ZoomGeometry.DOUBLE_TAP_SCALE)
        assertThat(zoomedIn.offset.x).isWithin(TOLERANCE).of(-250f)

        // And back, from wherever the reader had panned to.
        val zoomedOut = ZoomGeometry.doubleTap(zoomedIn.copy(offset = Offset(200f, 90f)), bounds, at)

        assertThat(zoomedOut).isEqualTo(Transform())
    }

    @Test
    fun `a double tap on an already-zoomed image returns to fit even at the maximum`() {
        val out = ZoomGeometry.doubleTap(Transform(scale = ZoomGeometry.MAX_SCALE), bounds, centre)

        assertThat(out.scale).isWithin(TOLERANCE).of(ZoomGeometry.MIN_SCALE)
    }

    /**
     * The single most common way this feature ships broken: at 3× a drag is a pan, and if
     * it is also a dismiss the image runs away from the finger and the viewer closes itself
     * halfway through someone reading a diagram.
     */
    @Test
    fun `a drag dismisses only at fit, never while zoomed`() {
        assertThat(ZoomGeometry.isDismissDrag(scale = 1f, zoom = 1f)).isTrue()
        assertThat(ZoomGeometry.isDismissDrag(scale = 3f, zoom = 1f)).isFalse()
        // A two-finger pinch that happens to start at fit is a zoom, not a dismiss.
        assertThat(ZoomGeometry.isDismissDrag(scale = 1f, zoom = 1.3f)).isFalse()
    }

    @Test
    fun `the dismiss threshold is a fraction of the viewport, downward only`() {
        val far = bounds.viewport.height * ZoomGeometry.DISMISS_FRACTION + 1f

        assertThat(ZoomGeometry.shouldDismiss(Offset(0f, far), bounds.viewport)).isTrue()
        assertThat(ZoomGeometry.shouldDismiss(Offset(0f, far - 2f), bounds.viewport)).isFalse()
        // Dragging up is not a dismissal; it springs back like an under-long drag down.
        assertThat(ZoomGeometry.shouldDismiss(Offset(0f, -far), bounds.viewport)).isFalse()
    }

    @Test
    fun `the scrim fades in proportion to the drag`() {
        val full = bounds.viewport.height * ZoomGeometry.DISMISS_FRACTION

        assertThat(ZoomGeometry.dismissProgress(Offset.Zero, bounds.viewport)).isWithin(TOLERANCE).of(0f)
        assertThat(ZoomGeometry.dismissProgress(Offset(0f, full / 2f), bounds.viewport))
            .isWithin(TOLERANCE).of(0.5f)
        // Past the threshold it saturates rather than inverting the scrim.
        assertThat(ZoomGeometry.dismissProgress(Offset(0f, full * 3f), bounds.viewport))
            .isWithin(TOLERANCE).of(1f)
    }

    @Test
    fun `a viewport that has not been measured yet cannot produce a transform`() {
        // The first frame composes before layout, and a zero viewport divides by zero
        // everywhere below. It must be inert rather than NaN.
        val unmeasured = ZoomBounds(viewport = Size.Zero, contentAspect = null)

        val after = ZoomGeometry.onGesture(Transform(), unmeasured, Offset.Zero, Offset(10f, 10f), zoom = 2f)

        assertThat(after.offset.x.isNaN()).isFalse()
        assertThat(after.offset.y.isNaN()).isFalse()
        assertThat(ZoomGeometry.dismissProgress(Offset(0f, 10f), Size.Zero)).isWithin(TOLERANCE).of(0f)
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
