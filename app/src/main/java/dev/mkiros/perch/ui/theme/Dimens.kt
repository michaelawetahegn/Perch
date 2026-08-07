package dev.mkiros.perch.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Every dimension in the app (DESIGN.md §4). Feature packages must never write a `.dp`
 * literal — if a screen needs a spacing that is not here, the token is missing and
 * belongs here, named for what it does.
 *
 * The scale is a strict 4dp grid: [xs] 4 · [sm] 8 · [md] 12 · [lg] 16 · [xl] 24 ·
 * [xxl] 32. The named values below are all expressed in terms of that grid; they exist
 * so a screen says `Dimens.rowVertical` rather than re-deciding what 12dp meant.
 */
object Dimens {
    // --- the grid ---
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Zero, for the many Compose APIs that want an explicit "none". */
    val none = 0.dp

    // --- app furniture ---
    /** Horizontal padding for every screen's content. §4. */
    val screenHorizontal = lg

    /** List rows: 16dp horizontal, 12dp vertical → ~88dp with a 3-line title. §4. */
    val rowHorizontal = lg
    val rowVertical = md

    /** The hairline divider between rows is inset this far from the left. §4. */
    val dividerInset = lg

    /** Hairline weight — dividers, code-block borders, table separators. */
    val hairline = 1.dp

    /** Minimum touch target. Nothing tappable may be smaller. §4. */
    val touchTarget = 48.dp

    /** The unread dot on a list row. §2 — the one coloured thing on a row. */
    val unreadDot = sm

    /** Icon glyphs sit at 20dp inside a [touchTarget]-sized hit area. */
    val icon = 20.dp

    // --- the reading surface (§8) ---
    /** The article text column caps here and centres — never a 100-character line. */
    val articleMeasure = 680.dp

    /** Paragraph rhythm: no top margin, this much beneath. §8. */
    val paragraphSpacing = lg

    /** Section heads: 32dp of air above, 10dp below. §8 — 10dp is off-grid by design. */
    val sectionHeadAbove = xxl
    val sectionHeadBelow = 10.dp

    /** Code blocks: 14dp padding, 8dp corners, scrolled horizontally. §8. */
    val codePadding = 14.dp
    val codeCorner = sm

    /** Inline `code` chips get 2dp of horizontal breathing room. §8. */
    val inlineCodePadding = 2.dp

    /** Images: full column width, editorial 4dp corners, 24dp of air, 8dp to caption. */
    val imageCorner = xs
    val imageSpacing = xl
    val captionGap = sm

    /** Pull-quotes: 24dp vertical margin, 20dp left inset, a 2dp `primary` rule. §8. */
    val quoteSpacing = xl
    val quoteInset = 20.dp
    val quoteRule = 2.dp

    /** Lists: a 20dp marker gutter with a hanging indent, 8dp between items. §8. */
    val listGutter = 20.dp
    val listItemSpacing = sm

    /** `hr` renders as a centred 32dp hairline with 32dp of air either side. §8. */
    val ruleWidth = xxl
    val ruleSpacing = xxl

    /** Editorial link underline: 1dp thick, offset 3dp below the baseline. §8. */
    val linkUnderline = 1.dp
    val linkUnderlineOffset = 3.dp
}
