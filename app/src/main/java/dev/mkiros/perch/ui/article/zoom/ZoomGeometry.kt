package dev.mkiros.perch.ui.article.zoom

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Where the image sits right now: a scale about the viewport's centre and a translation.
 *
 * Deliberately the same shape `graphicsLayer` takes, and deliberately a value — every
 * gesture is a pure `Transform -> Transform`, which is what lets [ZoomGeometry] be tested
 * without a composition, a clock or a finger.
 */
@Immutable
data class Transform(val scale: Float = ZoomGeometry.MIN_SCALE, val offset: Offset = Offset.Zero)

/**
 * How much room the gesture has to move in: the viewport, and the image drawn to fit it.
 *
 * [contentAspect] is width ÷ height of the *image*, which is not the viewport's — a tall
 * photo on a phone is letterboxed left and right, and the strip of scrim beside it is not
 * pannable space. Null until the bytes have arrived, in which case the image is assumed to
 * fill the viewport; that errs towards allowing a pan that turns out to be unnecessary
 * rather than towards locking a pan the reader needs.
 */
@Immutable
class ZoomBounds(val viewport: Size, val contentAspect: Float?) {

    /** The image's size at scale 1 — `ContentScale.Fit` inside [viewport]. */
    val content: Size = when {
        viewport.isEmpty() || contentAspect == null || contentAspect <= 0f -> viewport
        contentAspect > viewport.width / viewport.height ->
            Size(viewport.width, viewport.width / contentAspect)
        else -> Size(viewport.height * contentAspect, viewport.height)
    }

    /** Half the overhang at [scale]: how far the image may travel before an edge shows. */
    fun slack(scale: Float): Offset = Offset(
        x = max(0f, (content.width * scale - viewport.width) / 2f),
        y = max(0f, (content.height * scale - viewport.height) / 2f),
    )

    /** [offset], cut down to what the image at [scale] can actually afford to move. */
    fun clamp(offset: Offset, scale: Float): Offset {
        val slack = slack(scale)
        return Offset(
            x = offset.x.coerceIn(-slack.x, slack.x),
            y = offset.y.coerceIn(-slack.y, slack.y),
        )
    }
}

/**
 * U12's whole behaviour, as arithmetic.
 *
 * The reference implementation this follows is `saket/telephoto`, whose lesson is that the
 * gestures are easy and the *edges* are the feature: a zoom with no ceiling, a pan with no
 * fence, and a dismiss that fires while zoomed are the three ways a viewer becomes unusable
 * and none of them show up in a happy-path demo.
 */
object ZoomGeometry {

    /** Fit. The image never draws smaller than the viewport allows. */
    const val MIN_SCALE = 1f

    /** Past ~5× a photo is mush; the ceiling is a stop, not a punishment (see below). */
    const val MAX_SCALE = 5f

    /** Where a double tap lands: enough to read a diagram's labels, still recognisably the same picture. */
    const val DOUBLE_TAP_SCALE = 2f

    /** The hard stop for a rubber-banded overshoot, as a multiple of [MAX_SCALE]. */
    const val OVERSHOOT_LIMIT = 1.4f

    /** How far down the viewport a drag must travel before letting go dismisses. */
    const val DISMISS_FRACTION = 0.18f

    /**
     * One frame of a pinch or a pan.
     *
     * The pinched point stays under the fingers: at a new scale the point `p` (measured
     * from the viewport's centre) would drift to `p·s′/s`, so the offset takes exactly that
     * drift back out before [pan] is added. Getting this wrong is the difference between
     * zooming into what you are looking at and zooming into the middle of the screen.
     */
    fun onGesture(
        current: Transform,
        bounds: ZoomBounds,
        centroid: Offset,
        pan: Offset,
        zoom: Float,
    ): Transform {
        if (bounds.viewport.isEmpty() || current.scale <= 0f) return current

        val scale = rubberBand(current.scale * zoom)
        val focus = centroid - bounds.viewport.center
        val held = focus - (focus - current.offset) * (scale / current.scale)
        return Transform(scale, bounds.clamp(held + pan, scale))
    }

    /**
     * Fit ⇄ [DOUBLE_TAP_SCALE], about the tapped point.
     *
     * Zoomed *at all* toggles out, not only zoomed to exactly the mid stop — after a pinch
     * the scale is some arbitrary 3.4×, and a double tap there must mean "show me the whole
     * thing again" rather than "go to 2×".
     */
    fun doubleTap(current: Transform, bounds: ZoomBounds, at: Offset): Transform =
        if (isZoomed(current.scale)) {
            Transform()
        } else {
            onGesture(current, bounds, at, Offset.Zero, zoom = DOUBLE_TAP_SCALE / current.scale)
        }

    /** Where the image belongs once the fingers lift: inside the stops, inside the fence. */
    fun settle(current: Transform, bounds: ZoomBounds): Transform {
        val scale = current.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        return Transform(scale, bounds.clamp(current.offset, scale))
    }

    /**
     * Is this drag a dismissal or a pan?
     *
     * Only at fit, and only while nothing is being pinched. A zoomed image owes every drag
     * to panning — the alternative is a viewer that closes itself while someone is reading
     * the bottom-left corner of a schematic.
     */
    fun isDismissDrag(scale: Float, zoom: Float): Boolean =
        !isZoomed(scale) && abs(zoom - 1f) < ZOOM_EPSILON

    /** Down past the threshold and let go. Up springs back: this gesture has a direction. */
    fun shouldDismiss(dismissOffset: Offset, viewport: Size): Boolean =
        dismissOffset.y > viewport.height * DISMISS_FRACTION

    /** 0 at rest, 1 at the threshold — what the scrim's alpha is drawn from. */
    fun dismissProgress(dismissOffset: Offset, viewport: Size): Float {
        val threshold = viewport.height * DISMISS_FRACTION
        if (threshold <= 0f) return 0f
        return (dismissOffset.y / threshold).coerceIn(0f, 1f)
    }

    fun isZoomed(scale: Float): Boolean = scale > MIN_SCALE + SCALE_EPSILON

    /**
     * Past a stop the fingers stop being obeyed literally and start being resisted.
     *
     * Logarithmic rather than linear so the resistance grows with the overreach and the
     * scale is bounded however hard the pinch: a fixed fraction of an unbounded overshoot
     * is still unbounded. Below [MIN_SCALE] the same shape applies mirrored, so pinching a
     * fitted image in bows it slightly and then springs back rather than doing nothing at
     * all — the tiny give is what tells the reader the gesture was received.
     */
    private fun rubberBand(raw: Float): Float = when {
        raw > MAX_SCALE -> {
            val ceiling = MAX_SCALE * OVERSHOOT_LIMIT
            min(ceiling, MAX_SCALE + (ceiling - MAX_SCALE) * damped(raw / MAX_SCALE))
        }
        raw < MIN_SCALE -> {
            val floor = MIN_SCALE / OVERSHOOT_LIMIT
            max(floor, MIN_SCALE - (MIN_SCALE - floor) * damped(MIN_SCALE / max(raw, 0.001f)))
        }
        else -> raw
    }

    /** An overreach of `x`× the stop, mapped into 0..1 and flattening as it grows. */
    private fun damped(overreach: Float): Float = (ln(overreach) / DAMPING).coerceIn(0f, 1f)

    /** `ln` of this much overreach saturates the band: eight times the stop is as far as it bends. */
    private const val DAMPING = 2.08f

    /** A pinch's zoom is never exactly 1, and a pan's is never meaningfully off it. */
    private const val ZOOM_EPSILON = 0.005f

    private const val SCALE_EPSILON = 0.001f
}
