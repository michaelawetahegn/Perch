package dev.mkiros.perch.ui.article.document

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.mkiros.perch.R
import dev.mkiros.perch.data.document.PageSource
import dev.mkiros.perch.ui.article.ArticleTestTags
import dev.mkiros.perch.ui.article.DocumentUi
import dev.mkiros.perch.ui.theme.ArticleType
import dev.mkiros.perch.ui.theme.Dimens
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext

/**
 * The document reading surface (PLAN-13 §0.6): a PDF drawn as its own pages, at the
 * measure, edge to edge and untouched, each followed by a separator.
 *
 * Item 0 is the header — [header] (the article's headline and byline) and the document
 * strip; item N is page N. Every page is laid out at its own aspect from
 * [DocumentUi.aspects] before its bitmap exists, so nothing reflows when one arrives.
 * The source is opened when the screen shows and closed when it leaves.
 */
@Composable
fun DocumentArticle(
    document: DocumentUi,
    openPages: (File) -> PageSource?,
    onScrollSettled: (Int) -> Unit,
    header: @Composable () -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 0)
    var toastVisible by remember { mutableStateOf(false) }
    var toastPageNumber by remember { mutableStateOf(1) }

    val pages by produceState<PageCache?>(initialValue = null, document.file) {
        value = withContext(Dispatchers.IO) { openPages(document.file) }?.let(::PageCache)
        awaitDispose { value?.close() }
    }

    // Track scroll settle to write page number
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .drop(1)
            .collect {
                val page = pageUnderCentre(listState.layoutInfo.visibleItemsInfo, 500) ?: 0
                onScrollSettled(page)
            }
    }

    // Dispose handler to write final position
    DisposableEffect(Unit) {
        onDispose {
            val page = pageUnderCentre(listState.layoutInfo.visibleItemsInfo, 500) ?: 0
            onScrollSettled(page)
        }
    }

    // Track page under centre for toast
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                val page = pageUnderCentre(listState.layoutInfo.visibleItemsInfo, 500)
                if (page != null && page > 0) {
                    toastPageNumber = page
                    toastVisible = true
                }
            }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pageWidth = min(maxWidth, Dimens.articleMeasure)
        // §0.5: the fit-width bucket; G07c re-chooses it when a zoom gesture ends.
        val pageWidthPx = with(LocalDensity.current) { pageWidth.roundToPx() }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag(ArticleTestTags.DOCUMENT),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = Dimens.xxl),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .widthIn(max = Dimens.articleMeasure)
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.screenHorizontal),
                ) {
                    header()
                    DocumentStrip(
                        pageCount = document.pageCount,
                        sizeBytes = document.sizeBytes,
                        modifier = Modifier.testTag(ArticleTestTags.DOCUMENT_STRIP),
                    )
                }
            }

            items(count = document.pageCount) { index ->
                Column {
                    DocumentPage(
                        pages = pages,
                        index = index,
                        widthPx = pageWidthPx,
                        modifier = Modifier
                            .width(pageWidth)
                            .aspectRatio(document.aspects[index])
                            .testTag("${ArticleTestTags.DOCUMENT_PAGE}:$index"),
                    )
                    // §0.6: the outline colour reads as near-black in light and still shows in dark.
                    Box(
                        modifier = Modifier
                            .width(pageWidth)
                            .height(SEPARATOR)
                            .background(MaterialTheme.colorScheme.outline)
                            .testTag("${ArticleTestTags.DOCUMENT_SEPARATOR}:$index"),
                    )
                }
            }
        }

        // Page toast - bottom centre
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Dimens.xl)
        ) {
            AnimatedVisibility(
                visible = toastVisible,
                exit = fadeOut(animationSpec = tween(durationMillis = 1200))
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.padding(horizontal = Dimens.xl)
                ) {
                    Text(
                        text = stringResource(R.string.document_page_toast, toastPageNumber, document.pageCount),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(horizontal = Dimens.md, vertical = Dimens.sm)
                            .testTag(ArticleTestTags.DOCUMENT_TOAST)
                    )
                }
            }
        }
    }
}

/**
 * One page: the surface-coloured box of the page's shape until its bitmap arrives, then
 * the page as rendered — white in both themes, the document as published (§0.6).
 */
@Composable
private fun DocumentPage(pages: PageCache?, index: Int, widthPx: Int, modifier: Modifier) {
    val bitmap by produceState(pages?.cached(index, widthPx), pages, index, widthPx) {
        if (pages != null && value == null) value = pages.page(index, widthPx)
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        bitmap?.let { page ->
            DisposableEffect(pages, page) { onDispose(pages!!.show(index, widthPx)) }
            Image(
                bitmap = page.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("${ArticleTestTags.DOCUMENT_PAGE_IMAGE}:$index"),
            )
        }
    }
}

/** `PDF · 112 pages · 850 KB · saved offline`, the chip and a caption (§0.6). */
@Composable
private fun DocumentStrip(pageCount: Int, sizeBytes: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.padding(bottom = Dimens.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        Text(
            text = stringResource(R.string.document_kind_pdf),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
                .padding(horizontal = Dimens.xs + 2.dp, vertical = 2.dp),
        )
        Text(
            text = stringResource(
                R.string.document_strip,
                pluralStringResource(R.plurals.document_pages, pageCount, pageCount),
                Formatter.formatShortFileSize(context, sizeBytes),
            ),
            style = ArticleType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Find the page whose item straddles the viewport centre, pure over layout info.
 * The first item is the header (index 0), so page N is at item index N.
 */
fun pageUnderCentre(visibleItems: List<Any>, viewportHeight: Int): Int? {
    val center = viewportHeight / 2
    // TODO: implement with actual item bounds
    return null
}

/** The rule under every page, the last included (§0.6). */
private val SEPARATOR = 2.dp
