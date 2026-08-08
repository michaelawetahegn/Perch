package dev.mkiros.perch.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.mkiros.perch.R

/**
 * Two type systems, deliberately split (DESIGN.md §3 and §8).
 *
 * [PerchTypography] is the app *furniture*: Material 3's sans scale, spelled out at the
 * sizes §3 tabulates so a later token change is a diff here and nowhere else.
 * [ArticleType] is the *editorial* surface: serif, print-derived, its own scale.
 *
 * Body and furniture bundle nothing. `FontFamily.Default` (Roboto) and `FontFamily.Serif`
 * (Noto Serif) both ship on device — zero APK cost, no licensing. Every size is in `sp`
 * so the system font scale is respected everywhere.
 *
 * **[Mono] is the one exception** (U11, amending DESIGN.md §3). The platform monospace is
 * Droid Sans Mono, whose `0`/`O`, `1`/`l` and `;`/`:` are close enough at 13sp to misread
 * a line of code — and code is the one place in this app where a misread character is a
 * different program. JetBrains Mono is the closest OFL-licensed relative of the face the
 * reader is used to, and it ships one 268 KB regular weight; the licence is verbatim in
 * `assets/JetBrainsMono-OFL.txt`.
 */
private val Sans = FontFamily.Default
private val Serif = FontFamily.Serif
private val Mono = FontFamily(Font(R.font.jetbrains_mono))

/** Standard ligatures and contextual alternates off — see [ArticleType.code]. */
private const val NO_LIGATURES = "liga 0, calt 0, dlig 0"

val PerchTypography = Typography(
    // Top app bar title — `LargeTopAppBar` on home. §3.
    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    // Article title on the article screen's collapsed bar. §3.
    headlineSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    // Entry title in a list row — max 3 lines, ellipsised by the row. §3.
    // w600 rather than w500 since U08: the row's only remaining text hierarchy is title
    // against metadata, so the title has to carry it on its own.
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    // Body copy in the app furniture — settings summaries, sheet confirmations, empty
    // states. (It was the list row's snippet until U08 removed it.) §3.
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    // Source name · relative time. §3.
    labelMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * The article surface's serif scale (DESIGN.md §8). Kept out of [Typography] because
 * these roles have no Material equivalent — a standfirst and a pull-quote are editorial
 * ideas, not platform ones — and because mixing them into the M3 scale would let a
 * furniture screen accidentally render serif.
 *
 * Colour is not baked in: the renderer applies `onSurface` / `onSurfaceVariant` from the
 * scheme so these styles work unchanged in both light and dark.
 */
object ArticleType {
    /** Serif 30/36 w700, left-aligned, never centred. */
    val headline = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    )

    /** The summary, when it adds anything beyond the headline. */
    val standfirst = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
    )

    /** `SOURCE · AUTHOR · 12 MAR 2026` — sans, uppercased by the renderer. */
    val byline = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.06.em,
    )

    /** Serif 18/29 — the 1.6 leading is what makes a long read comfortable. */
    val body = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 29.sp,
    )

    /** `h2` after lowering maps here; `h1` and `h4`–`h6` fold into [sectionHead2]/[sectionHead3]. */
    val sectionHead2 = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    )

    val sectionHead3 = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
    )

    /** Image captions and quote attributions. */
    val caption = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** Pull-quote: serif italic, no quotation marks — the rule does the quoting. */
    val pullQuote = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 21.sp,
        lineHeight = 30.sp,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
    )

    /**
     * `pre`/`code` — the one place sans loses. Never wrapped; the block scrolls.
     *
     * JetBrains Mono ships programming ligatures on, and they are turned off here: they
     * draw `->` as `→` and `!=` as `≠`, which is a glyph substitution inside *someone
     * else's program*. A reader comparing the article against their own editor has to be
     * able to trust that the characters on screen are the characters in the source.
     */
    val code = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = NO_LIGATURES,
    )

    /** Feed tables are rare and usually broken; sans keeps them from posing as content. */
    val table = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    val tableHeader = table.copy(fontWeight = FontWeight.SemiBold)

    /**
     * `ol` markers, in the gutter beside the item. Sans with tabular figures so `9.` and
     * `10.` occupy the same width and the hanging indent stays straight. §8.
     */
    val listMarker = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 29.sp,
        fontFeatureSettings = "tnum",
    )

    /** An editorial underline, not a blue hyperlink — colour comes from the renderer. */
    val link = TextDecoration.Underline

    // --- the inline marks a `RichSpan` can carry (§8) ---
    // Deliberately here rather than in the renderer: these are the only typographic
    // vocabulary a feed gets, and every `sp` in the app has to live in this package.

    val strong = SpanStyle(fontWeight = FontWeight.Bold)

    val emphasis = SpanStyle(fontStyle = FontStyle.Italic)

    /** Inline `code` — a chip, not a block; the background is the renderer's to supply. */
    val inlineCode =
        SpanStyle(fontFamily = Mono, fontSize = 15.sp, fontFeatureSettings = NO_LIGATURES)

    val superscript = SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 13.sp)

    val subscript = SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 13.sp)
}
