package dev.mkiros.perch.ui.article

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.extract.FullText
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.ArticleLowering
import dev.mkiros.perch.data.repo.ArticleTextRepository
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.di.AppContainer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the article screen has to show. */
sealed interface ArticleUiState {

    data object Loading : ArticleUiState

    /** The id no longer resolves — the source was removed, or retention collected the row. */
    data object Missing : ArticleUiState

    /**
     * @param standfirst the summary, but only when it says something the body does not.
     * @param byline `SOURCE · AUTHOR · 3 AUG 2026`, already uppercased.
     * @param blocks the lowered body; empty means the feed carried no full text.
     * @param summary kept separately from [standfirst] because the empty-body fallback
     *   shows it even when it would have been redundant beside a body.
     * @param isSaved on the *Read later* queue, and [isLiked] *Liked* (U09). They are on
     *   the loaded state rather than in a flow of their own so the two top-bar toggles
     *   cannot draw a state the rest of the screen has not caught up with.
     * @param isFetchingFullText Perch is off getting the article from the entry's own page
     *   (U10). The body stays on screen while it runs — whatever the feed gave us is what
     *   there is to read until something better arrives.
     * @param canLoadFullText whether *Load full article* is offered: true whenever this
     *   body did **not** come from an extraction. The trigger is a heuristic and will
     *   sometimes decide a stub is an article, so the reader is never stuck with one.
     */
    data class Loaded(
        val title: String,
        val standfirst: String?,
        val byline: String,
        val blocks: List<ArticleBlock>,
        val summary: String?,
        val link: String?,
        val isSaved: Boolean = false,
        val isLiked: Boolean = false,
        val isFetchingFullText: Boolean = false,
        val canLoadFullText: Boolean = false,
    ) : ArticleUiState
}

/**
 * One entry, loaded once by id and lowered to blocks off the main thread.
 *
 * Opening is what marks read — there is no scroll threshold and no explicit button,
 * because a reader who opened an entry has dealt with it (DESIGN.md §5). The flip is
 * fire-and-forget: the article screen never reads `isRead` back, so nothing here waits
 * on the write, and the home list re-emits on its own when Room notices.
 */
class ArticleViewModel(
    private val entries: EntryRepository,
    private val feeds: FeedRepository,
    private val articleText: ArticleTextRepository,
    private val entryId: Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val _state = MutableStateFlow<ArticleUiState>(ArticleUiState.Loading)
    val state: StateFlow<ArticleUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val entry = entries.find(entryId)
            if (entry == null) {
                _state.value = ArticleUiState.Missing
                return@launch
            }
            val source = feeds.find(entry.feedId)
            byline = byline(
                source = source?.let { it.customTitle ?: it.title },
                author = entry.author,
                publishedAt = entry.publishedAt,
            )
            _state.value = loaded(entry)
            entries.setRead(entryId, isRead = true)

            // U10. The body is already on screen, so this is never a wait the reader is
            // held behind — worst case they read the excerpt while the article arrives.
            // An entry whose body already came from an extraction is left alone: the page
            // has been read once and re-reading it would say the same thing.
            if (entry.fullTextAt == null &&
                FullText.needsExtraction(entry.contentHtml, entry.bodyIsExcerpt)
            ) {
                fetchFullText()
            }
        }
    }

    /**
     * *Load full article*, from the overflow (U10).
     *
     * The trigger is a heuristic: it will sometimes read a genuinely short post as a stub,
     * and sometimes miss an excerpt dressed up to look like an article. This is the way
     * out of the second case, and it is why the action is offered whenever the body did
     * not already come from an extraction rather than only when the heuristic fired.
     */
    fun loadFullArticle() {
        val loaded = _state.value as? ArticleUiState.Loaded ?: return
        if (loaded.isFetchingFullText || !loaded.canLoadFullText) return
        viewModelScope.launch { fetchFullText() }
    }

    private suspend fun fetchFullText() {
        update { it.copy(isFetchingFullText = true) }
        val recovered = runCatching { articleText.loadFullText(entryId) }.getOrNull()
        // A failed or thinner extraction returns null and changes nothing, which is the
        // point: the reader keeps whatever the feed gave them, and the "Read on the web"
        // fallback is still there under an empty body.
        if (recovered == null) {
            update { it.copy(isFetchingFullText = false) }
        } else {
            _state.value = loaded(recovered)
        }
    }

    private fun loaded(entry: EntryEntity): ArticleUiState.Loaded {
        val blocks = ArticleLowering.toBlocks(entry.contentHtml)
        return ArticleUiState.Loaded(
            title = entry.title,
            standfirst = standfirst(entry.summary, blocks),
            byline = byline,
            blocks = blocks,
            summary = entry.summary?.takeIf { it.isNotBlank() },
            link = entry.link,
            isSaved = entry.isSaved,
            isLiked = entry.isStarred,
            isFetchingFullText = false,
            canLoadFullText = entry.fullTextAt == null && entry.link != null,
        )
    }

    private inline fun update(block: (ArticleUiState.Loaded) -> ArticleUiState.Loaded) {
        val current = _state.value as? ArticleUiState.Loaded ?: return
        _state.value = block(current)
    }

    /** Computed once from the feed and the entry; re-lowering a body does not change it. */
    private var byline: String = ""

    /**
     * The top bar's *Read later* toggle (U09).
     *
     * The flag is echoed into [_state] as well as written, rather than re-read from the
     * database: this screen loads once by id and has no flow behind it, so a toggle that
     * only wrote would leave the icon showing the state the reader just left. The write is
     * the truth; this is the same value, applied to the copy on screen.
     */
    fun toggleSaved() {
        val loaded = _state.value as? ArticleUiState.Loaded ?: return
        val next = !loaded.isSaved
        _state.value = loaded.copy(isSaved = next)
        viewModelScope.launch { entries.setSaved(entryId, next) }
    }

    /** *Liked*, the same way. */
    fun toggleLiked() {
        val loaded = _state.value as? ArticleUiState.Loaded ?: return
        val next = !loaded.isLiked
        _state.value = loaded.copy(isLiked = next)
        viewModelScope.launch { entries.setLiked(entryId, next) }
    }

    /**
     * Most feeds derive `summary` by truncating the body, so running it above a body that
     * opens with the same words reads as a stutter. The standfirst earns its place only
     * when it is not the opening of what follows.
     *
     * The comparison is against the body's opening *prose*, not its first block: the
     * summary is flattened text, so 300 characters of it routinely span a heading and two
     * or three paragraphs, and a first-block-only check misses every one of those. It
     * stops at the first block that is not prose — an image or a code listing is a place
     * the summary could not have come from, so anything past it is not a repetition.
     */
    private fun standfirst(summary: String?, blocks: List<ArticleBlock>): String? {
        val text = summary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (blocks.isEmpty()) return null
        val probe = text.removeSuffix(ELLIPSIS).collapsed()
        return text.takeUnless { probe.isNotEmpty() && opening(blocks, probe.length).startsWith(probe) }
    }

    /**
     * At least [minChars] of the body as flat text, the way `summarize` flattens it —
     * every block's words in order, joined by a single space.
     *
     * A block that carries no text is *skipped*, not a stopping point. A figure dropped
     * mid-sentence is how a source with inline diagrams writes, and the summary reads
     * straight through it; stopping there would leave the comparison short and print the
     * lede twice.
     */
    private fun opening(blocks: List<ArticleBlock>, minChars: Int): String = buildString {
        for (block in blocks) {
            val prose = when (block) {
                is ArticleBlock.Paragraph -> block.text.text
                is ArticleBlock.Heading -> block.text.text
                is ArticleBlock.Code -> block.text
                is ArticleBlock.ListBlock -> block.items.joinToString(" ") { it.text }
                is ArticleBlock.Table ->
                    (block.header + block.rows.flatten()).joinToString(" ") { it.text }
                // A pull-quote is prose the summary flattens like any other, and a lede
                // that runs into the article's opening quotation is a common shape.
                is ArticleBlock.Quote -> opening(block.blocks, minChars)
                is ArticleBlock.Image, is ArticleBlock.Unsupported, ArticleBlock.Rule -> ""
            }.collapsed()
            if (prose.isEmpty()) continue
            if (isNotEmpty()) append(' ')
            append(prose)
            if (length >= minChars) break
        }
    }

    private fun String.collapsed(): String = trim().replace(WHITESPACE, " ")

    /**
     * `SOURCE · AUTHOR · 3 AUG 2026`, with the parts a given entry lacks simply absent —
     * a byline reading `· · 3 AUG 2026` is worse than a short one.
     */
    private fun byline(source: String?, author: String?, publishedAt: Long): String {
        val date = DATE.format(Instant.ofEpochMilli(publishedAt).atZone(zone))
        // A one-author blog puts its own name in both fields; saying it twice looks broken.
        val parts = listOfNotNull(source, author.takeUnless { it.equals(source, ignoreCase = true) }, date)
        return parts.filter { it.isNotBlank() }.joinToString(SEPARATOR).uppercase(Locale.getDefault())
    }

    companion object {
        fun factory(container: AppContainer, entryId: Long) = viewModelFactory {
            initializer {
                ArticleViewModel(
                    entries = container.entries,
                    feeds = container.feeds,
                    articleText = container.articleText,
                    entryId = entryId,
                )
            }
        }

        private const val SEPARATOR = " · "
        private const val ELLIPSIS = "…"
        private val WHITESPACE = Regex("""\s+""")

        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    }
}
