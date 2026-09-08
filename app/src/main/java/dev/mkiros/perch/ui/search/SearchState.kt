package dev.mkiros.perch.ui.search

import androidx.compose.runtime.saveable.Saver
import dev.mkiros.perch.data.db.FtsQuery

/**
 * What the reader is looking for, and where (S10, PLAN-9 §0.8).
 *
 * **Search is state, not a route**, for the reason V08 gave the scoped list: a route
 * argument would make a second way to be narrowed, so a reader could be scoped by the URL
 * and unscoped by the drawer at once. It is hoisted into `PerchNavHost` beside `homeScope`
 * and, like it, is a rung of the back chain — a rung the chain cannot see is a rung that is
 * only true by luck of composition order.
 *
 * The four scope fields are exactly
 * [dev.mkiros.perch.data.repo.EntryRepository.pagedSearch]'s parameters and are held that
 * way deliberately: the surface a search was opened from is not a taxonomy the UI invents
 * and then translates, it *is* the query's scope, and one shape means nothing can drift
 * between what the label says and what the database was asked.
 *
 * @param query the reader's own text, unsanitised. [FtsQuery] is applied once, in the
 *   repository, so nothing on the way here has to know FTS has a syntax.
 * @param label the human name of the surface, resolved when the search was opened. Held
 *   rather than looked up again because the surfaces are three different kinds of thing
 *   (a source, a folder, one of the two collections) and only the screen that opened the
 *   search knows which; a rename mid-search leaves a stale caption over correct results,
 *   which is the cheaper of the two wrongnesses.
 */
data class SearchState(
    val query: String = "",
    val feedId: Long? = null,
    val folderId: Long? = null,
    val savedOnly: Boolean = false,
    val likedOnly: Boolean = false,
    val label: String? = null,
) {

    /** Anything but every stored article — which is what "Search everything" undoes. */
    val isNarrowed: Boolean
        get() = feedId != null || folderId != null || savedOnly || likedOnly

    /**
     * Whether what has been typed is a question at all.
     *
     * Deliberately [FtsQuery]'s answer and not `query.isNotBlank()`: a field holding only
     * punctuation or an emoji reduces to no query, so the database is never asked, and
     * reporting "nothing found" for it would blame the reader for a search that did not
     * run. This is the whole of what tells DESIGN.md §7's two empty states apart.
     */
    val isAsking: Boolean get() = FtsQuery.from(query) != null

    fun asking(raw: String): SearchState = copy(query = raw)

    /**
     * §0.8's one scope control: every stored article, keeping the question.
     *
     * Keeping it is the point — the reader has already typed it, and a widen that cleared
     * the field would be indistinguishable from closing search and opening it again.
     */
    fun widened(): SearchState = SearchState(query = query)

    companion object {

        fun everything(): SearchState = SearchState()

        fun inSource(feedId: Long, label: String?): SearchState =
            SearchState(feedId = feedId, label = label)

        fun inFolder(folderId: Long, label: String?): SearchState =
            SearchState(folderId = folderId, label = label)

        fun inToRead(label: String): SearchState = SearchState(savedOnly = true, label = label)

        fun inLiked(label: String): SearchState = SearchState(likedOnly = true, label = label)

        /**
         * Hoisted into the shell, so a rotation does not throw away a question mid-typing.
         *
         * Null — no search open — saves as the empty list, the encoding
         * [dev.mkiros.perch.ui.article.zoom.ZoomedImage] already uses, because a `Saver`
         * cannot itself return null from `save` without meaning "refuse to save this".
         */
        val Saver: Saver<SearchState?, Any> = Saver(
            save = { state ->
                state?.let {
                    listOf(it.query, it.feedId, it.folderId, it.savedOnly, it.likedOnly, it.label)
                } ?: emptyList<Any?>()
            },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val fields = saved as List<Any?>
                if (fields.isEmpty()) {
                    null
                } else {
                    SearchState(
                        query = fields[0] as String,
                        feedId = fields[1] as Long?,
                        folderId = fields[2] as Long?,
                        savedOnly = fields[3] as Boolean,
                        likedOnly = fields[4] as Boolean,
                        label = fields[5] as String?,
                    )
                }
            },
        )
    }
}
