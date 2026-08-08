package dev.mkiros.perch.ui.nav

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * PLAN-2 §0's back policy, rung by rung: **back never quits from anywhere but the top of
 * the Feed.**
 *
 * The policy is an *order*, so these tests are about precedence rather than about any one
 * step — each names a state where two or more rungs are live at once and asserts which one
 * wins. That is the property that actually breaks: every rung in isolation is obvious, and
 * a policy realised as N scattered `BackHandler`s has an order that is an accident of where
 * each one happened to be composed.
 */
class BackChainTest {

    /**
     * U09a's rung. Selection mode only ever exists *inside* the open drawer, so this is the
     * one pair whose order is not merely tidy: get it wrong and the drawer shuts, taking
     * the selection with it, and back has thrown away work rather than undone a step.
     */
    @Test
    fun `back leaves selection before it closes the drawer`() {
        val step = nextBackStep(
            BackState(
                selectionActive = true,
                overlayOpen = true,
                onArticle = true,
                tab = PerchTab.Liked,
                feedScrolled = true,
            ),
        )

        assertThat(step).isEqualTo(BackStep.LeaveSelection)
    }

    @Test
    fun `an open overlay closes before anything else happens`() {
        // Every other rung is also live: on an article, on a non-Feed tab, scrolled down.
        val step = nextBackStep(
            BackState(
                overlayOpen = true,
                onArticle = true,
                tab = PerchTab.Liked,
                feedScrolled = true,
            ),
        )

        assertThat(step).isEqualTo(BackStep.CloseOverlay)
    }

    @Test
    fun `an article pops before the tab changes`() {
        val step = nextBackStep(
            BackState(onArticle = true, tab = PerchTab.ToRead, feedScrolled = true),
        )

        assertThat(step).isEqualTo(BackStep.PopArticle)
    }

    @Test
    fun `To-Read returns to Feed rather than scrolling it`() {
        val step = nextBackStep(BackState(tab = PerchTab.ToRead, feedScrolled = true))

        assertThat(step).isEqualTo(BackStep.ReturnToFeed)
    }

    @Test
    fun `Liked returns to Feed`() {
        assertThat(nextBackStep(BackState(tab = PerchTab.Liked)))
            .isEqualTo(BackStep.ReturnToFeed)
    }

    @Test
    fun `Feed scrolled down scrolls to the top instead of quitting`() {
        val step = nextBackStep(BackState(tab = PerchTab.Feed, feedScrolled = true))

        assertThat(step).isEqualTo(BackStep.ScrollFeedToTop)
    }

    @Test
    fun `only Feed at the top may leave the app`() {
        val step = nextBackStep(BackState(tab = PerchTab.Feed, feedScrolled = false))

        assertThat(step).isEqualTo(BackStep.Exit)
    }

    /**
     * The chain walked end to end. Every state above the last has *something* to do, which
     * is the whole claim: there is exactly one way out of Perch and this is it.
     */
    @Test
    fun `nothing but Feed at the top ever reaches Exit`() {
        val states = listOf(
            BackState(selectionActive = true, overlayOpen = true),
            BackState(overlayOpen = true),
            BackState(onArticle = true),
            BackState(tab = PerchTab.ToRead),
            BackState(tab = PerchTab.Liked),
            BackState(feedScrolled = true),
        )

        assertThat(states.map(::nextBackStep)).doesNotContain(BackStep.Exit)
        assertThat(nextBackStep(BackState())).isEqualTo(BackStep.Exit)
    }

    /** The declared order is the tried order — the enum *is* the policy. */
    @Test
    fun `the steps are declared in the order they are tried`() {
        assertThat(BackStep.entries).containsExactly(
            BackStep.LeaveSelection,
            BackStep.CloseOverlay,
            BackStep.PopArticle,
            BackStep.ReturnToFeed,
            BackStep.ScrollFeedToTop,
            BackStep.Exit,
        ).inOrder()
    }
}
