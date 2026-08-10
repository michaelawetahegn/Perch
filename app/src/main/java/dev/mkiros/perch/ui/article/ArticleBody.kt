package dev.mkiros.perch.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import dev.mkiros.perch.R
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.RichSpan
import dev.mkiros.perch.ui.article.code.CodeHighlighter
import dev.mkiros.perch.ui.article.code.CodeLanguage
import dev.mkiros.perch.ui.theme.ArticleType
import dev.mkiros.perch.ui.theme.CodeColors
import dev.mkiros.perch.ui.theme.Dimens
import dev.mkiros.perch.ui.theme.LocalCodeColors

/**
 * The one renderer (DESIGN.md §8).
 *
 * There is no source-specific branch anywhere below this line, and there must never be
 * one: forty-two dialects were already flattened into nine block shapes by the lowering
 * (T25a), so everything left to decide is typographic and applies to all of them equally.
 * If a source looks wrong here, the fix belongs in the lowering.
 *
 * @param articleLink where an [ArticleBlock.Unsupported] card sends the reader — the
 *   embed itself has no URL we can trust, but the post it came from does.
 * @param onOpenImage a figure was tapped and wants the full-screen viewer (U12). Defaults
 *   to doing nothing so a screenshot or a block test can compose the body without one.
 */
@Composable
fun ArticleBody(
    blocks: List<ArticleBlock>,
    articleLink: String?,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenImage: (ArticleBlock.Image) -> Unit = {},
) {
    Column(modifier) {
        blocks.forEach { block -> Block(block, articleLink, onOpenLink, onOpenImage) }
    }
}

/**
 * @param quoted paragraphs inside a pull-quote take the quote's larger italic face; every
 *   other block renders the same inside a quote as outside it.
 */
@Composable
private fun Block(
    block: ArticleBlock,
    articleLink: String?,
    onOpenLink: (String) -> Unit,
    onOpenImage: (ArticleBlock.Image) -> Unit,
    quoted: Boolean = false,
) {
    when (block) {
        is ArticleBlock.Paragraph -> ParagraphBlock(block.text, quoted, onOpenLink)
        is ArticleBlock.Heading -> HeadingBlock(block, onOpenLink)
        is ArticleBlock.Code -> CodeBlock(block)
        is ArticleBlock.Image -> ImageBlock(block, onOpenLink, onOpenImage)
        is ArticleBlock.Quote -> QuoteBlock(block, articleLink, onOpenLink, onOpenImage)
        is ArticleBlock.ListBlock -> ListBlock(block, onOpenLink)
        is ArticleBlock.Table -> TableBlock(block, onOpenLink)
        ArticleBlock.Rule -> RuleBlock()
        is ArticleBlock.Unsupported -> EmbedCard(articleLink, onOpenLink)
    }
}

@Composable
private fun ParagraphBlock(text: RichSpan, quoted: Boolean, onOpenLink: (String) -> Unit) {
    Text(
        text = text.toAnnotatedString(MaterialTheme.colorScheme.surfaceContainerHigh, onOpenLink),
        style = if (quoted) ArticleType.pullQuote else ArticleType.body,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = BODY_ALPHA),
        modifier = Modifier.padding(bottom = Dimens.paragraphSpacing),
    )
}

@Composable
private fun HeadingBlock(block: ArticleBlock.Heading, onOpenLink: (String) -> Unit) {
    Text(
        text = block.text.toAnnotatedString(MaterialTheme.colorScheme.surfaceContainerHigh, onOpenLink),
        style = if (block.level <= SECTION_HEAD_2) ArticleType.sectionHead2 else ArticleType.sectionHead3,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(
            top = Dimens.sectionHeadAbove,
            bottom = Dimens.sectionHeadBelow,
        ),
    )
}

/**
 * Never wrapped and never reflowed — a wrapped line of code is a different program to
 * read. The block scrolls instead, which is also why it clips before it scrolls.
 *
 * U11 adds two things around that, and both are structural rather than decorative. The
 * **gutter sits outside the scroll**, so sliding a wide line sideways moves the code and
 * leaves the numbers where they were; a gutter inside the scroll leaves the screen on the
 * first swipe and is worse than no gutter at all. And the numbers are **their own
 * composable in a `DisableSelection`** rather than characters prepended to each line, so
 * the article's `SelectionContainer` yields runnable code with nothing to strip out.
 *
 * V11 adds the rule between the two. Pinning the numbers is what makes it necessary: a
 * wide line scrolled left arrives within a few dp of them and the two columns read as
 * one. The rule is the Row's full height, which is why the Row is measured at
 * [IntrinsicSize.Min] — inside the article's vertical scroll the incoming height is
 * unbounded, and `fillMaxHeight` against an unbounded constraint is zero.
 */
@Composable
private fun CodeBlock(block: ArticleBlock.Code) {
    val shape = RoundedCornerShape(Dimens.codeCorner)
    // In dark mode `surfaceContainer` already separates itself from the page; in light
    // mode the two are close enough that the block needs an edge.
    val light = MaterialTheme.colorScheme.surface.luminance() > MID_LUMINANCE
    val codeColors = LocalCodeColors.current
    // Tokenising is the one genuinely expensive thing in this renderer, and the result
    // depends on nothing that changes while the article is open.
    val highlighted = remember(block, codeColors) { highlight(block, codeColors) }
    val lines = remember(block) { block.text.count { it == '\n' } + 1 }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Dimens.paragraphSpacing)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = Dimens.hairline,
                color = if (light) MaterialTheme.colorScheme.outlineVariant else Color.Transparent,
                shape = shape,
            )
            .height(IntrinsicSize.Min),
    ) {
        // A one-liner is a command or a signature, not a listing; numbering it says
        // "line 1 of 1", which is noise.
        if (lines > 1) {
            DisableSelection {
                Text(
                    // One `Text` rather than one per line: it sizes itself to the widest
                    // number, so the code's left edge does not shift at 9→10 or 99→100,
                    // and every row keeps the code's own line height by construction.
                    text = (1..lines).joinToString("\n"),
                    style = ArticleType.code,
                    // `outline`, not `onSurfaceVariant`: numbering is furniture, and at
                    // onSurfaceVariant it reads as loud as a comment — which is content.
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.End,
                    softWrap = false,
                    modifier = Modifier
                        .padding(
                            start = Dimens.codePadding,
                            top = Dimens.codePadding,
                            bottom = Dimens.codePadding,
                            end = Dimens.codeGutterGap,
                        )
                        .testTag(ArticleTestTags.CODE_GUTTER),
                )
            }
            // Furniture, at the same weight as the block's own border: the numbers are a
            // column, and this is where it ends.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(Dimens.hairline)
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .testTag(ArticleTestTags.CODE_GUTTER_RULE),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                // Outside the scroll, so the gap is the rule's air rather than the first
                // line's indent — scrolled left, the code stops at the rule.
                .padding(start = if (lines > 1) Dimens.codeGutterGap else Dimens.none)
                .horizontalScroll(rememberScrollState())
                .testTag(ArticleTestTags.CODE),
        ) {
            Text(
                text = highlighted,
                style = ArticleType.code,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
                modifier = Modifier
                    .padding(
                        start = if (lines > 1) Dimens.none else Dimens.codePadding,
                        top = Dimens.codePadding,
                        bottom = Dimens.codePadding,
                        end = Dimens.codePadding,
                    )
                    .testTag(ArticleTestTags.CODE_TEXT),
            )
        }
    }
}

/**
 * [ArticleBlock.Code.text] verbatim, wearing colour. Highlighting is presentation only:
 * the returned string's characters are the block's own, so copying it copies the program.
 */
private fun highlight(block: ArticleBlock.Code, colors: CodeColors): AnnotatedString {
    val language = CodeLanguage.of(block.language, block.text)
    val spans = CodeHighlighter.tokenize(block.text, language)
    if (spans.isEmpty()) return AnnotatedString(block.text)
    return AnnotatedString(
        text = block.text,
        spanStyles = spans.map {
            AnnotatedString.Range(SpanStyle(color = colors.of(it.token)), it.start, it.end)
        },
    )
}

/**
 * A failed load collapses the whole figure — caption included. A broken-image glyph or a
 * grey box mid-sentence reads as the app's fault, and the reader loses nothing by the
 * paragraph simply continuing (§8).
 */
@Composable
private fun ImageBlock(
    block: ArticleBlock.Image,
    onOpenLink: (String) -> Unit,
    onOpenImage: (ArticleBlock.Image) -> Unit,
) {
    var state by remember(block.url) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    if (state is AsyncImagePainter.State.Error) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.imageSpacing),
    ) {
        AsyncImage(
            model = block.url,
            contentDescription = block.alt,
            contentScale = ContentScale.FillWidth,
            onState = { state = it },
            modifier = Modifier
                .fillMaxWidth()
                // Space is reserved before the bytes arrive so the paragraph below does
                // not jump; once the real ratio is known the image sizes itself.
                .then(
                    if (state is AsyncImagePainter.State.Success) {
                        Modifier
                    } else {
                        Modifier.aspectRatio(PLACEHOLDER_RATIO)
                    },
                )
                .clip(RoundedCornerShape(Dimens.imageCorner))
                // The figure is the whole point of some articles and is illegible at a
                // phone's measure; a tap is the only affordance it has (U12).
                .clickable(
                    onClickLabel = stringResource(R.string.image_viewer_open),
                    onClick = { onOpenImage(block) },
                )
                .testTag(ArticleTestTags.IMAGE),
        )
        block.caption?.let { caption ->
            Text(
                text = caption.toAnnotatedString(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    onOpenLink,
                ),
                style = ArticleType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Dimens.captionGap),
            )
        }
    }
}

@Composable
private fun QuoteBlock(
    block: ArticleBlock.Quote,
    articleLink: String?,
    onOpenLink: (String) -> Unit,
    onOpenImage: (ArticleBlock.Image) -> Unit,
) {
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .padding(vertical = Dimens.quoteSpacing)
            .testTag(ArticleTestTags.QUOTE),
    ) {
        Box(
            modifier = Modifier
                .width(Dimens.quoteRule)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = QUOTE_RULE_ALPHA)),
        )
        Column(modifier = Modifier.padding(start = Dimens.quoteInset - Dimens.quoteRule)) {
            block.blocks.forEach { inner ->
                Block(inner, articleLink, onOpenLink, onOpenImage, quoted = true)
            }
        }
    }
}

/** The marker sits in a fixed gutter, so a wrapped line aligns under the text, not the bullet. */
@Composable
private fun ListBlock(block: ArticleBlock.ListBlock, onOpenLink: (String) -> Unit) {
    Column(modifier = Modifier.padding(bottom = Dimens.paragraphSpacing)) {
        block.items.forEachIndexed { index, item ->
            Row(modifier = Modifier.padding(bottom = Dimens.listItemSpacing)) {
                Text(
                    text = if (block.ordered) "${index + 1}." else BULLET,
                    style = if (block.ordered) ArticleType.listMarker else ArticleType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(Dimens.listGutter),
                )
                Text(
                    text = item.toAnnotatedString(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        onOpenLink,
                    ),
                    style = ArticleType.body,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = BODY_ALPHA),
                )
            }
        }
    }
}

/**
 * A table that reads as a table (U11a, DESIGN.md §8).
 *
 * Three decisions carry the whole treatment. **Columns are measured, not fixed**: a
 * `Yes`/`No` column and a paragraph of impact text do not want the same slot, and giving
 * them one both crushes the sentence and wastes a third of the page. A column that wants
 * more than [Dimens.tableColumnMax] stops there and **wraps inside itself** rather than
 * pushing the table wider — the horizontal scroll exists for tables with many columns, not
 * for one long sentence. And the **rules and the header tint are drawn at the table's own
 * width**, because a `fillMaxWidth` divider inside a `horizontalScroll` measures against an
 * unbounded constraint and lands on zero: hairlines nobody can see, which is what made a
 * table look like run-together columns before this.
 *
 * The header scrolls with the body. A frozen header sounds like the better idea until the
 * body slides underneath it and every value sits under the wrong column name.
 *
 * V11 adds the fourth: a table that runs past the edge of the page **says so**, by fading
 * out into the page on whichever side it still has more of. Nothing else on the reading
 * surface scrolls sideways, so a reader has no reason to try unless the table admits it.
 */
@Composable
private fun TableBlock(block: ArticleBlock.Table, onOpenLink: (String) -> Unit) {
    val layout = rememberTableLayout(block)
    val rule = MaterialTheme.colorScheme.outlineVariant
    val scroll = rememberScrollState()

    Box(modifier = Modifier.padding(vertical = Dimens.tableSpacing)) {
        Column(
            modifier = Modifier
                .horizontalScroll(scroll)
                .testTag(ArticleTestTags.TABLE),
        ) {
            if (block.header.isNotEmpty()) {
                TableRow(
                    cells = block.header,
                    layout = layout,
                    style = ArticleType.tableHeader,
                    onOpenLink = onOpenLink,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .testTag(ArticleTestTags.TABLE_HEADER),
                )
            }
            block.rows.forEachIndexed { index, row ->
                if (index > 0 || block.header.isNotEmpty()) {
                    TableRule(layout.width, rule)
                }
                TableRow(row, layout, ArticleType.table, onOpenLink)
            }
            // The foot rule is what closes the table: without it the last row runs on into
            // the page and the block stops looking like one thing.
            if (block.rows.isNotEmpty()) TableRule(layout.width, rule)
        }
        // Only where there is something to reach: an edge on a table that fits would be
        // an affordance for a gesture that does nothing.
        if (scroll.canScrollBackward) TableEdge(atEnd = false, ArticleTestTags.TABLE_EDGE_START)
        if (scroll.canScrollForward) TableEdge(atEnd = true, ArticleTestTags.TABLE_EDGE_END)
    }
}

/**
 * The table dissolving into the page at one edge (V11).
 *
 * It is drawn over the *viewport* rather than added to the table, which is the same trap
 * [TableBlock]'s rules had from the other side: inside the scroll it would sit at the far
 * end of the content, off-screen, where no reader would ever see it. So the fade is a
 * sibling of the scrolling column, sized to it with [BoxScope.matchParentSize], and it
 * draws only — a node with no pointer input lets the swipe through to the table.
 */
@Composable
private fun BoxScope.TableEdge(atEnd: Boolean, tag: String) {
    val page = MaterialTheme.colorScheme.surface
    val width = with(LocalDensity.current) { Dimens.tableEdgeFade.toPx() }

    Box(
        modifier = Modifier
            .matchParentSize()
            .testTag(tag)
            .drawBehind {
                val inner = if (atEnd) size.width - width else width
                val outer = if (atEnd) size.width else 0f
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(page.copy(alpha = 0f), page),
                        startX = inner,
                        endX = outer,
                    ),
                    topLeft = Offset(if (atEnd) inner else 0f, 0f),
                    size = Size(width, size.height),
                )
            },
    )
}

@Composable
private fun TableRule(width: Dp, colour: Color) {
    Box(
        modifier = Modifier
            .width(width)
            .height(Dimens.hairline)
            .background(colour)
            .testTag(ArticleTestTags.TABLE_RULE),
    )
}

@Composable
private fun TableRow(
    cells: List<RichSpan>,
    layout: TableLayout,
    style: androidx.compose.ui.text.TextStyle,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.width(layout.width)) {
        layout.columns.forEachIndexed { index, column ->
            val cell = cells.getOrNull(index) ?: RichSpan("")
            Text(
                text = cell.toAnnotatedString(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    onOpenLink,
                ),
                style = style.copy(textAlign = column.align),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .width(column.width)
                    .padding(vertical = Dimens.tableCellVertical, horizontal = Dimens.tableCellHorizontal),
            )
        }
    }
}

private data class TableColumn(val width: Dp, val align: TextAlign)

private data class TableLayout(val columns: List<TableColumn>) {
    val width: Dp = columns.fold(Dimens.none) { total, column -> total + column.width }
}

/**
 * Column widths from the text itself, measured once per table.
 *
 * Only the first [TABLE_MEASURED_ROWS] rows are measured: a column's width is settled by
 * its first screenful in every table the corpus ships, and a ZDI advisory runs to two
 * hundred rows of ten columns, which is two thousand text layouts to answer a question the
 * first fifty already answered. Anything longer than the width they agreed on wraps, which
 * is the same thing that happens to a long cell anywhere else in the table.
 */
@Composable
private fun rememberTableLayout(block: ArticleBlock.Table): TableLayout {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val headerStyle = ArticleType.tableHeader
    val bodyStyle = ArticleType.table

    return remember(block, measurer, density, headerStyle, bodyStyle) {
        val sample = block.rows.take(TABLE_MEASURED_ROWS)
        val count = maxOf(
            block.header.size,
            sample.maxOfOrNull { it.size } ?: 0,
            block.rows.firstOrNull()?.size ?: 0,
        )

        fun measure(text: String, style: androidx.compose.ui.text.TextStyle): Dp =
            if (text.isEmpty()) {
                Dimens.none
            } else {
                with(density) {
                    measurer.measure(text, style, softWrap = false, maxLines = 1).size.width.toDp()
                }
            }

        val columns = List(count) { index ->
            val body = sample.mapNotNull { it.getOrNull(index)?.text }
            val widest = maxOf(
                measure(block.header.getOrNull(index)?.text.orEmpty(), headerStyle),
                body.maxOfOrNull { measure(it, bodyStyle) } ?: Dimens.none,
            )
            TableColumn(
                width = (widest + Dimens.tableCellHorizontal * 2)
                    .coerceIn(Dimens.tableColumnMin, Dimens.tableColumnMax),
                align = if (body.isNumeric()) TextAlign.End else TextAlign.Start,
            )
        }
        TableLayout(columns)
    }
}

/**
 * A column of numbers wants its digits lined up on the right; a column of words does not.
 * The test is deliberately strict — one `N/A` in a column of CVSS scores and the whole
 * column goes back to reading left, which is the safe way to be wrong.
 */
private fun List<String>.isNumeric(): Boolean {
    val written = filter { it.isNotBlank() }
    return written.isNotEmpty() && written.all { NUMERIC.matches(it.trim()) }
}

private val NUMERIC = Regex("""[+-]?[$€£]?\d[\d,.\s]*%?""")

/** A print section break: a short centred hairline with air, not a full-width line. */
@Composable
private fun RuleBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.ruleSpacing),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider(
            modifier = Modifier
                .width(Dimens.ruleWidth)
                .testTag(ArticleTestTags.RULE),
            thickness = Dimens.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** Never an empty hole and never raw markup — one honest card that says where to go. */
@Composable
private fun EmbedCard(articleLink: String?, onOpenLink: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Dimens.paragraphSpacing)
            .clip(RoundedCornerShape(Dimens.codeCorner))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = articleLink != null) { articleLink?.let(onOpenLink) }
            .padding(Dimens.codePadding)
            .testTag(ArticleTestTags.EMBED),
    ) {
        Text(
            text = stringResource(R.string.article_embed),
            style = ArticleType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Body text sits a touch under full contrast; solid `onSurface` reads harsh at 18sp serif. */
private const val BODY_ALPHA = 0.92f

private const val QUOTE_RULE_ALPHA = 0.4f

/** `h1`/`h2` lower to level 2; anything deeper is a level 3. */
private const val SECTION_HEAD_2 = 2

/** What an image reserves before its real dimensions are known. */
private const val PLACEHOLDER_RATIO = 16f / 9f

private const val MID_LUMINANCE = 0.5f

/** How many body rows a table's column widths are measured from. */
private const val TABLE_MEASURED_ROWS = 50

private const val BULLET = "•"
