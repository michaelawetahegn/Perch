package dev.mkiros.perch.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * PLAN-12 §0.4/F09 (#68) — the reader: "hitting the bottom and then dragging up should do
 * the same thing that reloading does on other apps". The arithmetic of that pull is pure,
 * the way `BackChain` keeps back policy pure: the nested-scroll connection only feeds it
 * deltas and asks it on release.
 *
 * Compose's y axis points down, so a drag *up* arrives as a negative unconsumed delta.
 */
class PullUpStateTest {

    private val threshold = 72f

    @Test
    fun `accumulates only upward unconsumed deltas`() {
        val state = PullUpState(threshold).apply { onDragStart() }

        state.onPostScroll(unconsumedY = -30f, atEnd = true)
        state.onPostScroll(unconsumedY = -30f, atEnd = true)
        state.onPostScroll(unconsumedY = 30f, atEnd = true)

        assertThat(state.pulledPx).isEqualTo(60f)
        assertThat(state.isArmed).isFalse()
    }

    @Test
    fun `arms at the threshold and not a pixel before`() {
        val state = PullUpState(threshold).apply { onDragStart() }

        state.onPostScroll(unconsumedY = -71f, atEnd = true)
        assertThat(state.isArmed).isFalse()

        state.onPostScroll(unconsumedY = -1f, atEnd = true)
        assertThat(state.isArmed).isTrue()
    }

    @Test
    fun `fires once on release and is quiet on the next`() {
        val state = PullUpState(threshold).apply { onDragStart() }
        state.onPostScroll(unconsumedY = -100f, atEnd = true)

        assertThat(state.onRelease()).isTrue()
        assertThat(state.onRelease()).isFalse()
        assertThat(state.pulledPx).isEqualTo(0f)
        assertThat(state.isArmed).isFalse()
    }

    @Test
    fun `a short release resets without firing`() {
        val state = PullUpState(threshold).apply { onDragStart() }
        state.onPostScroll(unconsumedY = -30f, atEnd = true)

        assertThat(state.onRelease()).isFalse()
        assertThat(state.pulledPx).isEqualTo(0f)
    }

    @Test
    fun `ignores everything while not at the end`() {
        val state = PullUpState(threshold).apply { onDragStart() }

        state.onPostScroll(unconsumedY = -100f, atEnd = false)
        assertThat(state.pulledPx).isEqualTo(0f)
        assertThat(state.isArmed).isFalse()
        assertThat(state.onRelease()).isFalse()
    }

    /** An accessibility `ScrollBy` or a test's scroll-to-node is user input with no finger to lift. */
    @Test
    fun `a scroll with no finger down is not a pull`() {
        val state = PullUpState(threshold)

        state.onPostScroll(unconsumedY = -100f, atEnd = true)

        assertThat(state.pulledPx).isEqualTo(0f)
        assertThat(state.isArmed).isFalse()
        assertThat(state.onRelease()).isFalse()
    }

    @Test
    fun `leaving the end mid-pull throws the pull away`() {
        val state = PullUpState(threshold).apply { onDragStart() }
        state.onPostScroll(unconsumedY = -50f, atEnd = true)

        state.onPostScroll(unconsumedY = -10f, atEnd = false)

        assertThat(state.pulledPx).isEqualTo(0f)
    }
}
