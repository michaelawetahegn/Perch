package dev.mkiros.perch.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mkiros.perch.BuildConfig
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.data.repo.OpmlImportResult
import dev.mkiros.perch.data.repo.OpmlRepository
import dev.mkiros.perch.data.repo.ProfileImportResult
import dev.mkiros.perch.data.repo.ProfileRepository
import dev.mkiros.perch.data.settings.PerchSettings
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.theme.ThemeMode
import dev.mkiros.perch.work.RefreshInterval
import dev.mkiros.perch.work.WorkScheduler
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What Settings is showing: the persisted preferences, plus the two things the screen
 * needs that are not preferences — the version line and the name to hand the SAF
 * create-document dialog.
 */
data class SettingsUiState(
    val settings: PerchSettings = PerchSettings(),
    val versionName: String = BuildConfig.VERSION_NAME,
    val exportFileName: String = "",
    /** U14's profile file name; separate from [exportFileName], which is the OPML one. */
    val profileFileName: String = "",
)

/**
 * The outcome of an OPML transfer, as data rather than as a phrased string — the wording
 * lives in `strings.xml` where it can be translated and where a plural is a plural.
 */
sealed interface SettingsMessage {

    /** @param folders how many folders the file brought that the library did not have (U13). */
    data class Imported(
        val added: Int,
        val duplicates: Int,
        val invalid: Int,
        val folders: Int = 0,
    ) : SettingsMessage

    /** The file parsed as something other than OPML; nothing was written. */
    data class ImportRejected(val reason: String) : SettingsMessage

    /** The document could not be read or written at all — a revoked permission, a full disk. */
    data object TransferFailed : SettingsMessage

    data object Exported : SettingsMessage

    data object ProfileExported : SettingsMessage

    /**
     * A profile came back (U14). [pending] is stated rather than hidden: on a fresh install
     * every entry's state is waiting for the first refresh, and a reader who is told
     * "0 entries restored" would reasonably conclude the file was empty.
     */
    data class ProfileRestored(
        val sources: Int,
        val folders: Int,
        val applied: Int,
        val pending: Int,
    ) : SettingsMessage

    /** The file was not a Perch profile at all. Nothing was written. */
    data class ProfileRejected(val reason: String) : SettingsMessage

    /**
     * The file came from a later version of Perch. Its own message rather than a [reason]
     * inside [ProfileRejected], because it is the one rejection with an obvious remedy —
     * update the app — and telling the reader that is the whole point of refusing.
     */
    data class ProfileTooNew(val found: Int, val supported: Int) : SettingsMessage
}

/**
 * Applies a chosen interval to the background schedule. A seam rather than a direct call
 * so that the ViewModel does not hold a `Context`, and so a test can assert scheduling
 * without WorkManager if it only cares about persistence.
 */
fun interface RefreshScheduler {
    fun schedule(interval: RefreshInterval)
}

/**
 * Settings (SPEC.md §9, DESIGN.md §5).
 *
 * Two rules shape this class. **Preferences are written, never held**: every setter edits
 * the store and returns, and the UI re-renders from [uiState], which is the store's flow —
 * so there is no second copy of the truth to drift, and a change made here reaches home
 * and the theme without an event. And **the document I/O is the caller's**: [exportOpml]
 * and [importOpml] take the read/write half as a lambda, so the ViewModel never touches a
 * `Uri` or a `ContentResolver` and the screen never touches OPML.
 */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val opml: OpmlRepository,
    private val profile: ProfileRepository,
    private val feeds: FeedRepository,
    private val scheduler: RefreshScheduler,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = settings.settings
        .map {
            SettingsUiState(
                settings = it,
                exportFileName = opml.suggestedFileName(),
                profileFileName = profile.suggestedFileName(),
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            SettingsUiState(
                exportFileName = opml.suggestedFileName(),
                profileFileName = profile.suggestedFileName(),
            ),
        )

    private val _message = MutableStateFlow<SettingsMessage?>(null)

    /** The last transfer's outcome, until the snackbar showing it is done with it. */
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setShowReadEntries(show: Boolean) {
        viewModelScope.launch { settings.setShowReadEntries(show) }
    }

    /**
     * Persists the interval *and* moves the schedule.
     *
     * Both, in that order, in one place: a preference that no longer matches what
     * WorkManager is running is the bug this whole method exists to prevent, and the
     * schedule is the half that survives the process, so it is written after the value
     * that a restart would otherwise re-derive it from.
     */
    fun setRefreshInterval(interval: RefreshInterval) {
        viewModelScope.launch {
            settings.setRefreshInterval(interval)
            scheduler.schedule(interval)
        }
    }

    /** Writes every subscription as OPML through [write], on the I/O dispatcher. */
    fun exportOpml(write: suspend (String) -> Unit) {
        viewModelScope.launch {
            val text = opml.export()
            _message.value = try {
                withContext(Dispatchers.IO) { write(text) }
                SettingsMessage.Exported
            } catch (e: IOException) {
                SettingsMessage.TransferFailed
            }
        }
    }

    /**
     * Imports whatever [read] yields, then triggers the one refresh SPEC.md §9 asks for.
     *
     * The refresh is deliberately not awaited and cannot fail the import: the rows are
     * already in the database, the reader is already being told how many landed, and forty
     * fetches must not hold a snackbar hostage.
     */
    fun importOpml(read: suspend () -> String) {
        viewModelScope.launch {
            val text = try {
                withContext(Dispatchers.IO) { read() }
            } catch (e: IOException) {
                _message.value = SettingsMessage.TransferFailed
                return@launch
            }
            when (val result = opml.import(text)) {
                is OpmlImportResult.Malformed ->
                    _message.value = SettingsMessage.ImportRejected(result.message)

                is OpmlImportResult.Imported -> {
                    _message.value = SettingsMessage.Imported(
                        added = result.added,
                        duplicates = result.duplicates,
                        invalid = result.invalid,
                        folders = result.folders,
                    )
                    if (result.added > 0) refreshImported()
                }
            }
        }
    }

    /** Writes the whole reading identity through [write], on the I/O dispatcher (U14). */
    fun exportProfile(write: suspend (String) -> Unit) {
        viewModelScope.launch {
            val text = profile.export()
            _message.value = try {
                withContext(Dispatchers.IO) { write(text) }
                SettingsMessage.ProfileExported
            } catch (e: IOException) {
                SettingsMessage.TransferFailed
            }
        }
    }

    /**
     * Restores whatever [read] yields, then refreshes.
     *
     * The refresh is not optional garnish here the way it is for OPML: a restored profile's
     * entry state is parked until the articles it describes arrive, and this is what makes
     * them arrive. It still cannot fail the restore — the rows are already written and the
     * next scheduled pass would collect them anyway.
     */
    fun importProfile(read: suspend () -> String) {
        viewModelScope.launch {
            val text = try {
                withContext(Dispatchers.IO) { read() }
            } catch (e: IOException) {
                _message.value = SettingsMessage.TransferFailed
                return@launch
            }
            when (val result = profile.import(text)) {
                is ProfileImportResult.Malformed ->
                    _message.value = SettingsMessage.ProfileRejected(result.message)

                is ProfileImportResult.UnsupportedVersion ->
                    _message.value = SettingsMessage.ProfileTooNew(result.found, result.supported)

                is ProfileImportResult.Restored -> {
                    _message.value = SettingsMessage.ProfileRestored(
                        sources = result.sourcesAdded,
                        folders = result.foldersCreated,
                        applied = result.stateApplied,
                        pending = result.statePending,
                    )
                    refreshImported()
                }
            }
        }
    }

    /** The post-import poll. Its failures belong to the drawer's `⚠`, not to a snackbar. */
    private fun refreshImported() {
        viewModelScope.launch {
            runCatching { feeds.refreshAll() }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * @param context whatever composed the screen; only its application context is
         *   retained, and only to reach WorkManager.
         */
        fun factory(container: AppContainer, context: Context) = viewModelFactory {
            initializer {
                val app = context.applicationContext
                SettingsViewModel(
                    settings = container.settings,
                    opml = container.opml,
                    profile = container.profile,
                    feeds = container.feeds,
                    scheduler = { interval -> WorkScheduler.setInterval(app, interval) },
                )
            }
        }
    }
}
