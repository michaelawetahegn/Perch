package dev.mkiros.perch.support

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.model.BackfillRunner
import org.junit.rules.ExternalResource
import java.time.Clock

/**
 * Opens an in-memory database, builds an [AppContainer] over it, and closes both again.
 *
 * Twenty-two tests used to carry the same fourteen-line `setUp`/`tearDown` pair (D10 /
 * issue #43); this is that pair, once. A test that needs a fixed [clock] or a settings
 * store of its own passes it here and changes nothing else:
 *
 * ```
 * @get:Rule(order = 1) val perch = PerchRule(clock = clock, settings = settings)
 * ```
 *
 * **`order = 1` is load-bearing wherever a Compose rule shares the class**, which is why
 * it is part of the idiom rather than a detail. JUnit applies the *highest* order first,
 * so a higher number is the *inner* rule: this one has to close the database inside the
 * Compose test environment, exactly where the hand-written `@After` closed it. Left to the
 * default the two rules tie, the container closes after the environment has gone, and a
 * coroutine a screen left running — a `viewModelScope` nothing cancels — reaches a closed
 * connection pool with no test scope left to bill it to. JUnit then reports it against
 * whichever test runs next (seen as `UncaughtExceptionsBeforeTest` in `BackfillOfferTest`).
 *
 * [close][AppContainer.close] rather than a bare `database.close()`, because the container
 * also owns the settings store's writer scope (issue #1).
 */
class PerchRule(
    private val clock: Clock = Clock.systemDefaultZone(),
    val settings: SettingsStore = SettingsStore.inMemory(),
    /** D22: the Feed's WorkManager seam is the container's, so a fake arrives the same way. */
    private val backfillRunner: BackfillRunner = BackfillRunner.NoOp,
) : ExternalResource() {

    /** Valid from the rule's `before` — that is, from `@Before` onwards. */
    lateinit var container: AppContainer
        private set

    val database: PerchDatabase get() = container.database

    override fun before() {
        container = AppContainer(
            database = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>()),
            httpClient = PerchHttp.client(cacheDir = null),
            clock = clock,
            settings = settings,
            backfillRunner = backfillRunner,
        )
    }

    override fun after() {
        container.close()
    }
}
