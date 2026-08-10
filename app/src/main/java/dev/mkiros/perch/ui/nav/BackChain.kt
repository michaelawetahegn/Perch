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

    /**
     * The full-screen image viewer (U12) closes, leaving the article behind it untouched.
     *
     * Below [CloseOverlay] and above [PopArticle]: the viewer is opened *from* an article
     * and drawn over it, so back must peel it off before the article itself goes anywhere.
     */
    CloseImageViewer,

    /** An article returns to the list it was opened from. */
    PopArticle,

    /** To-Read or Liked returns to Feed. Peers do not stack; back means "go home". */
    ReturnToFeed,

    /**
     * Feed, narrowed to one source or one folder, widens back to the unified inbox (V08).
     *
     * Above [ScrollFeedToTop] deliberately. Since V08 the scope is somewhere the reader
     * *goes* — one tap on a source name in an article puts them in that source's list —
     * and one tap in should cost one press out. Scrolling to the top first would make
     * leaving a source they entered with a single tap cost two.
     */
    LeaveScope,

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
 * @param imageViewerOpen a figure is open full screen over the article (U12). Answered by
 *   the viewer's own handler, which is composed deeper and so is reached first; the rung
 *   exists here for the same reason [overlayOpen] does.
 * @param onArticle the article route is on top of the stack. `NavHost` pops it, so this
 *   rung exists to keep the root handler *out of the way* of predictive back.
 * @param tab which of §0's three destinations is showing.
 * @param feedScoped Feed is narrowed to one source or folder rather than showing the
 *   unified inbox (V08). Answered by the shell, which owns the scope for exactly this
 *   reason: a rung the chain cannot see is a rung that is only true by luck.
 * @param feedScrolled Feed's list is not at its first row.
 */
data class BackState(
    val selectionActive: Boolean = false,
    val overlayOpen: Boolean = false,
    val imageViewerOpen: Boolean = false,
    val onArticle: Boolean = false,
    val tab: PerchTab = PerchTab.Feed,
    val feedScoped: Boolean = false,
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
    state.imageViewerOpen -> BackStep.CloseImageViewer
    state.onArticle -> BackStep.PopArticle
    state.tab != PerchTab.Feed -> BackStep.ReturnToFeed
    state.feedScoped -> BackStep.LeaveScope
    state.feedScrolled -> BackStep.ScrollFeedToTop
    else -> BackStep.Exit
}
