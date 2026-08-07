package dev.mkiros.perch.ui.nav

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.theme.PerchTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock

/**
 * The shell: three routes reachable, none of them crashing, and back meaning back.
 *
 * The screens themselves are still stubs (T21–T27 fill them in), so what is asserted here
 * is only what the scaffold owns — that a destination composes with a real [AppContainer]
 * behind it, and that the system back gesture unwinds the stack rather than leaving the
 * reader stranded on an article.
 *
 * It lives in `src/testDebug`, not `src/test`: the empty `ComponentActivity` it hosts the graph
 * in comes from `ui-test-manifest`, a `debugImplementation`, so the release unit-test variant
 * has no such activity in its merged manifest. Every Compose test belongs in this source set.
 */
@RunWith(RobolectricTestRunner::class)
class PerchNavHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PerchDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        container = AppContainer(
            database = database,
            httpClient = PerchHttp.client(cacheDir = null),
            clock = Clock.systemUTC(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `home is the start destination`() {
        showNavHost()

        assertThat(currentRoute()).isEqualTo(Routes.HOME)
        compose.onNodeWithText("Unread").assertExists()
    }

    @Test
    fun `the article route composes the entry it was given`() {
        val entryId = seedOneEntry(title = "A standardized reading surface")
        showNavHost()

        navigateTo(Routes.article(entryId))

        assertThat(currentRoute()).isEqualTo(Routes.ARTICLE)
        compose.onNodeWithText("A standardized reading surface").assertExists()
    }

    @Test
    fun `the settings route composes`() {
        showNavHost()

        navigateTo(Routes.SETTINGS)

        assertThat(currentRoute()).isEqualTo(Routes.SETTINGS)
        compose.onNodeWithText("Settings").assertExists()
    }

    @Test
    fun `back from an article returns to home`() {
        val entryId = seedOneEntry(title = "Something to read")
        showNavHost()
        navigateTo(Routes.article(entryId))

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertThat(currentRoute()).isEqualTo(Routes.HOME)
    }

    private fun showNavHost() {
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                navController = rememberNavController()
                PerchNavHost(container = container, navController = navController)
            }
        }
        compose.waitForIdle()
    }

    private fun navigateTo(route: String) {
        compose.runOnUiThread { navController.navigate(route) }
        compose.waitForIdle()
    }

    private fun currentRoute(): String? = navController.currentDestination?.route

    /** Returns the new entry's id. */
    private fun seedOneEntry(title: String): Long = runBlocking {
        val feedId = database.feedDao().insert(
            FeedEntity(
                feedUrl = "https://example.com/feed.xml",
                siteUrl = "https://example.com",
                title = "Example",
                customTitle = null,
                faviconUrl = null,
                etag = null,
                lastModified = null,
                lastFetchedAt = null,
                lastSuccessAt = null,
                lastError = null,
                addedAt = 0L,
            ),
        )
        database.entryDao().insert(
            EntryEntity(
                feedId = feedId,
                guid = "guid-1",
                title = title,
                link = "https://example.com/post",
                author = null,
                publishedAt = 1_700_000_000_000L,
                publishedIsEstimated = false,
                summary = "A short summary.",
                contentHtml = "<p>A short summary.</p>",
                imageUrl = null,
                readAt = null,
                fetchedAt = 1_700_000_000_000L,
            ),
        )
    }
}
