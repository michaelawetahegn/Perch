package dev.mkiros.perch.ui.settings

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.settings.PerchSettings
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.theme.ThemeMode
import dev.mkiros.perch.work.RefreshInterval
import dev.mkiros.perch.work.WorkScheduler
import java.io.File
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * T27: Settings.
 *
 * Three claims, one per thing this screen can do that nothing else can. The interval test
 * goes all the way to WorkManager because a preference that agrees with itself and not
 * with the schedule is precisely the bug; the theme test asserts on the `ColorScheme` the
 * app is actually drawing with rather than on a stored enum; and the OPML tests assert on
 * the `Intent` that left the activity, because the storage-access framework is the part
 * this app does not own and therefore the part worth pinning.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var viewModel: SettingsViewModel

    /**
     * A real file-backed store: "persists" is the claim, so nothing here may be satisfied
     * by a value that only ever lived in memory. The directory is made here rather than by
     * a `TemporaryFolder` rule, because [perch] takes the store at construction time and
     * one rule may not assume another has already run.
     */
    private val storeDir: File = Files.createTempDirectory("perch-settings").toFile()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** The scheme the app is drawing with, sampled from inside the theme on every frame. */
    private var scheme: ColorScheme? = null

    @get:Rule(order = 1)
    val perch = PerchRule(
        settings = SettingsStore.at(File(storeDir, "settings.preferences_pb"), storeScope),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        storeDir.deleteRecursively()
    }

    // ---- refresh interval ---------------------------------------------------------

    @Test
    fun `choosing an interval persists it and moves the background schedule`() {
        showSettings()

        chooseInterval(RefreshInterval.Every6Hours)

        awaitSettings { it.refreshInterval == RefreshInterval.Every6Hours }
        val scheduled = periodicRefresh().single()
        assertThat(scheduled.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(scheduled.periodicityInfo!!.repeatIntervalMillis)
            .isEqualTo(Duration.ofHours(6).toMillis())
    }

    @Test
    fun `changing the interval twice leaves one schedule, not two`() {
        showSettings()

        chooseInterval(RefreshInterval.Every15Minutes)
        awaitSettings { it.refreshInterval == RefreshInterval.Every15Minutes }
        chooseInterval(RefreshInterval.Every3Hours)
        awaitSettings { it.refreshInterval == RefreshInterval.Every3Hours }

        assertThat(periodicRefresh()).hasSize(1)
        assertThat(periodicRefresh().single().periodicityInfo!!.repeatIntervalMillis)
            .isEqualTo(Duration.ofHours(3).toMillis())
    }

    @Test
    fun `choosing manual cancels the background schedule`() {
        WorkScheduler.setInterval(context, RefreshInterval.Hourly)
        showSettings()

        chooseInterval(RefreshInterval.Manual)

        awaitSettings { it.refreshInterval == RefreshInterval.Manual }
        assertThat(periodicRefresh()).isEmpty()
    }

    // ---- theme --------------------------------------------------------------------

    @Test
    fun `choosing dark repaints the app in the dark scheme`() {
        showSettings()
        val light = requireNotNull(scheme).background

        compose.onNodeWithTag(SettingsTags.THEME_ROW).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(label(ThemeMode.Dark.labelRes()))
            .performSemanticsAction(SemanticsActions.OnClick)
        awaitSettings { it.themeMode == ThemeMode.Dark }

        val dark = requireNotNull(scheme).background
        assertThat(dark).isNotEqualTo(light)
        // Not just "different": the surface a reader looks at has to have got darker.
        assertThat(luminance(dark)).isLessThan(luminance(light))
    }

    // ---- OPML over the storage-access framework -----------------------------------

    @Test
    fun `export asks the system to create an OPML document`() {
        showSettings()

        compose.onNodeWithTag(SettingsTags.EXPORT_ROW).performSemanticsAction(SemanticsActions.OnClick)

        val intent = nextSafIntent()
        assertThat(intent.action).isEqualTo(Intent.ACTION_CREATE_DOCUMENT)
        assertThat(intent.type).isEqualTo("text/xml")
        // The extension is what makes the file usable in another reader (SPEC.md §9).
        assertThat(intent.getStringExtra(Intent.EXTRA_TITLE)).endsWith(".opml")
    }

    @Test
    fun `import asks the system to open a document`() {
        showSettings()

        compose.onNodeWithTag(SettingsTags.IMPORT_ROW).performSemanticsAction(SemanticsActions.OnClick)

        val intent = nextSafIntent()
        assertThat(intent.action).isEqualTo(Intent.ACTION_OPEN_DOCUMENT)
        // Everything, deliberately: OPML arrives from other readers under whatever type a
        // cloud provider decided to label it, and the file is validated on read anyway.
        assertThat(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)).asList()
            .containsExactly("*/*")
    }

    // ---- the profile file (U14) ---------------------------------------------------

    @Test
    fun `exporting a profile asks the system to create a dated json document`() {
        showSettings()

        compose.onNodeWithTag(SettingsTags.PROFILE_EXPORT_ROW)
            .performSemanticsAction(SemanticsActions.OnClick)

        val intent = nextSafIntent()
        assertThat(intent.action).isEqualTo(Intent.ACTION_CREATE_DOCUMENT)
        assertThat(intent.type).isEqualTo("application/json")
        val name = intent.getStringExtra(Intent.EXTRA_TITLE)!!
        assertThat(name).startsWith("perch-profile-")
        assertThat(name).endsWith(".json")
    }

    @Test
    fun `importing a profile asks the system to open a document`() {
        showSettings()

        compose.onNodeWithTag(SettingsTags.PROFILE_IMPORT_ROW)
            .performSemanticsAction(SemanticsActions.OnClick)

        val intent = nextSafIntent()
        assertThat(intent.action).isEqualTo(Intent.ACTION_OPEN_DOCUMENT)
        assertThat(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)).asList()
            .containsExactly("*/*")
    }

    // ---- reading ------------------------------------------------------------------

    @Test
    fun `show read entries persists`() {
        showSettings()

        compose.onNodeWithTag(SettingsTags.SHOW_READ_SWITCH)
            .performSemanticsAction(SemanticsActions.OnClick)

        awaitSettings { it.showReadEntries }
    }

    // ---- harness ------------------------------------------------------------------

    private fun showSettings() {
        viewModel = SettingsViewModel(
            settings = perch.container.settings,
            opml = perch.container.opml,
            profile = perch.container.profile,
            feeds = perch.container.feeds,
            scheduler = { interval -> WorkScheduler.setInterval(context, interval) },
        )
        compose.setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            // Pinned to the fallback palette so the assertion is about the mode, not about
            // whatever wallpaper the host device happens to have.
            PerchTheme(mode = state.settings.themeMode, dynamicColor = false) {
                scheme = MaterialTheme.colorScheme
                SettingsScreen(viewModel = viewModel, onBack = {})
            }
        }
        compose.waitForIdle()
    }

    private fun chooseInterval(interval: RefreshInterval) {
        compose.onNodeWithTag(SettingsTags.INTERVAL_ROW)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(label(interval.labelRes())).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun periodicRefresh(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkScheduler.UNIQUE_NAME).get()
            .filter { it.state != WorkInfo.State.CANCELLED }

    private fun nextSafIntent(): Intent {
        compose.waitForIdle()
        val started = shadowOf(compose.activity).nextStartedActivityForResult
        return requireNotNull(started) { "no activity was started for a result" }.intent
    }

    /** A DataStore write lands on its own thread, so this waits in wall-clock time. */
    private fun awaitSettings(predicate: (PerchSettings) -> Boolean) =
        compose.awaitInRealTime("stored settings matching the test's predicate") {
            predicate(runBlocking { perch.container.settings.settings.first() })
        }

    private fun label(resId: Int): String = context.getString(resId)

    /** Rec. 709 relative luminance, enough to tell a dark scheme from a light one. */
    private fun luminance(color: androidx.compose.ui.graphics.Color): Float =
        0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
}
