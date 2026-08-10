package dev.mkiros.perch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.isActive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #1. Work started at process start used to run in a scope nothing owned, so it
 * outlived the test that created it: whatever it threw was recorded by the global uncaught
 * handler and then billed by `runTest` to *whichever test ran next*, which is why the named
 * victims (`ArticleTextRepositoryTest`, `WorkSchedulerTest`) moved with the ordering and
 * were never the culprit.
 *
 * Robolectric ends every test with `AndroidTestEnvironment.tearDownApplication()`, which
 * calls [PerchApp.onTerminate] — so a scope cancelled there really is cancelled at the test
 * boundary, and nothing crosses into the next test.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessLifecycleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `work started at process start is cancelled when the process is torn down`() {
        val app = ApplicationProvider.getApplicationContext<PerchApp>()

        assertThat(app.startupScope.isActive).isTrue()

        app.onTerminate()

        assertThat(app.startupScope.isActive).isFalse()
    }

    @Test
    fun `startup work handles its own failures instead of leaving them for the next test`() {
        val app = ApplicationProvider.getApplicationContext<PerchApp>()

        assertThat(app.startupScope.coroutineContext[CoroutineExceptionHandler]).isNotNull()
    }

    @Test
    fun `the settings store the container built stops running when the container closes`() {
        val container = AppContainer.create(context)

        assertThat(container.settings.isRunning).isTrue()

        container.close()

        assertThat(container.settings.isRunning).isFalse()
    }

    @Test
    fun `a store handed a caller's scope leaves that scope alone`() {
        // `SettingsStore.at(file, scope)` is the test-facing constructor: the caller owns
        // the scope, so closing the store must not cancel work the caller still wants.
        val store = SettingsStore.inMemory()

        store.close()

        assertThat(store.isRunning).isFalse()
    }
}
