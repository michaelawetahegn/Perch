package dev.mkiros.perch.ui.home

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.dp

/**
 * The arithmetic of pulling up past the end of a list (PLAN-12 §0.4, F09, #68).
 *
 * The reader asked for the mirror of pull-to-refresh at the bottom: at the end of All Time,
 * dragging further up past a threshold loads the next batch of the archive, the way dragging
 * down past a threshold refreshes. Compose ships no bottom `PullToRefreshBox`, so this is
 * the pure half of one — kept pure, the way `BackChain` keeps back policy pure, so the rule
 * can be tested without a list, a touch, or a frame.
 *
 * A pull is a *finger*: deltas count only between [onDragStart] and [onRelease], because a
 * scroll with no finger down — an accessibility `ScrollBy`, a test's `performScrollToNode` —
 * reaches the same nested-scroll connection as user input, and a pull that armed on one of
 * those would never be released. Of what the finger did, only what the list did not consume
 * counts, and only while [onPostScroll] is told the list is at its end: a delta consumed by
 * the list was scrolling, not pulling. Compose's y axis points down, so an upward drag
 * arrives negative. Leaving the end mid-pull throws the pull away; a release fires once when
 * the pull has passed [thresholdPx] and resets either way.
 */
@Stable
class PullUpState(private val thresholdPx: Float) {

    /** How far past the end the finger has dragged, in pixels, non-negative. */
    var pulledPx by mutableFloatStateOf(0f)
        private set

    /** Past the threshold: releasing now loads older posts. Drives the footer's label. */
    val isArmed: Boolean get() = pulledPx >= thresholdPx

    private var dragging by mutableStateOf(false)

    /** A finger went down on the list. Only from here to [onRelease] do deltas count. */
    fun onDragStart() {
        dragging = true
        pulledPx = 0f
    }

    fun onPostScroll(unconsumedY: Float, atEnd: Boolean) {
        when {
            !dragging -> Unit
            !atEnd -> pulledPx = 0f
            unconsumedY < 0f -> pulledPx -= unconsumedY
        }
    }

    /** The finger lifted. True exactly when the pull was armed; the pull is reset either way. */
    fun onRelease(): Boolean {
        val fire = dragging && isArmed
        dragging = false
        pulledPx = 0f
        return fire
    }

    companion object {
        /** §0.4: the pull that arms the release. */
        val THRESHOLD = 72.dp
    }
}

/**
 * The thin connection: user-input deltas the list left unconsumed go to [PullUpState]. A
 * fling's own deltas ([NestedScrollSource.SideEffect]) never count — a list flung to its end
 * has not been pulled. Nothing is consumed, so `PullToRefreshBox` above sees exactly what it
 * always did.
 */
fun PullUpState.connection(atEnd: () -> Boolean): NestedScrollConnection =
    object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) onPostScroll(available.y, atEnd())
            return Offset.Zero
        }
    }

/**
 * The finger's side of the pull: the list's own drag interactions (`LazyListState
 * .interactionSource`) open and close it, and a release that was armed calls [onFire].
 */
@Composable
fun PullUpState.ReleaseOn(interactionSource: InteractionSource, onFire: () -> Unit) {
    val fire by rememberUpdatedState(onFire)
    LaunchedEffect(this, interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> onDragStart()
                is DragInteraction.Stop, is DragInteraction.Cancel -> if (onRelease()) fire()
            }
        }
    }
}
