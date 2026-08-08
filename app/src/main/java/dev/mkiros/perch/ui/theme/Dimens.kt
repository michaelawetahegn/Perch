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

    /**
     * The gutter the dot sits in. Reserved on read rows too, so a row does not shift
     * sideways the moment it is read.
     */
    val unreadGutter = lg

    /**
     * The lead image on a list row (§5, U08) — trailing, square, cropped. 96dp rather
     * than 64: at 64 the image is an icon beside the text, at 96 it is the second thing
     * the eye lands on, which is what the reference row does with it.
     *
     * It is not optional. A row with no image, a row still loading one and a row whose
     * image failed all reserve this exact square, so nothing in the list moves sideways
     * or collapses as images arrive.
     */
    val thumbnail = 96.dp

    /** The thumbnail's corner radius — `medium`, the app's sheet/dialog radius. §4. */
    val thumbnailCorner = md

    /** Air between the title column and the thumbnail. */
    val thumbnailGap = md

    // --- the brand (U09b) ---
    /**
     * The mark in the drawer header. Small enough to sit beside the logotype without
     * becoming a second headline, large enough that the amber block is still a block.
     */
    val brandMarkSmall = xxl

    /** The mark standing in for an icon in the no-sources-yet empty state. */
    val brandMark = 72.dp

    /** Air between the mark and the logotype in the horizontal lockup. */
    val brandGap = md

    /** The drawer header's own padding — it sits above the drawer's first item. */
    val brandHeaderVertical = xl

    /**
     * Where the wordmark's left edge lands in the drawer. Not [drawerRowPadding]: a
     * `NavigationDrawerItem` is inset by [md] and then insets its own icon by 16dp, so
     * matching the rows means matching that sum. Left at 16 the lockup hangs a visible
     * 12dp outside the column of icons under it.
     */
    val brandHeaderHorizontal = 28.dp

    /** Empty states: one centred icon above the copy. §7. */
    val emptyIcon = 48.dp

    /** Empty-state copy stops short of the screen edges rather than centring on air. */
    val emptyContentWidth = 280.dp

    /** Skeleton rows (§7): a title bar and a shorter metadata bar, on `surfaceContainer`. */
    val skeletonTitle = lg
    val skeletonMeta = md
    val skeletonCorner = xs

    /** Icon glyphs sit at 20dp inside a [touchTarget]-sized hit area. */
    val icon = 20.dp

    /**
     * A drawer source row (§5): 56dp tall and fully rounded, matching the Material
     * drawer items above and below it. It is hand-built rather than a
     * `NavigationDrawerItem` only because it also has to answer a long press.
     */
    val drawerRowHeight = 56.dp
    val drawerRowPadding = lg
    val drawerRowGap = md

    /**
     * A source nested under its folder header (U06). The indent is the chevron's slot, so
     * a source's icon lines up with its folder's name rather than with its chevron.
     */
    val drawerNestIndent = xxl

    /** A dialog whose body is a list stops growing here and scrolls instead. */
    val dialogListMaxHeight = 320.dp

    /** The spinner a primary button becomes while it is working. §5. */
    val buttonSpinner = 18.dp

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

    /**
     * Air between the pinned line-number gutter and the code it numbers (U11). Wide enough
     * that a number never reads as part of the first token, narrow enough that the gutter
     * does not become a column of its own on a phone.
     */
    val codeGutterGap = md

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

    /**
     * Home's time-range control (U08a) and the folder headers below it. The control keeps
     * its own padding rather than borrowing the list's, because it sits above the scroll.
     *
     * [rangeControlInset] is not a margin: it is the difference between the list's 16dp
     * gutter and a text button's own 12dp of content padding, so the range label starts on
     * the same vertical line as the row titles under it rather than 4dp inside them.
     */
    val rangeRowVertical = xs
    val rangeControlInset = xs
    val sectionHeaderTop = lg
    val sectionHeaderBottom = sm

    /**
     * The footer spinner of a paged list (U07a). Deliberately smaller and thinner than a
     * Material default: it is a note that the next page is coming, not an event.
     */
    val appendIndicator = 20.dp
    val appendIndicatorStroke = 2.dp

    /** Editorial link underline: 1dp thick, offset 3dp below the baseline. §8. */
    val linkUnderline = 1.dp
    val linkUnderlineOffset = 3.dp

    /**
     * A table column is measured from its own text (U11a) and then held between these two.
     * The floor keeps a `Yes`/`No` column from collapsing to something untappable and
     * unreadable; the ceiling is where a column stops growing and starts wrapping, so one
     * long sentence cannot push the whole table sideways. §8.
     */
    val tableColumnMin = 56.dp
    val tableColumnMax = 260.dp

    /** Cell padding: comfortable enough that neighbouring columns read as separate. §8. */
    val tableCellHorizontal = md
    val tableCellVertical = sm

    /** Air above and below a table, as a figure gets: it is a block, not a paragraph. §8. */
    val tableSpacing = xl
}
