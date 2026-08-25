package dev.mkiros.perch.acceptance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.map.Mapper
import coil.request.Options
import com.google.common.truth.Truth.assertWithMessage
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.extract.ArticleExtractor
import dev.mkiros.perch.data.extract.FullText
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.ArticleLowering
import dev.mkiros.perch.data.repo.OpmlImportResult
import dev.mkiros.perch.data.repo.PerchPaging
import dev.mkiros.perch.data.repo.SourceResolution
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.CUTOUT_PX
import dev.mkiros.perch.ui.applyWindowInsets
import dev.mkiros.perch.ui.article.ArticleScreen
import dev.mkiros.perch.ui.article.ArticleTestTags
import dev.mkiros.perch.ui.article.ArticleUiState
import dev.mkiros.perch.ui.article.ArticleViewModel
import dev.mkiros.perch.ui.collection.CollectionTestTags
import dev.mkiros.perch.ui.home.EntryRowTestTags
import dev.mkiros.perch.ui.home.HomeTestTags
import dev.mkiros.perch.ui.home.SelectionTestTags
import dev.mkiros.perch.ui.home.TimeFilter
import dev.mkiros.perch.ui.nav.NavTestTags
import dev.mkiros.perch.ui.nav.PerchNavHost
import dev.mkiros.perch.ui.nav.PerchTab
import dev.mkiros.perch.ui.screenshot.Screenshots
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.theme.ThemeMode
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T32, extended by U15 — the daily-driver gate. Everything before this proves Perch works
 * against *fixtures*; this proves it works against **the real internet**, and that
 * forty-one sources come out looking like one publication.
 *
 * It is network-gated and excluded from the default build, because `./gradlew test` must
 * stay offline and deterministic. Run it deliberately:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest -Pperch.live=true --tests '*LiveAcceptance*'
 * ```
 *
 * Thirteen gates, in one method because they are one run: the pull feeds the standardize
 * pass, which picks the sample the article screenshots render; the sampled *opens* feed the
 * thumbnail and full-text gates; the folders the OPML gate builds are what the folder-order
 * gate reads and what puts more than one category on the home screenshot's rows (W04).
 * Every gate collects its failures rather than throwing, so one broken source names itself
 * and the other twelve still report their counts — a live run that tells you only the first
 * thing that went wrong costs another ten minutes to learn the second.
 *
 * **PLAN-3 V15** re-runs U15's list and adds to it. Its clauses map onto this file as:
 * (1) gate 1, with V12's exclusion list; (2) **gate 8** — the Feed's time window, asked of
 * the live corpus (V15 asked it of V02's day boundary; W02/#15 made the window a rolling
 * twenty-four hours and the gate asks that instead); (3) **gate 9**, new — V06's folder
 * order across the live folder set, asked of the drawer's two queries alone since W03 took
 * the list out of the rule; (4) gate 6b, now reaching V09's *page* path as well as every
 * feed body; (5) gates 4 and 5b, now floored at what U15 actually measured rather than at
 * U15's opening bid; (6) gate 6c; and (7) gate 7, which grew the three surfaces v0.3
 * added — V08's scoped list, V10's refused folder header, and V04's viewer under a cutout.
 *
 * **PLAN-4 W11** asks this plan's own three questions. Two of them were already here, put
 * there by the tasks that changed the answer: the Feed's first live page is in
 * non-increasing `publishedAt` order (**gate 7**, since W03 made the Feed one stream) and
 * every live entry inside twenty-four hours is in the window with nothing older beside it
 * (**gate 8**, since W02 made the window rolling). The third is **gate 5c**, new — #17's
 * Hugging Face page, fetched live, held to extracting a body that beats the teaser it
 * replaces.
 *
 * Where it deviates from PLAN.md: the file lives in `src/testDebug` rather than
 * `src/test`. Gate 3 needs a Compose rule, and `ui-test-manifest` is a
 * `debugImplementation` — a `src/test` class holding one fails the *release* unit-test
 * variant, which `./gradlew test` also runs, before `assumeTrue` ever executes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class LiveAcceptanceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer

    private val clock: Clock = Clock.systemUTC()

    /**
     * Held rather than defaulted because gate 7 has to pin the window open: home opens on
     * *Today* (U07), and a live pull whose newest item is three days old would be
     * screenshotted as an empty bucket.
     */
    private val settings = SettingsStore.inMemory()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PerchDatabase.inMemory(context)
        container = AppContainer(
            database = database,
            httpClient = PerchHttp.client(cacheDir = null),
            clock = clock,
            settings = settings,
        )
        // The figure treatment is what gate 3 is looking at, and an image block collapses
        // the whole figure on a failed load — so every remote image resolves to a flat
        // placeholder of a fixed shape. Layout structure is the subject; the photograph
        // is not, and fetching real ones would make the capture depend on the weather.
        // Gate 7's list shots ride on the same mapper, which is what makes them *mixed*:
        // a row whose entry resolved an `imageUrl` gets the slab, a row whose entry did
        // not still gets U08's outline placeholder.
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components { add(FlatImages(context)) }
                .dispatcher(Dispatchers.Main.immediate)
                .fetcherDispatcher(Dispatchers.Main.immediate)
                .decoderDispatcher(Dispatchers.Main.immediate)
                .transformationDispatcher(Dispatchers.Main.immediate)
                .build(),
        )
    }

    @After
    fun tearDown() {
        Coil.reset()
        database.close()
    }

    @Test
    fun `every source in the reading list pulls, standardizes and reads as one publication`() {
        assumeTrue(
            "live acceptance is opt-in: rerun with -Pperch.live=true",
            System.getProperty(LIVE_PROPERTY) == "true",
        )
        val failures = mutableListOf<String>()
        val dir = Screenshots.dir(SCREENSHOT_DIR)
        dir.listFiles()?.forEach { it.delete() }

        val pull = pullEveryFeed()
        report("GATE 1 (pull)", pull.summary())
        failures += staleExclusions()
        if (pull.refusals.isNotEmpty()) {
            failures += "gate 1: ${pull.refusals.size} of ${pull.attempted} sources that " +
                "should pull did not — ${pull.refusals.sorted()}. Either the source broke " +
                "(fix it, or retire it from fixtures/feeds.txt) or Perch did. If it is " +
                "permanently out, name it in EXCLUDED_SOURCES with a reason."
        }

        val standard = lowerEveryEntry()
        report("GATE 2 (standardize)", standard.summary())
        failures += standard.failures
        if (standard.unsupported.size * 100 > standard.blocks * UNSUPPORTED_PERCENT) {
            failures += "gate 2: ${standard.unsupported.size}/${standard.blocks} blocks are " +
                "Unsupported (over $UNSUPPORTED_PERCENT%). Extend the mapper for: " +
                "${standard.unsupported.distinct().sorted()}"
        }

        // Gates 4 and 5 are one pass over the network, because they are one question asked
        // of the same event: what a reader gets when they open an article Perch thinks is
        // short. Run before the remaining gates so the tables, the paging and the
        // screenshots all see the corpus as a reader would leave it.
        val opened = openTheShortOnes()
        report("GATE 4 (thumbnails)", opened.thumbnailSummary())
        failures += opened.thumbnailFailures()
        report("GATE 5 (full text)", opened.fullTextSummary())
        failures += opened.fullTextFailures()
        val perSource = thumbnailsPerSource(opened)
        report("GATE 5b (thumbnails per source)", perSource.summary())
        failures += perSource.failures

        val blindSpot = theBlindSpotPageStillYieldsItsArticle()
        report("GATE 5c (W07's blind spot)", blindSpot.summary)
        failures += blindSpot.failures

        val folders = foldersRoundTrip()
        report("GATE 6 (folders survive OPML)", folders.summary())
        failures += folders.failures

        val order = foldersReadInTheReadersOrder()
        report("GATE 9 (folder order)", order.summary())
        failures += order.failures

        val tables = everyTableStaysRectangular()
        report("GATE 6b (tables)", tables.summary())
        failures += tables.failures

        val paging = theFeedLoadsOnePage()
        report("GATE 6c (paging)", paging.summary())
        failures += paging.failures

        val boundary = theWindowIsTheLastTwentyFourHours()
        report("GATE 8 (the last 24 hours)", boundary.summary())
        failures += boundary.failures

        if (standard.samples.isEmpty()) {
            failures += "gate 3: nothing was pulled, so there was nothing to render"
        } else {
            val shots = capture(standard.samples)
            report("GATE 3 (one publication)", shots.gate3.joinToString("\n"))
            report("GATE 7 (the v0.2 surfaces)", shots.gate7.joinToString("\n"))
            failures += shots.failures
        }

        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
    }

    // ---- gate 1: pull ----------------------------------------------------------

    private class PullReport(val attempted: Int) {
        var withEntries = 0
        val refusals = mutableListOf<String>()
        fun summary() = "$withEntries/$attempted sources that should pull resolved with ≥1 entry" +
            refusals.sorted().joinToString("") { "\n  refused   $it" } +
            EXCLUDED_SOURCES.entries.sortedBy { it.key }
                .joinToString("") { "\n  excluded  ${it.key} — ${it.value}" }
    }

    /**
     * Every URL in `fixtures/feeds.txt` bar [EXCLUDED_SOURCES], through the stack the
     * add-source sheet uses — [dev.mkiros.perch.data.net.FeedFetcher] (SPEC.md §6 limits
     * and all) → discovery → `FeedParser` → the database. Not a reimplementation of the
     * app's pull: the app's pull, pointed at the real internet.
     *
     * Every one of them must come back with a feed and at least one entry. There is no
     * quota to hide behind, and there does not need to be: a source that stops working
     * arrives as its own URL and its own error, which is either something to fix, a source
     * to retire, or an exclusion to write down.
     *
     * Four in flight, matching the repository's own refresh concurrency, and each source
     * is committed the moment it resolves so the parsed feed can be collected rather than
     * forty-one of them held at once.
     */
    private fun pullEveryFeed(): PullReport = runBlocking {
        val urls = feedUrls()
        val report = PullReport(urls.size)
        val inFlight = Semaphore(CONCURRENCY)
        val writes = Mutex()

        coroutineScope {
            urls.map { url ->
                async {
                    inFlight.withPermit {
                        val outcome = runCatching { container.feeds.resolve(url) }
                        val resolution = outcome.getOrElse {
                            return@withPermit "$url — threw ${it.javaClass.simpleName}: ${it.message}"
                        }
                        when (resolution) {
                            is SourceResolution.Resolved -> {
                                if (resolution.entryCount == 0) return@withPermit "$url — feed has no entries"
                                writes.withLock { container.feeds.add(resolution) }
                                null
                            }
                            is SourceResolution.AlreadySubscribed ->
                                "$url — collided with ${resolution.title}, already subscribed"
                            is SourceResolution.NoFeedFound -> "$url — no feed found"
                            is SourceResolution.Unreachable -> "$url — ${resolution.message}"
                        }
                    }
                }
            }.awaitAll().forEach { refusal ->
                if (refusal == null) report.withEntries++ else report.refusals += refusal
            }
        }
        report
    }

    // ---- gate 2: standardize ---------------------------------------------------

    /** One entry's shape, kept so gate 3 can pick the hostile sample out of the corpus. */
    private class Sample(
        val entryId: Long,
        val feedUrl: String,
        val title: String,
        val code: Int,
        val images: Int,
        val enumerated: Int,
    )

    private class StandardizeReport {
        var entries = 0
        var blocks = 0
        val unsupported = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val samples = mutableListOf<Sample>()
        fun summary() = "$entries entries lowered to $blocks blocks; " +
            "${unsupported.size} Unsupported ${unsupported.distinct().sorted()}"
    }

    /**
     * Lowers every entry the pull stored. The rows are already `HtmlSanitizer` output —
     * sanitizing lives in `FeedRepository`, so nothing downstream ever sees feed markup —
     * which makes reading them back and calling `toBlocks` the app's exact article
     * pipeline rather than an approximation of it.
     *
     * Read one feed at a time: the corpus is tens of megabytes of content and the unit
     * test JVM is not generously heaped.
     */
    private fun lowerEveryEntry(): StandardizeReport = runBlocking {
        val report = StandardizeReport()
        for (feed in database.feedDao().getAll()) {
            for (entry in database.entryDao().observeByFeed(feed.id).first()) {
                report.entries++
                val blocks = try {
                    ArticleLowering.toBlocks(entry.contentHtml)
                } catch (t: Throwable) {
                    report.failures += "gate 2: ${feed.feedUrl} “${entry.title}” — " +
                        "toBlocks threw ${t.javaClass.simpleName}: ${t.message}"
                    null
                } ?: continue
                if (!entry.contentHtml.isNullOrBlank() && blocks.isEmpty()) {
                    report.failures += "gate 2: ${feed.feedUrl} “${entry.title}” — a " +
                        "${entry.contentHtml!!.length}-char body lowered to zero blocks"
                }
                val flat = flatten(blocks)
                report.blocks += flat.size
                report.unsupported += flat.filterIsInstance<ArticleBlock.Unsupported>().map { it.label }
                report.samples += entry.sample(feed.feedUrl, flat)
            }
        }
        report
    }

    private fun EntryEntity.sample(feedUrl: String, blocks: List<ArticleBlock>) = Sample(
        entryId = id,
        feedUrl = feedUrl,
        title = title,
        code = blocks.count { it is ArticleBlock.Code },
        images = blocks.count { it is ArticleBlock.Image },
        enumerated = blocks.count { it is ArticleBlock.ListBlock || it is ArticleBlock.Table },
    )

    /** Quote is the one block that holds blocks; its contents count too. */
    private fun flatten(blocks: List<ArticleBlock>): List<ArticleBlock> =
        blocks.flatMap {
            if (it is ArticleBlock.Quote) listOf(it) + flatten(it.blocks) else listOf(it)
        }

    // ---- gates 4 and 5: what opening a short article actually gets you ----------

    /** One entry, before and after the app opened it. */
    private class Opened(
        val feedUrl: String,
        val title: String,
        val proseBefore: Int,
        val proseAfter: Int,
        val imageBefore: String?,
        val imageAfter: String?,
        val excerptSource: Boolean,
    ) {
        val recovered: Boolean get() = proseAfter >= RECOVERED_PROSE_CHARS
        val teaser: Boolean get() = proseBefore in 1 until TEASER_CHARS
        val ratio: Double get() = if (proseBefore == 0) Double.NaN else proseAfter.toDouble() / proseBefore
    }

    private class OpenReport(val corpusEntries: Int, val corpusWithImage: Int) {
        val opened = mutableListOf<Opened>()
        val errors = mutableListOf<String>()
        var fabiensanglard = "not pulled"
        var gpuopen = "not pulled"
        var gpuopenProbe: ExemplarProbe? = null

        val corpusPercent: Double
            get() = if (corpusEntries == 0) 0.0 else corpusWithImage * 100.0 / corpusEntries

        /**
         * Gate 4 is scored on the **sampled-and-fetched set**, which U15 allows in so many
         * words because U10's fetch is opportunistic rather than universal: the app opens a
         * page only for an entry [FullText.needsExtraction] admits, so those are the only
         * entries `og:image` — §0's fifth and last rung — ever runs for. Scoring the whole
         * corpus instead would be scoring feed markup, which U05 already measured at a
         * ceiling near 33%. Both numbers print; neither is hidden behind the other.
         */
        val fetchedPercent: Double
            get() = if (opened.isEmpty()) 0.0 else opened.count { it.imageAfter != null } * 100.0 / opened.size

        fun thumbnailSummary() = buildString {
            appendLine(
                "%d/%d of the sampled-and-fetched set resolve a thumbnail (%.1f%%, need %d%%)"
                    .format(opened.count { it.imageAfter != null }, opened.size, fetchedPercent, MIN_THUMBNAIL_PERCENT),
            )
            appendLine(
                "  of which %d were gained from the page's og:image while it was open"
                    .format(opened.count { it.imageBefore == null && it.imageAfter != null }),
            )
            append(
                "  whole live corpus, feed markup only: %d/%d (%.1f%%) — U05's ceiling, not gated here"
                    .format(corpusWithImage, corpusEntries, corpusPercent),
            )
        }

        fun thumbnailFailures(): List<String> = when {
            opened.isEmpty() -> listOf("gate 4: no entry needed extraction, so nothing was fetched")
            fetchedPercent < MIN_THUMBNAIL_PERCENT ->
                listOf(
                    "gate 4: only %.1f%% of the %d sampled-and-fetched entries resolved a thumbnail (need %d%%)"
                        .format(fetchedPercent, opened.size, MIN_THUMBNAIL_PERCENT),
                )
            else -> emptyList()
        }

        private val excerptOpens get() = opened.filter { it.excerptSource }
        private val teaserOpens get() = excerptOpens.filter { it.teaser }

        /**
         * U15's ≥10× rule, scored over the teasers **together** rather than one at a time.
         *
         * The per-entry form is a claim about the publisher's word count, not about the
         * extractor: hexacorn.com's posts are eight hundred to fourteen hundred characters
         * *in full*, so a 242-character teaser of one cannot grow tenfold however well the
         * extraction goes, and gwern.substack.com's older newsletters are one-line pointers
         * to gwern.net whose pages really do hold nothing else. Demanding ten from those is
         * demanding that the extractor invent seven hundred characters.
         *
         * Aggregated, the rule keeps its teeth and loses the false claim: an extractor that
         * stopped recovering, or started returning nav furniture, moves this number
         * immediately, and every teaser still prints its own before/after so a single
         * source going quiet is visible rather than averaged away. The entries that recover
         * *nothing* are not excused here either — they are counted, and fail, one line up.
         */
        private val teaserRatio: Double
            get() = teaserOpens.sumOf { it.proseBefore }.let { before ->
                if (before == 0) Double.NaN else teaserOpens.sumOf { it.proseAfter }.toDouble() / before
            }

        fun fullTextSummary() = buildString {
            val recovered = excerptOpens.count { it.recovered }
            appendLine(
                "%d/%d sampled entries from body-less or excerpt-only sources recovered ≥%d chars of prose (%.1f%%, need %d%%)"
                    .format(
                        recovered, excerptOpens.size, RECOVERED_PROSE_CHARS,
                        percent(recovered, excerptOpens.size), MIN_RECOVERY_PERCENT,
                    ),
            )
            appendLine(
                "%d/%d of them stood behind a teaser under %d chars: %d chars of teaser became %d, ×%.1f (need ×%.0f)"
                    .format(
                        teaserOpens.size, excerptOpens.size, TEASER_CHARS,
                        teaserOpens.sumOf { it.proseBefore }, teaserOpens.sumOf { it.proseAfter },
                        teaserRatio, TENFOLD,
                    ),
            )
            val short = teaserOpens.filter { it.ratio < TENFOLD }
            if (short.isNotEmpty()) {
                appendLine(
                    "  under ×10 one by one, which is the length of the post and not the reach " +
                        "of the extractor: " +
                        short.joinToString { "“${it.title.take(TABLE_LABEL)}” ×%.1f".format(it.ratio) },
                )
            }
            appendLine("  fabiensanglard.net — $fabiensanglard")
            appendLine("  gpuopen.com       — $gpuopen")
            appendLine("  %-44s %8s %8s %6s".format("entry", "before", "after", "ratio"))
            excerptOpens.sortedBy { it.proseAfter }.forEach {
                appendLine(
                    "  %-44s %8d %8d %6s".format(
                        it.title.take(TABLE_LABEL), it.proseBefore, it.proseAfter,
                        if (it.proseBefore == 0) "—" else "%.1f".format(it.ratio),
                    ),
                )
            }
            errors.sorted().forEach { appendLine("  fetch  $it") }
            append("  ${errors.size} of ${opened.size + errors.size} opens threw")
        }

        fun fullTextFailures(): List<String> {
            val out = mutableListOf<String>()
            if (excerptOpens.isEmpty()) {
                out += "gate 5: no source qualified as body-less or excerpt-only, which " +
                    "cannot be right while fabiensanglard.net is in the reading list"
                return out
            }
            val recovered = excerptOpens.count { it.recovered }
            if (percent(recovered, excerptOpens.size) < MIN_RECOVERY_PERCENT) {
                out += "gate 5: only $recovered/${excerptOpens.size} sampled entries recovered " +
                    "≥$RECOVERED_PROSE_CHARS chars. Missed: " +
                    excerptOpens.filterNot { it.recovered }.joinToString { "${it.feedUrl} “${it.title}”" }
            }
            if (teaserOpens.isNotEmpty() && teaserRatio < TENFOLD) {
                out += "gate 5: the sampled teasers grew ×%.1f in aggregate, not ×%.0f — %d chars became %d across %d entries"
                    .format(
                        teaserRatio, TENFOLD, teaserOpens.sumOf { it.proseBefore },
                        teaserOpens.sumOf { it.proseAfter }, teaserOpens.size,
                    )
            }
            if (gpuopenProbe?.recoveredNothing == true) {
                out += "gate 5: gpuopen.com is §0's teaser exemplar and the live probe " +
                    "recovered nothing from any of ${gpuopenProbe?.sampled} sampled entries"
            }
            return out
        }
    }

    /**
     * Opens a sample of the entries the app itself would fetch a page for, through the
     * exact call the article screen makes ([dev.mkiros.perch.data.repo.ArticleTextRepository.loadFullText]),
     * and records what changed.
     *
     * The sample is capped at [SAMPLE_PER_SOURCE] per source rather than taken across the
     * corpus, because one prolific source would otherwise be most of it — fabiensanglard.net
     * alone ships 144 entries and every one of them qualifies.
     */
    private fun openTheShortOnes(): OpenReport = runBlocking {
        val feeds = database.feedDao().getAll()
        val allEntries = feeds.associateWith { database.entryDao().observeByFeed(it.id).first() }
        val report = OpenReport(
            corpusEntries = allEntries.values.sumOf { it.size },
            corpusWithImage = allEntries.values.sumOf { rows -> rows.count { it.imageUrl != null } },
        )

        // A source counts as body-less or excerpt-only when most of what it ships is:
        // one short post on a full-text blog is not the shape §0 is describing.
        val shortSources = allEntries.filterValues { rows ->
            rows.isNotEmpty() &&
                rows.count { FullText.needsExtraction(it.contentHtml, it.bodyIsExcerpt) } * 2 >= rows.size
        }.keys.map { it.id }.toSet()

        report.fabiensanglard = describe(allEntries, shortSources, "fabiensanglard.net")
        report.gpuopen = describe(allEntries, shortSources, "gpuopen.com")
        if (report.gpuopen == NOT_PULLED) {
            val probe = probeExemplar(GPUOPEN_FEED)
            report.gpuopen = probe.summary
            report.gpuopenProbe = probe
        }

        val picks = allEntries.flatMap { (feed, rows) ->
            rows.filter { FullText.needsExtraction(it.contentHtml, it.bodyIsExcerpt) }
                .take(SAMPLE_PER_SOURCE)
                .map { feed to it }
        }
        val inFlight = Semaphore(CONCURRENCY)
        val writes = Mutex()

        coroutineScope {
            picks.map { (feed, entry) ->
                async {
                    inFlight.withPermit {
                        val before = FullText.prose(entry.contentHtml).length
                        val outcome = runCatching { container.articleText.loadFullText(entry.id) }
                        writes.withLock {
                            outcome.onFailure {
                                report.errors += "${feed.feedUrl} “${entry.title}” — " +
                                    "${it.javaClass.simpleName}: ${it.message}"
                            }.onSuccess {
                                val after = database.entryDao().findById(entry.id) ?: return@onSuccess
                                report.opened += Opened(
                                    feedUrl = feed.feedUrl,
                                    title = entry.title,
                                    proseBefore = before,
                                    proseAfter = FullText.prose(after.contentHtml).length,
                                    imageBefore = entry.imageUrl,
                                    imageAfter = after.imageUrl,
                                    excerptSource = feed.id in shortSources,
                                )
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        report
    }

    /** §0 names these two sources by hand, so the run has to say what became of each. */
    private fun describe(
        entries: Map<FeedEntity, List<EntryEntity>>,
        shortSources: Set<Long>,
        host: String,
    ): String {
        val (feed, rows) = entries.entries.firstOrNull { it.key.feedUrl.contains(host) }
            ?: return NOT_PULLED
        val needing = rows.count { FullText.needsExtraction(it.contentHtml, it.bodyIsExcerpt) }
        return "${rows.size} entries, $needing need extraction, " +
            if (feed.id in shortSources) "counted as an excerpt source" else "counted as full-text"
    }

    /** What a live probe of a source outside the reading list found. */
    private class ExemplarProbe(val summary: String, val sampled: Int, val recovered: Int) {
        /** The one thing worth reddening a run over: the shape §0 named, and nothing came back. */
        val recoveredNothing: Boolean get() = sampled > 0 && recovered == 0
    }

    /**
     * §0 names gpuopen.com as *the* teaser shape — a 194-character `<description>` and no
     * `content:encoded` — and it is not in `fixtures/feeds.txt`, so naming it in the output
     * would otherwise mean printing "not pulled" and proving nothing. The gate goes and gets
     * it instead, into a database of its own so that no other gate's counts move by a single
     * entry, and opens the sample through the same `loadFullText` the article screen calls.
     *
     * A probe that cannot reach the source reports and does not fail: this is one source
     * outside the contracted forty-one, and gate 1 is where the reading list's reachability
     * is judged. A probe that *does* pull entries and recovers nothing from any of them is a
     * different thing entirely, and fails.
     */
    private fun probeExemplar(url: String): ExemplarProbe = runBlocking {
        val prefix = "not in the reading list; probed live — "
        val fresh = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val reader = AppContainer(
                database = fresh,
                httpClient = PerchHttp.client(cacheDir = null),
                clock = clock,
            )
            val resolution = runCatching { reader.feeds.resolve(url) }.getOrElse {
                return@runBlocking ExemplarProbe(
                    "$prefix$url threw ${it.javaClass.simpleName}: ${it.message}", 0, 0,
                )
            }
            if (resolution !is SourceResolution.Resolved) {
                return@runBlocking ExemplarProbe("$prefix$url came back as $resolution", 0, 0)
            }
            reader.feeds.add(resolution)
            val feed = fresh.feedDao().getAll().first { it.feedUrl.contains("gpuopen.com") }
            val rows = fresh.entryDao().observeByFeed(feed.id).first()
            val picks = rows
                .filter { FullText.needsExtraction(it.contentHtml, it.bodyIsExcerpt) }
                .take(SAMPLE_PER_SOURCE)
            if (picks.isEmpty()) {
                return@runBlocking ExemplarProbe(
                    "$prefix${rows.size} entries, none of which Perch thinks needs extraction", 0, 0,
                )
            }
            val measured = picks.map { entry ->
                val before = FullText.prose(entry.contentHtml).length
                runCatching { reader.articleText.loadFullText(entry.id) }
                val after = FullText.prose(fresh.entryDao().findById(entry.id)?.contentHtml).length
                Triple(entry.title, before, after)
            }
            ExemplarProbe(
                summary = prefix + "${rows.size} entries, ${picks.size} sampled: " +
                    measured.joinToString { (title, before, after) ->
                        "“${title.take(TABLE_LABEL)}” $before→$after" +
                            if (before == 0) "" else " ×%.0f".format(after.toDouble() / before)
                    },
                sampled = measured.size,
                recovered = measured.count { (_, _, after) -> after >= RECOVERED_PROSE_CHARS },
            )
        } finally {
            fresh.close()
        }
    }

    private class PerSourceReport(val table: String, val fetched: Double) {
        val failures = mutableListOf<String>()
        fun summary() = table
    }

    /**
     * Gate 5b: the per-source table, so a source at 0% is attributable rather than averaged
     * away — and, since V15, a **ratchet**.
     *
     * V15 asks for coverage "unchanged or better than U15's table". U15's table itself was
     * printed and not recorded, so what is held to is the number it scored the run on:
     * [U15_THUMBNAIL_PERCENT], well above gate 4's opening [MIN_THUMBNAIL_PERCENT] floor.
     * The comparison carries [LIVE_DRIFT] of slack in one direction only, because the corpus
     * underneath it is not the one U15 measured: sources publish text-only posts between
     * runs and the sampled set moves with them. A drop past the slack is a regression in the
     * five-rung thumbnail ladder; a rise ratchets nothing automatically, since raising the
     * constant is a decision and belongs in a commit message.
     */
    private fun thumbnailsPerSource(opened: OpenReport): PerSourceReport = runBlocking {
        val rows = database.feedDao().getAll().map { feed ->
            val entries = database.entryDao().observeByFeed(feed.id).first()
            Triple(
                feed.feedUrl.substringAfter("://").substringBefore("/"),
                entries.count { it.imageUrl != null },
                entries.size,
            )
        }.sortedBy { (_, withImage, total) -> if (total == 0) 100.0 else withImage * 100.0 / total }

        val table = buildString {
            appendLine("  %-32s %8s %10s".format("source", "with-img", "entries"))
            rows.forEach { (host, withImage, total) ->
                appendLine("  %-32s %7.1f%% %4d/%-5d".format(host, percent(withImage, total), withImage, total))
            }
            appendLine(
                "  a source at 0% ships no image markup and was never fetched — that is the feed's shape, not ours",
            )
            append(
                "  sampled-and-fetched %.1f%% against U15's %.1f%% (floor %.1f%%, %.1f points of live drift allowed)"
                    .format(
                        opened.fetchedPercent, U15_THUMBNAIL_PERCENT,
                        U15_THUMBNAIL_PERCENT - LIVE_DRIFT, LIVE_DRIFT,
                    ),
            )
        }
        val report = PerSourceReport(table, opened.fetchedPercent)
        if (opened.opened.isNotEmpty() && opened.fetchedPercent < U15_THUMBNAIL_PERCENT - LIVE_DRIFT) {
            report.failures += "gate 5b: thumbnail coverage fell to %.1f%% of the sampled-and-fetched set, "
                .format(opened.fetchedPercent) +
                "under U15's %.1f%% by more than %.1f points".format(U15_THUMBNAIL_PERCENT, LIVE_DRIFT)
        }
        report
    }

    // ---- gate 5c: the page W07 was structurally blind to --------------------------

    /** What the live probe of #17's page found. */
    private class BlindSpotReport(val summary: String) {
        val failures = mutableListOf<String>()
    }

    /**
     * W11 clause: #17's page, asked of the live web rather than of the harvested copy.
     *
     * `fixtures/articles/huggingface-efficient-knowledge-distillation.html` pins the defect
     * W06 diagnosed and W07 fixed, but a fixture is a page as it was on the day it was
     * harvested, and this one's whole failure mode was a *utility class name* — Tailwind's
     * `max-lg:overflow-hidden`, read as `hidden` by an unanchored `namesChrome()`. Names
     * like that change when the site rebuilds, and the offline copy would never notice.
     * So the gate goes and gets the page.
     *
     * The oracle is the one FullText itself uses: an extraction only ever earns its place by
     * beating the body it replaces. Two ways to lose — extracting to nothing at all, which is
     * literally the symptom #17 was filed about, and extracting to something no better than
     * the teaser the page's own metadata already carries. The floor is
     * [FullText.MIN_PROSE_CHARS], the *trigger* for going to look: a body under it is one
     * Perch would itself call short, so an extraction landing under it recovered nothing
     * worth the fetch. Deliberately not the fixture's 9355 characters — that would red the
     * run the day the post is edited, which is the publisher's business and not Perch's.
     *
     * Unreachable reports and does not fail, on the gpuopen and ZDI precedent: this is one
     * page outside the contracted forty-one, and gate 1 is where reachability is judged.
     */
    private fun theBlindSpotPageStillYieldsItsArticle(): BlindSpotReport = runBlocking {
        val label = "#17's Hugging Face post"
        val fetched = runCatching { FeedFetcher(container.httpClient).fetch(BLIND_SPOT_PAGE_URL) }
            .getOrNull()
            ?: return@runBlocking BlindSpotReport(
                "$label: $BLIND_SPOT_PAGE_URL could not be fetched — reported, not gated",
            )
        val page = Jsoup.parse(ByteArrayInputStream(fetched.bytes), null, fetched.finalUrl)
        val teaser = TEASER_META
            .firstNotNullOfOrNull {
                page.selectFirst(it)?.attr("content")?.takeIf(String::isNotBlank)
            }
            .orEmpty()
        val extracted = ArticleExtractor.extract(page.outerHtml(), fetched.finalUrl)
        val prose = FullText.prose(extracted).length
        val report = BlindSpotReport(
            "$label: the page's own teaser is ${teaser.length} chars, extraction returned " +
                "$prose chars of prose (${if (prose == 0) "nothing" else "×%.0f".format(
                    prose.toDouble() / maxOf(teaser.length, 1),
                )})",
        )
        if (extracted == null) {
            report.failures += "gate 5c: $label — $BLIND_SPOT_PAGE_URL extracted to nothing " +
                "at all, which is the symptom #17 was filed about (W06/W07)"
            return@runBlocking report
        }
        if (prose < FullText.MIN_PROSE_CHARS) {
            report.failures += "gate 5c: $label — extraction recovered $prose chars, under " +
                "FullText's own ${FullText.MIN_PROSE_CHARS}-char threshold for calling a " +
                "body short, so it recovered nothing worth the fetch"
        }
        if (prose <= teaser.length) {
            report.failures += "gate 5c: $label — extraction returned $prose chars against " +
                "the page's ${teaser.length}-char teaser. An extraction only replaces a body " +
                "it beats (U10), so this one would never reach the reader"
        }
        report
    }

    // ---- gate 6: folders survive the OPML round trip ----------------------------

    private class FolderReport {
        val failures = mutableListOf<String>()
        var named = 0
        var sources = 0
        var result: OpmlImportResult? = null
        fun summary() = "$sources sources across $named named folders plus Uncategorized; " +
            "reimported as $result"
    }

    /**
     * The live library, split across folders, out through `OpmlRepository.export` and back
     * into a database that has never seen any of it.
     *
     * The split is round-robin over the sources in `feedUrl` order rather than by subject,
     * because what is being tested is that *membership* survives the file — an arbitrary
     * assignment tests it exactly as well as a thoughtful one and does not depend on which
     * sources came back. One source in [FOLDERS] + 1 is left where it started, so the run
     * also covers §0's rule that Uncategorized's sources are written unfiled at top level.
     */
    private fun foldersRoundTrip(): FolderReport = runBlocking {
        val report = FolderReport()
        val feeds = database.feedDao().getAll().sortedBy { it.feedUrl }
        report.sources = feeds.size
        if (feeds.isEmpty()) {
            report.failures += "gate 6: nothing was pulled, so there was nothing to file"
            return@runBlocking report
        }

        val folderIds = FOLDER_NAMES.map { container.folders.createFolder(it) }
        report.named = folderIds.size
        feeds.forEachIndexed { index, feed ->
            val slot = index % (folderIds.size + 1)
            if (slot < folderIds.size) container.folders.moveSource(feed.id, folderIds[slot])
        }
        val before = folderMap(database)

        val exported = container.opml.export()
        val fresh = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val reader = AppContainer(
                database = fresh,
                httpClient = PerchHttp.client(cacheDir = null),
                clock = clock,
            )
            val result = reader.opml.import(exported)
            report.result = result
            if (result !is OpmlImportResult.Imported) {
                report.failures += "gate 6: the file Perch just wrote came back as $result"
                return@runBlocking report
            }
            if (result.added != feeds.size) {
                report.failures += "gate 6: exported ${feeds.size} sources, imported ${result.added}"
            }
            if (result.folders != folderIds.size) {
                report.failures += "gate 6: exported ${folderIds.size} named folders, " +
                    "the import created ${result.folders}"
            }
            val after = folderMap(fresh)
            if (after != before) {
                val moved = before.filter { (url, folder) -> after[url] != folder }
                report.failures += "gate 6: ${moved.size} sources changed folder across the " +
                    "round trip: " + moved.entries.take(TABLE_ROWS)
                        .joinToString { (url, was) -> "$url $was → ${after[url]}" }
            }
        } finally {
            fresh.close()
        }
        report
    }

    private suspend fun folderMap(db: PerchDatabase): Map<String, String> {
        val names = db.folderDao().getAll().associate { it.id to it.name }
        return db.feedDao().getAll().associate { it.feedUrl to names.getValue(it.folderId) }
    }

    // ---- gate 9: folders read in the reader's order -----------------------------

    private class FolderOrderReport {
        val failures = mutableListOf<String>()
        var drawer = emptyList<String>()
        var expected = emptyList<String>()
        fun summary() = "drawer   ${drawer.joinToString(" · ")}\n" +
            "  expected ${expected.joinToString(" · ")}"
    }

    /**
     * V06/V15 clause (3): alphabetical, case-insensitive, Uncategorized last — across the
     * live folder set, from all three of the places that state it.
     *
     * There were three statements of the rule until W03; `EntryQueries.LIST_ITEMS` left it
     * when the Feed became one chronological stream, so the two that remain are
     * `FolderDao.observeAll` and its non-Flow twin — the drawer reads one, everything else
     * reads the other, and agreeing with each other is the property that matters rather
     * than either being right on its own. The list is deliberately *not* asked about folder
     * order any more: gate 7 asks it about recency instead, and a live list that grouped by
     * folder would fail there.
     *
     * [FOLDER_NAMES] is deliberately created in an order alphabetising undoes, and the gate
     * says so out loud: if a future edit made the creation order already sorted, this would
     * pass without distinguishing V06's rule from the `sortIndex` one it replaced.
     */
    private fun foldersReadInTheReadersOrder(): FolderOrderReport = runBlocking {
        val report = FolderOrderReport()
        val direct = database.folderDao().getAll()
        val observed = database.folderDao().observeAll().first()
        report.drawer = direct.map { it.name }

        val named = direct.filter { it.id != FolderEntity.UNCATEGORIZED_ID }
        val uncategorised = direct.filter { it.id == FolderEntity.UNCATEGORIZED_ID }
        report.expected = named.map { it.name }.sortedBy { it.lowercase() } + uncategorised.map { it.name }

        if (named.size < MIN_NAMED_FOLDERS) {
            report.failures += "gate 9: ${named.size} named folders in the live library, " +
                "too few for an order to mean anything"
            return@runBlocking report
        }
        if (FOLDER_NAMES == FOLDER_NAMES.sortedBy { it.lowercase() }) {
            report.failures += "gate 9: FOLDER_NAMES is already in alphabetical order, so " +
                "this gate can no longer tell V06's rule from creation order — reorder it"
        }
        if (report.drawer != report.expected) {
            report.failures += "gate 9: FolderDao.getAll returned ${report.drawer}, not ${report.expected}"
        }
        if (observed.map { it.name } != report.drawer) {
            report.failures += "gate 9: FolderDao.observeAll returned ${observed.map { it.name }}, " +
                "which the drawer and the non-Flow twin disagree with"
        }
        report
    }

    // ---- gate 8: the window is the last twenty-four hours -----------------------

    private class DayBoundaryReport {
        val failures = mutableListOf<String>()
        var corpus = 0
        var inWindow = 0
        var zone = ""
        var queried = ""

        fun summary() = "$inWindow of $corpus live entries were published inside the last " +
            "24 hours, and Today's own query returns exactly those\n  the app's own clock " +
            "zone: $zone\n  $queried"
    }

    /**
     * V15 clause (2), rewritten by W02/#15: Today is the **last twenty-four hours**, asked
     * of the live corpus.
     *
     * U07 opened this window at local midnight, and the gate that lived here measured the
     * margin between the reader's midnight and Greenwich's — issue #9, which emptied the
     * Feed "around 7 or 8 p.m. Central". A rolling window has no such margin *by
     * construction*: there is no midnight in it to belong to the wrong zone, so a gate
     * demanding that margin be non-zero would now fail on a correct app.
     *
     * What the reader still has a question about is the boundary itself, so that is what
     * this asks: fix one "now", work out from the entries in hand which ones were published
     * inside the last 24 h, and require Today's own list query — the SQL, the filter and
     * `TimeFilter.since` together — to return exactly that set and nothing else. The count
     * has to be non-zero or a live pull returned nothing recent and the gate proved nothing.
     *
     * One rung more, because the window being right is not the same as the app being right:
     * the container's default clock must still carry the *device's* zone. The window no
     * longer reads it, but every date a reader sees does (`RelativeTime`, the byline).
     */
    private fun theWindowIsTheLastTwentyFourHours(): DayBoundaryReport = runBlocking {
        val report = DayBoundaryReport()
        val entries = database.entryDao().observeAll().first()
        report.corpus = entries.size

        val appZone = AppContainer(database = database, httpClient = container.httpClient).clock.zone
        report.zone = "$appZone"
        if (appZone != ZoneId.systemDefault()) {
            report.failures += "gate 8: AppContainer's default clock is zoned $appZone, not " +
                "the device's ${ZoneId.systemDefault()} — this is issue #9 exactly"
        }
        if (entries.isEmpty()) {
            report.failures += "gate 8: nothing was pulled, so there was no window to bound"
            return@runBlocking report
        }

        // One "now" for the whole gate: two reads of a live clock would put entries either
        // side of a boundary that moved underneath them.
        val clock = Clock.fixed(Instant.now(), READER_ZONE)
        val since = TimeFilter.Today.since(clock)!!
        // Derived from the durations the reader was promised, not from `since` — a gate
        // that asks `since` what `since` means asks nothing.
        val edge = Instant.now(clock).minus(Duration.ofHours(24)).toEpochMilli()
        val expected = entries.filter { it.publishedAt >= edge }.map { it.id }.toSet()
        report.inWindow = expected.size

        if (expected.isEmpty()) {
            report.failures += "gate 8: not one of ${entries.size} live entries was published in " +
                "the last 24 hours, so Today's window was never exercised"
            return@runBlocking report
        }

        val visible = database.entryDao()
            .observeListItems(feedId = null, folderId = null, includeRead = true, publishedAfter = since)
            .first()
            .map { it.id }
            .toSet()
        report.queried = "through the list query: ${visible.size} rows, against " +
            "${expected.size} entries on the far side of the 24 h edge, which fell at " +
            "${Instant.ofEpochMilli(edge).atZone(READER_ZONE)}"

        (expected - visible).forEach { id ->
            val entry = entries.first { it.id == id }
            report.failures += "gate 8: “${entry.title.take(TABLE_LABEL)}” was published " +
                "${Instant.ofEpochMilli(entry.publishedAt).atZone(READER_ZONE)}, inside the last " +
                "24 hours, and Today's query dropped it"
        }
        (visible - expected).forEach { id ->
            val entry = entries.first { it.id == id }
            report.failures += "gate 8: “${entry.title.take(TABLE_LABEL)}” was published " +
                "${Instant.ofEpochMilli(entry.publishedAt).atZone(READER_ZONE)}, older than 24 " +
                "hours, and Today's query kept it"
        }
        report
    }

    // ---- gate 6b: every table survives lowering rectangular ---------------------

    private class TableReport {
        var documents = 0
        var tables = 0
        var cells = 0
        var skipped = 0
        var page = "not probed"
        val failures = mutableListOf<String>()
        fun summary() = "$tables tables across $documents entries, $cells written cells, " +
            "all rectangular with their header intact" +
            (if (skipped == 0) "" else "; $skipped nested-table documents skipped (see the source comment)") +
            "\n  $page"
    }

    /**
     * Every table in the live corpus, checked against the markup it came from.
     *
     * Three properties, and between them they are what "renders with its rules and header"
     * reduces to for the renderer U11a built. It draws one rule per column boundary at the
     * summed column width, so a **rectangular** grid is the precondition for a rule
     * landing anywhere meaningful; it tints row 1 only when `header` is non-empty, so
     * header promotion has to agree with the markup; and it can only draw cells that
     * survived, so the count of *written* cells must match the source exactly. Blank cells
     * are counted separately rather than totalled, because `colspan` padding legitimately
     * invents them — comparing only the written ones is what stops a lowering that padded
     * a table out of thin air from passing.
     *
     * A document with a table nested inside a table is counted and skipped: jsoup's
     * `select` and `ArticleLowering`'s own `select("tr")` disagree about which grid an
     * inner row belongs to, and an oracle that has to model that is a second
     * implementation of the thing under test.
     */
    private fun everyTableStaysRectangular(): TableReport = runBlocking {
        val report = TableReport()
        for (feed in database.feedDao().getAll()) {
            for (entry in database.entryDao().observeByFeed(feed.id).first()) {
                val html = entry.contentHtml ?: continue
                checkTables(html, "${feed.feedUrl} “${entry.title.take(TABLE_LABEL)}”", report)
            }
        }
        theZdiPageKeepsItsTable(report)
        report
    }

    /** One document's tables, against the markup they came from. Returns what lowered. */
    private fun checkTables(html: String, where: String, report: TableReport): List<ArticleBlock.Table> {
        if (!html.contains("<table", ignoreCase = true)) return emptyList()
        val document = Jsoup.parse(html)
        if (document.select("table table").isNotEmpty()) {
            report.skipped++
            return emptyList()
        }
        report.documents++

        val lowered = flatten(ArticleLowering.toBlocks(html))
            .filterIsInstance<ArticleBlock.Table>()
        report.tables += lowered.size
        lowered.forEach { table ->
            val widths = table.rows.map { it.size }.distinct()
            if (widths.size > 1) {
                report.failures += "gate 6b: $where — a table lowered to ragged rows $widths"
            }
            if (table.header.isNotEmpty() && widths.singleOrNull() != table.header.size) {
                report.failures += "gate 6b: $where — header of ${table.header.size} " +
                    "over body rows of $widths"
            }
        }

        val written = lowered.sumOf { table ->
            table.header.count { it.text.isNotBlank() } +
                table.rows.sumOf { row -> row.count { it.text.isNotBlank() } }
        }
        val source = document.select("td, th").count { it.text().isNotBlank() }
        report.cells += written
        if (written != source) {
            report.failures += "gate 6b: $where — $source written cells in the markup, " +
                "$written in the blocks"
        }

        val promoted = lowered.count { it.header.isNotEmpty() }
        val declared = document.select("table").count { table ->
            table.select("tr").firstOrNull()?.children()
                ?.any { it.tagName().equals("th", ignoreCase = true) } == true
        }
        if (promoted != declared) {
            report.failures += "gate 6b: $where — $declared tables lead with a th, " +
                "$promoted lowered with a header"
        }
        return lowered
    }

    /**
     * V15 clause (4): the same question asked of the **page** path, not just of feed bodies.
     *
     * Every table above arrived inside a `<content:encoded>`; V09/#4 is about the other
     * route — a Squarespace post whose table is one `sqs-block` sibling away from the
     * winning subtree, which `ArticleExtractor` used to drop on the floor. ZDI is not in the
     * reading list (its feed ships full bodies), so the page is fetched by name, through the
     * app's own fetcher and its own extractor, and put through the same oracle.
     *
     * Unreachable reports and does not fail, on the gpuopen precedent: this is one page
     * outside the contracted forty-one and gate 1 is where reachability is judged. Reachable
     * and *shorter than the page's own biggest table* is V09 regressing, and fails.
     */
    private suspend fun theZdiPageKeepsItsTable(report: TableReport) {
        val label = "V09's page path"
        val fetched = runCatching { FeedFetcher(container.httpClient).fetch(ZDI_PAGE_URL) }
            .getOrNull()
        if (fetched == null) {
            report.page = "$label: $ZDI_PAGE_URL could not be fetched — reported, not gated"
            return
        }
        val page = Jsoup.parse(ByteArrayInputStream(fetched.bytes), null, fetched.finalUrl)
        val onThePage = page.select("table").maxOfOrNull { table ->
            table.select("td, th").count { it.text().isNotBlank() }
        } ?: 0
        val extracted = ArticleExtractor.extract(page.outerHtml(), fetched.finalUrl)
        if (extracted == null) {
            report.failures += "gate 6b: $label — $ZDI_PAGE_URL extracted to nothing at all"
            return
        }
        val lowered = checkTables(extracted, "$label $ZDI_PAGE_URL", report)
        val recovered = lowered.maxOfOrNull { table ->
            table.header.count { it.text.isNotBlank() } +
                table.rows.sumOf { row -> row.count { it.text.isNotBlank() } }
        } ?: 0
        report.page = "$label: the ZDI post's own table has $onThePage written cells, " +
            "extraction kept $recovered across ${lowered.size} lowered tables"
        if (recovered < onThePage) {
            report.failures += "gate 6b: $label — the page's biggest table holds $onThePage " +
                "written cells and the extracted article kept $recovered. On a ZDI post the " +
                "table is the post (V09/#4)"
        }
    }

    // ---- gate 6c: the feed loads a page, not the corpus -------------------------

    private class PagingReport(val corpus: Int, val loaded: Int) {
        val failures = mutableListOf<String>()
        fun summary() = "the first load at All Time across every source returned $loaded rows " +
            "of a $corpus-entry corpus (page size ${PerchPaging.PAGE_SIZE})"
    }

    /**
     * The same `PagingSource` the Feed's `Pager` builds, asked for its first page with the
     * app's own [PerchPaging.config] — driven directly rather than through
     * `collectAsLazyPagingItems`, because what U07a is defended on here is how many rows
     * cross the database boundary, and a differ would only add its own bookkeeping to that
     * answer.
     */
    private fun theFeedLoadsOnePage(): PagingReport = runBlocking {
        val corpus = database.entryDao().countAll()
        val source = database.entryDao().pagedListItems(
            feedId = null,
            folderId = null,
            includeRead = true,
            publishedAfter = null,
        )
        val page = source.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = PerchPaging.config.initialLoadSize,
                placeholdersEnabled = false,
            ),
        )
        val loaded = (page as? PagingSource.LoadResult.Page)?.data?.size ?: -1
        val report = PagingReport(corpus, loaded)
        when {
            loaded < 0 -> report.failures += "gate 6c: the Feed's PagingSource returned $page"
            corpus <= PerchPaging.PAGE_SIZE ->
                report.failures += "gate 6c: a $corpus-entry corpus is smaller than one page, " +
                    "so this gate proved nothing"
            loaded != PerchPaging.PAGE_SIZE ->
                report.failures += "gate 6c: the first load returned $loaded rows, not " +
                    "${PerchPaging.PAGE_SIZE} — the Feed is materialising more than a page"
        }
        report
    }

    // ---- gates 3 and 7: look like one publication -------------------------------

    /**
     * What is on screen. One `setContent` for the whole run, switched by state: a
     * `ComposeTestRule` refuses a second call, and gates 3 and 7 between them mount three
     * different surfaces.
     */
    private sealed interface Scene {
        val name: String
        val mode: ThemeMode

        class Article(
            override val name: String,
            override val mode: ThemeMode,
            val viewModel: ArticleViewModel,
        ) : Scene

        /** The real shell — bottom bar, drawer, `NavHost` — over the live database. */
        class Shell(override val name: String, override val mode: ThemeMode) : Scene
    }

    private class Captures {
        val gate3 = mutableListOf<String>()
        val gate7 = mutableListOf<String>()
        val failures = mutableListOf<String>()
    }

    /**
     * The article screen for a deliberately hostile sample (gate 3), then the four
     * surfaces v0.2 added (gate 7).
     *
     * The sample is chosen *from what came back*, not hardcoded: the code-heavy and
     * image-heavy picks name their source and fall back to the whole corpus if that
     * source is down, so a dead feed degrades the sample instead of failing the gate on
     * something gate 1 already reported.
     */
    private fun capture(samples: List<Sample>): Captures {
        val captures = Captures()
        val scene = mutableStateOf<Scene?>(null)
        compose.setContent {
            scene.value?.let { current ->
                key(current.name) {
                    PerchTheme(mode = current.mode, dynamicColor = false) {
                        when (current) {
                            is Scene.Article -> ArticleScreen(viewModel = current.viewModel, onBack = {})
                            is Scene.Shell -> PerchNavHost(container = container)
                        }
                    }
                }
            }
        }

        val picks = listOfNotNull(
            pick(samples, "code-nullprogram") { it.feedUrl.contains("nullprogram.com") to it.code },
            pick(samples, "code-regehr") { it.feedUrl.contains("regehr.org") to it.code },
            pick(samples, "images") { it.feedUrl.contains("ciechanow.ski") to it.images },
            pick(samples, "enumerated") { true to it.enumerated },
            pick(samples, "longest-headline") { true to it.title.length },
        ).distinctBy { it.second.entryId }

        picks.forEach { (name, sample) ->
            listOf(ThemeMode.Light, ThemeMode.Dark).forEach { mode ->
                val suffix = if (mode == ThemeMode.Light) "light" else "dark"
                showArticle(scene, "$name-$suffix", mode, sample)
                captures.gate3 += line(capture("$name-$suffix"), sample)
            }
        }

        captureCode(scene, samples, captures)
        val zoomed = captureImageViewer(scene, samples, captures)
        captureLists(scene, captures)
        // The scoped list before the drawer, and not the other way round: opening the drawer
        // is a tap on the app bar, but *closing* it is a state change no assertion in this
        // file can see (the sheet composes while closed, NOTES.md/T22), and a tap meant for
        // the list behind an open drawer lands on the scrim.
        captureTheScopedList(captures)
        captureTheDrawerRefusing(captures)
        // Last, and deliberately: insets are dispatched to the Compose root itself, and the
        // root outlives a scene change. A cutout applied before the list shots would still
        // be on the window underneath them.
        captureTheViewerUnderACutout(scene, zoomed, captures)
        return captures
    }

    /**
     * U15's code shot: the code-heaviest article there is, scrolled until the first block
     * is on screen. A code block a third of the way down an article is not visible in a
     * capture of the top of it, and an unscrolled shot of a code post is a shot of its
     * standfirst.
     */
    private fun captureCode(scene: MutableState<Scene?>, samples: List<Sample>, captures: Captures) {
        val sample = samples.filter { it.code > 0 }.maxByOrNull { it.code }
        if (sample == null) {
            captures.failures += "gate 7: not one entry in the live corpus lowered to a code block"
            return
        }
        listOf(ThemeMode.Light, ThemeMode.Dark).forEach { mode ->
            val suffix = if (mode == ThemeMode.Light) "light" else "dark"
            showArticle(scene, "u15-code-$suffix", mode, sample)
            val blocks = compose.onAllNodesWithTag(ArticleTestTags.CODE).fetchSemanticsNodes()
            if (blocks.isEmpty()) {
                captures.failures += "gate 7: “${sample.title}” lowered to ${sample.code} code " +
                    "blocks and rendered none"
                return
            }
            compose.onAllNodesWithTag(ArticleTestTags.CODE)[0].performScrollTo()
            compose.waitForIdle()
            captures.gate7 += line(capture("u15-code-$suffix"), sample)
        }
    }

    /**
     * U12's overlay over a live figure. The image is opened by tapping the article's own
     * image block and then double-tapped, so what is captured is the viewer as a reader
     * reaches it rather than the composable in isolation.
     */
    private fun captureImageViewer(
        scene: MutableState<Scene?>,
        samples: List<Sample>,
        captures: Captures,
    ): Sample? {
        val ordered = samples.filter { it.images > 0 }.sortedByDescending { it.images }
        val sample = ordered.firstOrNull { candidate ->
            showArticle(scene, "u15-viewer-probe-${candidate.entryId}", ThemeMode.Dark, candidate)
            compose.onAllNodesWithTag(ArticleTestTags.IMAGE).fetchSemanticsNodes().isNotEmpty()
        }
        if (sample == null) {
            captures.failures += "gate 7: no live entry rendered an image block to zoom into"
            return null
        }
        showArticle(scene, "u15-image-viewer-dark", ThemeMode.Dark, sample)
        compose.onAllNodesWithTag(ArticleTestTags.IMAGE)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ArticleTestTags.IMAGE_VIEWER).performTouchInput { doubleClick() }
        compose.waitForIdle()
        captures.gate7 += line(capture("u15-image-viewer-dark"), sample)
        return sample
    }

    /**
     * V04/#3 over a live figure: the same viewer, under a punch-hole cutout deeper than the
     * status bar it sits in.
     *
     * Robolectric has no bars and no cutout on any device profile, so the window this runs
     * in is inset-free until the test says otherwise — which is why the reported symptom
     * (the close affordance under the status bar) is invisible to every other shot in this
     * file. `applyWindowInsets` dispatches them to the Compose roots directly; the assertion
     * beside the capture is what stops the shot from being a picture of a passing bug.
     */
    private fun captureTheViewerUnderACutout(
        scene: MutableState<Scene?>,
        sample: Sample?,
        captures: Captures,
    ) {
        if (sample == null) return
        showArticle(scene, "v15-viewer-cutout-dark", ThemeMode.Dark, sample)
        compose.onAllNodesWithTag(ArticleTestTags.IMAGE)[0].performClick()
        compose.waitForIdle()
        compose.applyWindowInsets(cutoutPx = CUTOUT_PX)

        val top = with(compose.density) {
            compose.onNodeWithTag(ArticleTestTags.IMAGE_VIEWER_CLOSE)
                .getUnclippedBoundsInRoot().top.toPx()
        }
        if (top < CUTOUT_PX) {
            captures.failures += "gate 7: under a ${CUTOUT_PX}px cutout the viewer's close " +
                "affordance sits at %.0fpx, inside it — V04's contract".format(top)
        }
        captures.gate7 += "  ${capture("v15-viewer-cutout-dark").file.name} — image viewer " +
            "under a ${CUTOUT_PX}px cutout, close at %.0fpx".format(top)
    }

    /**
     * The redesigned home and the To-Read queue, both through the real shell so the bottom
     * bar is in shot. The window is pinned to All Time and a handful of entries are saved
     * first, because a live pull's newest item can be days old and an empty queue is not
     * what gate 7 is asking to look at.
     */
    private fun captureLists(scene: MutableState<Scene?>, captures: Captures) {
        val staging = runBlocking {
            val how = fileForTheShot()
            settings.setTimeFilter(TimeFilter.AllTime)
            database.entryDao().observeAll().first().take(SAVED_FOR_THE_SHOT).forEach {
                container.entries.setSaved(it.id, true)
                container.entries.setLiked(it.id, true)
            }
            how
        }
        captures.gate7 += "  $staging"

        scene.value = Scene.Shell("u15-home-dark", ThemeMode.Dark)
        compose.awaitInRealTime("the Feed to fill") {
            compose.onAllNodesWithTag(HomeTestTags.ENTRY).fetchSemanticsNodes().isNotEmpty()
        }
        // W03: the Feed is one chronological stream. What used to be counted here was
        // folder sections; what is checked now is that no folder moved an article — the
        // live list, read a full page deep, is in non-increasing publication order.
        val page = runBlocking { container.entries.observeEntries(includeRead = true).first() }
            .take(PerchPaging.PAGE_SIZE)
        val outOfOrder = page.zipWithNext().count { (above, below) ->
            above.publishedAt < below.publishedAt
        }
        if (page.size < PerchPaging.PAGE_SIZE) {
            captures.failures += "gate 7: only ${page.size} live entries, fewer than the " +
                "${PerchPaging.PAGE_SIZE}-row page the order is supposed to hold across"
        }
        if (outOfOrder > 0) {
            captures.failures += "gate 7: $outOfOrder of the first ${page.size} live rows are " +
                "older than the row above them; the Feed is not one chronological stream"
        }
        val sections = page.map { it.folderName }.distinct().size

        // V15 clause (7) asks for *mixed* thumbnails, and V07 made the missing one a drawn
        // state rather than an absent one — so the shot has to carry both kinds. The mark is
        // the tell: it is drawn only once an image is known not to be coming, so counting it
        // separates V07's finished placeholder from the bare frame of an image in flight.
        val marks = nodes(EntryRowTestTags.THUMBNAIL_MARK)
        val images = nodes(EntryRowTestTags.THUMBNAIL_IMAGE)
        if (marks == 0) {
            captures.failures += "gate 7: not one row on the home shot draws V07's placeholder, " +
                "so the shot says nothing about what a thumbnail-less source looks like"
        }
        if (images == 0) {
            captures.failures += "gate 7: not one row on the home shot resolved a thumbnail, " +
                "so the shot is not the mixed list clause (7) asks for"
        }
        captures.gate7 += "  ${capture("u15-home-dark").file.name} — home, one stream " +
            "carrying $sections categories across the first page, $images thumbnails and " +
            "$marks V07 placeholders on screen"

        compose.onNodeWithTag(NavTestTags.tab(PerchTab.ToRead)).performClick()
        compose.awaitInRealTime("the queue to load") {
            compose.onAllNodesWithTag(CollectionTestTags.ENTRY).fetchSemanticsNodes().isNotEmpty()
        }
        captures.gate7 += "  ${capture("u15-to-read-dark").file.name} — To-Read, $SAVED_FOR_THE_SHOT saved"

        compose.onNodeWithTag(NavTestTags.tab(PerchTab.Liked)).performClick()
        compose.awaitInRealTime("the liked list to load") {
            compose.onAllNodesWithTag(CollectionTestTags.ENTRY).fetchSemanticsNodes().isNotEmpty()
        }
        captures.gate7 += "  ${capture("u15-liked-dark").file.name} — Liked, $SAVED_FOR_THE_SHOT liked"

        compose.onNodeWithTag(NavTestTags.tab(PerchTab.Feed)).performClick()
        compose.awaitInRealTime("the Feed to come back") {
            compose.onAllNodesWithTag(HomeTestTags.ENTRY).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * V10/#5: the drawer mid-source-selection, where a folder header is a rule the reader
     * can see. The rule itself (a source selection will not take a folder) is
     * `DrawerMultiSelectTest`'s; what is live here is that it holds against a real library —
     * forty sources under real folder names, not two seeded rows.
     *
     * The row is long-pressed through its own semantics action: an injected tap never
     * reaches a node inside an opened drawer sheet (NOTES.md, T22).
     */
    private fun captureTheDrawerRefusing(captures: Captures) {
        val (source, folder) = runBlocking {
            val folders = database.folderDao().getAll()
                .filter { it.id != FolderEntity.UNCATEGORIZED_ID }
            val titles = database.feedDao().getAll().groupBy { it.title }
            val feed = folders.firstNotNullOfOrNull { folder ->
                database.feedDao().getAll()
                    .firstOrNull { it.folderId == folder.id && titles.getValue(it.title).size == 1 }
            }
            feed to folders.firstOrNull()
        }
        if (source == null || folder == null) {
            captures.failures += "gate 7: no uniquely-named source inside a named folder, so " +
                "V10's refused header cannot be addressed on the live library"
            return
        }

        compose.onNodeWithContentDescription("Open sources").performClick()
        compose.waitForIdle()
        // §0.1: the folder opens shut, and a source row does not exist in the tree until
        // its folder does (X02) — expand it before the row can be long-pressed at all.
        compose.onNodeWithTag(HomeTestTags.folderExpand(folder.id))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        drawerRow(source.title).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()

        val header = compose.onNodeWithTag(HomeTestTags.folderHeader(folder.id))
        val enabled = header.fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Disabled) == null
        if (enabled) {
            captures.failures += "gate 7: mid-source-selection “${folder.name}” is still drawn " +
                "as available; V10 says an unavailable header has to say so"
        }
        if (compose.onAllNodesWithTag(SelectionTestTags.BAR).fetchSemanticsNodes().isEmpty()) {
            captures.failures += "gate 7: long-pressing “${source.title}” did not open the " +
                "selection bar, so the drawer shot is not mid-selection at all"
        }
        captures.gate7 += "  ${capture("v15-drawer-selection-dark").file.name} — drawer " +
            "mid-source-selection on “${source.title}”, “${folder.name}” refused"
        // Left open and left in selection: this is the last shot the shell is used for.
    }

    /**
     * V08/#10: the list scoped to one source, reached the way a reader reaches it — by
     * tapping the blog's name in an article's byline, not by setting the state behind it.
     *
     * Read entries are kept in the list for this one shot. Opening the article to *get* to
     * the byline marks it read, and on a quiet source that is the difference between a
     * scoped list and an empty one — an empty scoped list would be a shot of issue #9's
     * complaint rather than of V08's feature.
     */
    private fun captureTheScopedList(captures: Captures) {
        runBlocking { settings.setShowReadEntries(true) }
        val top = runBlocking {
            database.entryDao()
                .observeListItems(feedId = null, folderId = null, includeRead = true, publishedAfter = null)
                .first().firstOrNull()
        }
        if (top == null) {
            captures.failures += "gate 7: the live list is empty, so there was no article to open"
            return
        }
        compose.awaitInRealTime("the Feed to hold the row it is about to open") {
            compose.onAllNodesWithTag(HomeTestTags.ENTRY).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag(HomeTestTags.ENTRY)[0].performClick()
        compose.awaitInRealTime("“${top.title.take(HEADLINE_ECHO)}” to open") {
            compose.onAllNodesWithTag(ArticleTestTags.SOURCE).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ArticleTestTags.SOURCE).performClick()
        compose.awaitInRealTime("the scoped Feed to load") {
            compose.onAllNodesWithTag(HomeTestTags.ENTRY).fetchSemanticsNodes().isNotEmpty()
        }

        val title = compose.onNodeWithTag(HomeTestTags.TITLE).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        if (title != top.sourceTitle) {
            captures.failures += "gate 7: tapping “${top.sourceTitle}” in the byline left the " +
                "Feed titled “$title” — V08 scopes the list to the source the reader tapped"
        }
        captures.gate7 += "  ${capture("v15-scoped-source-dark").file.name} — the Feed scoped " +
            "to “${top.sourceTitle}” from the article's byline"
    }

    /** A drawer row, addressed by its label exactly as `DrawerMultiSelectTest` does. */
    private fun drawerRow(label: String) =
        compose.onAllNodesWithText(label).filterToOne(hasClickAction())

    private fun nodes(tag: String) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    /**
     * Re-files the live library so the home shot can show what §0's home is *for*.
     *
     * **W03 changed what "for" means here.** Until this plan the Feed ordered by folder
     * first and recency second, so staging chose the two *quietest* sources: a small folder
     * fitted inside the first page and put a second section header on screen where a quarter
     * of a thousand-entry corpus did not. There are no sections now. The first screenful is
     * simply the newest thirty entries, and a folder places nothing — what it still does is
     * name each row's category beside its source (W04/#20). So the sources worth staging are
     * the ones whose newest entries are actually *on* that screenful, and the rule inverts:
     * **freshest first, not quietest first.** Staged by the old rule on 2026-08-18 this shot
     * came back reading "Systems" on all six visible rows: a true picture of one stream and
     * a useless one of a category label.
     *
     * The folders are taken in the order the **reader** sees them, which since V06 is
     * alphabetical and not `sortIndex`. Staging by creation order instead is what left this
     * shot with one section of three from V06 until V15: the bulk went to `Graphics`, which
     * was created last and displayed first, so the whole first page was one folder.
     *
     * This is staging, in the same sense as the eight saved entries below and the flat
     * placeholder images in [setUp]: it arranges what the camera points at, and asserts
     * nothing about folders. Gate 6, above, is where folder membership is actually tested,
     * and it has already run and passed against the arbitrary split by the time this moves
     * anything. It cannot bias the thumbnail mix either — since W03 nothing about a folder
     * decides which rows are on screen, so the placeholders and images the shot catches are
     * the corpus's own and not the staging's.
     */
    private suspend fun fileForTheShot(): String {
        val named = database.folderDao().getAll()
            .filter { it.id != FolderEntity.UNCATEGORIZED_ID }
        if (named.size < MIN_NAMED_FOLDERS) return "not staged: ${named.size} named folders"
        val freshestFirst = database.feedDao().getAll()
            .map { it to database.entryDao().observeByFeed(it.id).first() }
            .sortedByDescending { (_, entries) -> entries.maxOfOrNull { it.publishedAt } ?: 0L }

        // One opener per named folder bar the last, taken off the top: a source's newest
        // entry is on the first screenful only if few entries anywhere are newer than it, so
        // the freshest sources are exactly the ones whose category label the camera can see.
        // [OPENER_POOL] is the guard, not the selector — a source that has published nothing
        // for a year cannot put its name on the shot, so it is no more useful as an opener
        // than the bulk folder it would otherwise fall into.
        val openers = freshestFirst.take(minOf(named.size - 1, OPENER_POOL))

        openers.forEachIndexed { index, (feed, _) ->
            container.folders.moveSource(feed.id, named[index].id)
        }
        freshestFirst.map { (feed, _) -> feed }
            .filterNot { feed -> openers.any { it.first.id == feed.id } }
            .forEach { container.folders.moveSource(it.id, named.last().id) }

        return "staged: " + openers.mapIndexed { index, (feed, rows) ->
            val newest = rows.maxOfOrNull { it.publishedAt } ?: 0L
            val hours = (System.currentTimeMillis() - newest) / 3_600_000
            "${named[index].name} ← ${feed.title} (${rows.size} entries, newest ${hours}h old)"
        }.joinToString("; ") + "; ${named.last().name} ← the rest"
    }

    private fun showArticle(
        scene: MutableState<Scene?>,
        name: String,
        mode: ThemeMode,
        sample: Sample,
    ) {
        val viewModel = ArticleViewModel(
            entries = container.entries,
            feeds = container.feeds,
            articleText = container.articleText,
            entryId = sample.entryId,
            zone = ZoneOffset.UTC,
        )
        scene.value = Scene.Article(name, mode, viewModel)
        compose.awaitInRealTime("“${sample.title}” to load") {
            viewModel.state.value !is ArticleUiState.Loading
        }
    }

    private fun capture(name: String): Screenshots.Shot {
        val shot = Screenshots.capture(compose, compose.activity, Screenshots.dir(SCREENSHOT_DIR), name)
        check(shot.distinctColours > MIN_COLOURS) { "${shot.file.name} rendered a blank slab" }
        return shot
    }

    private fun line(shot: Screenshots.Shot, sample: Sample) =
        "  ${shot.file.name} (${shot.file.length() / 1024} KB) — " +
            "${sample.feedUrl}: “${sample.title.take(HEADLINE_ECHO)}”"

    /**
     * The sample scoring highest on [by], among those it admits. Falls back to the whole
     * corpus when the named source contributed nothing, so a feed that was down at gate 1
     * costs the sample its best case rather than costing gate 3 a screenshot.
     */
    private fun pick(
        samples: List<Sample>,
        name: String,
        by: (Sample) -> Pair<Boolean, Int>,
    ): Pair<String, Sample>? {
        val admitted = samples.filter { by(it).let { (eligible, score) -> eligible && score > 0 } }
        val best = admitted.maxByOrNull { by(it).second }
            ?: samples.maxByOrNull { by(it).second }
            ?: return null
        return name to best
    }

    // ---- harness ----------------------------------------------------------------

    private fun readingList(): List<String> =
        File(Screenshots.repoRoot(), "fixtures/feeds.txt").readLines()
            .map { it.trim() }
            .filter { it.startsWith("http") }

    /** The reading list minus [EXCLUDED_SOURCES] — the sources gate 1 holds to account. */
    private fun feedUrls(): List<String> = readingList().filterNot { it in EXCLUDED_SOURCES }

    /**
     * An exclusion naming a URL the reading list no longer carries is worse than no
     * exclusion at all: it reads as "we know about that one" while excusing nothing, and
     * the next source to break under the same name would be excused silently.
     */
    private fun staleExclusions(): List<String> {
        val list = readingList().toSet()
        return EXCLUDED_SOURCES.keys.filterNot { it in list }.map {
            "gate 1: EXCLUDED_SOURCES names $it, which is not in fixtures/feeds.txt — " +
                "drop the exclusion, it is excusing nothing"
        }
    }

    /** Straight to stdout: these counts are what the commit message has to quote. */
    private fun report(gate: String, body: String) = println("\n$gate\n$body")

    /** Every remote image becomes one flat, fixed-shape placeholder. See [setUp]. */
    private class FlatImages(private val context: Context) : Mapper<String, Drawable> {
        override fun map(data: String, options: Options): Drawable = BitmapDrawable(
            context.resources,
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
                eraseColor(PLACEHOLDER)
            },
        )

        private companion object {
            const val WIDTH = 1200
            const val HEIGHT = 675
            const val PLACEHOLDER = 0xFF8E9A94.toInt()
        }
    }

    private companion object {
        const val LIVE_PROPERTY = "perch.live"

        /**
         * PLAN-3 V12: sources in `fixtures/feeds.txt` that are known not to pull, and why.
         * Gate 1 does not fetch them — it prints them — so the budget goes to the sources
         * whose reachability is still a question, and every other source is held to a hard
         * gate. `37/42` used to mean "something, somewhere"; a name means something.
         *
         * An entry here is a decision, not a snooze: it says the source is out of scope for
         * Perch as specified, with the measurement that settled it. Two of them are over
         * SPEC.md §6's 8 MiB body cap — deliberately, see §6: the cap is what keeps four
         * concurrent refreshes inside a phone's heap, and these two are full-archive feeds
         * whose *whole point* is being enormous.
         */
        val EXCLUDED_SOURCES = mapOf(
            "https://danluu.com/atom.xml" to
                "the full archive in one document; measured 11.1 MB (2.3 MB gzipped) on " +
                "2026-08-10, over SPEC §6's 8 MiB body cap",
            "https://googleprojectzero.blogspot.com/feeds/posts/default" to
                "full post bodies, no paging; measured 13.2 MB (9.6 MB gzipped) on " +
                "2026-08-10, over SPEC §6's 8 MiB body cap",
            "https://rachelbythebay.com/w/atom.xml" to
                "port 443 never answers from this network — connect times out at 15s " +
                "(2026-08-10). The feed itself is well-formed: fixtures/snapshots holds it",
        )

        /** PLAN.md T32 gate 2. */
        const val UNSUPPORTED_PERCENT = 2

        /** PLAN-2 U15 gate 4, over the sampled-and-fetched set. See [OpenReport.fetchedPercent]. */
        const val MIN_THUMBNAIL_PERCENT = 60

        /**
         * What U15 actually measured on 2026-08-09, which V15 clause (5) turns into the
         * floor. See [thumbnailsPerSource] for why the drift is one-directional slack and
         * not a tolerance band.
         *
         * Ten points is not a shrug: the set it scores is the *sampled*-and-fetched one,
         * around seventy entries, so a single entry moves it by 1.4 points and three runs an
         * hour apart on 2026-08-10 spanned 69.6% to 72.5% with no change to the code between
         * them. A narrower band reds on which of fabiensanglard.net's 144 posts the sample
         * happened to take. What is left is still a ratchet: 65.4% is the floor, against
         * [MIN_THUMBNAIL_PERCENT]'s 60.
         */
        const val U15_THUMBNAIL_PERCENT = 75.4
        const val LIVE_DRIFT = 10.0

        /**
         * The zone issue #9 was reported from — "no articles, usually around 7 or 8 p.m.
         * Central". Gate 8's window no longer depends on it (W02/#15 made the window
         * rolling), but every instant that gate prints is rendered in it, so a failure
         * still reads in the reader's own wall clock rather than in Greenwich's.
         */
        val READER_ZONE: ZoneId = ZoneId.of("America/Chicago")

        /**
         * V09/#4's page, fetched by name for gate 6b: ZDI's feed ships full bodies, so the
         * reading list never exercises the *extraction* path that used to drop the table.
         * `fixtures/articles/zdi-page-june-2026-apple-update-review.html` is the offline
         * copy of this same URL.
         */
        const val ZDI_PAGE_URL =
            "https://www.thezdi.com/blog/2026/6/30/the-june-2026-apple-security-update-review"

        /**
         * W06/W07/#17's page, fetched by name for gate 5c.
         * `fixtures/articles/huggingface-efficient-knowledge-distillation.html` is the
         * offline copy of this same URL; the live one is what notices a Tailwind rebuild.
         */
        const val BLIND_SPOT_PAGE_URL =
            "https://huggingface.co/blog/MultiverseComputingCAI/efficient-knowledge-distillation"

        /** Where a page keeps the teaser a feed would have carried, best first. */
        val TEASER_META = listOf("meta[property=og:description]", "meta[name=description]")

        /** PLAN-2 U15 gate 5, for both the recovery share and the tenfold share. */
        const val MIN_RECOVERY_PERCENT = 90

        /**
         * What counts as having recovered "real prose" — two or three paragraphs. A lower
         * bar than [FullText.MIN_PROSE_CHARS] on purpose: that constant is the *trigger*,
         * the question of whether to go and look, and reusing it here would quietly assert
         * that no source in the reading list ever publishes a genuinely short post.
         */
        const val RECOVERED_PROSE_CHARS = 500

        /**
         * The teaser shape §0 names — gpuopen.com's 194-character `<description>`. The
         * tenfold rule is scored over these rather than over every excerpt, because a body
         * of eleven hundred characters is a *short article* as often as it is a stub and
         * demanding eleven thousand back from one would be asserting something about the
         * publisher. Every excerpt's ratio prints regardless.
         */
        const val TEASER_CHARS = 400
        const val TENFOLD = 10.0

        /** §0's teaser exemplar, probed live because it is not one of the forty-one. */
        const val GPUOPEN_FEED = "https://gpuopen.com/feed/"
        const val NOT_PULLED = "not pulled"

        /** Per source, so one prolific feed cannot become the whole sample. */
        const val SAMPLE_PER_SOURCE = 3

        /** The repository's own refresh concurrency (SPEC.md §7). */
        const val CONCURRENCY = 4

        val FOLDER_NAMES = listOf("Systems", "Security", "Graphics")

        /** Two named sections plus Uncategorized is what §0's home is supposed to look like. */
        const val MIN_NAMED_FOLDERS = 2
        const val SAVED_FOR_THE_SHOT = 8

        /** How far down the freshest sources [fileForTheShot] may look for its openers. */
        const val OPENER_POOL = 12

        const val SCREENSHOT_DIR = "build/perch-screenshots"
        const val HEADLINE_ECHO = 60
        const val TABLE_LABEL = 44
        const val TABLE_ROWS = 10
        const val MIN_COLOURS = 8

        fun percent(part: Int, whole: Int): Double = if (whole == 0) 100.0 else part * 100.0 / whole
    }
}
