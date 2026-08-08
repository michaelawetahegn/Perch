package dev.mkiros.perch.data.settings

import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.ui.home.TimeFilter
import dev.mkiros.perch.ui.theme.ThemeMode
import dev.mkiros.perch.work.RefreshInterval
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The preferences file. Pure JVM — DataStore over a `File` needs no Android.
 *
 * Every test writes through one store and reads back through a **second** one over the
 * same file, because "persists" is the claim: a store that only agreed with its own
 * in-memory copy would pass an assertion made on the instance that did the writing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File get() = File(folder.root, "settings.preferences_pb")

    @Test
    fun `a fresh install polls hourly, follows the system theme, and shows an unread inbox`() =
        runTest {
            val settings = withStore { it.settings.first() }

            assertThat(settings.refreshInterval).isEqualTo(RefreshInterval.Hourly)
            assertThat(settings.themeMode).isEqualTo(ThemeMode.System)
            assertThat(settings.showReadEntries).isFalse()
            assertThat(settings.timeFilter).isEqualTo(TimeFilter.Today)
        }

    @Test
    fun `a chosen refresh interval survives a restart`() = runTest {
        withStore { it.setRefreshInterval(RefreshInterval.Every6Hours) }

        val reopened = withStore { it.settings.first() }

        assertThat(reopened.refreshInterval).isEqualTo(RefreshInterval.Every6Hours)
    }

    @Test
    fun `a chosen theme survives a restart`() = runTest {
        withStore { it.setThemeMode(ThemeMode.Dark) }

        assertThat(withStore { it.settings.first() }.themeMode).isEqualTo(ThemeMode.Dark)
    }

    @Test
    fun `show read entries survives a restart`() = runTest {
        withStore { it.setShowReadEntries(true) }

        assertThat(withStore { it.settings.first() }.showReadEntries).isTrue()
    }

    @Test
    fun `the chosen time window survives a restart`() = runTest {
        // The chip is the one setting the reader changes several times a session, so it
        // has to come back the way they left it — including across process death.
        withStore { it.setTimeFilter(TimeFilter.PastMonth) }

        assertThat(withStore { it.settings.first() }.timeFilter).isEqualTo(TimeFilter.PastMonth)
    }

    @Test
    fun `each setting is written independently of the others`() = runTest {
        withStore { it.setThemeMode(ThemeMode.Light) }
        withStore { it.setShowReadEntries(true) }

        val settings = withStore { it.settings.first() }

        assertThat(settings.themeMode).isEqualTo(ThemeMode.Light)
        assertThat(settings.showReadEntries).isTrue()
        // Untouched, so still the default rather than whatever the last write cleared.
        assertThat(settings.refreshInterval).isEqualTo(RefreshInterval.Hourly)
    }

    @Test
    fun `a value written by a build that has since renamed the constant reads as the default`() =
        runTest {
            // Exactly what an older or newer install would leave behind: a valid file
            // holding a name this build no longer has a constant for. Same length, so the
            // file stays a well-formed protobuf and it is the *decode* under test, not
            // the corruption path the next test covers.
            withStore { it.setRefreshInterval(RefreshInterval.Every3Hours) }
            val bytes = file.readText(Charsets.ISO_8859_1)
            file.writeText(bytes.replace("Every3Hours", "Every9Hours"), Charsets.ISO_8859_1)

            assertThat(withStore { it.settings.first() }.refreshInterval)
                .isEqualTo(RefreshInterval.Hourly)
        }

    @Test
    fun `a corrupt file reads as the defaults rather than throwing`() = runTest {
        withStore { it.setThemeMode(ThemeMode.Dark) }
        file.writeText("this is not a protobuf")

        assertThat(withStore { it.settings.first() }).isEqualTo(PerchSettings())
    }

    /**
     * Runs [block] against a store opened on [file], then closes its scope.
     *
     * DataStore refuses a second active instance over the same file within a process, so
     * the scope's lifetime *is* the instance's lifetime — cancelling it is what makes the
     * next call a genuinely new store rather than the same one handed back.
     */
    private suspend fun <T> withStore(block: suspend (SettingsStore) -> T): T {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        return try {
            block(SettingsStore.at(file, scope))
        } finally {
            scope.cancel()
        }
    }
}
