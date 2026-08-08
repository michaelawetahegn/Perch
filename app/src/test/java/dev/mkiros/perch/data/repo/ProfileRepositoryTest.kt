package dev.mkiros.perch.data.repo

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.profile.ProfileJson
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Profile backup and restore (U14).
 *
 * The claim is a whole reading identity in one file: a reinstall costs one import instead
 * of forty-two paste operations, and what comes back is not just the sources but the
 * folders they were filed under and everything the reader had read, liked and kept.
 *
 * Two things make this hard, and both are asserted here rather than argued about.
 * **Ordering:** a restore lands before the entries it describes exist, so state that has
 * nowhere to go yet is parked and applied when its entry next arrives — without that, a
 * restore followed by the refresh it obviously implies loses everything it just restored.
 * **Repetition:** a merge that is not idempotent turns "restore twice to be safe" into
 * duplicated sources, so the second run must change nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileRepositoryTest {

    private val now = Instant.parse("2026-08-08T12:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    private lateinit var server: MockWebServer
    private lateinit var origin: PerchDatabase

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = feedDispatcher()
        origin = newDatabase()
    }

    @After
    fun tearDown() {
        origin.close()
        server.shutdown()
    }

    // ---- the round trip ------------------------------------------------------------

    @Test
    fun `a restore brings back every source under the folder it was filed in`() = runTest {
        seedLibrary(origin)
        val text = origin.profiles().export()

        val phone = newDatabase()
        val result = phone.profiles().import(text)

        assertThat(result).isInstanceOf(ProfileImportResult.Restored::class.java)
        assertThat(phone.sourcesByFolder()).containsExactly(
            urlOf("feed-a"), "Graphics",
            urlOf("feed-b"), "Security",
            urlOf("feed-c"), FolderEntity.UNCATEGORIZED_NAME,
        )
        assertThat(phone.feedDao().findByUrl(urlOf("feed-a"))?.customTitle).isEqualTo("Alpha, renamed")
        phone.close()
    }

    /**
     * The headline: export → wipe → import → refresh leaves the same reader state on the
     * new install as on the old one. The refresh is a real one — MockWebServer, the actual
     * parser, `FeedRepository.refreshAll` — because the whole point is that the pass which
     * *writes* entries is the pass that has to notice the parked state.
     */
    @Test
    fun `read liked and saved survive a wipe and come back on the next refresh`() = runTest {
        seedLibrary(origin)
        markStateInOrigin()
        val text = origin.profiles().export()

        val phone = newDatabase()
        phone.profiles().import(text)
        phone.refreshAll()

        val a1 = phone.entryDao().findByGuid(phone.feedIdOf("feed-a"), "a1")!!
        assertThat(a1.isRead).isTrue()
        assertThat(a1.readAt).isEqualTo(READ_AT)
        val a2 = phone.entryDao().findByGuid(phone.feedIdOf("feed-a"), "a2")!!
        assertThat(a2.isSaved).isTrue()
        assertThat(a2.savedAt).isEqualTo(SAVED_AT)
        assertThat(a2.isStarred).isTrue()
        assertThat(a2.starredAt).isEqualTo(LIKED_AT)
        val b1 = phone.entryDao().findByGuid(phone.feedIdOf("feed-b"), "b1")!!
        assertThat(b1.isStarred).isTrue()
        assertThat(b1.isRead).isFalse()
        phone.close()
    }

    /**
     * The ordering trap, stated on its own. Between the import and the refresh the state
     * has nowhere to live — the entries it describes have not been fetched — so it is held
     * rather than dropped, and consumed exactly once when they arrive.
     */
    @Test
    fun `state for an entry that has not arrived yet is held until it does`() = runTest {
        seedLibrary(origin)
        markStateInOrigin()
        val text = origin.profiles().export()

        val phone = newDatabase()
        val result = phone.profiles().import(text) as ProfileImportResult.Restored

        assertThat(phone.entryDao().countAll()).isEqualTo(0)
        assertThat(result.stateApplied).isEqualTo(0)
        assertThat(result.statePending).isEqualTo(3)

        phone.refreshAll()

        assertThat(phone.entryDao().countPendingState()).isEqualTo(0)
        assertThat(phone.entryDao().findByGuid(phone.feedIdOf("feed-a"), "a1")?.isRead).isTrue()
        phone.close()
    }

    /** The other order: the entry is already there, so its state lands during the import. */
    @Test
    fun `state for an entry already present is applied by the import itself`() = runTest {
        seedLibrary(origin)
        markStateInOrigin()
        val text = origin.profiles().export()

        val phone = newDatabase()
        phone.profiles().import(text)
        phone.refreshAll()
        // Everything is now in place; a second import has entries to write straight to.
        phone.entryDao().setRead(listOf(phone.entryIdOf("feed-a", "a1")), isRead = false, readAt = null)

        val again = phone.profiles().import(text) as ProfileImportResult.Restored

        assertThat(again.stateApplied).isEqualTo(3)
        assertThat(again.statePending).isEqualTo(0)
        assertThat(phone.entryDao().findByGuid(phone.feedIdOf("feed-a"), "a1")?.isRead).isTrue()
        phone.close()
    }

    // ---- idempotence ---------------------------------------------------------------

    @Test
    fun `restoring twice equals restoring once`() = runTest {
        seedLibrary(origin)
        markStateInOrigin()
        val text = origin.profiles().export()
        val phone = newDatabase()
        phone.profiles().import(text)
        phone.refreshAll()
        val afterFirst = phone.snapshot()

        val second = phone.profiles().import(text) as ProfileImportResult.Restored

        assertThat(second.sourcesAdded).isEqualTo(0)
        assertThat(second.foldersCreated).isEqualTo(0)
        assertThat(phone.snapshot()).isEqualTo(afterFirst)
        phone.close()
    }

    @Test
    fun `a source already subscribed to keeps the folder it is already in`() = runTest {
        seedLibrary(origin)
        val text = origin.profiles().export()
        val phone = newDatabase()
        val moved = phone.folders().createFolder("Reading now")
        phone.feedDao().insert(sourceRow("feed-a", "Alpha", moved))

        val result = phone.profiles().import(text) as ProfileImportResult.Restored

        assertThat(result.sourcesAdded).isEqualTo(2)
        assertThat(result.sourcesExisting).isEqualTo(1)
        assertThat(phone.sourcesByFolder()[urlOf("feed-a")]).isEqualTo("Reading now")
        phone.close()
    }

    // ---- refusals ------------------------------------------------------------------

    @Test
    fun `a profile from a later version of Perch is refused and nothing is written`() = runTest {
        seedLibrary(origin)
        val text = origin.profiles().export().replace(
            "\"schemaVersion\": ${ProfileJson.SCHEMA_VERSION}",
            "\"schemaVersion\": ${ProfileJson.SCHEMA_VERSION + 1}",
        )

        val phone = newDatabase()
        val result = phone.profiles().import(text)

        assertThat(result).isEqualTo(
            ProfileImportResult.UnsupportedVersion(
                found = ProfileJson.SCHEMA_VERSION + 1,
                supported = ProfileJson.SCHEMA_VERSION,
            ),
        )
        assertThat(phone.feedDao().countAll()).isEqualTo(0)
        assertThat(phone.folderDao().getAll().map { it.name })
            .containsExactly(FolderEntity.UNCATEGORIZED_NAME)
        phone.close()
    }

    @Test
    fun `a file that is not a profile is refused without touching the library`() = runTest {
        val phone = newDatabase()

        val result = phone.profiles().import("<opml version=\"2.0\"><body/></opml>")

        assertThat(result).isInstanceOf(ProfileImportResult.Malformed::class.java)
        assertThat(phone.feedDao().countAll()).isEqualTo(0)
        phone.close()
    }

    @Test
    fun `the suggested file name is dated`() {
        assertThat(origin.profiles().suggestedFileName()).isEqualTo("perch-profile-20260808.json")
    }

    // ---- harness -------------------------------------------------------------------

    private fun newDatabase() =
        PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())

    private fun PerchDatabase.folders() = FolderRepository(folderDao(), clock)

    private fun PerchDatabase.profiles() =
        ProfileRepository(feedDao(), entryDao(), folders(), clock)

    private suspend fun PerchDatabase.refreshAll() = FeedRepository(
        feedDao = feedDao(),
        entryDao = entryDao(),
        fetcher = FeedFetcher(
            OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
        ),
        clock = clock,
    ).refreshAll()

    private fun urlOf(path: String) = server.url("/$path.xml").toString()

    private suspend fun PerchDatabase.feedIdOf(path: String) = feedDao().findByUrl(urlOf(path))!!.id

    private suspend fun PerchDatabase.entryIdOf(path: String, guid: String) =
        entryDao().findByGuid(feedIdOf(path), guid)!!.id

    /** feedUrl → the name of the folder it is filed under. */
    private suspend fun PerchDatabase.sourcesByFolder(): Map<String, String> {
        val names = folderDao().getAll().associate { it.id to it.name }
        return feedDao().getAll().associate { it.feedUrl to names.getValue(it.folderId) }
    }

    /** Everything a second restore must not disturb. */
    private suspend fun PerchDatabase.snapshot(): String = buildString {
        folderDao().getAll().forEach { appendLine("folder ${it.name} ${it.sortIndex}") }
        feedDao().getAll().forEach { appendLine("source ${it.feedUrl} ${it.customTitle} ${it.folderId}") }
        entryDao().observeAll().first().forEach {
            appendLine(
                "entry ${it.guid} ${it.isRead}/${it.readAt} ${it.isSaved}/${it.savedAt} " +
                    "${it.isStarred}/${it.starredAt}",
            )
        }
        appendLine("pending ${entryDao().countPendingState()}")
    }

    private suspend fun seedLibrary(db: PerchDatabase) {
        val graphics = db.folders().createFolder("Graphics")
        val security = db.folders().createFolder("Security")
        db.feedDao().insert(sourceRow("feed-a", "Alpha", graphics, custom = "Alpha, renamed"))
        db.feedDao().insert(sourceRow("feed-b", "Beta", security))
        db.feedDao().insert(sourceRow("feed-c", "Gamma", FolderEntity.UNCATEGORIZED_ID))
        db.refreshAll()
    }

    /** What the reader did on the old phone, and all that the profile carries of it. */
    private suspend fun markStateInOrigin() {
        val entries = origin.entryDao()
        entries.setRead(listOf(origin.entryIdOf("feed-a", "a1")), isRead = true, readAt = READ_AT)
        entries.setSaved(origin.entryIdOf("feed-a", "a2"), isSaved = true, savedAt = SAVED_AT)
        entries.setStarred(origin.entryIdOf("feed-a", "a2"), isStarred = true, starredAt = LIKED_AT)
        entries.setStarred(origin.entryIdOf("feed-b", "b1"), isStarred = true, starredAt = LIKED_AT)
    }

    private fun sourceRow(path: String, title: String, folderId: Long, custom: String? = null) =
        FeedEntity(
            feedUrl = urlOf(path),
            siteUrl = "https://$path.example/",
            title = title,
            customTitle = custom,
            faviconUrl = null,
            etag = null,
            lastModified = null,
            lastFetchedAt = null,
            lastSuccessAt = null,
            lastError = null,
            addedAt = now,
            folderId = folderId,
        )

    private fun feedDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/feed-a.xml" -> ok(rss("a1", "a2"))
            "/feed-b.xml" -> ok(rss("b1"))
            else -> ok(rss("c1"))
        }
    }

    private fun ok(body: String) = MockResponse()
        .setBody(body)
        .addHeader("Content-Type", "application/rss+xml; charset=utf-8")

    private fun rss(vararg guids: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <rss version="2.0"><channel>
          <title>Example Feed</title>
          <link>https://example.com/</link>
          ${guids.joinToString("\n") { item(it) }}
        </channel></rss>
    """.trimIndent()

    private fun item(guid: String) = "<item>" +
        "<guid isPermaLink=\"false\">$guid</guid><title>$guid</title>" +
        "<link>https://example.com/$guid</link>" +
        "<pubDate>Mon, 03 Aug 2026 10:00:00 GMT</pubDate></item>"

    private companion object {
        const val READ_AT = 600L
        const val SAVED_AT = 7_007L
        const val LIKED_AT = 8_008L
    }
}
