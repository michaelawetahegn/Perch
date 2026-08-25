# NOTES.md

## Log
**The `.wslconfig` 7 GB cap only applies after `wsl --shutdown`** (2026-08-07) — MemTotal ~6.9 GB means live, ~9.9 GB means a freeze. Full environment picture is in CLAUDE.md.
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
- 2026-08-25 — **PLAN-6 (#23) done, archived.** `PageMetadataExtractor` (Y01, standards-only; measured over
  `fixtures/articles/`: title 17/23, date 5/23). `PageContentExtractor` (Y03: fetch→extract→sanitize→image) is the
  **one** function `ArticleTextRepository` and `SavedLinkRepository` both call — do not clone it. `feeds.isSynthetic`
  → DB v6 (Y02), seeded `perch:saved-links` row; every general feed query gained `WHERE isSynthetic = 0`, reached
  explicitly via `findByUrl(SAVED_LINKS_FEED_URL)`. `SavedLinkRepository.saveLink` (Y03) catches a pasted feed by
  parsing the fetched bytes, not discovery. `SaveLinkViewModel`/`-Sheet` (Y04). Y05 review: grep gate clean, no doc
  drift, no test weakened.
- 2026-08-24/25 — **PLAN-7 (#21) done, archived.** `ArchiveDiscovery`/`BackfillRepository`/
  `BackfillWorker` (`data/archive/`, `data/repo/`): discovery order RFC 5005 `prev-archive`
  → `robots.txt Sitemap:` → `/sitemap.xml` (sitemap indexes recursed, depth ≤3, ≤50 fetches,
  gzip by suffix or magic bytes); post-vs-page is a **dated URL path** *or* a shape
  **learned from the feed's own entry links** (`learnPostShape`), never a table of engines.
  Reach (§0.4): `EntryDao.reach(feedId)`, derived, no migration. `plan()` recomputes from
  discovery + `guidsForFeed` every call (cancel/resume/idempotent are one property); `run()`
  writes through `PageContentExtractor` (Y03's, shared — `ArticleTextRepository`/
  `SavedLinkRepository`/`BackfillRepository`, exactly three callers), `DEFAULT_DELAY_MILLIS`
  apart, `MAX_PAGES=40`, worthwhile past `MATERIALLY_MORE_FACTOR=2`×. UI: `BackfillRunner`
  seam (`WorkManagerBackfillRunner` real, `FakeBackfillRunner` in tests), the offer dialog,
  progress strip, §0.4's reach sentence. `fzakaria.com/feed.xml` is a permanent live gate-1
  source (10 feed entries, 143/133 discovered, real body). Grep gate clean throughout, no
  test weakened. `./gradlew test`: 1666 at slice close.
- 2026-08-25 — **PLAN-8 R00/#24: the newest forty, and two true numbers.**
  `BackfillRepository.plan()` now does `fresh.sortedByDescending { it.lastmod ?: Instant.MIN
  }.take(MAX_PAGES)` — discovery order is sitemap *document* order, not date order (Z04
  measured this live), so an unsorted `.take` handed back an arbitrary 40. `BackfillOffer`
  now carries both `newPostCount` (the archive's true count) and `pageCount` (the capped
  run); `backfill_offer_body_capped` is a second plural, used only when they differ, keyed
  on `pageCount`. **Trap hit while writing the RED test:** `DateParser.plausible()` floors
  at 2000-01-01 and clamps anything more than 24h in the future to "now" — a synthetic
  `lastmod` has to land inside that window or it silently becomes `null`/collides with other
  future dates instead of testing the sort. `./gradlew test` (debug+release): 976+693=1669
  (+2/+1), 0 failures.
- 2026-08-25 — **PLAN-8 R01: v0.5 read whole, all six questions clean or fixed.** Doc drift:
  README's feature list gained paste-a-link and archive-backfill; SPEC.md §4 updated to
  schema v6 (was stuck describing v3). No site-specific parsing: grep gate empty, every
  `PageMetadata.kt`/`ArchiveDiscovery.kt` rule traced to a standard (OG/JSON-LD/Dublin
  Core/RFC 5005/9309/sitemaps.org) or a shape learned from the feed itself. No orphans:
  `collapsedFolders` fully gone, `PageContentExtractor` has one definition and exactly three
  callers. No test weakened anywhere in `v0.4.0..HEAD`; `FeedCorpusTest` byte-identical to
  v0.4.0. Suite: **1669** (976 debug + 693 release), grew monotonically from the 1524 floor,
  every commit touching `src/main` carried its own test. Q6 (a guessed date renders like a
  known one) is not a one-line fix — filed as **#25** for v0.6.
- 2026-08-25 — **PLAN-8 R02: live acceptance v5, all 12 gates green.** Two real findings, not
  flakes. (1) **`quantpedia.com`'s own TLS cert has expired** — confirmed independently with
  `curl -v` against the system CA store, nothing Perch-side; added to `EXCLUDED_SOURCES`
  (gate 1 now 38/38). (2) Gate 11/12's own new pasted-link check was wrong: `CollectionTestTags.LIST`
  tags `PullToRefreshBox`, not the `LazyColumn` inside it, so `performScrollToNode` on that
  tag never actually scrolls — target `hasScrollToIndexAction()` instead. The pasted article
  sits last in `SAVED` (`savedAt DESC`, pasted before the shot's other saves), below the
  fold, so this was a real miss, not a timing flake. `research.checkpoint.com` needs genuine
  spacing between live runs (202/empty body) — the 10-quiet-minute rule holds; don't shortcut
  it with a `curl` probe, that spends the allowance the real run needs. Full gate counts:
  1039 entries pulled, gate 4 75.8%/gate 5 94.3% (both over floor), fzakaria backfill stored
  40/40, pasted link title real (not URL fallback). `./gradlew test` 1669, 0 failures;
  `assembleRelease` clean.
