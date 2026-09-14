# PLAN-12 — v0.8.0: the archive is a place you can keep scrolling into, and articles that read right

Four reader reports from 2026-09-14 — **#67** (captions read as body text), **#68** (the
archive is a 40-post cliff), **#69** (backfill duplicates every post), **#70** (Bellingcat
renders badly) — plus the five tech-debt issues v0.6.1's re-survey left for "the next plan"
(**#60–#64**) and the one finding v0.7.0's review parked (**#71**). One **MINOR** release.

Sessions read this file cold; every task carries its anchors so a session **reads, never
searches**. Anchors are line numbers at `79a4943` (the v0.7.0 archive commit) unless a task
says otherwise; if a line has drifted, grep the *name* quoted beside it — never the number.

## §0 — Decisions for this version (authoritative; do not re-derive)

### §0.1 The version is `0.8.0`, `versionCode` **10**, database **8 → 10** in two steps

#68 is a new user-visible behaviour, so the **MINOR** digit moves (SPEC.md §1).
`perchVersionCode` 9 → 10, `perchVersionName` `0.7.0` → `0.8.0`, at `app/build.gradle.kts:12-13`
and **nowhere else**. Two tasks touch the schema, each with a real `Migration`, an exported
`app/schemas/dev.mkiros.perch.data.db.PerchDatabase/N.json` (Room writes it on the next
compile through `room.schemaLocation`; `./gradlew :app:kspDebugKotlin` is the cheapest task
that does; **commit it**) and a `PerchMigrationNToMTest` beside `PerchMigration7To8Test`
(`app/src/test/.../data/db/PerchMigration7To8Test.kt:25`, copy its `ExportedSchemas.seedVersion`
/ `openAtCurrentVersion` shape):

- **8 → 9 (F02)** is data-only: it deletes the duplicate rows #69 already left on the reader's
  phone. The schema does not change, so `9.json` differs from `8.json` in its version only.
- **9 → 10 (F07)** adds the `archive_posts` table (§0.4).

`fallbackToDestructiveMigration()` never comes back. v0.7.0 is on the human's phone.

### §0.2 Duplicates (#69): one article is one row, whichever path found it first

**The bug.** The feed poll stores WordPress's `<guid isPermaLink="false">…/?p=53745</guid>` as the
entry's identity (`RssParser.kt:21` `identity = { plainText(item.childText("guid")) }`;
`ItemMapping.kt:44-47` uses `link` only as a *fallback*). The backfill stores the page's final
URL as the guid (`PageContent.kt:94` `guid = finalUrl`, `:98` `link = finalUrl`). The only
uniqueness anywhere is `(feedId, guid)` — the index at `EntryEntity.kt:47`, `EntryDao.upsertAll`'s
one lookup `findByGuid` (`EntryDao.kt:575`, query at `:225`), and `BackfillRepository.plan`'s
filter against `guidsForFeed` (`BackfillRepository.kt:81-82`, DAO `:252`). `entries.link` is
never consulted. Both directions duplicate: poll-then-backfill (the reader's screenshot) and
backfill-then-poll (a post the archive fetched that the feed later lists).

**The fix, in three parts, no schema change:**

1. **`upsertAll` matches by link as well as guid.** A new `EntryDao.findByGuidOrLink(feedId,
   guid, link)` — `WHERE feedId = :feedId AND (guid = :guid OR (:link IS NOT NULL AND link =
   :link)) ORDER BY (guid = :guid) DESC LIMIT 1` — replaces `findByGuid` at `EntryDao.kt:575`.
   When the match came through the link, the existing row **keeps its guid**: the `existing !=
   null` branch (`:577-587`) adds `guid = existing.guid` to the copy, so identity stays with
   whichever row was first and `pending_entry_state` (keyed by guid, U14) is untouched.
2. **The backfill's candidate filter compares normalised URLs against both columns.** A pure
   `urlKey(url: String): String` in a new `data/parse/UrlKey.kt`: lowercase scheme and host, drop
   a leading `www.`, drop the fragment, drop `utm_*`/`fbclid`/`gclid` query parameters (and the
   `?` if none remain), strip one trailing `/`, drop a default port. A new
   `EntryDao.identitiesForFeed(feedId): List<EntryIdentity>` (`guid`, `link`) replaces
   `guidsForFeed`, and `plan()` builds the stored set from `urlKey` of every guid *and* every
   non-null link. `run()`'s `fetchAndStore` re-checks after the redirect (`:136-139` —
   `fetched.finalUrl` may differ from `post.url`) before `upsertAll`.
3. **`urlKey` is used for matching only.** `entries.link` and `entries.guid` are stored exactly
   as the feed or page gave them; nothing rewrites a stored value. No hostname, no site rule:
   PLAN-10 §0.2's grep gate applies (`grep -rnoE '"[a-z0-9.-]+\.(com|org|net|io|dev|me|ski|ca|xyz|blog)"' app/src/main/java/dev/mkiros/perch/data/` must stay empty).

**The cleanup (F02, `MIGRATION_8_9`).** For every pair of same-feed rows sharing a non-null
`link` where one row's `guid = link` (the backfill copy) and the other's does not (the feed
row), the feed row is the keeper: OR the four reader-owned flags into it first (`isRead`,
`isSaved`, `isStarred`, and `scrollPosition = MAX`), carrying the matching `*At` timestamp, then
delete the copy. Where *both* rows are backfill copies (`guid = link` twice cannot happen — the
index forbids it) or *neither* is, delete nothing. The search index needs no statement: the
`entries_fts_delete` trigger (`PerchDatabase.kt:259-260`, shipped in `MIGRATION_6_7`) removes the
FTS row for every deleted entry. The migration test seeds a v8 file with a duplicated pair where
the copy is read and the keeper is not, opens at the current version, and asserts one row, read.

**The title the reader sees** on a backfilled row is `og:title` (`PageMetadata.kt:43-48`),
which is why the copy carried " - bellingcat"; once one row survives, the feed's `<title>` wins
because the feed row is the keeper. Do not change `PageMetadataExtractor`'s rung order here.

### §0.3 Rendering (#70, #67): three structural rules, no site knowledge

**Facts a session would otherwise re-derive.** Bellingcat's feed ships full `content:encoded`
(~164 k chars per item) *and* `FullText.needsExtraction` (`FullText.kt:41-44`) still fires on
its trailing "The post … appeared first on" — so both the feed path (`HtmlSanitizer` →
`ArticleLowering`) and the page path (`ArticleExtractor` → the same two) render it. Its images are
**not** lazy-loaded (every `src` is real); they are all inside `<div class="media">`, and
`media` is a token in `ArticleExtractor.NEGATIVE` (`ArticleExtractor.kt:379`), so `strip()`
(`:131-133`) and `clean()` (`:303-305`) delete each image *with its container*. The donation
block is `<div class="wp-block-bellingcat-donate-block">` holding an `<h2>Support Bellingcat</h2>`,
one paragraph and a `wp-block-button` link — no `<aside>`, no `role`, no `<form>`; only class
tokens name it, and `HtmlSanitizer`'s `Cleaner` discards `class`/`id` (`HtmlSanitizer.kt:144`)
before `ArticleLowering` ever runs. GIJN's captions are WordPress's legacy shortcode:
`<div class="wp-caption"><img aria-describedby="caption-attachment-N"><p id="caption-attachment-N"
class="wp-caption-text">…</p></div>` — no `<figure>`, and `div` is in `ArticleLowering.UNWRAP`
(`ArticleLowering.kt:334`), so it flattens to `Image` + `Paragraph`. Meanwhile
`<figure><figcaption>` **already** lowers to `ArticleBlock.Image(caption)` (`ArticleLowering.kt:110-115`)
and renders at `ArticleType.caption` in `onSurfaceVariant` (`ArticleBody.kt:299-308`, `Type.kt:145`,
DESIGN.md:316/348). **No new block type, no new text style.**

**Where the rules go.** The **pre-`Cleaner` pass in `HtmlSanitizer.sanitize`**
(`HtmlSanitizer.kt:47-49`, where `DROP_WHOLESALE` and the tracking-pixel filter already run) is
the last point on the feed path where `class`, `id` and ARIA attributes exist. Every rule below
runs there, on the parsed `Document`, so the page path (which calls the same sanitizer after
extraction — `PageContentExtractor`, D16) gets it for free.

1. **Captions (F05).** An `<img>` whose `aria-describedby` names an element in the same document
   becomes `<figure><img …><figcaption>{that element's inline content}</figcaption></figure>`,
   replacing the img and removing the described element — WAI-ARIA is the standard; WordPress
   merely emits it. Structural fallback for the same shape without ARIA: a container holding
   exactly one `<img>` and, after it, exactly one `<p>`/`<span>`/`<small>`/`<div>` whose class
   token list matches `\b(caption|credit|cutline)\b` — same rewrite. **Nothing looser**: the
   reader's "different font and colour under an image" cannot be seen (the sanitizer strips
   `style`, `RichSpan` carries no colour or size — `ArticleBlock.kt:48-50`), and an `<em>`
   paragraph after a lead image is as often a pull-quote or an editor's note. Say both things in
   the closing comment on #67. An `<img>` already inside a `<figure>` is left alone.
2. **Promotional blocks (F04).** An element whose `class` or `id` tokens match
   `\b(donat\w*|promo\w*|banner|newsletter|subscribe|subscription|appeal|membership|support-us|cta|call-to-action)\b`
   is removed **only if** it also (a) contains at least one `<a>` or `<button>` and (b) carries
   fewer than **400** characters of text — both conditions, so a `<div id="donation-records">`
   wrapping three real paragraphs survives and a naked `<p class="promo">` with no link does too.
   The same new tokens (`donat`, `appeal`, `membership`) join `ArticleExtractor.NEGATIVE`
   (`:379`) so the extractor's own scoring agrees. The constant lives once, in `HtmlSanitizer`,
   and the extractor reads it from there.
3. **Images inside a chrome-named container (F03).** `ArticleExtractor.namesChrome()`
   (`:319-324`) returns **false** for an element that contains a content `<img>` (not a
   tracking pixel — reuse the sanitizer's rule) and fewer than 400 characters of text: a
   container named `media`/`thumb`/`gallery` that is mostly an image *is the content*. In the
   same task, the feed path gains the lazy-source promotion the page path already has:
   `ArticleExtractor.LAZY_SRC` (`:371` — `data-src`, `data-lazy-src`, `data-original`,
   `data-srcset`) moves into the sanitizer's pre-`Cleaner` pass, where `src` is set from the
   first present lazy attribute when `src` is absent, empty, a `data:` URI or a 1×1, and the
   widest candidate of `srcset` is used when nothing else is. The extractor calls the shared
   helper instead of its own copy (PLAN-10 §0.2: prefer deleting to adding).

**The corpus grows in the planning commit, not in a task.** `fixtures/snapshots/bellingcat-com.xml`
(10 items, 439 kB, under SPEC §6's 8 MiB), its `fixtures/manifest.tsv` line and its
`fixtures/feeds.txt` line are already committed with this plan; `FeedCorpusTest`,
`ArticleLoweringCorpusTest`, `TableCorpusTest`, `ThumbnailCorpusTest` and `PageMetadataCorpusTest`
were run green over it before the plan was committed (title 19/25, date 6/25). Two page fixtures
are also in place, **unlisted** in `ArticleFixtures` until their task lists them:
`fixtures/articles/bellingcat-kinahan-visas.html` (F03) and `fixtures/articles/gijn-conflict-zones.html`
(F05). **`gijn.org` answers 403 to every non-browser client**, Perch's User-Agent included — it
must **not** join `fixtures/feeds.txt` (gate 1 of live acceptance has no quota, V12/#8); its page
was saved from a browser-agent fetch and is the whole of GIJN's presence in the corpus.

### §0.4 Scrolling into history (#68): the archive plan is remembered, and fetched a batch at a time

**What already exists and stays.** `ArchiveDiscovery` (`ArchiveDiscovery.kt:44`) finds the
archive (RFC 5005 → `robots.txt` `Sitemap:` → `/sitemap.xml`) and stores nothing (`:41`).
`BackfillRepository.plan()` (`:66`) re-runs discovery every call — up to 50 sitemap fetches —
subtracts what is stored, and `run()` (`:103`) fetches the newest `MAX_PAGES = 40` (`:174`).
Because `run()` dedupes against the entries table, **a second run already fetches the next 40**
— the batching engine exists; what is missing is memory, a cursor that survives retention
pruning, and a trigger at the bottom of the list. `MAX_PAGES` is PLAN-7 §0.3's politeness budget
and PLAN-8 R00 says not to raise it; **it stays 40 and is the batch size.**

**The table (F07).** `archive_posts(feedId INTEGER NOT NULL, url TEXT NOT NULL, lastmod INTEGER,
discoveredAt INTEGER NOT NULL, fetchedAt INTEGER, PRIMARY KEY(feedId, url), FOREIGN KEY(feedId)
REFERENCES feeds(id) ON DELETE CASCADE)`, entity `ArchivePostEntity`, DAO `ArchivePostDao`.
Rules:

- `plan(feedId)` **discovers only when the feed has no rows or its newest `discoveredAt` is older
  than 7 days**; a discovery `INSERT OR IGNORE`s every post found (never deletes — a sitemap
  that shrinks does not forget history). Rows whose `urlKey` (§0.2) matches a stored entry's
  guid or link are stamped `fetchedAt = now` at discovery time, so the first `plan()` after
  adding a source leaves the ten feed items marked.
- `BackfillPlan.newPostCount` becomes the count of rows with `fetchedAt IS NULL`;
  `toFetch` is the newest 40 of those by `lastmod DESC NULLS LAST` (the order at `:91`).
  `isWorthwhile` keeps its `MATERIALLY_MORE_FACTOR` rule for the add-source offer only.
- `run()` stamps `fetchedAt` on every page it fetched **or skipped as already stored** (§0.2's
  redirect re-check included) and on a page that failed with a 4xx — a 5xx or a network error
  leaves it null so a later batch retries. Because the cursor is now the stamp and not the
  entries table, `deleteReadOlderThan` (`EntryDao.kt:431`, called on every refresh from
  `FeedRepository.kt:374` with `RETENTION` at `:459`) can no longer make a later batch
  re-download history the reader already read.
- The reach sentence (`BackfillOfferUi.kt:146`, `HomeScreen.kt:444-452`) and the offer's two
  numbers (`strings.xml:149-159`) read `newPostCount` from the table. `sourceReach`
  (`BackfillRepositoryTest.kt:333`'s contract) is unchanged.
- Removing a source cascades; `FeedDao`'s delete needs no edit (foreign key). Profile export
  (U14) does **not** carry `archive_posts` — a restore rediscovers.

**The trigger (F08).** `HomeViewModel.loadOlder(feedId)` enqueues `backfillRunner.enqueue(feedId)`
directly and sets `_runningBackfillId` (`HomeViewModel.kt:356`) so the existing
`BackfillProgressStrip` (`BackfillOfferUi.kt:78`, `HomeScreen.kt:431`) narrates it — **no dialog,
and not through `sourceAdded()`** (`:389`), whose `isWorthwhile` gate would refuse a second batch.
`PagedEntryList` (`PagedList.kt:59`) gains an optional trailing-content slot rendered **before**
`pagedFooter` (`:88`) — To-Read, Liked and search pass nothing and are untouched. `HomeScreen`
fills it **only when `timeFilter == AllTime && scope is HomeScope.Source`**, the reach sentence's
own guard (`:444`), and only when `newPostCount > 0`: one row, `ArchiveFooter`, in
`ui/home/BackfillOfferUi.kt` beside the strip, reading

> *N older posts in this source's archive* — **Load 40 more**

(plurals `archive_footer_remaining`, button `archive_footer_load`, capped at `min(40, N)`),
disabled with the label *Fetching…* while `backfillProgress?.isRunning` (WorkManager's `KEEP`
policy, `BackfillWorker.kt:70`, would otherwise make a second tap join the same run silently).
When `newPostCount` is 0 the slot is empty and `pagedFooter`'s existing "that's everything"
marker stands. **Scoped to one source only**: the runner, the unique work name
(`BackfillWorker.kt:61`) and the reach are all per-`feedId`; "All sources" would fan out N
workers with no shared budget, which PLAN-7 §0.3 forbids. In All-sources and folder scopes the
list ends as it does today. The drawer's existing "Fetch older posts" (`requestBackfill`,
`:402`) stays as the second way in.

**The gesture (F09).** The reader asked for the mirror of pull-to-refresh at the bottom: at the
end of the list, dragging further up past a threshold triggers `loadOlder`. Compose ships no
bottom `PullToRefreshBox`. The shape is a `NestedScrollConnection` on the list that, while
`!listState.canScrollForward` and the footer is showing, accumulates `onPostScroll`'s unconsumed
upward `available.y`, moves the footer's label to *Release to load older posts* past **72 dp**,
and fires once on `onPreFling`/release; the accumulation resets when the finger lifts short.
The arithmetic is a pure `PullUpState` (`ui/home/PullUp.kt`) with its own unit test, the way
`BackChain` keeps back policy pure; the connection is thin. **Two attempts.** If injected
`swipeUp()` in Robolectric cannot reach the connection, the pure test plus a passing
`PagedFeedTest` on the tappable footer is the proof, the box records `[BLOCKED: gesture untestable
in Robolectric — footer button stands]`, and the button from F08 is what ships.

**"All time shows a limited number by default."** Paging already does this: `PerchPaging.PAGE_SIZE`
is 30 (`EntryRepository.kt:38`) and the list appends as the reader scrolls. Nothing to build.

### §0.5 The tech-debt five (#60–#64) and #71: behaviour unchanged, tests unchanged

Each is a refactor whose proof is the suite staying green **and** the commit naming the existing
tests that pin the behaviour it touched (PLAN-10 §0.2 rule 1). #60 and #71 are the two that
change behaviour, so they are TDD with a RED shown. Where an issue body's anchors have drifted
since `ece0ddd`, the names have not — grep the name. Two "smaller than a session" items from
`TECH_DEBT.md` ride along: `STOP_TIMEOUT_MS` declared three times goes with #63 (F13, which is in
`SettingsViewModel` anyway); the back arrow drawn twice goes with #64 (F14). Nothing else from
`TECH_DEBT.md` is started.

### §0.6 The test floor is 1950 and may only rise

v0.7.0 shipped 1950 tests (1137 debug + 813 release). Every task adds tests; none may delete
one — #61 consolidates *helpers*, and `./gradlew test` must come back at the same count or
higher. `FeedCorpusTest` is untouchable. The design screenshots are the pixel gate for #62 and
#64: PLAN-10 §0.3 forbids retaking a baseline, and the proof is NOTES.md's worktree `md5sum`
recipe (D28/D29a).

### §0.7 Traps that will look like bugs

- **`PerchDatabaseMigrationTest`** fails the moment `VERSION` moves and the JSON does not exist —
  that is F02's and F07's RED, not a broken build. Compile to export it.
- **Room validates the exported schema on open** (NOTES.md, S08): F07's `CREATE TABLE` must be
  byte-for-byte what `10.json` exports. Write the entity first, compile, copy the `createSql`
  out of the JSON into the migration.
- **A raw `DELETE` in a migration fires `entries_fts_delete`** — it exists from version 7 on, so
  F02's seeded v8 file has it. Do not add a second FTS delete.
- **Injected taps do not reach a node inside a dialog, drawer sheet or dropdown** (NOTES.md) —
  `performSemanticsAction(OnClick)`. The archive footer is in the list, not a sheet: a plain
  `performClick` works, but the list must be scrolled to its end first (`performScrollToNode`).
- **Waiting on Room is not waiting on the screen** (V01/#1): poll in wall-clock time through the
  shared wait helper, never `waitForIdle`.
- **`PullToRefreshBox` ignores a swipe unless its child scrolls** and every empty state is a
  `LazyColumn` (NOTES.md) — F09's connection sits on the list, inside the box.
- **`HtmlSanitizer`'s `Cleaner` drops unknown tags**: a `<figure>` the pre-pass creates survives
  only because `figure` and `figcaption` are already in `SAFELIST` (`HtmlSanitizer.kt:144`
  region) — confirm before assuming; the F05 RED will say.
- **`ArticleExtractorTest`'s corpus cases iterate `ArticleFixtures.all`** — an unlisted page
  file is inert; listing it is what turns the corpus test on for that page. Pick `mid`/`last`
  sentences from the page's *prose*, never from a caption or the donate block.
- **`LiveAcceptanceTest` gate 1 now holds `bellingcat.com` to account.** It pulled 10/10 in
  planning; if it 404s once during F16, it was transient here too — re-run once before excluding.
- **Live acceptance failed gate 7 in planning, and that failure is #70.** With Bellingcat in the
  corpus, `captureImageViewer` (`LiveAcceptanceTest.kt:1424-1442`) picks the image-richest
  sample — now a Bellingcat article with 44 image blocks — taps its first image, and the viewer
  does not open (`Expected exactly '1' node … TestTag = 'article:image-viewer'`). `showArticle`
  (`:1932`) waits only for `Loading` to end; the full-text fetch that follows replaces the body
  with the extracted one, which today has **no images** (§0.3 rule 3), so the tapped node is
  gone by the time the tap lands. F03 fixes the cause; F04's Done runs the live suite once to
  prove gate 7 is back. If it still fails there, the harness is allowed one fix — wait for
  `ArticleUiState.Loaded.isFetchingFullText` (`ArticleViewModel.kt:75`) to clear before the tap
  — and one more run; then `[BLOCKED: …]`, never a third.
- **Two known full-suite-only flakes** (NOTES.md): `WorkSchedulerTest > choosing manual…` and
  `SettingsViewModelTest`. Both green alone. Re-run once before diagnosing.

### §0.8 Rungs

`./gradlew test` in the **foreground** is the rung for every task but the release (`unit`,
3–7 min). **No task takes a screenshot** — #62 and #64 prove the pixels unchanged by `md5sum`,
not by looking. Live acceptance is a bounded pre-step of F16 (~90 s, two runs at most). The
review box (F15) is second from last, as CLAUDE.md requires.

---

## The tasks

- [x] **F01 — One article, one row: the backfill and the feed agree on identity. TDD. Issue #69.**
      `gh issue view 69 --json body` — the reader's words: "it creates duplicate entries after
      pulling older posts from the archive. Reproduce this before you fix the bug."
      Everything is decided in §0.2 parts 1–3; this task is the execution, in this order:
      1. **RED, backfill direction:** in `BackfillRepositoryTest` (`app/src/test/.../data/repo/
         BackfillRepositoryTest.kt`), copy `plan drops a candidate already stored under the feed`
         (`:84`) and its helpers `addFeed` (`:348`), `storeExisting` (`:370` — give it a `link`
         parameter; today it sets `link = guid`), `sitemapOf`, `article()` (`:445`),
         `MapPageFetcher`. New: `a post the feed stored under a ?p= guid is not fetched again from
         the sitemap` — `storeExisting(feedId, guid = "$SITE/?p=123", link = POST_1)`, sitemap lists
         `POST_1`; assert `plan.toFetch` is empty, and after `run()` `entryDao.countAll()` is 1.
         A second case with the sitemap spelling `POST_1` with a trailing slash and `utm_source`,
         pinning `urlKey`. A `UrlKeyTest` in `data/parse/` for each normalisation in §0.2 part 2.
      2. **RED, poll direction:** in the existing `EntryDao` test file, `upsertAll of a feed item
         whose link matches a backfilled row's guid updates that row instead of inserting` —
         insert `testEntry(guid = POST_1, link = POST_1)`, upsert `testEntry(guid = "?p=1", link =
         POST_1)`, assert one row, `guid == POST_1`, and the reader-owned flags kept.
      3. **GREEN:** `UrlKey.kt`, `EntryDao.findByGuidOrLink` + `identitiesForFeed` (delete
         `guidsForFeed` and `findByGuid` if nothing else calls them — `grep -rn` first),
         `upsertAll`'s match and `guid = existing.guid`, `plan()`'s set, `fetchAndStore`'s
         redirect re-check.
      4. SPEC.md's entry-identity sentence (grep `guid` in §4) gains: "an entry is matched on
         `(feedId, guid)`, then on `(feedId, link)` — a backfilled page and a feed item are the
         same article (PLAN-12 §0.2)".
      - Done: RED shown for both directions in the commit message, then `./gradlew test` green
        and above 1950; the §0.2 grep gate empty; issue #69 commented with the commit and the
        tests (**left open** — F02 closes it once the phone's existing duplicates are cleaned);
        pushed.
      - Rung: unit

- [x] **F02 — The duplicates already on the phone are merged away. Schema 8 → 9. Issue #69.**
      §0.2's cleanup, verbatim. `MIGRATION_8_9` appended to `MIGRATIONS`
      (`PerchDatabase.kt:210-217`), `VERSION = 9`, `9.json` exported and committed.
      **RED first:** `PerchMigration8To9Test` seeds a v8 file with (a) a feed row `guid=?p=1,
      link=L, isRead=0, isSaved=1` and its copy `guid=L, link=L, isRead=1, isSaved=0`; (b) an
      unrelated pair sharing nothing; (c) a row with `link IS NULL`. Opening at the current
      version leaves exactly one row for L, `isRead=1 AND isSaved=1`, and the other rows intact;
      `entries_fts` has no row for the deleted id (`SELECT COUNT(*) FROM entries_fts WHERE rowid =
      …`). A second case: a v8 file with no duplicates is unchanged, row for row.
      SPEC.md §4's schema history: "version 9 merges the duplicate rows #69 created; no shape
      change (PLAN-12 F02)".
      - Done: RED (the migration test failing on the missing `9.json`, then on the surviving copy)
        in the commit message; `./gradlew test` green and above F01's count;
        `PerchDatabaseMigrationTest` green; issue #69 closed naming both commits and the tests;
        pushed.
      - Rung: unit

- [x] **F03 — An image inside a container named for its role survives extraction, and the feed path
      resolves lazy sources. TDD. Issue #70 (part 1 of 2).**
      `gh issue view 70 --json body` — the reader's words: "some of the images aren't even pulled".
      §0.3 rule 3 is the decision; §0.3's first paragraph is the diagnosis (it is **not** lazy
      loading — it is `media` in `NEGATIVE`).
      1. **RED:** `ArticleExtractorTest` — `a container named media keeps the image it wraps`:
         inline HTML, a scoring `<article>` of ten paragraphs with `<div class="media"><img
         src="https://x/a.jpg"></div>` between them; after `extract()` the `<img>` is present.
         Then list `bellingcat-kinahan-visas.html` in `ArticleFixtures` (`data/extract/
         ArticleFixtures.kt`; `url = https://www.bellingcat.com/news/2026/08/01/welcome-to-dubai-kinahan-cartels-visas-revealed/`,
         `cms = "WordPress (Gutenberg)"`, `mid`/`last` from the prose, `excludes` = the cookie/
         nav chrome you find — **not** "Support Bellingcat", that is F04's) and add an assertion
         to the corpus image case, or a new case: every `<img>` in the fixture's `<article>`
         that is not a pixel survives extraction (count them in the fixture with jsoup in the
         test, compare after).
         `HtmlSanitizerTest` — `a lazy image's data-src becomes its src` and `an image with only
         a srcset takes the widest candidate`.
      2. **GREEN:** `namesChrome()`'s image guard; `LAZY_SRC` and the promotion moved into the
         sanitizer pre-pass, the extractor's `absolutise()` (`:330-337`) calling it.
      - Done: RED shown; `./gradlew test` green and above F02's count; `wc -l` of
        `ArticleExtractor.kt` not up (the copy moved, it did not fork); issue #70 commented
        (left open for F04); pushed.
      - Rung: unit

- [BLOCKED: gate 7 — the rule itself landed (sanitizer pre-pass + extractor, 2004 tests green, #70 closed); live acceptance still fails at the image viewer with the §0.7 harness fix applied, two runs, see NOTES.md 2026-09-14] **F04 — A call-to-action block is not article text. TDD. Issue #70 (part 2 of 2).**
      The reader's words: "some of the donation banners and stuff are displayed as text".
      §0.3 rule 2 is the decision, including both guards and the 400-character ceiling.
      1. **RED:** `HtmlSanitizerTest` — `a donate block with a button and a sentence is dropped`
         (the Bellingcat shape from §0.3, inline: `wp-block-…-donate-block` > `h2` + `p` + a
         `wp-block-button` link) → the sanitized document has no "Support" heading; `a section whose
         id merely contains donation but holds real prose survives` (three long paragraphs, no
         link); `a promo paragraph with no link survives`. `ArticleLoweringCorpusTest` or a new
         case over `bellingcat-com.xml`: no lowered block of any item contains "Your donations".
         Add "Support Bellingcat" to the F03 fixture's `excludes`.
      2. **GREEN:** the token list and the two guards in the pre-pass; the three tokens added to
         `ArticleExtractor.NEGATIVE` by reference to the sanitizer's constant.
      3. Comment on #70 that Bellingcat joined the corpus in the planning commit (feed, manifest,
         reading list, page fixture) and name the tests that now hold it.
      4. **Live, bounded (§0.7's gate-7 trap):** `./gradlew :app:testDebugUnitTest -Pperch.live=true
         --tests '*LiveAcceptance*'` in the **foreground**, ~90 s. Gate 7's image viewer must open
         on the Bellingcat sample. If it does not, apply §0.7's one harness fix, run once more,
         and if it still fails mark this box `[BLOCKED: gate 7 — …]` with the output — do not
         chase it further.
      - Done: RED shown; `./gradlew test` green and above F03's count; the §0.2 grep gate still
        empty; the live run's gate lines pasted into the commit, gate 7 among them; issue #70
        closed naming both commits; pushed.
      - Rung: unit + one bounded live run

- [x] **F05 — A caption reads as a caption. TDD. Issue #67.**
      `gh issue view 67 --json body` — the reader's words: "recognizing texts that are under
      images … likely subtext under an image instead of normal text". §0.3 rule 1 is the
      decision, and the two things to say back on the issue (what can be seen, what cannot).
      1. **RED:** `HtmlSanitizerTest` — `an image whose aria-describedby names a paragraph
         becomes a figure with that paragraph as its figcaption` (the GIJN shape from §0.3,
         inline); `a container of one image and one caption-classed paragraph becomes a figure`;
         `an italic paragraph after an image stays a paragraph`. `ArticleLoweringTest` — `a
         wordpress caption div lowers to one Image carrying its caption`: `lower(sanitize(html))`
         is a single `ArticleBlock.Image` whose `caption` text is the GIJN sentence. Then list
         `gijn-conflict-zones.html` in `ArticleFixtures` (`url = https://gijn.org/stories/
         investigating-inside-conflict-zones-africa/`, `cms = "WordPress"`) and add a case: the
         extracted, lowered page has ≥ 3 `Image` blocks with a non-null caption and no
         `Paragraph` beginning "Zubaida Baba Ibrahim records".
      2. **GREEN:** the rewrite in the pre-pass; confirm `figure`/`figcaption` are in `SAFELIST`.
      3. DESIGN.md:348's "figcaption → Caption style" gains "…and any image described through
         `aria-describedby` or a `caption`/`credit`/`cutline`-classed sibling (PLAN-12 F05)".
      - Done: RED shown; `./gradlew test` green and above F04's count; issue #67 closed naming
        the commit, the tests, and §0.3's statement of what the heuristic cannot see; pushed.
      - Rung: unit

- [x] **F06 — Every settled scroll at the same offset writes once. TDD. Issue #71.**
      `gh issue view 71 --json body`. `ArticleViewModel.saveScrollPosition` (E01, `ArticleViewModel.kt`,
      grep the name) remembers the last value it wrote, seeded from `Loaded.scrollPosition`, and
      returns early on an equal value. **RED:** `ArticleViewModelTest` — two settles at 400 reach
      the repository once; a settle at 400 then 401 reaches it twice; the first settle after open
      at the stored value reaches it zero times. Behaviour is otherwise E01's — the leaving write
      stays `NonCancellable`.
      - Done: RED shown; `./gradlew test` green and above F05's count; `TECH_DEBT.md`'s E03 bullet
        deleted; issue #71 closed; pushed.
      - Rung: unit

- [ ] **F07 — The archive plan is remembered: `archive_posts`. TDD. Schema 9 → 10. Issue #68 (part 1 of 3).**
      `gh issue view 68 --json body,comments` — the reader's words: "if you import a blog in the
      beginning and the app realizes there are 12,000 posts, then that should be cached".
      §0.4's table and its four rules are the decision; this task is the data layer only —
      no UI.
      1. **RED, schema:** `PerchMigration9To10Test` — a populated v9 file opens at 10 with every
         feed and entry intact and an empty `archive_posts`; deleting a feed cascades (insert a
         row, delete the feed, count 0).
      2. **RED, repository:** in `BackfillRepositoryTest` — `plan discovers once and reads the
         table after`: two `plan()` calls, `MapPageFetcher`'s sitemap hit count is 1; `plan
         rediscovers when the stored plan is older than seven days` (the test clock);
         `discovery stamps posts the feed already stored as fetched` (§0.2's `urlKey`);
         `run stamps every page it fetched or skipped, and a fetch failure leaves the stamp
         empty`; `newPostCount is what is left unfetched, and toFetch is the newest forty of it`;
         `retention pruning does not resurrect a fetched page` — store, stamp, delete the entry
         through `deleteReadOlderThan`, `plan()` again, `toFetch` is still empty. Existing cases
         `:212` (second run stores nothing new) and `:227` (a cancelled run resumes) must stay
         green **unchanged**.
      3. **GREEN:** `ArchivePostEntity`, `ArchivePostDao` (`upsertIgnore`, `unfetched(feedId,
         limit)`, `countUnfetched`, `newestDiscoveredAt`, `markFetched(feedId, urls)`),
         `PerchDatabase.VERSION = 10`, `MIGRATION_9_10`, `10.json`; `plan()` and `run()` per §0.4.
         SPEC.md §4 gains the table and "version 10 adds `archive_posts` (PLAN-12 F07)"; §3's
         package tree gains the two files.
      - Done: RED shown for both layers; `./gradlew test` green and above F06's count;
        `PerchDatabaseMigrationTest` green; issue #68 commented (left open); pushed.
      - Rung: unit

- [ ] **F08 — The end of All Time offers the next forty. TDD. Issue #68 (part 2 of 3).**
      The reader's words: "if you keep scrolling down into the older history, then it should
      start retrieving posts in batches". §0.4's *trigger* paragraph is the decision: `loadOlder`,
      the footer slot, the guard, the strings, the disabled state, scoped to one source.
      1. **RED:** `BackfillOfferTest` (`app/src/testDebug/.../ui/home/BackfillOfferTest.kt`, its
         `MapPageFetcher` and `awaitInRealTime`) — `the bottom of a source's All Time list names
         the archive and loads forty more` (seed a feed with 10 entries and a 100-post sitemap,
         scope to the source, All Time, `performScrollToNode` to `BackfillTestTags.ARCHIVE_FOOTER`,
         assert its text carries "90", click `ARCHIVE_LOAD`, await 40 more rows, the footer now
         says "50"); `the footer is disabled while a batch runs`; `the footer is absent in the
         All-sources scope, in a folder scope, and outside All Time`; `the footer is absent when
         nothing is left` (`pagedFooter`'s marker shows instead — `PagedFeedTest` names it).
         `HomeViewModelTest` (or the VM's existing file): `loadOlder enqueues the runner and marks
         the feed running without an offer`.
      2. **GREEN:** `PagedEntryList`'s slot, `ArchiveFooter`, `HomeViewModel.loadOlder`, the
         plurals and strings, `HomeScreen`'s guard.
      3. DESIGN.md's Feed section (grep "Reaches back") gains the footer's copy and rule.
      - Done: RED shown; `./gradlew test` green and above F07's count; issue #68 commented
        (left open); pushed.
      - Rung: unit

- [ ] **F09 — Pulling up past the end loads older posts. TDD, two attempts. Issue #68 (part 3 of 3).**
      The reader's follow-up comment: "hitting the bottom and then dragging up should do the
      same thing that reloading does on other apps". §0.4's *gesture* paragraph is the decision,
      including the pure `PullUpState`, the 72 dp threshold, the label change, and the BLOCKED
      wording if Robolectric cannot drive it.
      1. **RED:** `PullUpStateTest` (`app/src/test/.../ui/home/`) — accumulates only upward
         unconsumed deltas, arms at the threshold, fires once on release, resets on a short
         release, ignores everything while not at the end. `BackfillOfferTest` — `dragging up
         past the end of All Time loads the next batch`: scroll to the footer, `performTouchInput
         { swipeUp() }`, await 40 more rows.
      2. **GREEN:** `PullUp.kt`, the `NestedScrollConnection` on the list inside `PullToRefreshBox`
         (`HomeScreen.kt:454`), the footer's armed label (`archive_footer_release`).
      3. Close #68 with the three commits, the tests, and §0.4's scoping rule (one source at a
         time; All-sources unchanged) in the reader's terms.
      - Done: RED shown; `./gradlew test` green and above F08's count; issue #68 closed; pushed —
        or the box rewritten `[BLOCKED: …]` per §0.4 with the pure test green and #68 closed on
        F08's button, saying so.
      - Rung: unit

- [ ] **F10 — The four ViewModel `runCatching`s rethrow cancellation. TDD. Issue #60.**
      `gh issue view 60 --json body` — the fix shape and the RED shape are both in the body.
      Sites today: `AddSourceViewModel.kt:128` and `:152`, `ArticleViewModel.kt:149`,
      `SettingsViewModel.kt:274` (the last is also F13's file — F10 runs first, F13 keeps the
      rethrow). `FeedRepository.kt:321-322` is the pattern to copy, `BackfillRepository.kt:124-127`
      the comment tone. RED per ViewModel as the issue says: launch in a `TestScope`, cancel while
      the fake repository is suspended (`MapPageFetcher` / `support/Await.kt`), assert the state
      was not written after.
      - Done: RED shown for each of the three ViewModels; `./gradlew test` green and above F09's
        count; issue #60 closed; pushed.
      - Rung: unit

- [ ] **F11 — One seeder, on `PerchRule`. Issue #61.**
      `gh issue view 61 --json body` — the shape, the files and the rule that no test may be
      deleted. Today: 25 private `seedFeed`/`seedEntry`/`seedFolder` declarations in 12 files
      (`grep -rn "private fun seed" app/src/test app/src/testDebug`), and 12 files outside
      `support/Entities.kt` still build a `FeedEntity(`/`EntryEntity(` literal. F08 and F09 will
      have added tests to `BackfillOfferTest` — consolidate them too. Keep a per-file wrapper only
      where it adds a default the issue names (`CollectionRefreshTest`'s path,
      `BackfillOfferTest`'s entry count), and make it call the shared one.
      - Done: `grep -rn "private fun seedFeed\|private fun seedEntry\|private fun seedFolder"`
        lists only wrappers that add a per-file default; no `FeedEntity(`/`EntryEntity(` literal
        outside `support/Entities.kt`; `./gradlew test` green at **exactly or above** F10's
        count (paste both numbers); `wc -l` of the test sources down by roughly 250; issue #61
        closed; pushed.
      - Rung: unit

- [ ] **F12 — `Screenshots.captureAndAssert` owns the capture wrapper. Issue #62.**
      `gh issue view 62 --json body`. Today: `private fun capture(` in `CodeScreenshotTest.kt:162`,
      `TableScreenshotTest.kt:148`, `ImageViewerScreenshotTest.kt:163`, `DesignScreenshotTest.kt:388`,
      `BrandScreenshotTest.kt:207`, `LiveAcceptanceTest.kt:1320` and `:1951`; `MIN_COLOURS =`
      declared four times. `Screenshots` is `ScreenshotSupport.kt:23` (`capture` at `:53`, `dir`
      at `:63`). **Thresholds do not move** — `DesignScreenshotTest` keeps `minBytes = 10_000L`
      by passing it. Proof the pixels did not move: NOTES.md's worktree recipe, `md5sum` both
      sides, 35/35.
      - Done: one wrapper, `MIN_COLOURS` once; the `md5sum` line in the commit; `./gradlew test`
        green at or above F11's count; issue #62 closed; pushed.
      - Rung: unit

- [ ] **F13 — `SettingsViewModel`'s four transfer actions share two helpers. Issue #63.**
      `gh issue view 63 --json body`. Today: `exportOpml` `:167`, `importOpml` `:189`,
      `exportProfile` `:219`, `importProfile` `:242`, each with its own `withContext(Dispatchers.IO)`
      and `if (e is CancellationException) throw e`. The two helper signatures are in the issue.
      Pinning tests to name, not touch: `SettingsViewModelTest` and `SettingsScreenTest`, including
      D06's throwing `read`/`write` cases. F10's rethrow at `:274` stays. Also: `STOP_TIMEOUT_MS`
      declared in `HomeViewModel`, `SettingsViewModel` and `AddSourceViewModel` becomes one
      `internal const` in `ui/` (grep the name for the three sites).
      - Done: the four actions call the two helpers; `SettingsViewModel.kt` shorter (`wc -l`
        before/after in the commit); one `STOP_TIMEOUT_MS`; `./gradlew test` green at or above
        F12's count; issue #63 closed; pushed.
      - Rung: unit

- [ ] **F14 — The new-folder dialog has one call site. Issue #64.**
      `gh issue view 64 --json body`. Today the *pair* is `HomeScreen.kt:182` + `:591` and
      `AddSourceSheet.kt:57` + `:84` (each `var creatingFolder by rememberSaveable` and the
      ten-line `if`). **Not** the other two `FolderNameDialog(` sites in `HomeScreen` (`:568`
      rename, `:635` the E02 batch "New folder…" keyed on `creatingFolderFor`) — leave those.
      The issue prefers whichever shape removes both `creatingFolder` declarations; keep the
      `rememberSaveable`. Pinning tests to name: `FolderDrawerTest` and `AddSourceSheetTest`'s
      "New folder…" cases. Also: the back arrow `navigationIcon` drawn identically in
      `SettingsScreen.kt` and `ArticleScreen.kt` (grep `ArrowBack`) becomes one composable in
      `ui/theme/` or beside the first caller. Pixels unchanged: the `md5sum` recipe, 35/35.
      - Done: one call site, one back arrow; the `md5sum` line; `./gradlew test` green at or
        above F13's count; issue #64 closed; pushed.
      - Rung: unit

- [ ] **F15 — The review pass. The whole of v0.8.0, read at once.**
      Read `git diff v0.7.0..HEAD` — **the whole of it** — and answer, in the commit message and
      in NOTES.md where it outlives the plan:
      1. Does any doc still describe v0.7.0? README.md, SPEC.md (§1 version, §3 tree — `UrlKey.kt`,
         `ArchivePostEntity`, `ArchivePostDao`, `PullUp.kt`; §4 schema history says 10 and lists
         `archive_posts`), DESIGN.md (captions, the archive footer), NOTES.md, CLAUDE.md,
         `docs/RALPH.md`, `TECH_DEBT.md` (its "Next plan" section must now name only what this
         plan did not do), against what shipped. CLAUDE.md still names PLAN-12 active — that is
         F16's edit.
      2. Did any task leave a helper, string, dimension or test tag orphaned? Scheduled to die:
         `guidsForFeed`/`findByGuid` (F01, if unreferenced), the extractor's private `LAZY_SRC`
         (F03), 24 seeders (F11), six `capture` wrappers and three `MIN_COLOURS` (F12), two
         `STOP_TIMEOUT_MS` (F13), one `creatingFolder` and one back arrow (F14). Confirm with grep.
      3. Was any test weakened rather than rewritten? Name every changed assertion in
         `BackfillRepositoryTest` (F01, F07 touched its helpers) and say which is at least as
         strong as what it replaced.
      4. Is the suite above 1950, and did every fix (F01–F10) land with its RED in the commit?
      5. Do `9.json` and `10.json` match their migrations under Room's validation — name the two
         migration tests — and does the §0.2 grep gate still return nothing?
      Fix what is small and mechanical **in this session**. Anything larger becomes an issue for
      the next plan and a line in `TECH_DEBT.md` "## Next plan" — do not start a feature in a
      review.
      - Done: the five answered in the commit message, each with the command that settled it;
        `./gradlew test` green; any new issue linked; pushed.
      - Rung: unit

- [ ] **F16 — Release v0.8.0.** Bump `perchVersionCode` 9 → **10** and `perchVersionName`
      `0.7.0` → **`0.8.0`** at `app/build.gradle.kts:12-13`, **the one place they live**. §0.1
      settles the digit; it is not this task's judgement call.
      - **Live acceptance first, bounded:** `./gradlew :app:testDebugUnitTest -Pperch.live=true
        --tests '*LiveAcceptance*'` in the **foreground**, ~90 s, **at most two runs**; gate 1 has
        no quota (V12/#8) and now includes `bellingcat.com`; `quantpedia.com` stays excluded;
        `research.checkpoint.com` answers 202 when runs come too close. Paste every gate's count
        into the commit message. If the second run still fails on a network gate, say which and
        release anyway, filing the failure as an issue — unless the failing gate is Bellingcat's
        *rendering* (a gate this plan's F03–F05 changed), in which case stop and mark the box
        `[BLOCKED: …]` with the output.
      - `./gradlew test assembleRelease` — **not `clean`** (runs `lintVitalRelease`). Signing from
        `~/.perch/signing.properties` (U02) — **absent it the build silently debug-signs**, so
        verify the certificate on the file, not the build log.
      - **Gradle writes `app-release.apk`; the rename to `perch-0.8.0.apk` is this task's own** (W12).
      - Release notes through `docs/RELEASE-NOTES.md`'s template; `scripts/release-notes.sh
        v0.7.0` drafts from #67–#71 — write them in the reader's words. "Installing / upgrading":
        installs in place over v0.7.0 and keeps read state, likes and To-Read; the database moves
        8 → 10 — the first step merges the duplicate rows the old backfill left (keeping read,
        saved and liked on the survivor), the second adds the remembered archive. **Verify the
        upgrade only if the emulator is already up:** `./scripts/device.sh check` first; if it is
        running, `./scripts/device.sh install` over v0.7.0 and confirm To-Read still holds its
        rows through the UI. If it is not running, **do not boot it** — say so in the commit and
        let the two migration tests stand as the upgrade proof.
      - Tag `v0.8.0`, push, `gh release create v0.8.0` with the notes and `perch-0.8.0.apk`.
        Then the turnover edits so the next session is not a loop session: CLAUDE.md's
        active-plan section says v0.8.0 shipped and there is no active plan; `loop.sh:19` and
        `scripts/progress.sh:11` keep naming `PLAN-12.md` (a stray launch fails loudly on the
        missing root file, as before); NOTES.md pruned under 100 lines with this version's floor
        and APK path. **Do not move this file** — the watching session archives it into
        `docs/plans/` after the loop reports complete.
      - Done: `gh release view v0.8.0 --json assets` lists the APK; `aapt2 dump badging` reads
        `versionCode='10' versionName='0.8.0'`; `apksigner verify --print-certs` prints U02's
        digest `61367c0499de5c49c824f4d7ba7b4e692d33960cc57c0622772227a8b7fce489`; `git status`
        clean and pushed; `gh issue list --state open` lists none of #60–#64, #67–#71.
      - Rung: build
