package dev.mkiros.perch.ui.search

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the reader is looking for and where, as a value (PLAN-9 §0.8).
 *
 * The interesting properties are all here rather than on the screen: which surface a
 * search inherited, whether it inherited one at all, whether what has been typed is a
 * question yet, and what widening leaves behind. Search is *state* and not a route, so
 * these are the things a rotation or a process death must not quietly change — and the
 * only way to assert that without a device is against the [SearchState.Saver] itself.
 */
class SearchStateTest {

    @Test
    fun `a search opened from the unified Feed is narrowed to nothing`() {
        val state = SearchState.everything()

        assertThat(state.isNarrowed).isFalse()
        assertThat(state.label).isNull()
        assertThat(state.feedId).isNull()
        assertThat(state.folderId).isNull()
        assertThat(state.savedOnly).isFalse()
        assertThat(state.likedOnly).isFalse()
    }

    @Test
    fun `a search opened on a source carries that source and no other surface`() {
        val state = SearchState.inSource(feedId = 7L, label = "Null Program")

        assertThat(state.feedId).isEqualTo(7L)
        assertThat(state.folderId).isNull()
        assertThat(state.savedOnly).isFalse()
        assertThat(state.likedOnly).isFalse()
        assertThat(state.label).isEqualTo("Null Program")
        assertThat(state.isNarrowed).isTrue()
    }

    @Test
    fun `a search opened on a folder carries that folder and no other surface`() {
        val state = SearchState.inFolder(folderId = 3L, label = "Systems")

        assertThat(state.folderId).isEqualTo(3L)
        assertThat(state.feedId).isNull()
        assertThat(state.label).isEqualTo("Systems")
        assertThat(state.isNarrowed).isTrue()
    }

    @Test
    fun `a search opened from To-Read looks only at what was saved`() {
        val state = SearchState.inToRead("To-Read")

        assertThat(state.savedOnly).isTrue()
        assertThat(state.likedOnly).isFalse()
        assertThat(state.feedId).isNull()
        assertThat(state.isNarrowed).isTrue()
    }

    @Test
    fun `a search opened from Liked looks only at what was liked`() {
        val state = SearchState.inLiked("Liked")

        assertThat(state.likedOnly).isTrue()
        assertThat(state.savedOnly).isFalse()
        assertThat(state.isNarrowed).isTrue()
    }

    /**
     * §0.8's one scope control. Widening keeps the question — the reader has already typed
     * it, and making them type it again is the whole reason a "search everything" button
     * exists rather than a "close and reopen" instruction.
     */
    @Test
    fun `widening keeps the question and drops every surface`() {
        val widened = SearchState.inSource(9L, "Null Program").asking("strava").widened()

        assertThat(widened.query).isEqualTo("strava")
        assertThat(widened.isNarrowed).isFalse()
        assertThat(widened.label).isNull()
    }

    /**
     * The two empty states are told apart by this and nothing else, so it has to agree
     * with the query the database is actually asked — which is [FtsQuery]'s, not
     * `isNotBlank()`'s. A field holding only punctuation searched for nothing, so
     * reporting "no results" would blame the reader for a query that was never run.
     */
    @Test
    fun `text that reduces to no query at all is not a question`() {
        assertThat(SearchState.everything().isAsking).isFalse()
        assertThat(SearchState.everything().asking("   ").isAsking).isFalse()
        assertThat(SearchState.everything().asking("\"").isAsking).isFalse()
        assertThat(SearchState.everything().asking("*!?…").isAsking).isFalse()
        assertThat(SearchState.everything().asking("🙂").isAsking).isFalse()
    }

    @Test
    fun `one word is a question`() {
        assertThat(SearchState.everything().asking("st").isAsking).isTrue()
        assertThat(SearchState.inLiked("Liked").asking("strava fitness").isAsking).isTrue()
    }

    @Test
    fun `the saver round-trips every surface a search can be opened from`() {
        val surfaces = listOf(
            SearchState.everything().asking("strava"),
            SearchState.inSource(7L, "Null Program").asking("allocators"),
            SearchState.inFolder(3L, "Systems"),
            SearchState.inToRead("To-Read").asking("fitness app"),
            SearchState.inLiked("Liked"),
        )

        assertThat(surfaces.map(::roundTrip)).isEqualTo(surfaces)
    }

    /** No search open is a state too, and the one the shell restores into most often. */
    @Test
    fun `the saver round-trips no search at all`() {
        assertThat(roundTrip(null)).isNull()
    }

    private fun roundTrip(state: SearchState?): SearchState? {
        val scope = SaverScope { true }
        val saved = with(SearchState.Saver) { scope.save(state) }
        return SearchState.Saver.restore(checkNotNull(saved))
    }
}
