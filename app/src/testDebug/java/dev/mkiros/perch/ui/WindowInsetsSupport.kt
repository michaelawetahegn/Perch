package dev.mkiros.perch.ui

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Gives the composition under test the insets a real phone would have.
 *
 * **Robolectric's device profiles have no bars and no cutout** — every qualifier renders a
 * window whose `safeDrawing` is zero on all four sides. So a test that only renders the
 * default profile cannot tell a screen that handles insets from one that ignores them
 * entirely: both look identical, and V04's whole subject is invisible.
 *
 * The insets are dispatched to each Compose root directly rather than to the decor view.
 * Compose reads them through an `OnApplyWindowInsetsListener` that `WindowInsetsHolder`
 * installs on the `AndroidComposeView` itself; the decor view's own `onApplyWindowInsets`
 * is free to consume what it likes on the way down, and a test that has to reason about
 * that is testing the framework. A sheet, a dialog or an overlay in its own window is its
 * own root — hence *every* root, not the first.
 */
fun ComposeTestRule.applyWindowInsets(
    statusBarPx: Int = STATUS_BAR_PX,
    navigationBarPx: Int = NAVIGATION_BAR_PX,
    cutoutPx: Int = 0,
) {
    val insets = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
        .setInsets(
            WindowInsetsCompat.Type.navigationBars(),
            Insets.of(0, 0, 0, navigationBarPx),
        )
        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, cutoutPx, 0, 0))
        .build()

    onAllNodes(isRoot()).fetchSemanticsNodes()
        .map { (it.root as ViewRootForTest).view }
        .distinct()
        .forEach { ViewCompat.dispatchApplyWindowInsets(it, insets) }
    waitForIdle()
}

/**
 * A tall status bar at xhdpi — 96px / 24dp is the ordinary one; this is deliberately
 * larger so an assertion cannot pass on a rounding accident.
 */
const val STATUS_BAR_PX = 96

/** A three-button navigation bar at xhdpi: 24dp. */
const val NAVIGATION_BAR_PX = 48

/** A punch-hole cutout that reaches further down than the status bar it sits in. */
const val CUTOUT_PX = 132
