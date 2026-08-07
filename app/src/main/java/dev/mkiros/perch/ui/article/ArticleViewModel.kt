package dev.mkiros.perch.ui.article

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.ArticleLowering
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
     */
    data class Loaded(
        val title: String,
        val standfirst: String?,
        val byline: String,
        val blocks: List<ArticleBlock>,
        val summary: String?,
        val link: String?,
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
    entryId: Long,
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
            val blocks = ArticleLowering.toBlocks(entry.contentHtml)
            _state.value = ArticleUiState.Loaded(
                title = entry.title,
                standfirst = standfirst(entry.summary, blocks),
                byline = byline(
                    source = source?.let { it.customTitle ?: it.title },
                    author = entry.author,
                    publishedAt = entry.publishedAt,
                ),
                blocks = blocks,
                summary = entry.summary?.takeIf { it.isNotBlank() },
                link = entry.link,
            )
            entries.setRead(entryId, isRead = true)
        }
    }

    /**
     * Most feeds derive `summary` by truncating the first paragraph, so running it above
     * a body that opens with the same words reads as a stutter. The standfirst earns its
     * place only when it is not the opening of what follows.
     */
    private fun standfirst(summary: String?, blocks: List<ArticleBlock>): String? {
        val text = summary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (blocks.isEmpty()) return null
        val opening = (blocks.first() as? ArticleBlock.Paragraph)?.text?.text?.trim().orEmpty()
        return text.takeUnless { opening.startsWith(it.removeSuffix(ELLIPSIS)) }
    }

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
            initializer { ArticleViewModel(container.entries, container.feeds, entryId) }
        }

        private const val SEPARATOR = " · "
        private const val ELLIPSIS = "…"

        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    }
}
