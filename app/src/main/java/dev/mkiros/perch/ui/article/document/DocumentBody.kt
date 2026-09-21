package dev.mkiros.perch.ui.article.document

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.SemanticsPropertyKey
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
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
// `transformable(canPan = …)` is experimental in foundation 1.7; pinch-only needs it (§0.6).
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentArticle(
    document: DocumentUi,
    openPages: (File) -> PageSource?,
    scrollPosition: Int,
    onScrollSettled: (Int) -> Unit,
    header: @Composable () -> Unit,
) {
    // Item N is page N (item 0 is the header), so the page stopped on is the item to open at.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = scrollPosition)
    var toastPage by remember { mutableStateOf(1) }
    var toastVisible by remember { mutableStateOf(false) }

    val pages by produceState<PageCache?>(initialValue = null, document.file) {
        value = withContext(Dispatchers.IO) { openPages(document.file) }?.let(::PageCache)
        awaitDispose { value?.close() }
    }

    // §0.6: the page is written when a scroll settles and as the screen leaves, never per frame.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .filter { !it }
            .collect { onScrollSettled(listState.layoutInfo.pageUnderCentre() ?: 0) }
    }
    DisposableEffect(listState) {
        onDispose { onScrollSettled(listState.layoutInfo.pageUnderCentre() ?: 0) }
    }

    // The toast follows the page under the centre once it changes; the first page seen is
    // the one opened at, so it never shows on open.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.pageUnderCentre() }
            .filterNotNull()
            .distinctUntilChanged()
            .drop(1)
            .collectLatest { page ->
                toastPage = page
                toastVisible = true
                delay(TOAST_LINGER_MS)
                toastVisible = false
            }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val pageWidth = min(maxWidth, Dimens.articleMeasure)
        val pageWidthPx = with(density) { pageWidth.roundToPx() }
        // Gestures arrive in the box's coordinates; the zoom arithmetic is in the page's.
        val pageLeftPx = (constraints.maxWidth - pageWidthPx) / 2f

        var transform by remember(document.file) { mutableStateOf(DocTransform()) }
        var centroidX by remember { mutableFloatStateOf(0f) }
        val transformState = rememberTransformableState { zoom, _, _ ->
            transform = DocumentZoom.pinch(transform, pageWidthPx.toFloat(), centroidX - pageLeftPx, 0f, zoom)
        }
        val dragState = rememberDraggableState { dx ->
            transform = DocumentZoom.drag(transform, pageWidthPx.toFloat(), dx)
        }

        // §0.5: bucket 1 or 2, re-chosen only when a gesture ends — never mid-pinch, when
        // the bitmap already rendered is scaled up instead.
        var bucket by remember(document.file) { mutableIntStateOf(1) }
        LaunchedEffect(transformState) {
            snapshotFlow { transformState.isTransformInProgress to transform.scale }
                .filter { (inProgress, _) -> !inProgress }
                .collect { (_, scale) -> bucket = ceil(scale).toInt().coerceIn(1, 2) }
        }

        val column by produceState<TextColumn?>(initialValue = null, pages, pageWidthPx) {
            val open = pages ?: return@produceState
            value = open.textColumn(document.pageCount, pageWidthPx)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // §0.10: on the list's parent, never the list — on one node they compete for slop.
                .pointerInput(Unit) {
                    // Only watched, never consumed: the pinch's centroid, which the
                    // transformable's callback does not carry.
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent(PointerEventPass.Initial).changes.filter { it.pressed }
                            if (down.size >= 2) centroidX = down.map { it.position.x }.average().toFloat()
                        }
                    }
                }
                .transformable(transformState, canPan = { false })
                .draggable(dragState, Orientation.Horizontal, enabled = DocumentZoom.isZoomed(transform.scale))
                .pointerInput(pageWidthPx, pageLeftPx) {
                    detectTapGestures(
                        onDoubleTap = { at ->
                            transform = DocumentZoom.doubleTap(transform, pageWidthPx.toFloat(), at.x - pageLeftPx, column)
                        },
                    )
                },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(ArticleTestTags.DOCUMENT)
                    .semantics { column?.let { this[DocumentTextColumnKey] = it } },
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
                    val zoomedWidth = pageWidth * transform.scale
                    val zoomedHeight = zoomedWidth / document.aspects[index]
                    Column {
                        // The page's frame stays at the measure; the page inside it grows and
                        // slides, and the frame clips it — the list's own scroll covers the height.
                        Box(
                            modifier = Modifier
                                .width(pageWidth)
                                .height(zoomedHeight)
                                .clipToBounds(),
                        ) {
                            DocumentPage(
                                pages = pages,
                                index = index,
                                widthPx = pageWidthPx * bucket,
                                modifier = Modifier
                                    .offset { IntOffset(transform.offsetX.roundToInt(), 0) }
                                    .wrapContentWidth(Alignment.Start, unbounded = true)
                                    .requiredSize(zoomedWidth, zoomedHeight)
                                    .testTag("${ArticleTestTags.DOCUMENT_PAGE}:$index"),
                            )
                        }
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
        }

        // §0.6: a pill at the bottom centre, gone TOAST_LINGER_MS after the last change.
        AnimatedVisibility(
            visible = toastVisible,
            enter = fadeIn(tween(TOAST_FADE_MS)),
            exit = fadeOut(tween(TOAST_FADE_MS)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Dimens.xl),
        ) {
            Text(
                text = stringResource(R.string.document_page_toast, toastPage, document.pageCount),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.inverseSurface, CircleShape)
                    .padding(horizontal = Dimens.md, vertical = Dimens.sm)
                    .testTag(ArticleTestTags.DOCUMENT_TOAST),
            )
        }
    }
}

/**
 * One page: the surface-coloured box of the page's shape until its bitmap arrives, then
 * the page as rendered — white in both themes, the document as published (§0.6).
 */
@Composable
private fun DocumentPage(pages: PageCache?, index: Int, widthPx: Int, modifier: Modifier) {
    // The bitmap drawn and the width it was rendered at: a new render bucket keeps the old
    // bitmap on screen, scaled, until the sharper one arrives.
    var shown by remember(pages, index) { mutableStateOf(pages?.cached(index, widthPx)?.let { widthPx to it }) }
    LaunchedEffect(pages, index, widthPx) {
        if (pages != null && shown?.first != widthPx) pages.page(index, widthPx)?.let { shown = widthPx to it }
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        shown?.let { (width, page) ->
            DisposableEffect(pages, width, page) { onDispose(pages!!.show(index, width)) }
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

/**
 * The body's [TextColumn], measured from the fit-width bitmaps of pages 2–4 (or all of a
 * shorter document) — pinned while they are read, since an evicted bitmap is recycled (§0.6).
 */
private suspend fun PageCache.textColumn(pageCount: Int, widthPx: Int): TextColumn? {
    val measured = if (pageCount >= 4) 1..3 else 0 until pageCount
    val unpins = measured.map { show(it, widthPx) }
    return try {
        val bitmaps = measured.mapNotNull { page(it, widthPx) }
        withContext(Dispatchers.Default) { TextColumn.of(bitmaps) }
    } finally {
        unpins.forEach { it() }
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

/** [pageUnderCentre] over the list's own layout: item N is page N, the header never one. */
private fun LazyListLayoutInfo.pageUnderCentre(): Int? =
    pageUnderCentre(visibleItemsInfo.map(::VisibleItem), viewportSize.height)

private class VisibleItem(info: LazyListItemInfo) : LayoutItem {
    override val index = info.index
    override val offset = info.offset
    override val size = info.size
}

/** The rule under every page, the last included (§0.6). */
private val SEPARATOR = 2.dp

/** How long the page toast stays after the page under the centre last changed (§0.6). */
private const val TOAST_LINGER_MS = 1_200L

/** The toast's fade, DESIGN.md §6's read-state crossfade. */
private const val TOAST_FADE_MS = 150

/**
 * The measured [TextColumn], on the document list once it exists — a custom key, so no
 * accessibility service reads it; a test waits on it because a double tap before it is
 * the 2× fallback (§0.6).
 */
val DocumentTextColumnKey = SemanticsPropertyKey<TextColumn>("DocumentTextColumn")
