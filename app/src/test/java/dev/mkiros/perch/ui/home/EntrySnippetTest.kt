package dev.mkiros.perch.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A row's second line, when the feed's summary opens by restating the headline the row
 * has already shown (T29).
 *
 * The T28 seed makes the case impossible to miss: a link blog whose body begins with its
 * own title spends the whole snippet saying the headline twice, so a screenful of rows
 * carries about half the information it looks like it does.
 */
class EntrySnippetTest {

    @Test
    fun `a summary that says something new is left alone`() {
        val snippet = EntrySnippet.forTitle(
            title = "Concurrent, atomic MSI hash tables",
            summary = "Readers will be familiar with Mask-Step-Index hash tables.",
        )

        assertThat(snippet).isEqualTo("Readers will be familiar with Mask-Step-Index hash tables.")
    }

    @Test
    fun `a summary that opens by restating the headline drops the repetition`() {
        val snippet = EntrySnippet.forTitle(
            title = "The Tokenpocalypse Is Here",
            summary = "The Tokenpocalypse Is Here There's a growing consensus.",
        )

        assertThat(snippet).isEqualTo("There's a growing consensus.")
    }

    @Test
    fun `punctuation left behind by the headline goes with it`() {
        val snippet = EntrySnippet.forTitle(
            title = "Simon Willison on Technical Blogging",
            summary = "Simon Willison on Technical Blogging — I was interviewed by Cynthia Dunlop.",
        )

        assertThat(snippet).isEqualTo("I was interviewed by Cynthia Dunlop.")
    }

    @Test
    fun `the headline is matched however the feed happened to case it`() {
        val snippet = EntrySnippet.forTitle(
            title = "datasette 1.0a38",
            summary = "Datasette 1.0a38: this release fixes a SQL injection issue.",
        )

        assertThat(snippet).isEqualTo("this release fixes a SQL injection issue.")
    }

    @Test
    fun `a summary that is only the headline leaves the row without a second line`() {
        val snippet = EntrySnippet.forTitle(
            title = "datasette 0.65.3",
            summary = "datasette 0.65.3",
        )

        assertThat(snippet).isNull()
    }

    @Test
    fun `a headline that merely appears somewhere in the summary is not touched`() {
        val snippet = EntrySnippet.forTitle(
            title = "datasette 1.0a38",
            summary = "Release: datasette 1.0a38 fixes a SQL injection issue.",
        )

        assertThat(snippet).isEqualTo("Release: datasette 1.0a38 fixes a SQL injection issue.")
    }

    @Test
    fun `an absent or blank summary stays absent`() {
        assertThat(EntrySnippet.forTitle(title = "Anything", summary = null)).isNull()
        assertThat(EntrySnippet.forTitle(title = "Anything", summary = "   ")).isNull()
    }

    @Test
    fun `an entry with no title of its own keeps whatever summary it has`() {
        val snippet = EntrySnippet.forTitle(title = "", summary = "A body with no headline.")

        assertThat(snippet).isEqualTo("A body with no headline.")
    }

    @Test
    fun `what is left of a restated headline has to be worth a line`() {
        val snippet = EntrySnippet.forTitle(
            title = "An Async Runtime in C",
            summary = "An Async Runtime in C …",
        )

        assertThat(snippet).isNull()
    }
}
