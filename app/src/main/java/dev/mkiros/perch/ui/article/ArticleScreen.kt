package dev.mkiros.perch.ui.article

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkiros.perch.R
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.ui.article.zoom.ImageViewer
import dev.mkiros.perch.ui.article.zoom.ZoomedImage
import dev.mkiros.perch.ui.theme.ArticleType
import dev.mkiros.perch.ui.theme.Dimens

/**
 * One entry, read in the app (DESIGN.md §8).
 *
 * The furniture is Material sans and the reading surface is serif, and the two never mix:
 * everything below the app bar is [ArticleType]. The column caps at
 * [Dimens.articleMeasure] and centres, so a tablet gets a wider margin rather than a
 * hundred-character line, and the whole surface is selectable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(
    viewModel: ArticleViewModel,
    onBack: () -> Unit,
    // V08: the source's name in the byline is a way into that source's list. The screen
    // does not know what that list is — the shell scopes the Feed and shows it.
    onOpenSource: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    zoomed: MutableState<ZoomedImage?> = rememberSaveable(stateSaver = ZoomedImage.Saver) {
        mutableStateOf(null)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                modifier = Modifier.size(Dimens.icon),
                            )
                        }
                    },
                    actions = {
                        val loaded = state as? ArticleUiState.Loaded
                        if (loaded != null) {
                            // Filled when on, outlined when off (U09). The two toggles read as
                            // one pair, so they share the treatment: the glyph carries the
                            // state, the tint only emphasises it.
                            ToggleAction(
                                on = loaded.isLiked,
                                onIcon = Icons.Filled.Favorite,
                                offIcon = Icons.Outlined.FavoriteBorder,
                                labelRes = if (loaded.isLiked) {
                                    R.string.entry_action_unlike
                                } else {
                                    R.string.entry_action_like
                                },
                                testTag = ArticleTestTags.LIKE,
                                onClick = viewModel::toggleLiked,
                            )
                            ToggleAction(
                                on = loaded.isSaved,
                                onIcon = Icons.Filled.Bookmark,
                                offIcon = Icons.Outlined.BookmarkBorder,
                                labelRes = if (loaded.isSaved) {
                                    R.string.entry_action_unsave
                                } else {
                                    R.string.entry_action_save
                                },
                                testTag = ArticleTestTags.SAVE,
                                onClick = viewModel::toggleSaved,
                            )
                        }
                        loaded?.link?.let { link ->
                            IconButton(
                                onClick = { openInBrowser(context, link) },
                                modifier = Modifier.testTag(ArticleTestTags.OPEN_IN_BROWSER),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = stringResource(R.string.action_open_in_browser),
                                    modifier = Modifier.size(Dimens.icon),
                                )
                            }
                        }
                        if (loaded != null) {
                            Overflow(loaded, onLoadFullArticle = viewModel::loadFullArticle)
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (val current = state) {
                    ArticleUiState.Loading -> Unit
                    ArticleUiState.Missing -> Text(
                        text = stringResource(R.string.article_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = Dimens.screenHorizontal),
                    )
                    is ArticleUiState.Loaded -> Column(modifier = Modifier.fillMaxSize()) {
                        // A hairline under the app bar rather than a spinner over the text:
                        // the body — excerpt or not — stays readable the whole time the
                        // article is being fetched (U10).
                        if (current.isFetchingFullText) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(ArticleTestTags.FULL_TEXT_PROGRESS),
                            )
                        }
                        Article(
                            state = current,
                            onOpenSource = onOpenSource,
                            onOpenLink = { url -> openInBrowser(context, url) },
                            onOpenImage = { image -> zoomed.value = ZoomedImage(image.url, image.alt) },
                        )
                    }
                }
            }
        }

        // A sibling of the whole screen, app bar included: the viewer is full-bleed, and
        // it is an overlay rather than a destination so that the article — its scroll
        // position, its selection, its in-flight full-text fetch — is still there
        // underneath when it closes.
        zoomed.value?.let { image ->
            ImageViewer(image = image, onDismiss = { zoomed.value = null })
        }
    }
}

/**
 * The overflow, which exists for one action: *Load full article* (U10).
 *
 * It is enabled whenever the body did not already come from an extraction, not only when
 * the automatic trigger declined to fire. The trigger is a heuristic and will sometimes
 * read an excerpt as an article; this is how the reader gets out of that without leaving
 * the app, which is the whole point of the task.
 */
@Composable
private fun Overflow(state: ArticleUiState.Loaded, onLoadFullArticle: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.testTag(ArticleTestTags.OVERFLOW),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.action_more),
                modifier = Modifier.size(Dimens.icon),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.article_load_full_text)) },
                enabled = state.canLoadFullText && !state.isFetchingFullText,
                onClick = {
                    open = false
                    onLoadFullArticle()
                },
                modifier = Modifier.testTag(ArticleTestTags.LOAD_FULL_TEXT),
            )
        }
    }
}

/**
 * One of the top bar's two state toggles.
 *
 * The label changes with the state rather than staying put — the content description is
 * what a screen reader announces on press, and "Like" announced while un-liking is a lie.
 */
@Composable
private fun ToggleAction(
    on: Boolean,
    onIcon: ImageVector,
    offIcon: ImageVector,
    labelRes: Int,
    testTag: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(testTag)) {
        Icon(
            imageVector = if (on) onIcon else offIcon,
            contentDescription = stringResource(labelRes),
            tint = if (on) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(Dimens.icon),
        )
    }
}

@Composable
private fun Article(
    state: ArticleUiState.Loaded,
    onOpenSource: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenImage: (ArticleBlock.Image) -> Unit,
) {
    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = Dimens.articleMeasure)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = Dimens.screenHorizontal),
            ) {
                Text(
                    text = state.title,
                    style = ArticleType.headline,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(ArticleTestTags.HEADLINE),
                )
                Byline(state, onOpenSource)
                state.standfirst?.let { standfirst ->
                    Text(
                        text = standfirst,
                        style = ArticleType.standfirst,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(bottom = Dimens.paragraphSpacing)
                            .testTag(ArticleTestTags.STANDFIRST),
                    )
                }

                if (state.blocks.isEmpty()) {
                    EmptyBody(state, onOpenLink)
                } else {
                    ArticleBody(
                        blocks = state.blocks,
                        articleLink = state.link,
                        onOpenLink = onOpenLink,
                        onOpenImage = onOpenImage,
                    )
                }

                Spacer(modifier = Modifier.height(Dimens.xxl))
            }
        }
    }
}

/**
 * `SOURCE · AUTHOR · 3 AUG 2026`, with the source segment a way into that source's list
 * (V08, issue #10).
 *
 * Only the source is interactive: the author is not a destination Perch has and a date is
 * not a link at all, so the line is composed of segments rather than made tappable whole.
 * The segment is a control and is dressed as one — `Role.Button`, a ripple, and a hit
 * area of [Dimens.touchTarget] whatever the type size — but dressed in §8's editorial
 * underline rather than in a coloured hyperlink, which is the loudest way for a reading
 * surface to stop looking like a publication.
 *
 * The row is [Dimens.touchTarget] tall whether or not the source is there, so an entry
 * whose feed row has gone does not shift the article up under the headline.
 */
@Composable
private fun Byline(state: ArticleUiState.Loaded, onOpenSource: (Long) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = Dimens.touchTarget).padding(bottom = Dimens.sm),
    ) {
        val rest = state.byline
        state.source?.let { source ->
            Text(
                text = source.name,
                style = ArticleType.byline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = ArticleType.link,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.article_open_source),
                    ) { onOpenSource(source.feedId) }
                    .heightIn(min = Dimens.touchTarget)
                    .wrapContentHeight()
                    .testTag(ArticleTestTags.SOURCE),
            )
            if (rest.isNotEmpty()) {
                Text(
                    text = ArticleViewModel.SEPARATOR,
                    style = ArticleType.byline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (rest.isNotEmpty()) {
            Text(
                text = rest,
                style = ArticleType.byline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Lets a long author name wrap rather than run off the measure; the
                // source keeps its intrinsic width because it is the tappable one.
                modifier = Modifier.weight(1f, fill = false).testTag(ArticleTestTags.BYLINE),
            )
        }
    }
}

/**
 * A feed that ships summaries only is not an error, so this is not an error state: the
 * summary is what there is to read, and the button is where the rest lives (§8).
 */
@Composable
private fun EmptyBody(state: ArticleUiState.Loaded, onOpenLink: (String) -> Unit) {
    Text(
        text = state.summary ?: stringResource(R.string.article_empty_body),
        style = ArticleType.body,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = EMPTY_BODY_ALPHA),
        modifier = Modifier.padding(bottom = Dimens.paragraphSpacing),
    )
    state.link?.let { link ->
        Button(
            onClick = { onOpenLink(link) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ArticleTestTags.READ_ON_WEB),
        ) {
            Text(text = stringResource(R.string.article_read_on_web))
        }
    }
}

/**
 * A Custom Tab rather than a browser hand-off: the reader keeps Perch's task, comes back
 * with a swipe, and the tab inherits the app's colours (DESIGN.md §8).
 */
private fun openInBrowser(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    } catch (_: ActivityNotFoundException) {
        // No browser installed at all. There is nowhere to send the reader, and crashing
        // out of an article they were reading would be the worse answer.
    }
}

object ArticleTestTags {
    const val HEADLINE = "article:headline"
    const val BYLINE = "article:byline"

    /** The byline's tappable first segment (V08); [BYLINE] is the rest of the line. */
    const val SOURCE = "article:source"
    const val STANDFIRST = "article:standfirst"
    const val OPEN_IN_BROWSER = "article:open-in-browser"
    const val LIKE = "article:like"
    const val SAVE = "article:save"
    const val READ_ON_WEB = "article:read-on-web"
    const val OVERFLOW = "article:overflow"
    const val LOAD_FULL_TEXT = "article:load-full-text"
    const val FULL_TEXT_PROGRESS = "article:full-text-progress"
    const val CODE = "article:code"
    const val CODE_TEXT = "article:code-text"
    const val CODE_GUTTER = "article:code-gutter"
    const val IMAGE = "article:image"
    const val IMAGE_VIEWER = "article:image-viewer"
    const val IMAGE_VIEWER_IMAGE = "article:image-viewer-image"
    const val IMAGE_VIEWER_CLOSE = "article:image-viewer-close"
    const val QUOTE = "article:quote"
    const val TABLE = "article:table"
    const val TABLE_HEADER = "article:table-header"
    const val TABLE_RULE = "article:table-rule"
    const val RULE = "article:rule"
    const val EMBED = "article:embed"
}

/** The summary is a stand-in for the body, so it reads a shade quieter than one. */
private const val EMPTY_BODY_ALPHA = 0.92f
