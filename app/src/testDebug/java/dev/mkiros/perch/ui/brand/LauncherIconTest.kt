package dev.mkiros.perch.ui.brand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.screenshot.Screenshots
import dev.mkiros.perch.ui.theme.PerchMarkPaths
import java.io.File
import kotlin.math.hypot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The launcher icon (U09b), checked where launcher icons actually go wrong.
 *
 * An adaptive icon's layers are 108dp but a launcher shows only what its own mask keeps,
 * and the masks differ per device — circle, squircle, rounded square. The single number
 * that survives all of them is the **centre 66dp circle**; a mark drawn to the canvas
 * edge looks fine in Studio's preview and loses its corners on a real phone. So this
 * measures the mark's ink against that circle rather than trusting the eye, and renders
 * the three masks for the eye anyway.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 4px per viewport unit — enough that a 2-unit stroke is 8px and measurable. */
    private val scale = 4
    private val canvasPx = (PerchMarkPaths.VIEWPORT * scale).toInt()

    @Test
    fun `the launcher icon is adaptive and ships all three layers`() {
        val icon = launcherIcon()

        assertThat(icon.background).isNotNull()
        assertThat(icon.foreground).isNotNull()
        // Without a monochrome layer Android 13's themed icons fall back to a washed-out
        // auto-generated one, which is the version most people would actually see.
        assertThat(monochrome()).isNotNull()
    }

    @Test
    fun `the mark's ink stays inside the circle every launcher mask contains`() {
        val ink = inkExtent(render(launcherIcon().foreground))

        assertThat(ink.maxRadius).isLessThan(PerchMarkPaths.SAFE_RADIUS)
        // And it is not passing by being tiny: a mark that fills a third of the safe
        // circle satisfies "nothing clipped" and still reads as a speck on a home screen.
        assertThat(ink.maxRadius).isGreaterThan(PerchMarkPaths.SAFE_RADIUS * 0.8f)
    }

    @Test
    fun `the mark is optically centred on the icon canvas`() {
        val ink = inkExtent(render(launcherIcon().foreground))

        // The back sheet hangs off the right, so a mark centred on the *front page* would
        // sit visibly left of centre once masked. Centring is measured on the ink.
        assertThat(ink.centreX).isWithin(1.5f).of(PerchMarkPaths.CENTRE)
        assertThat(ink.centreY).isWithin(1.5f).of(PerchMarkPaths.CENTRE)
    }

    @Test
    fun `the monochrome layer leaves the sheets hollow`() {
        val mono = render(monochrome())
        val colour = render(launcherIcon().foreground)

        // A point inside the front page and clear of every rule. Filled in, the themed
        // icon tints to a featureless slab; hollow, it stays a legible outline.
        val x = (67f * scale).toInt()
        val y = (54f * scale).toInt()
        assertThat(Color.alpha(mono.getPixel(x, y))).isEqualTo(0)
        assertThat(Color.alpha(colour.getPixel(x, y))).isEqualTo(255)

        // The same layer still has to carry the mark, not just a rectangle outline.
        assertThat(inkExtent(mono).maxRadius).isGreaterThan(PerchMarkPaths.SAFE_RADIUS * 0.8f)
    }

    @Test
    fun `the P's bowl is drawn round a counter, not filled`() {
        val counter = counter(colour(), ::light)

        // V11/3: the counter was 3.5 × 4.6 units in a 108-unit canvas — barely two pixels
        // across once a 48dp icon and its mask have taken their share of it, which is
        // less than antialiasing leaves open, so the P read as a blob. These floors are
        // what a counter has to measure here to be a counter anywhere.
        assertThat(counter.width).isAtLeast(5f)
        assertThat(counter.height).isAtLeast(7f)
    }

    @Test
    fun `the themed layer keeps the whole of that counter`() {
        val counter = counter(mono(), ::opaque)

        // The themed layer is the one the counter was reported against, and it is the one
        // that can lose it: the sheets are hollow there, so anything drawn behind the
        // front page shows through the hole in the P. It has to measure the same as the
        // colour layer's — if it does not, something is being drawn across the page that
        // the page is supposed to be in front of.
        assertThat(counter.width).isAtLeast(5f)
        assertThat(counter.height).isAtLeast(7f)
    }

    @Test
    fun `the counter is still open where the icon is smallest`() {
        val small = themed(SMALL_ICON_PX)
        val file = File(Screenshots.dir("build/perch-screenshots"), "launcher-monochrome-48dp.png")
        file.outputStream().use { small.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // The same claim where a reader meets it. 48px is an adaptive icon at 48dp on an
        // mdpi screen — the smallest it is ever drawn. The counter's middle is measured
        // from the full-size render rather than written down here, so it follows the
        // glyph if the glyph is ever redrawn.
        val counter = counter(mono(), ::opaque)
        val alpha = Color.alpha(
            small.bitmap.getPixel(small.px(counter.centreX), small.px(counter.centreY)),
        )
        assertThat(alpha).isLessThan(OPAQUE_ENOUGH)
    }

    @Test
    fun `the launcher drawables draw the same paths as the mark`() {
        val expected = setOf(
            PerchMarkPaths.BACK_SHEET,
            PerchMarkPaths.FRONT_PAGE,
            PerchMarkPaths.P_GLYPH,
            PerchMarkPaths.TOP_RULES,
            PerchMarkPaths.FULL_RULE,
            PerchMarkPaths.ACCENT_BLOCK,
            PerchMarkPaths.BODY_RULES,
        ).map(::normalise).toSet()

        // The two XML layers restate Brand.kt's path data because a VectorDrawable cannot
        // read a Kotlin constant. This is what stops the three copies drifting apart.
        listOf("ic_launcher_foreground", "ic_launcher_monochrome").forEach { name ->
            assertThat(pathDataIn(name)).containsExactlyElementsIn(expected)
        }
    }

    @Test
    fun `the mark survives the circle squircle and rounded-square masks`() {
        val icon = launcherIcon()
        val dir = Screenshots.dir("build/perch-screenshots")

        masks().forEach { (name, mask) ->
            val shot = maskedIcon(icon, mask)
            val file = File(dir, "launcher-$name.png")
            file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }

            assertThat(file.length()).isGreaterThan(0L)
            // Every mask must leave the amber block visible — it is the one thing that
            // stops the icon reading as a grey document at 48dp.
            assertThat(shot.containsColourNear(ACCENT, tolerance = 24)).isTrue()
        }

        // And the themed variant, on a plate, so it can be judged the same way.
        val monoFile = File(dir, "launcher-monochrome.png")
        monoFile.outputStream().use { platedMonochrome().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertThat(monoFile.length()).isGreaterThan(0L)
    }

    // --- rendering -------------------------------------------------------------------

    private fun launcherIcon(): AdaptiveIconDrawable =
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher) as AdaptiveIconDrawable

    private fun monochrome(): Drawable =
        checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_launcher_monochrome))

    /** One 108-unit layer, alone, on transparency — the raw coordinate space. */
    private fun render(layer: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(canvasPx, canvasPx, Bitmap.Config.ARGB_8888)
        layer.setBounds(0, 0, canvasPx, canvasPx)
        layer.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * The whole icon as a launcher composites it: the layers overhang the mask by
     * [AdaptiveIconDrawable.getExtraInsetFraction] on every side, so a mask of side `S`
     * shows the centre 72 of the 108 units.
     */
    private fun maskedIcon(icon: AdaptiveIconDrawable, mask: (Float) -> Path): Bitmap {
        val side = 288
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.clipPath(mask(side.toFloat()))

        val inset = AdaptiveIconDrawable.getExtraInsetFraction()
        val layerSide = (side * (1f + 2f * inset)).toInt()
        val offset = -((layerSide - side) / 2)
        listOf(icon.background, icon.foreground).forEach { layer ->
            layer?.setBounds(offset, offset, offset + layerSide, offset + layerSide)
            layer?.draw(canvas)
        }
        return bitmap
    }

    /** The themed layer over a flat plate, since alone it is ink on transparency. */
    private fun platedMonochrome(): Bitmap {
        val side = 288
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPath(circle(side.toFloat()), Paint().apply { color = PLATE })
        canvas.clipPath(circle(side.toFloat()))
        canvas.drawBitmap(themed(side).bitmap, 0f, 0f, null)
        return bitmap
    }

    /**
     * A rendered layer and the map from viewport units back into its pixels, so a
     * measurement taken at one size can be checked at another.
     */
    private data class Frame(val bitmap: Bitmap, val scale: Float, val origin: Float) {
        fun px(unit: Float): Int = (unit * scale + origin).toInt()
    }

    /** The themed layer alone, at its raw 108-unit scale. */
    private fun mono(): Frame = Frame(render(monochrome()), scale.toFloat(), 0f)

    /** The colour foreground, where the front page's fill hides the sheet behind it. */
    private fun colour(): Frame = Frame(render(launcherIcon().foreground), scale.toFloat(), 0f)

    /**
     * The themed layer as a launcher of side [side] composites it — the layer drawn at
     * `side * 1.5` and centred, which is where the extra inset goes.
     */
    private fun themed(side: Int): Frame {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val inset = AdaptiveIconDrawable.getExtraInsetFraction()
        val layerSide = side * (1f + 2f * inset)
        val offset = -(layerSide - side) / 2f
        monochrome().apply {
            setBounds(
                offset.toInt(),
                offset.toInt(),
                (offset + layerSide).toInt(),
                (offset + layerSide).toInt(),
            )
            draw(Canvas(bitmap))
        }
        return Frame(bitmap, layerSide / PerchMarkPaths.VIEWPORT, offset)
    }

    // --- measuring -------------------------------------------------------------------

    /** The bowl's hole, in viewport units. */
    private data class Counter(
        val width: Float,
        val height: Float,
        val centreX: Float,
        val centreY: Float,
    )

    /** Ink on the themed layer, which is one colour on transparency. */
    private fun opaque(pixel: Int): Boolean = Color.alpha(pixel) >= OPAQUE_ENOUGH

    /**
     * Ink on the colour layer, where the counter is not a hole in the bitmap but the
     * front page's paper showing through one — so what encloses it is darkness, not
     * transparency.
     */
    private fun light(pixel: Int): Boolean =
        Color.alpha(pixel) >= OPAQUE_ENOUGH &&
            (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3 < MID_TONE

    /**
     * The counter, found rather than assumed: every region the [ink] encloses is
     * collected, and the one that lies inside the P's quarter of the canvas is it. A
     * filled-in bowl has no such region and the search fails loudly, which is the point.
     *
     * The sheets enclose regions too — hollow on the themed layer, paper-filled on the
     * colour one — and they are excluded by being far larger than the box the P lives in,
     * not by any assumption about where the counter sits inside it.
     */
    private fun counter(frame: Frame, ink: (Int) -> Boolean): Counter {
        val bitmap = frame.bitmap
        val open = Array(bitmap.width) { x ->
            BooleanArray(bitmap.height) { y -> !ink(bitmap.getPixel(x, y)) }
        }

        // Whatever transparency the outside can reach is not a hole in anything.
        val queue = ArrayDeque<Pair<Int, Int>>()
        fun reach(x: Int, y: Int) {
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return
            if (!open[x][y]) return
            open[x][y] = false
            queue += x to y
        }
        for (x in 0 until bitmap.width) { reach(x, 0); reach(x, bitmap.height - 1) }
        for (y in 0 until bitmap.height) { reach(0, y); reach(bitmap.width - 1, y) }
        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            reach(x - 1, y); reach(x + 1, y); reach(x, y - 1); reach(x, y + 1)
        }

        val left = frame.px(P_LEFT)
        val top = frame.px(P_TOP)
        val right = frame.px(P_RIGHT)
        val bottom = frame.px(P_BOTTOM)
        for (y in top..bottom) {
            for (x in left..right) {
                if (!open[x][y]) continue
                val region = mutableListOf<Pair<Int, Int>>()
                queue += x to y
                open[x][y] = false
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst()
                    region += at
                    reach(at.first - 1, at.second); reach(at.first + 1, at.second)
                    reach(at.first, at.second - 1); reach(at.first, at.second + 1)
                }
                val inside = region.all { (rx, ry) -> rx in left..right && ry in top..bottom }
                if (!inside) continue
                val xs = region.map { it.first }
                val ys = region.map { it.second }
                return Counter(
                    width = (xs.max() - xs.min() + 1) / frame.scale,
                    height = (ys.max() - ys.min() + 1) / frame.scale,
                    centreX = ((xs.max() + xs.min() + 1) / 2f - frame.origin) / frame.scale,
                    centreY = ((ys.max() + ys.min() + 1) / 2f - frame.origin) / frame.scale,
                )
            }
        }
        error("the P has no counter: its bowl is filled in")
    }


    /** Where a layer's ink actually is, in viewport units. */
    private data class Ink(
        val maxRadius: Float,
        val centreX: Float,
        val centreY: Float,
    )

    private fun inkExtent(bitmap: Bitmap): Ink {
        var maxRadius = 0f
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) < OPAQUE_ENOUGH) continue
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                val centre = canvasPx / 2f
                maxRadius = maxOf(maxRadius, hypot(x + 0.5f - centre, y + 0.5f - centre))
            }
        }
        check(minX != Int.MAX_VALUE) { "the layer drew nothing" }
        return Ink(
            maxRadius = maxRadius / scale,
            centreX = (minX + maxX + 1) / 2f / scale,
            centreY = (minY + maxY + 1) / 2f / scale,
        )
    }

    private fun Bitmap.containsColourNear(target: Int, tolerance: Int): Boolean {
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val pixel = getPixel(x, y)
                if (Color.alpha(pixel) < 255) continue
                val near = listOf(
                    Color.red(pixel) - Color.red(target),
                    Color.green(pixel) - Color.green(target),
                    Color.blue(pixel) - Color.blue(target),
                ).all { kotlin.math.abs(it) <= tolerance }
                if (near) return true
            }
        }
        return false
    }

    // --- masks -----------------------------------------------------------------------

    private fun masks(): List<Pair<String, (Float) -> Path>> = listOf(
        "circle" to ::circle,
        "squircle" to { side: Float -> rounded(side, 0.44f) },
        "rounded-square" to { side: Float -> rounded(side, 0.20f) },
    )

    private fun circle(side: Float): Path =
        Path().apply { addCircle(side / 2f, side / 2f, side / 2f, Path.Direction.CW) }

    private fun rounded(side: Float, radiusFraction: Float): Path =
        Path().apply {
            addRoundRect(
                RectF(0f, 0f, side, side),
                side * radiusFraction,
                side * radiusFraction,
                Path.Direction.CW,
            )
        }

    // --- path-data sync --------------------------------------------------------------

    private fun pathDataIn(drawable: String): Set<String> {
        val xml = File(Screenshots.repoRoot(), "app/src/main/res/drawable/$drawable.xml").readText()
        return PATH_DATA.findAll(xml).map { normalise(it.groupValues[1]) }.toSet()
    }

    private fun normalise(pathData: String): String = pathData.trim().replace(WHITESPACE, " ")

    private companion object {
        val PATH_DATA = Regex("""android:pathData="([^"]*)"""")
        val WHITESPACE = Regex("""\s+""")

        /** Alpha below this is antialiasing spill, not ink. */
        const val OPAQUE_ENOUGH = 128

        /** Ink and paper are at the two ends of the scale; nothing sits near this. */
        const val MID_TONE = 128

        /** An adaptive icon at 48dp on an mdpi screen: the smallest it is ever drawn. */
        const val SMALL_ICON_PX = 48

        /**
         * The quarter of the canvas the P occupies, generously — it bounds the search for
         * the counter and nothing else, so it may be looser than the glyph.
         */
        const val P_LEFT = 33f
        const val P_TOP = 34f
        const val P_RIGHT = 51f
        const val P_BOTTOM = 56f

        /** `PerchBrand.accent`. */
        val ACCENT = Color.rgb(0xC7, 0x8E, 0x35)

        /** A neutral plate to judge the themed layer against. */
        val PLATE = Color.rgb(0xD4, 0xE7, 0xDE)
    }
}
