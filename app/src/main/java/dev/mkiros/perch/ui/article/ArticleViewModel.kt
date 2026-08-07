package dev.mkiros.perch.ui.article

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the article screen has to show. */
sealed interface ArticleUiState {

    data object Loading : ArticleUiState

    /** The id no longer resolves — the source was removed, or retention collected the row. */
    data object Missing : ArticleUiState

    data class Loaded(val title: String, val summary: String?) : ArticleUiState
}

/**
 * One entry, loaded once by id.
 *
 * T25 grows this into the reading surface — lowering the sanitized body to `ArticleBlock`s,
 * marking read on open, and handing the link to a Custom Tab. The scaffold loads the title
 * so the route is demonstrably wired to real data rather than to its argument.
 */
class ArticleViewModel(
    entries: EntryRepository,
    entryId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<ArticleUiState>(ArticleUiState.Loading)
    val state: StateFlow<ArticleUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val entry = entries.find(entryId)
            _state.value = when (entry) {
                null -> ArticleUiState.Missing
                else -> ArticleUiState.Loaded(title = entry.title, summary = entry.summary)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, entryId: Long) = viewModelFactory {
            initializer { ArticleViewModel(container.entries, entryId) }
        }
    }
}
