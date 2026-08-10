package dev.mkiros.perch.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Perch mark, defined once (U09b).
 *
 * The source art (`design/brand/perch-logo-source.png`, 84×88) is a stacked pair of
 * sheets carrying a serif **P**, rule lines standing in for text, and one amber block
 * standing in for an image. It is far too small to scale, so the mark is *redrawn* here
 * as path data rather than traced or upscaled.
 *
 * The geometry lives in **launcher coordinates** — a 108×108 viewport whose centre 66dp
 * circle is the only region an adaptive icon is guaranteed to show, since launchers mask
 * to circles, squircles and rounded squares. Everything below is drawn inside that
 * circle; `LauncherIconTest` fails the build if a future edit pushes ink outside it.
 *
 * `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml` restate the same strings
 * — a `VectorDrawable` cannot read a Kotlin constant — and `LauncherIconTest` asserts
 * the two copies still agree, so the mark cannot drift between the launcher and the app.
 */
object PerchMarkPaths {

    /** The sheet behind, showing only as a sliver down the right edge. */
    const val BACK_SHEET =
        "M42,39 H73 A3,3 0 0 1 76,42 V69 A3,3 0 0 1 73,72 H42 A3,3 0 0 1 39,69 " +
            "V42 A3,3 0 0 1 42,39 Z"

    /** The sheet in front, which carries everything else. */
    const val FRONT_PAGE =
        "M35,32 H67 A3,3 0 0 1 70,35 V73 A3,3 0 0 1 67,76 H35 A3,3 0 0 1 32,73 " +
            "V35 A3,3 0 0 1 35,32 Z"

    /**
     * The serif **P**, as one non-zero-wound path: the outer silhouette clockwise, the
     * bowl's counter wound the other way so it punches a hole the way a real glyph does,
     * then the foot serif and the top flag unioned on.
     */
    const val P_GLYPH =
        "M38.5,37 H42.9 A4.7,4.7 0 0 1 42.9,46.4 H41.7 V52 H38.5 Z " +
            "M41.7,44 H42.9 A2.3,2.3 0 0 0 42.9,39.4 H41.7 Z " +
            "M37,50.6 H43.2 V52 H37 Z " +
            "M37.5,37 H41.7 V38.4 H37.5 Z"

    /** Three rules beside the P — the headline block. */
    const val TOP_RULES = "M52,40 H65 M52,45 H65 M52,50 H65"

    /** The heavier rule that divides headline from body. */
    const val FULL_RULE = "M37,56.5 H65"

    /** The mark's one accent: the image block. */
    const val ACCENT_BLOCK = "M37,60 H48.5 V71.5 H37 Z"

    /** Three rules beside the block — the body block. */
    const val BODY_RULES = "M52,62 H65 M52,66 H65 M52,70 H65"

    // --- the launcher canvas ---

    /** Adaptive-icon layers are 108×108 regardless of density. */
    const val VIEWPORT = 108f

    /** Centre of the 108 canvas, and of the mark. */
    const val CENTRE = 54f

    /** Radius of the 66dp circle every launcher mask contains. Ink must stay inside it. */
    const val SAFE_RADIUS = 33f

    // --- stroke weights, in viewport units ---
    const val PAGE_STROKE = 3.2f
    const val RULE_STROKE = 2.6f
    const val FULL_RULE_STROKE = 3.2f
    const val BLOCK_STROKE = 2f

    /**
     * The mark's own bounding box inside that canvas, strokes included. In the app there
     * is no mask and no safe zone, so the vector is cropped to this and the caller's
     * `size` is the mark rather than the mark plus a ring of launcher padding.
     */
    const val INK_LEFT = 30f
    const val INK_TOP = 30f
    const val INK_WIDTH = 48f
    const val INK_HEIGHT = 48f
}

/**
 * The mark's three colours, and the type its wordmark is set in.
 *
 * A mark is fixed artwork: a document that inverts to a dark page with light rules stops
 * reading as *paper* and starts reading as a different logo. So paper, ink and accent are
 * the same in both themes and only the wordmark's lettering follows `onSurface`, which is
 * how the printed lockup behaves too.
 *
 * [paper] and [ink] are Neutral98 and Primary10 — the same two tones the light scheme's
 * surface and `onPrimaryContainer` come from, restated here because artwork cannot read a
 * `ColorScheme`. [accent] is the source art's amber, and the ramp it seeds is the scheme's
 * `tertiary` (see the note in Color.kt), so the app has one warm accent and not two.
 */
object PerchBrand {
    val paper = Color(0xFFF2FBF7)
    val ink = Color(0xFF002115)
    val accent = Color(0xFFC78E35)

    /**
     * "Perch", set in the platform serif. The source art's wordmark is a serif face, and
     * the same family already carries the article surface (§3), so the logotype and the
     * reading it leads to are set in one voice. It is not on the Material scale on
     * purpose — a logotype that grows with `titleLarge` is a token, not a mark.
     */
    val wordmark = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 28.sp,
    )

    /** "RSS READER" — letter-spaced caps under the logotype, as in the source lockup. */
    val tagline = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 2.sp,
    )
}

/**
 * The mark as an [ImageVector], cropped to [PerchMarkPaths.INK_WIDTH]×`INK_HEIGHT`.
 *
 * Built once: it has no theme input, so recomposing it per call would allocate a fresh
 * path tree on every drawer open for identical pixels.
 */
val PerchMarkVector: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    buildPerchMark(
        name = "PerchMark",
        paper = PerchBrand.paper,
        ink = PerchBrand.ink,
        accent = PerchBrand.accent,
    )
}

/**
 * The same drawing in one colour on one ground (V07).
 *
 * A `ColorFilter.tint` over [PerchMarkVector] would flatten the mark into its silhouette
 * — two overlapping rounded rectangles — losing the P, the rules and the block, which are
 * the whole reason it reads as a page. Rebuilding it with [paper] as every fill and [ink]
 * as every stroke keeps that structure and gives up only the amber, which is exactly what
 * "monochrome" is asking for. Pass the surface it sits on as [paper] and the sheets
 * disappear into it, leaving line art.
 *
 * Not cached: [ink] and [paper] are theme colours, so a caller `remember`s it against the
 * two it passed.
 */
fun perchMarkMonochrome(ink: Color, paper: Color): ImageVector =
    buildPerchMark(name = "PerchMarkMono", paper = paper, ink = ink, accent = ink)

private fun buildPerchMark(name: String, paper: Color, ink: Color, accent: Color): ImageVector {
    val paperFill = SolidColor(paper)
    val inkStroke = SolidColor(ink)
    val accentFill = SolidColor(accent)

    return ImageVector.Builder(
        name = name,
        defaultWidth = PerchMarkPaths.INK_WIDTH.dp,
        defaultHeight = PerchMarkPaths.INK_HEIGHT.dp,
        viewportWidth = PerchMarkPaths.INK_WIDTH,
        viewportHeight = PerchMarkPaths.INK_HEIGHT,
    )
        // The paths are authored in launcher coordinates; this shifts that canvas so the
        // mark's bounding box starts at the vector's origin.
        .group(-PerchMarkPaths.INK_LEFT, -PerchMarkPaths.INK_TOP) {
            sheet(PerchMarkPaths.BACK_SHEET, paperFill, inkStroke)
            sheet(PerchMarkPaths.FRONT_PAGE, paperFill, inkStroke)
            addPath(addPathNodes(PerchMarkPaths.P_GLYPH), fill = inkStroke)
            rules(PerchMarkPaths.TOP_RULES, inkStroke, PerchMarkPaths.RULE_STROKE)
            rules(PerchMarkPaths.FULL_RULE, inkStroke, PerchMarkPaths.FULL_RULE_STROKE)
            addPath(
                addPathNodes(PerchMarkPaths.ACCENT_BLOCK),
                fill = accentFill,
                stroke = inkStroke,
                strokeLineWidth = PerchMarkPaths.BLOCK_STROKE,
                strokeLineJoin = StrokeJoin.Miter,
            )
            rules(PerchMarkPaths.BODY_RULES, inkStroke, PerchMarkPaths.RULE_STROKE)
        }
        .build()
}

private fun ImageVector.Builder.group(
    translationX: Float,
    translationY: Float,
    content: ImageVector.Builder.() -> Unit,
): ImageVector.Builder =
    addGroup(translationX = translationX, translationY = translationY)
        .apply(content)
        .clearGroup()

private fun ImageVector.Builder.sheet(pathData: String, fill: SolidColor, stroke: SolidColor) {
    addPath(
        addPathNodes(pathData),
        fill = fill,
        stroke = stroke,
        strokeLineWidth = PerchMarkPaths.PAGE_STROKE,
        strokeLineJoin = StrokeJoin.Round,
    )
}

private fun ImageVector.Builder.rules(pathData: String, stroke: SolidColor, width: Float) {
    addPath(
        addPathNodes(pathData),
        stroke = stroke,
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Butt,
    )
}
