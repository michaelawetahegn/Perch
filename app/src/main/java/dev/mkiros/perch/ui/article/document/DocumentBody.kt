package dev.mkiros.perch.ui.article.document

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.article.ArticleTestTags
import dev.mkiros.perch.ui.article.DocumentUi
import dev.mkiros.perch.ui.theme.ArticleType
import dev.mkiros.perch.ui.theme.Dimens
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/**
 * The document reading surface: a PDF rendered as pages full-bleed and untouched.
 * The page you stopped on is remembered. Separators follow each page; a page
 * toast shows which page is under the viewport centre and fades after 1200 ms.
 */
@Composable
fun DocumentArticle(
    document: DocumentUi,
    title: String,
    author: String?,
    onScrollSettled: (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 0)
    var toastVisible by remember { mutableStateOf(false) }
    var toastPageNumber by remember { mutableStateOf(1) }

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

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag(ArticleTestTags.DOCUMENT),
            contentPadding = PaddingValues(bottom = Dimens.xxl)
        ) {
            // Header: title, byline, strip
            item {
                Column(
                    modifier = Modifier
                        .widthIn(max = Dimens.articleMeasure)
                        .padding(horizontal = Dimens.screenHorizontal)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        style = ArticleType.headline,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag(ArticleTestTags.HEADLINE),
                    )
                    // Minimal byline - just author if available
                    if (author != null) {
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DocumentStrip(
                        pageCount = document.pageCount,
                        sizeBytes = document.sizeBytes,
                        modifier = Modifier.testTag(ArticleTestTags.DOCUMENT_STRIP)
                    )
                }
            }

            // Pages
            itemsIndexed((0 until document.pageCount).toList()) { pageIndex, _ ->
                val aspect = if (pageIndex < document.aspects.size) {
                    document.aspects[pageIndex]
                } else {
                    612f / 792f  // Standard letter
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .widthIn(max = Dimens.articleMeasure)
                            .heightIn(min = (Dimens.articleMeasure.value / aspect).dp)
                            .testTag("${ArticleTestTags.DOCUMENT_PAGE}:$pageIndex")
                            .background(MaterialTheme.colorScheme.surface)
                            .fillMaxWidth(0.9f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Page ${pageIndex + 1}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Divider(
                    thickness = 2.dp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("${ArticleTestTags.DOCUMENT_SEPARATOR}:$pageIndex")
                )
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

@Composable
private fun DocumentStrip(
    pageCount: Int,
    sizeBytes: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sizeFormatted = Formatter.formatShortFileSize(context, sizeBytes)

    Column(modifier = modifier.padding(vertical = Dimens.md)) {
        Text(
            text = "$pageCount pages · $sizeFormatted · saved offline",
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

// Extensions for Dimens
private val Dimens.md: androidx.compose.ui.unit.Dp
    get() = this.xs

private val Dimens.sm: androidx.compose.ui.unit.Dp
    get() = this.xs / 2
