package dev.mkiros.perch.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mkiros.perch.data.repo.SaveLinkFailure
import dev.mkiros.perch.data.repo.SavedLinkRepository
import dev.mkiros.perch.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The save-link sheet's whole state (PLAN-6 §0.4, Y04): one field, one button.
 *
 * Mirrors [dev.mkiros.perch.ui.source.AddSourceUiState] but one step shorter — there is
 * nothing to confirm, because saving a link never subscribes to anything;
 * [SavedLinkRepository.saveLink] fetches, extracts and files the row in the one call.
 *
 * @param error the reason the last submit did not save anything, straight off
 *   [SavedLinkRepository.saveLink]'s [Result.failure] rather than a copy of it (§0.4 —
 *   every disappointment is already a value, not an exception to catch and re-describe).
 * @param savedEntryId the row [SavedLinkRepository.saveLink] just wrote. The host watches
 *   this to close the sheet, the same as [dev.mkiros.perch.ui.source.AddSourceUiState
 *   .addedFeedId] does for a committed source.
 */
data class SaveLinkUiState(
    val url: String = "",
    val isBusy: Boolean = false,
    val error: SaveLinkFailure? = null,
    val savedEntryId: Long? = null,
) {
    /** Blank is not an address, and a second tap mid-flight is not a second save. */
    val canSubmit: Boolean get() = url.isNotBlank() && !isBusy
}

/**
 * Saving a link: paste → save. Only reachable from To-Read (§0.4); Liked has no field for
 * this because a link a reader pastes has not been read yet, and "Liked" only ever means
 * something the reader read and kept.
 */
class SaveLinkViewModel(
    private val savedLinks: SavedLinkRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SaveLinkUiState())
    val state: StateFlow<SaveLinkUiState> = _state.asStateFlow()

    /** Editing withdraws the error, the same as the add-source sheet does. */
    fun onUrlChange(value: String) {
        _state.update { it.copy(url = value, error = null) }
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val result = savedLinks.saveLink(current.url)
            _state.update { state ->
                result.fold(
                    onSuccess = { SaveLinkUiState(savedEntryId = it) },
                    onFailure = { failure ->
                        state.copy(
                            isBusy = false,
                            // saveLink only ever fails with SaveLinkFailure (its own KDoc);
                            // the fallback is defensive, not a path this exercises.
                            error = failure as? SaveLinkFailure
                                ?: SaveLinkFailure.Unreachable(failure.message.orEmpty()),
                        )
                    },
                )
            }
        }
    }

    /** Clears the sheet for its next opening. */
    fun reset() {
        _state.value = SaveLinkUiState()
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SaveLinkViewModel(container.savedLinks) }
        }
    }
}
