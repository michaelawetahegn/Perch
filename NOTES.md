# NOTES.md

## Environment facts (measured at bootstrap, 2026-08-07)

Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM; no physical device, WHPX **enabled**. **The
`.wslconfig` 7 GB cap only applies after `wsl --shutdown`** — MemTotal ~6.9 GB means live, ~9.9 GB means a freeze.

## Log
- **Standing grep gates:** no `Color(0x` / `N.dp` / `N.sp` outside `ui/theme/` — screens address roles, never tones;
  **v0.5: no hostname literal under `data/`** (`grep -rnoE '"[a-z0-9.-]+\.(com|org|net|io|dev|me|ski|ca|xyz|blog)"`) — parse by **standards** (OG, JSON-LD, Dublin Core, sitemaps.org, RFC 5005/9309), never a table of known sites, so one blog's support makes similar ones work; fixtures exempt. **A rule lifting one fixture and moving no other is aimed at a site.**
  **U01: the repo is public** (MIT) — a harvested fixture may differ from the served page by exactly one thing,
  a third-party key rewritten `REDACTED-THIRD-PARTY-KEY` (`fixtures/homepages/`, the HF article page).
- **This `gh` is old:** bare `gh issue view N` dies on a Projects-classic GraphQL field (use `--json`), no `gh label
  list`, `gh issue close` has no `-r`. **V14: `scripts/release-notes.sh <last-tag>`** drafts a release page.
- 2026-08-07 — **Standing UI-test traps.** Compose UI tests live in **`app/src/testDebug/`** (`ui-test-manifest` is
  `debugImplementation`). An injected tap/long-press **never reaches a node inside a drawer sheet, bottom sheet or
  dropdown** — use `performSemanticsAction(OnClick/OnLongClick)`. `PullToRefreshBox` ignores a swipe unless its child
  scrolls — since V03 **every empty state is a `LazyColumn` with one `fillParentMaxSize` item**. Screenshots: go
  through `Screenshots` (its KDoc says why **never `captureToImage()`** — CLAUDE.md is wrong).
  **W05/#16: `shareIntent(title, link)` is pure**, and a *chooser* — assert `EXTRA_INTENT`, not the outer action.
- 2026-08-07 — **Live acceptance** (`acceptance/LiveAcceptanceTest`, `testDebug`): `./gradlew :app:testDebugUnitTest
  -Pperch.live=true --tests '*LiveAcceptance*'`. **V12/#8: gate 1 has no quota** — every source in `feeds.txt` bar
  `EXCLUDED_SOURCES` must pull (38/38 today), so a break arrives as a URL, and an exclusion carries the measurement
  that settled it. **SPEC §6's 8 MiB cap stays 8 MiB**: `danluu` (11.1 MB) and `projectzero` (13.2 MB) are out of
  scope, not evidence against it. **Not ours:** the LLVM feed omits spaces around inline `<code>`/`<a>`.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future install a data
  wipe** — the cert (SHA-256 `61367c04…fce489`) *is* the update identity and cannot be rotated. Both `chmod 600`,
  outside the repo, **not backed up yet**; absent it, release silently debug-signs. `assembleRelease` runs `lintVitalRelease`.
- 2026-08-07 — **U03: test databases come from `PerchDatabase.inMemory(context)`** (only it seeds Uncategorized).
  **U04: a fourth reader-owned flag needs two edits** — `EntryDao.upsertAll` (never Room `@Upsert`, it resolves on
  the primary key, ours on `(feedId, guid)`) and `deleteReadOlderThan`.
- 2026-08-18 — **W02/#15: the window is a *rolling* one** (24 h / 7 / 30 / 365 days back from `clock.instant()`),
  label **"Past 24 Hours"**, **defaults to Today** — a UI test seeding anything older pins `TimeFilter.AllTime` via its
  own `SettingsStore`. U07's calendar window is dead; the zone now only decides what a human *reads*. **W03: the Feed is
  one stream** — `HomeTestTags.section` and `startsSection` are gone; a test naming `"home:section:N"` spells the dead
  tag out on purpose, so nothing can put a header back unnoticed.
- 2026-08-08 — **U09: the bottom bar and `NavHost` are siblings**; **Feed's `DrawerState`/`LazyListState` are
  hoisted into `PerchNavHost`** (state remembered inside Feed dies on a tab switch); back policy is the pure
  `nextBackStep(BackState)` in `BackChain.kt`. **U09a:** the selection `BackHandler` must live *inside* `ModalDrawerSheet` — the root one wins otherwise.
- 2026-08-08 — **U07a: all three lists are Paging 3**, **placeholders off**; the queries live once in **`EntryQueries`**
  because each exists twice — `Flow<List>` *and* `PagingSource`. **`uiState.entries` is gone**: ask the screen.
- 2026-08-08 — **U10:** Readability-over-jsoup in `data/extract/`, **no new dependency**; fixtures in
  `fixtures/articles/`. **`ArticleLowering` deletes truncation markers as chrome**, so `FullText` looks for
  "Continue reading" in the *unlowered* text; an extraction only ever replaces a body it beats.
- 2026-08-08 — **U14 (profile).** `pending_entry_state`, keyed `(feedUrl, guid)`, **no FK to `feeds`** — its job is
  outliving a source that does not exist yet. `EntryDao.upsertAll` consumes parked rows, so a restore's flag turns
  **on** and never off (idempotent). Codec is `org.json` — its tests need Robolectric. (DB is now **version 6** — Y02.)
- 2026-08-09 — **V01/#1: Robolectric builds `PerchApp` for every test** — a store cancels only a scope it *owns*. **Every full-suite flake so far: waiting on Room is not waiting on the screen. Poll in wall-clock time**, not `waitForIdle`.
- **V02/#9: a `Clock` carries a zone**; `AppContainer` injects `systemDefaultZone()`, **`DateParser` stays UTC
  deliberately**; a zone test pins `TimeZone.setDefault`. Since W02 the zone decides only what a human *reads*.
- **V06/#11: folder order is alphabetical (`COLLATE NOCASE`), Uncategorized pinned by `(id = 1) ASC`.** It governs
  the **drawer only** since W03 — `FolderDao.observeAll` and `.getAll`, which must agree; `EntryQueries.LIST_ITEMS`
  left the rule and is pure recency; `sortIndex` decides nothing (NOCASE-folding quirk: `FolderDao.kt`'s own KDoc).
- **V05/#12: "Unread" is gone from every reader-facing string** (identifiers keep it); **pin `HomeTestTags.TITLE`,
  never `onNodeWithText("Feed")`** — the tab has read "Feed" since U09. **W04/#20: a row's meta is now the bare
  source name** (`EntryRowTestTags.META`, category dimmed after a `·`, Uncategorized unlabelled; `DATE` beneath),
  so a drawer row is `hasClickAction() and !hasTestTag(HomeTestTags.ENTRY)`.
- **V08/#10: the scoped list is state, not a route.** `HomeScope` is **hoisted into `PerchNavHost`** — third such
  state — `BackStep.LeaveScope` a rung above `ScrollFeedToTop`. `selectTab` is a silent no-op from the article route (`popUpTo(start){saveState}`/`restoreState`, pop first); scoping does not touch the time window.
- **`research.checkpoint.com` answers 202 empty when live runs come too close together** (Cloudflare) — wait ~10 quiet minutes and rerun. Healthy, not an exclusion.
- 2026-08-25 — **PLAN-6 (#23) done, archived.** `PageMetadataExtractor` (Y01, standards-only; measured over
  `fixtures/articles/`: title 17/23, date 5/23). `PageContentExtractor` (Y03: fetch→extract→sanitize→image) is the
  **one** function `ArticleTextRepository` and `SavedLinkRepository` both call — do not clone it. `feeds.isSynthetic`
  → DB v6 (Y02), seeded `perch:saved-links` row; every general feed query gained `WHERE isSynthetic = 0`, reached
  explicitly via `findByUrl(SAVED_LINKS_FEED_URL)`. `SavedLinkRepository.saveLink` (Y03) catches a pasted feed by
  parsing the fetched bytes, not discovery. `SaveLinkViewModel`/`-Sheet` (Y04). Y05 review: grep gate clean, no doc
  drift, no test weakened.
- 2026-08-24 — **Z01–Z02a/#21: `ArchiveDiscovery`/`BackfillRepository`/`BackfillWorker`**
  (`data/archive/`, `data/repo/`). Discovery order: RFC 5005 `prev-archive` from the feed →
  `robots.txt Sitemap:` → `/sitemap.xml`, recursing sitemap indexes (depth ≤3, ≤50 fetches,
  gzip by `.gz` suffix or magic bytes). Post-vs-page is a **dated URL path** *or* a shape
  **learned from the feed's own entry links** (`learnPostShape`: segment count + which
  leading segments every entry agrees on) — covers Hugo `/posts/<slug>/`, Ghost/Substack
  `/p/<slug>`, bare `/<slug>/`; never a table of engines. Reach (§0.4): `EntryDao.reach
  (feedId)` is `COUNT`/`MIN(publishedAt)`, derived, no migration. `BackfillRepository.plan()`
  recomputes from discovery + `guidsForFeed` every call, so cancel/resume/idempotent are one
  property; `run()` writes through `PageContentExtractor` (Y03's, not cloned),
  `DEFAULT_DELAY_MILLIS` apart, `MAX_PAGES=40`, worthwhile past `MATERIALLY_MORE_FACTOR=2`×.
  `RobotsRules` is a second, decoupled `robots.txt` fetch. §0.3a's date chain: page metadata
  → RFC-5005/sitemap `<lastmod>` → `Instant.EPOCH` + `publishedIsEstimated`.
  `PerchWorkerFactory` gained a `backfill` lambda **before** `feeds`. Grep gate clean.
  `./gradlew test` (debug+release): 964+692=1656, 0 failures.
- 2026-08-24 — **Z03/#21: the offer, its progress, and §0.4's reach sentence** — UI only,
  no `data/` change. `BackfillRunner` (`ui/home/`) is the seam over WorkManager, mirroring
  `RefreshScheduler`'s shape — `WorkManagerBackfillRunner` (`work/`) is the real one, a
  `FakeBackfillRunner` drives tests with no WorkManager at all. `HomeViewModel.sourceAdded`
  offers only when `BackfillRepository.plan().isWorthwhile`; the drawer's selection bar's
  new **Fetch older posts** action (`requestBackfill`) re-offers regardless, for a reader
  who declined. `AddSourceSheet` gained `onAdded(feedId)`, read once before it resets and
  closes — the host's only chance to see what was just committed. §0.4's reach sentence
  reads `RelativeTime`'s default (system) zone, so its own test pins `TimeZone.setDefault`
  (V02 pattern). `./gradlew test` (debug+release): 974+692=1666 (+10/+0), 0 failures.
- 2026-08-25 — **Z04/#21: the reporter's blog, live.** `fzakaria.com/feed.xml` is now a
  permanent gate-1 source. Gate 10 asks `ArchiveDiscovery` directly, not
  `BackfillRepository.plan().toFetch` — `MAX_PAGES` bounds one backfill *run*, not
  discovery's reach, and sitemap order isn't `<lastmod>` order, so the reporter's post
  sorted past the cap on attempt 1. Measured: feed 10 entries, discovery 143/133 new, post
  found, 8140 chars extracted. **Unrelated flake:** `quantpedia.com` failed gate 1 with a
  TLS `CertPathValidatorException` this run — new, not touched, rerun before excluding.
  `./gradlew test`: 974+692=1666, 0 failures (no new offline test).
- 2026-08-25 — **Z05/#21: PLAN-7 review, closing the slice.** Four questions:
  1. Read `ArchiveDiscovery.kt` line by line — every heuristic traces to a named standard
     (RFC 5005 `prev-archive`, RFC 9309 `robots.txt Sitemap:`, sitemaps.org `urlset`/
     `sitemapindex`/gzip) or a cross-generator convention stated in its own KDoc (`DATED_PATH`;
     `learnPostShape` derives its shape from *that site's own feed*, never a table of
     engines). Grep gate re-run over every package this plan touched (`data/`, `ui/home/`,
     `work/`): `grep -rnoE '"[a-z0-9.-]+\.(com|org|net|io|dev|me|ski|ca|xyz|blog)"'` →
     nothing in either.
  2. `grep -rln "PageContentExtractor.extract"` → exactly three callers,
     `ArticleTextRepository`, `SavedLinkRepository`, `BackfillRepository` — one shared
     function, no second metadata/extraction path.
  3. `grep -i "all time\|feed publishes\|only what a feed"` over README/SPEC/DESIGN/NOTES/
     CLAUDE.md → no doc asserts Perch is bounded to what a feed publishes. README's "What
     it does" also never documents backfill or Y03's paste-a-link — consistent with Y05
     leaving the same gap; both are deferred to `PLAN-8`'s whole-version doc pass, not
     doc drift to fix here.
  4. `git diff 27a8dda..HEAD -- '*Test.kt'` has no removed assertion, no `@Ignore`/
     `@Disabled` line. Suite grew every task: 1590 (Y05) → 1656 (Z01–Z02a) → 1666
     (Z03–Z04), unchanged this session. `./gradlew test`: 974+692=1666, 0 failures.
