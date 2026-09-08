package dev.mkiros.perch.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import dev.mkiros.perch.ui.theme.Dimens

/**
 * The one centred "nothing here, and here is why" state, drawn by the Feed, by To-Read
 * and Liked, and by both of search's prompts (DESIGN.md §7).
 *
 * **It is a `LazyColumn` holding one full-size item, never a plain `Column`, and that is
 * the reason this composable exists at all.** `PullToRefreshBox` only ever sees a drag its
 * child dispatches down the nested-scroll chain, and a `Column` dispatches nothing — so
 * before V03/#6 pull-to-refresh was inert on exactly the screen where a reader reaches for
 * it. One item at the parent's full size keeps the content centred and hands the whole
 * surface to the gesture. Written once here, the rule cannot be lost by a fourth surface
 * that reasonably assumes a static screen needs no scrolling.
 *
 * The four differences between the callers are the parameters:
 *  - **[icon]** is a slot rather than an `ImageVector` because the Feed's no-sources state
 *    draws the brand mark instead of a glyph. Everything else passes [EmptyStateIcon].
 *  - **[title]** and **[body]**, which is what "one empty state per cause" means: the
 *    states differ by their sentence, not by their shape.
 *  - **[action]**, the one step out of this state, when there is one — the Feed's "add a
 *    source" and "widen the window" buttons.
 *
 * [modifier] lands on the centred content column, not on the `LazyColumn`, because that is
 * the node the callers' test tags have always marked.
 */
@Composable
internal fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = modifier
                    .fillParentMaxSize()
                    .padding(horizontal = Dimens.screenHorizontal),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                icon()
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Dimens.lg, bottom = Dimens.sm),
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(Dimens.emptyContentWidth),
                )
                if (action != null) {
                    Spacer(modifier = Modifier.size(Dimens.xl))
                    action()
                }
            }
        }
    }
}

/** The glyph every empty state but the Feed's no-sources one draws above its sentence. */
@Composable
internal fun EmptyStateIcon(image: ImageVector) {
    Icon(
        imageVector = image,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(Dimens.emptyIcon),
    )
}
