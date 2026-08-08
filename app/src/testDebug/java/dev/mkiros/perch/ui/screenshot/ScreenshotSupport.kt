package dev.mkiros.perch.ui.screenshot

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.io.File

/**
 * The JVM screenshot path T29 established, shared with T32's live acceptance gate.
 *
 * It draws the view tree onto a bitmap itself rather than calling `captureToImage()`:
 * that goes through `PixelCopy` and first blocks on a frame-commit callback, which a
 * Robolectric window never delivers — the capture times out after 2s having drawn
 * nothing. With `@GraphicsMode(NATIVE)` a plain `View.draw(Canvas)` rasterizes the same
 * real pixels, and it is synchronous.
 */
object Screenshots {

    /** A capture, and the one number that says whether it is worth looking at. */
    data class Shot(val file: File, val distinctColours: Int)

    /** Writes the whole screen — every window on it — to `<dir>/<name>.png`. */
    fun capture(compose: ComposeTestRule, activity: Activity, dir: File, name: String): Shot {
        compose.waitForIdle()
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windowsFrontToBack(compose, decor).forEach { window ->
            // Each window is drawn where it actually sits. A sheet or a dialog fills the
            // screen and lands at the origin either way, but a dropdown menu is a small
            // window anchored under its control — painted at the origin it would appear
            // in the top-left corner and the shot would misrepresent the screen.
            val at = IntArray(2).also(window::getLocationOnScreen)
            val save = canvas.save()
            canvas.translate(at[0].toFloat(), at[1].toFloat())
            window.draw(canvas)
            canvas.restoreToCount(save)
        }

        dir.mkdirs()
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Shot(file, distinctColours(bitmap))
    }

    /** [relative] resolved against the repository root, wherever the working directory is. */
    fun dir(relative: String): File = File(repoRoot(), relative).apply { mkdirs() }

    /**
     * Every window on screen, activity first. A sheet or a dialog is a window of its own,
     * so painting only the activity's decor view would screenshot the list with the
     * add-source sheet missing; each extra Compose root is drawn over the one below it,
     * scrim included, which is what the reader sees.
     */
    private fun windowsFrontToBack(compose: ComposeTestRule, decor: View): List<View> {
        val others = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            .map { (it.root as ViewRootForTest).view.rootView }
            .filter { it !== decor }
            .distinct()
        return listOf(decor) + others
    }

    /** How many distinct colours a coarse sample of the bitmap contains. */
    private fun distinctColours(bitmap: Bitmap): Int {
        val seen = mutableSetOf<Int>()
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                seen += bitmap.getPixel(x, y)
                x += SAMPLE_STRIDE
            }
            y += SAMPLE_STRIDE
        }
        return seen.size
    }

    fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "PLAN.md").isFile) return dir
            dir = dir.parentFile
        }
        error("PLAN.md not found above ${File("").absolutePath}")
    }

    /** Every Nth pixel in both axes — enough to tell a rendered screen from a slab. */
    private const val SAMPLE_STRIDE = 7
}

/**
 * Waits in *wall-clock* time. Compose's own `waitUntil` only advances the **virtual**
 * clock, so its timeout can expire without Room's query executor — a real background
 * thread here — ever being scheduled.
 */
fun ComposeTestRule.awaitInRealTime(
    what: String,
    timeoutMs: Long = 20_000L,
    predicate: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        waitForIdle()
        if (predicate()) {
            waitForIdle()
            return
        }
        Thread.sleep(10L)
    }
    throw AssertionError("timed out waiting for $what")
}
