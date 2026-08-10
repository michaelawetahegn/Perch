package dev.mkiros.perch.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import dev.mkiros.perch.R
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.ui.brand.PerchMarkMonochrome
import dev.mkiros.perch.ui.theme.Dimens

/**
 * Handles for the row's parts, which are inside its merged semantics node.
 *
 * The row itself has none: it wears whatever tag its caller passes in — `HomeTestTags.ENTRY`
 * on home — and a second `testTag` on the same node would silently replace it.
 */
object EntryRowTestTags {
    const val TITLE = "entry:title"
    const val META = "entry:meta"

    /** The square the image lives in, drawn at the same size in every state. */
    const val THUMBNAIL = "entry:thumb"
    const val THUMBNAIL_IMAGE = "entry:thumb:image"
    const val THUMBNAIL_PLACEHOLDER = "entry:thumb:placeholder"

    /** The mark on the placeholder — drawn once an image is known not to be coming. */
    const val THUMBNAIL_MARK = "entry:thumb:mark"
}

/**
 * One entry in the reading list (DESIGN.md §5, U08), built to
 * `design/reference/feed-row-reference.jpg` — structure from the reference, colour from
 * our own dark scheme:
 *
 * ```
 * ● Title, up to three lines     ┌────────┐
 *   Source / 5h                  │ thumb  │
 *                                └────────┘
 * ```
 *
 * The unread dot is `primary` and is the only coloured thing on the row (§2); a read row
 * drops its title to `onSurfaceVariant` and loses the dot, and that contrast delta is the
 * entire read/unread affordance — no strikethrough, no opacity, no badge. The dot's
 * gutter is reserved whether or not the dot is drawn, so titles stay on one left edge.
 *
 * U08 dropped the two-line snippet: the thumbnail now does the work of telling the reader
 * what an entry is about, and a row carrying both is a card in everything but name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(
    item: EntryListItem,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        // The long press lives on the *same* node as the tap rather than on a wrapper
        // around it (U09): an inner `clickable` consumes the pointer stream, so a
        // combinedClickable outside this row would be a long press that never arrives.
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Dimens.rowHorizontal, vertical = Dimens.rowVertical),
    ) {
        Box(modifier = Modifier.width(Dimens.unreadGutter)) {
            if (!item.isRead) {
                Box(
                    modifier = Modifier
                        .size(Dimens.unreadDot)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (item.isRead) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = TITLE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(EntryRowTestTags.TITLE),
            )
            Spacer(modifier = Modifier.size(Dimens.xs))
            Text(
                text = stringResource(
                    R.string.home_entry_meta,
                    item.sourceTitle,
                    RelativeTime.format(item.publishedAt, now),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(EntryRowTestTags.META),
            )
        }

        Spacer(modifier = Modifier.size(Dimens.thumbnailGap))
        Thumbnail(url = item.imageUrl)
    }
}

/**
 * The row's trailing square — an image when there is one, and otherwise the outlined
 * placeholder the reference draws.
 *
 * **All four states occupy this identical square.** No image is a designed state, not a
 * failure (PLAN-2 §0): most of the sources here are text-only blogs that will never
 * have one, an image in flight resolves in its own time, and an image URL harvested from
 * a feed months ago may well 404. If any of those collapsed the square or shrank it, the
 * list would reflow under the reader's thumb as images arrived — so `loading` and `error`
 * land on the same [Placeholder] as `null` and none of them shows a broken-image glyph.
 * They do not all *look* alike: `error` and `null` are settled and carry the mark, and
 * only `loading` is still a bare frame. (`ArticleFigure` collapses on a load error, which
 * is right *there* — a gap mid-article is better than an empty frame — and wrong here.)
 */
@Composable
private fun Thumbnail(url: String?) {
    val shape = RoundedCornerShape(Dimens.thumbnailCorner)
    Box(
        modifier = Modifier
            .size(Dimens.thumbnail)
            .clip(shape)
            .testTag(EntryRowTestTags.THUMBNAIL),
    ) {
        if (url == null) {
            Placeholder(shape, marked = true)
            return@Box
        }
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { Placeholder(shape, marked = false) },
            error = { Placeholder(shape, marked = true) },
            success = {
                SubcomposeAsyncImageContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(EntryRowTestTags.THUMBNAIL_IMAGE),
                )
            },
        )
    }
}

/**
 * The square with no image in it (V07, issue #13).
 *
 * A hairline around nothing is what a browser draws while an image is still coming, so
 * that is exactly what it looked like — on rows whose image was never coming at all, which
 * on a reading list of text blogs is most of them. So when the answer is final — no URL,
 * or a URL that failed — the square is *finished*: a low-contrast `surfaceVariant` fill
 * carrying the app's own mark as line art, in `outline`. Monochrome and quiet by
 * construction: the sheets take the fill's own colour, so only the rules, the P and the
 * block show, at the contrast the scheme reserves for a decorative boundary.
 *
 * [marked] is false for exactly one state — an image still in flight — which keeps the
 * plain frame, because a load in progress is the one case where "still coming" is true.
 */
@Composable
private fun Placeholder(shape: RoundedCornerShape, marked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (marked) Modifier.background(scheme.surfaceVariant, shape) else Modifier)
            .border(Dimens.hairline, scheme.outlineVariant, shape)
            .testTag(EntryRowTestTags.THUMBNAIL_PLACEHOLDER),
        contentAlignment = Alignment.Center,
    ) {
        if (marked) {
            PerchMarkMonochrome(
                ink = scheme.outline,
                paper = scheme.surfaceVariant,
                size = Dimens.thumbnailMark,
                modifier = Modifier.testTag(EntryRowTestTags.THUMBNAIL_MARK),
            )
        }
    }
}

private const val TITLE_MAX_LINES = 3
