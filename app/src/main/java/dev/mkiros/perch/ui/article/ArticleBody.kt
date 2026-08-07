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
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import dev.mkiros.perch.R
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.RichSpan
import dev.mkiros.perch.ui.theme.ArticleType
import dev.mkiros.perch.ui.theme.Dimens

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
        is ArticleBlock.Code -> CodeBlock(block.text)
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
 */
@Composable
private fun CodeBlock(source: String) {
    val shape = RoundedCornerShape(Dimens.codeCorner)
    // In dark mode `surfaceContainer` already separates itself from the page; in light
    // mode the two are close enough that the block needs an edge.
    val light = MaterialTheme.colorScheme.surface.luminance() > MID_LUMINANCE
    Box(
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
            .horizontalScroll(rememberScrollState())
            .testTag(ArticleTestTags.CODE),
    ) {
        Text(
            text = source,
            style = ArticleType.code,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier.padding(Dimens.codePadding),
        )
    }
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
