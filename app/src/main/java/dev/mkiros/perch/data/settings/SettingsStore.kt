package dev.mkiros.perch.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.mkiros.perch.ui.home.TimeFilter
import dev.mkiros.perch.ui.theme.ThemeMode
import dev.mkiros.perch.work.RefreshInterval
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Every preference the reader owns, as one value.
 *
 * One record rather than three flows because two of the three are read together on every
 * frame of home, and because the defaults are then stated once: a fresh install polls
 * hourly, follows the system's dark mode, and shows an unread inbox.
 */
data class PerchSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val refreshInterval: RefreshInterval = RefreshInterval.Default,
    /** When true the list keeps entries after they are read, instead of an unread inbox. */
    val showReadEntries: Boolean = false,
    /**
     * How far back home reaches (U07). Persisted rather than remembered in the view model
     * because the range is the setting a reader changes most often, and coming back to an
     * app that has quietly reset it to Today is how a reader loses their place.
     */
    val timeFilter: TimeFilter = TimeFilter.Default,
)

/**
 * The preferences file (SPEC.md §2 — DataStore, not `SharedPreferences`).
 *
 * Enums are persisted by `name` and decoded leniently: a value written by a future build
 * that renamed a constant reads back as the default rather than throwing on the reader's
 * next launch. A corrupt or unreadable file does the same — settings are not worth a
 * crash loop, and [settings] is the flow the whole UI hangs off.
 */
class SettingsStore(private val store: DataStore<Preferences>) {

    val settings: Flow<PerchSettings> = store.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs ->
            PerchSettings(
                themeMode = prefs[THEME_MODE].toEnum(ThemeMode.entries, ThemeMode.System),
                refreshInterval = prefs[REFRESH_INTERVAL]
                    .toEnum(RefreshInterval.entries, RefreshInterval.Default),
                showReadEntries = prefs[SHOW_READ_ENTRIES] ?: false,
                timeFilter = prefs[TIME_FILTER]
                    .toEnum(TimeFilter.entries, TimeFilter.Default),
            )
        }
        .distinctUntilChanged()

    /** One read, for a caller that is not observing — startup's scheduling pass. */
    suspend fun current(): PerchSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setRefreshInterval(interval: RefreshInterval) {
        store.edit { it[REFRESH_INTERVAL] = interval.name }
    }

    suspend fun setShowReadEntries(show: Boolean) {
        store.edit { it[SHOW_READ_ENTRIES] = show }
    }

    suspend fun setTimeFilter(filter: TimeFilter) {
        store.edit { it[TIME_FILTER] = filter.name }
    }

    companion object {
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val REFRESH_INTERVAL = stringPreferencesKey("refresh_interval")
        private val SHOW_READ_ENTRIES = booleanPreferencesKey("show_read_entries")
        private val TIME_FILTER = stringPreferencesKey("time_filter")

        /** The real one, backed by a file in the app's data directory. */
        fun create(context: Context): SettingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            ),
        )

        /** A store over [file], so a test can prove a write survives a new instance. */
        fun at(file: File, scope: CoroutineScope): SettingsStore =
            SettingsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))

        /**
         * A store that keeps nothing. The [dev.mkiros.perch.di.AppContainer] default, so
         * that the many tests which are not *about* settings need no temp directory and no
         * file lifecycle — and so that two of them running at once cannot share state.
         */
        fun inMemory(): SettingsStore = SettingsStore(InMemoryPreferences())

        private const val FILE_NAME = "perch-settings"

        private fun <T : Enum<T>> String?.toEnum(values: List<T>, fallback: T): T =
            values.firstOrNull { it.name == this } ?: fallback
    }
}

/** Minimal [DataStore] over a `StateFlow`; the mutex gives `updateData` its atomicity. */
private class InMemoryPreferences : DataStore<Preferences> {

    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = mutex.withLock {
        transform(state.value).also { state.value = it }
    }
}
