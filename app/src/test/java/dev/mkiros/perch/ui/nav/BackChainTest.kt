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

    /**
     * U12's rung. The viewer is drawn over the article rather than pushed onto the stack,
     * so a back press that popped the article would take the reader out of the piece they
     * were reading to close a picture — and the picture would still be the last thing they
     * saw of it.
     */
    @Test
    fun `back closes the image viewer before it leaves the article`() {
        val step = nextBackStep(
            BackState(imageViewerOpen = true, onArticle = true, feedScrolled = true),
        )

        assertThat(step).isEqualTo(BackStep.CloseImageViewer)
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

    /**
     * V08's rung. A reader reaches a scoped Feed by tapping a source's name in an article
     * — one tap — so back has to be able to undo it in one press. Below [BackStep.ReturnToFeed]
     * because the scope belongs to the Feed and a reader on Liked is not looking at it.
     */
    @Test
    fun `a scoped Feed widens before it scrolls to the top`() {
        val step = nextBackStep(
            BackState(tab = PerchTab.Feed, feedScoped = true, feedScrolled = true),
        )

        assertThat(step).isEqualTo(BackStep.LeaveScope)
    }

    @Test
    fun `Liked returns to Feed before the Feed's own scope is touched`() {
        val step = nextBackStep(BackState(tab = PerchTab.Liked, feedScoped = true))

        assertThat(step).isEqualTo(BackStep.ReturnToFeed)
    }

    @Test
    fun `Feed scrolled down scrolls to the top instead of quitting`() {
        val step = nextBackStep(BackState(tab = PerchTab.Feed, feedScrolled = true))

        assertThat(step).isEqualTo(BackStep.ScrollFeedToTop)
    }

    /**
     * S10/#28's rung. Search is opened *from* a surface and drawn over it, so back must
     * put the reader back on the list they were looking at — not widen it, not change
     * tabs, and above all not quit. It sits above [BackStep.ReturnToFeed] rather than
     * merely above [BackStep.LeaveScope] because search is reachable from To-Read and
     * Liked as well: were it below, back out of a search on Liked would jump to the Feed
     * and lose the question in the same press.
     */
    @Test
    fun `back leaves search before it returns to the Feed`() {
        val step = nextBackStep(
            BackState(searchOpen = true, tab = PerchTab.Liked, feedScoped = true, feedScrolled = true),
        )

        assertThat(step).isEqualTo(BackStep.LeaveSearch)
    }

    @Test
    fun `back leaves search before it widens a scoped Feed`() {
        val step = nextBackStep(BackState(searchOpen = true, feedScoped = true))

        assertThat(step).isEqualTo(BackStep.LeaveSearch)
    }

    /**
     * The other side of the same order: an article opened *from* the results pops back to
     * the results. Search outlives the article it launched, so a reader who reads one hit
     * and comes back is still looking at their own question.
     */
    @Test
    fun `an article opened from search pops back to the results`() {
        val step = nextBackStep(BackState(searchOpen = true, onArticle = true))

        assertThat(step).isEqualTo(BackStep.PopArticle)
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
            BackState(searchOpen = true),
            BackState(tab = PerchTab.ToRead),
            BackState(tab = PerchTab.Liked),
            BackState(feedScoped = true),
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
            BackStep.CloseImageViewer,
            BackStep.PopArticle,
            BackStep.LeaveSearch,
            BackStep.ReturnToFeed,
            BackStep.LeaveScope,
            BackStep.ScrollFeedToTop,
            BackStep.Exit,
        ).inOrder()
    }
}
