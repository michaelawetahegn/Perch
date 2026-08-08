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
        val inset = AdaptiveIconDrawable.getExtraInsetFraction()
        val layerSide = (side * (1f + 2f * inset)).toInt()
        val offset = -((layerSide - side) / 2)
        monochrome().apply {
            setBounds(offset, offset, offset + layerSide, offset + layerSide)
            draw(canvas)
        }
        return bitmap
    }

    // --- measuring -------------------------------------------------------------------

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

        /** `PerchBrand.accent`. */
        val ACCENT = Color.rgb(0xC7, 0x8E, 0x35)

        /** A neutral plate to judge the themed layer against. */
        val PLATE = Color.rgb(0xD4, 0xE7, 0xDE)
    }
}
