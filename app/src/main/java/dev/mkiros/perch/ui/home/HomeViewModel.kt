package dev.mkiros.perch.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Home's state. The scaffold owns only the inbox badge; the entry list (T21), the source
 * drawer (T22) and refresh (T26) attach here as this screen grows.
 */
class HomeViewModel(entries: EntryRepository) : ViewModel() {

    /** Total unread, for the drawer's "All unread" row and the bar's subtitle. */
    val totalUnread: StateFlow<Int> = entries.observeTotalUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    companion object {
        /** Five seconds outlives a rotation, so the query is not torn down and rebuilt. */
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(container.entries) }
        }
    }
}
