package dev.mkiros.perch.ui.nav

/**
 * The one thing back can do next (PLAN-2 §0, U09).
 *
 * Named steps rather than booleans because the policy is an *order*, and an order is the
 * thing that gets broken: every `BackHandler` scattered through a screen is another claim
 * on the gesture whose priority relative to the others is an accident of where it was
 * composed. This enum is the priority, declared once, in the order the steps are tried.
 */
enum class BackStep {

    /**
     * The drawer's selection mode (U09a) empties, leaving the drawer open.
     *
     * Above [CloseOverlay] deliberately: selection only exists inside the open drawer, so
     * if the drawer went first, back would throw away a batch the reader had assembled
     * rather than undo one step of it.
     */
    LeaveSelection,

    /** An open drawer, sheet or dialog. It goes next — it is on top of everything else. */
    CloseOverlay,

    /** An article returns to the list it was opened from. */
    PopArticle,

    /** To-Read or Liked returns to Feed. Peers do not stack; back means "go home". */
    ReturnToFeed,

    /**
     * Feed, scrolled down, scrolls to the top.
     *
     * This is the Reddit rule's whole point, and it is deliberately **not** a navigation:
     * nothing is popped, so it must not animate as one and predictive back must not
     * preview a screen behind it.
     */
    ScrollFeedToTop,

    /** Feed, already at the top. The only step that may leave the app. */
    Exit,
}

/**
 * Everything [nextBackStep] needs, gathered from wherever it actually lives.
 *
 * @param selectionActive the drawer is in U09a's multi-select mode. Like [overlayOpen]
 *   this rung is answered where it lives — by a handler inside the drawer sheet, which is
 *   composed deeper than the drawer's own and so is reached first — and is modelled here
 *   so the root handler stays out of its way rather than racing it.
 * @param overlayOpen the drawer, the add-source sheet, or any dialog. Those components
 *   each answer back themselves and, being composed deeper, win the dispatcher before the
 *   root handler is reached — this rung is what keeps the policy true anyway if one of
 *   them ever stops doing so.
 * @param onArticle the article route is on top of the stack. `NavHost` pops it, so this
 *   rung exists to keep the root handler *out of the way* of predictive back.
 * @param tab which of §0's three destinations is showing.
 * @param feedScrolled Feed's list is not at its first row.
 */
data class BackState(
    val selectionActive: Boolean = false,
    val overlayOpen: Boolean = false,
    val onArticle: Boolean = false,
    val tab: PerchTab = PerchTab.Feed,
    val feedScrolled: Boolean = false,
)

/**
 * §0's back policy, as one ordered chain rather than as N handlers that happen to agree.
 *
 * "Back never quits from anywhere but the top of the Feed." Every step above [BackStep.Exit]
 * is a thing to do *instead* of quitting, and they are tried in this order.
 */
fun nextBackStep(state: BackState): BackStep = when {
    state.selectionActive -> BackStep.LeaveSelection
    state.overlayOpen -> BackStep.CloseOverlay
    state.onArticle -> BackStep.PopArticle
    state.tab != PerchTab.Feed -> BackStep.ReturnToFeed
    state.feedScrolled -> BackStep.ScrollFeedToTop
    else -> BackStep.Exit
}
