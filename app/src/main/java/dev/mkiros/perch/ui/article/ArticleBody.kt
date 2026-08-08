package dev.mkiros.perch.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
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
 */
@Composable
fun ArticleBody(
    blocks: List<ArticleBlock>,
    articleLink: String?,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        blocks.forEach { block -> Block(block, articleLink, onOpenLink) }
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
    quoted: Boolean = false,
) {
    when (block) {
        is ArticleBlock.Paragraph -> ParagraphBlock(block.text, quoted, onOpenLink)
        is ArticleBlock.Heading -> HeadingBlock(block, onOpenLink)
        is ArticleBlock.Code -> CodeBlock(block)
        is ArticleBlock.Image -> ImageBlock(block, onOpenLink)
        is ArticleBlock.Quote -> QuoteBlock(block, articleLink, onOpenLink)
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
            ),
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
        }
        Box(
            modifier = Modifier
                .weight(1f)
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
private fun ImageBlock(block: ArticleBlock.Image, onOpenLink: (String) -> Unit) {
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
            block.blocks.forEach { inner -> Block(inner, articleLink, onOpenLink, quoted = true) }
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

/** Feed tables are rare and usually broken; the one rule is that none of them widens the page. */
@Composable
private fun TableBlock(block: ArticleBlock.Table, onOpenLink: (String) -> Unit) {
    Column(
        modifier = Modifier
            .padding(bottom = Dimens.paragraphSpacing)
            .horizontalScroll(rememberScrollState())
            .testTag(ArticleTestTags.TABLE),
    ) {
        if (block.header.isNotEmpty()) {
            TableRow(block.header, ArticleType.tableHeader, onOpenLink)
            HorizontalDivider(
                thickness = Dimens.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        block.rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = Dimens.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            TableRow(row, ArticleType.table, onOpenLink)
        }
    }
}

@Composable
private fun TableRow(
    cells: List<RichSpan>,
    style: androidx.compose.ui.text.TextStyle,
    onOpenLink: (String) -> Unit,
) {
    Row {
        cells.forEach { cell ->
            Text(
                text = cell.toAnnotatedString(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    onOpenLink,
                ),
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .width(Dimens.tableCellWidth)
                    .padding(vertical = Dimens.sm, horizontal = Dimens.xs),
            )
        }
    }
}

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

private const val BULLET = "•"
