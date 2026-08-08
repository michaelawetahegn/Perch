package dev.mkiros.perch.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.mkiros.perch.R

/**
 * §0's three top-level destinations: **Feed · To-Read · Liked**.
 *
 * They are peers the reader switches between constantly, and a peer switch that costs a
 * drawer open is a peer switch that does not happen — which is the whole argument for
 * spending a bar on them. The drawer keeps the job it is actually good at: scoping the
 * Feed to a folder or a source.
 */
enum class PerchTab(
    val route: String,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    Feed(Routes.FEED, R.string.tab_feed, Icons.Filled.Inbox, Icons.Outlined.Inbox),

    /**
     * *To-Read*, not "Saved": the column is `isSaved`, but the label has to say what the
     * list is **for**. "Saved" describes the gesture; "To-Read" describes the queue.
     */
    ToRead(
        Routes.SAVED,
        R.string.tab_to_read,
        Icons.AutoMirrored.Filled.LibraryBooks,
        Icons.AutoMirrored.Outlined.LibraryBooks,
    ),

    Liked(Routes.LIKED, R.string.tab_liked, Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
    ;

    companion object {
        /** The tab a route belongs to, or null for a route that is not a tab (the article). */
        fun ofRoute(route: String?): PerchTab? = entries.firstOrNull { it.route == route }
    }
}

/**
 * The bar itself.
 *
 * Filled icon when selected, outlined when not — the same signal the label's colour gives,
 * repeated in the glyph, because at a glance a reader reads the shape before the tint.
 * It is hidden on the article screen (§0), which is the nav host's decision rather than
 * this composable's: a bar under an article is furniture over a reading surface.
 */
@Composable
fun PerchBottomBar(
    current: PerchTab,
    onSelect: (PerchTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier.testTag(NavTestTags.BOTTOM_BAR)) {
        PerchTab.entries.forEach { tab ->
            val selected = tab == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.icon,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(tab.labelRes)) },
                modifier = Modifier.testTag(NavTestTags.tab(tab)),
            )
        }
    }
}

/**
 * Handles for the shell. The bar's labels are also the titles of the screens they lead to,
 * so an assertion about "is the bar showing" cannot be an assertion about text.
 */
object NavTestTags {
    const val BOTTOM_BAR = "nav:bar"

    fun tab(tab: PerchTab) = "nav:tab:${tab.name}"
}
