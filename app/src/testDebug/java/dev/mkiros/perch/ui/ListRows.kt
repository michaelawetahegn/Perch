package dev.mkiros.perch.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import dev.mkiros.perch.ui.home.EntryRowTestTags

/**
 * The titles of the rows the list is currently drawing.
 *
 * Since U07a the rows are not on the view model to be read: they arrive as `PagingData`,
 * a page at a time, and the only place the whole visible list exists is the composition.
 * So a test that used to ask `uiState.entries` asks the screen — which is the more honest
 * question anyway, since a row the reader cannot see was never evidence of anything.
 *
 * Addressed through the row's title tag on the **unmerged** tree: [EntryRowTestTags.TITLE]
 * sits inside the row's merged semantics node, and the merged node's own text would arrive
 * as title-plus-metadata in one string.
 */
fun ComposeTestRule.rowTitles(): List<String> =
    onAllNodesWithTag(EntryRowTestTags.TITLE, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString(separator = "") { it.text }
        }
