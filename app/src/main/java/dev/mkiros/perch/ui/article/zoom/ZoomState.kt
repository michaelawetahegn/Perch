package dev.mkiros.perch.ui.article.zoom

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * What the viewer is showing right now, and the four things a finger can do to it.
 *
 * A holder rather than a pile of `remember`s in [ImageViewer] because the gesture tests
 * read it: "a drag at 3× pans and does not dismiss" is a statement about scale and offset,
 * and asserting it against pixels would be asserting it against the rasteriser. All the
 * arithmetic is [ZoomGeometry]'s; this class owns only the *state*, the animations, and the
 * one decision the geometry cannot make — whether a pan is a pan or a dismissal.
 */
@Stable
class ZoomState {

    /** Scale and pan of the image itself. */
    var transform: Transform by mutableStateOf(Transform())
        private set

    /** How far a drag-to-dismiss has carried the image. Zero unless a dismissal is in progress. */
    var dismissOffset: Offset by mutableStateOf(Offset.Zero)
        private set

    private var viewport: Size by mutableStateOf(Size.Zero)
    private var contentAspect: Float? by mutableStateOf(null)

    val scale: Float get() = transform.scale
    val offset: Offset get() = transform.offset
    val zoomed: Boolean get() = ZoomGeometry.isZoomed(scale)

    /** 0 at rest, 1 at the point of dismissal — the scrim's alpha is drawn from this. */
    val dismissProgress: Float get() = ZoomGeometry.dismissProgress(dismissOffset, viewport)

    private val bounds: ZoomBounds get() = ZoomBounds(viewport, contentAspect)

    /** The viewer's size, from layout. A rotation re-clamps whatever pan was in hand. */
    fun measure(size: Size) {
        if (size == viewport) return
        viewport = size
        transform = ZoomGeometry.settle(transform, bounds)
    }

    /**
     * The image's own shape, once Coil knows it.
     *
     * Until then the image is assumed to fill the viewport, which over-estimates how far a
     * tall photo may be panned. Re-clamping here is what corrects that the moment the real
     * ratio lands, rather than leaving the first gesture working off the guess.
     */
    fun onContentAspect(aspect: Float?) {
        if (aspect == contentAspect) return
        contentAspect = aspect
        transform = ZoomGeometry.settle(transform, bounds)
    }

    /**
     * One frame of a pinch, a pan, or a drag.
     *
     * The fork is the whole of U12's hardest rule: at fit, a one-finger drag is a dismissal
     * gesture and moves the image bodily; zoomed, the identical drag is a pan and the viewer
     * must stay open. [ZoomGeometry.isDismissDrag] is the only place that is decided.
     */
    fun onGesture(centroid: Offset, pan: Offset, zoom: Float) {
        if (ZoomGeometry.isDismissDrag(scale, zoom)) {
            dismissOffset += pan
        } else {
            transform = ZoomGeometry.onGesture(transform, bounds, centroid, pan, zoom)
        }
    }

    /** Fit ⇄ mid stop, animated, about the tapped point. */
    suspend fun onDoubleTap(at: Offset) {
        animateTransform(ZoomGeometry.doubleTap(transform, bounds, at))
    }

    /** Whether letting go here closes the viewer. Asked once, when the last finger lifts. */
    fun shouldDismiss(): Boolean = ZoomGeometry.shouldDismiss(dismissOffset, viewport)

    /**
     * The fingers have lifted and nothing is being dismissed: spring back.
     *
     * Both halves run together — an overshot pinch returning to 5× while a half-hearted
     * drag-down slides back to zero is one movement, not two.
     */
    suspend fun settle(): Unit = coroutineScope {
        launch { animateTransform(ZoomGeometry.settle(transform, bounds)) }
        launch { animateDismiss() }
    }

    private suspend fun animateTransform(target: Transform) {
        val from = transform
        if (from == target) return
        animate(0f, 1f, animationSpec = tween(SETTLE_MS)) { fraction, _ ->
            transform = Transform(
                scale = lerp(from.scale, target.scale, fraction),
                offset = lerp(from.offset, target.offset, fraction),
            )
        }
    }

    private suspend fun animateDismiss() {
        val from = dismissOffset
        if (from == Offset.Zero) return
        animate(0f, 1f, animationSpec = tween(SETTLE_MS)) { fraction, _ ->
            dismissOffset = lerp(from, Offset.Zero, fraction)
        }
    }

    private companion object {
        /** Short enough to feel like the image springing back rather than being animated. */
        const val SETTLE_MS = 220
    }
}
