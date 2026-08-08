package dev.mkiros.perch.ui.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.data.repo.FolderRepository
import dev.mkiros.perch.data.repo.SourceResolution
import dev.mkiros.perch.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Why a pasted address did not become a source. Every one of them is a value the sheet
 * renders under the field, with the address still editable — never a toast, never a dead
 * end (DESIGN.md §5).
 */
sealed interface AddSourceError {

    /** Reachable, but neither a feed nor a page that leads to one. */
    data object NoFeedFound : AddSourceError

    /**
     * Could not be fetched at all. [message] comes from the fetcher already phrased for a
     * reader; it is null when something threw instead of failing politely.
     */
    data class Unreachable(val message: String?) : AddSourceError

    /** Already in the drawer, under [title]. */
    data class AlreadySubscribed(val title: String) : AddSourceError
}

/**
 * The sheet's whole state (DESIGN.md §5): one field, one button, and which of the three
 * things the button is currently doing.
 *
 * @param resolved non-null once the address has been fetched and parsed but *not* yet
 *   subscribed to — this is the confirmation step, and it is what the button commits.
 * @param isBusy a fetch or a commit is in flight; the button is a spinner.
 * @param addedFeedId the source that was just committed. The host watches this to close
 *   the sheet, and it is the only state that outlives the sheet.
 * @param folderId where the source will land (U06). Defaults to Uncategorized so the
 *   sheet is still one field and one button for a reader who does not use folders.
 */
data class AddSourceUiState(
    val url: String = "",
    val isBusy: Boolean = false,
    val resolved: SourceResolution.Resolved? = null,
    val error: AddSourceError? = null,
    val addedFeedId: Long? = null,
    val folderId: Long = FolderEntity.UNCATEGORIZED_ID,
) {
    /** Blank is not an address, and a second tap mid-flight is not a second source. */
    val canSubmit: Boolean get() = url.isNotBlank() && !isBusy
}

/**
 * Adding a source: paste → resolve → confirm → commit.
 *
 * The two halves of the button are the repository's two calls, and the split is the point
 * — resolving costs a round trip and tells the reader what they are about to follow;
 * committing spends nothing more, because [SourceResolution.Resolved] carries the entries
 * that resolving already fetched.
 */
class AddSourceViewModel(
    private val feeds: FeedRepository,
    private val folderRepository: FolderRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AddSourceUiState())
    val state: StateFlow<AddSourceUiState> = _state.asStateFlow()

    /**
     * The folders the sheet can file into, kept out of [AddSourceUiState] because they
     * belong to the drawer's world rather than to this sheet's paste-resolve-commit
     * progression — [reset] clears the progression and must not clear these.
     */
    val folders: StateFlow<List<FolderEntity>> = folderRepository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Files the source about to be committed under [folderId]. */
    fun onFolderChange(folderId: Long) {
        _state.update { it.copy(folderId = folderId) }
    }

    /**
     * Creates a folder from inside the sheet and selects it — following a source into a
     * folder that does not exist yet is the normal case, not a reason to close the sheet
     * and start again.
     */
    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { onFolderChange(folderRepository.createFolder(name)) }
    }

    /**
     * Editing withdraws the confirmation as well as the error: committing a resolution
     * from a keystroke ago would subscribe to an address no longer on screen.
     */
    fun onUrlChange(value: String) {
        _state.update { it.copy(url = value, error = null, resolved = null) }
    }

    /** Resolves what is in the field, or commits what resolving found. */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        val resolved = current.resolved
        if (resolved != null) commit(resolved) else resolve(current.url)
    }

    /** Clears the sheet for its next opening. */
    fun reset() {
        _state.value = AddSourceUiState()
    }

    private fun resolve(pasted: String) {
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val outcome = runCatching { feeds.resolve(normalizePastedUrl(pasted)) }
                .getOrElse { SourceResolution.Unreachable(it.message.orEmpty()) }
            _state.update { state ->
                when (outcome) {
                    is SourceResolution.Resolved -> state.copy(isBusy = false, resolved = outcome)
                    is SourceResolution.NoFeedFound ->
                        state.copy(isBusy = false, error = AddSourceError.NoFeedFound)
                    is SourceResolution.Unreachable -> state.copy(
                        isBusy = false,
                        error = AddSourceError.Unreachable(outcome.message.ifBlank { null }),
                    )
                    is SourceResolution.AlreadySubscribed -> state.copy(
                        isBusy = false,
                        error = AddSourceError.AlreadySubscribed(outcome.title),
                    )
                }
            }
        }
    }

    private fun commit(resolved: SourceResolution.Resolved) {
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val folderId = _state.value.folderId
            val added = runCatching { feeds.add(resolved, folderId) }
            _state.update { state ->
                added.fold(
                    onSuccess = { AddSourceUiState(addedFeedId = it) },
                    onFailure = {
                        state.copy(
                            isBusy = false,
                            error = AddSourceError.Unreachable(it.message?.ifBlank { null }),
                        )
                    },
                )
            }
        }
    }

    companion object {
        /** Five seconds outlives a rotation, so the folder query is not rebuilt. */
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                AddSourceViewModel(
                    feeds = container.feeds,
                    folderRepository = container.folders,
                )
            }
        }
    }
}
