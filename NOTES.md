# NOTES.md

## Environment facts (measured at bootstrap, 2026-08-07)

Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM; no physical device, WHPX **enabled**. **The
`.wslconfig` 7 GB cap only applies after `wsl --shutdown`** — MemTotal ~6.9 GB means live, ~9.9 GB means a freeze.

## Log
- **Standing grep gates:** no `Color(0x` / `N.dp` / `N.sp` outside `ui/theme/` — screens address roles, never tones;
  **v0.5: no hostname literal under `data/`** (`grep -rnoE '"[a-z0-9.-]+\.(com|org|net|io|dev|me|ski|ca|xyz|blog)"`) — parse by **standards** (OG, JSON-LD, Dublin Core, sitemaps.org, RFC 5005/9309), never a table of known sites, so one blog's support makes similar ones work; fixtures exempt. **A rule lifting one fixture and moving no other is aimed at a site.**
  **U01: the repo is public** (MIT) — a harvested fixture may differ from the served page by exactly one thing,
  a third-party key rewritten `REDACTED-THIRD-PARTY-KEY` (`fixtures/homepages/`, the HF article page).
- **This `gh` is old:** bare `gh issue view N` dies on a Projects-classic GraphQL field (use `--json`), no `gh label list`,
  `gh issue close` has no `-r`. **V14: `scripts/release-notes.sh <last-tag>`** drafts a release page from the issues closed
  since it, through the `docs/RELEASE-NOTES.md` template.
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
- 2026-08-18 — **W07/#17: a page that extracts to null is read again with every `class`/`id` erased** — structure
  alone decides, and a page that already worked never reaches it (the defect was `namesChrome()` matching an
  unanchored substring: Tailwind's `max-lg:overflow-hidden` read as `hidden`). Corpus cost **zero**, HF 0 → 9355. A
  page that fails goes in `ArticleFixtures.pending`, never `all`, held to still failing by the blind-spot test (W10).
- 2026-08-18 — **W02/#15: the window is a *rolling* one** (24 h / 7 / 30 / 365 days back from `clock.instant()`),
  label **"Past 24 Hours"**, **defaults to Today** — a UI test seeding anything older pins `TimeFilter.AllTime` via its
  own `SettingsStore`. U07's calendar window is dead; the zone now only decides what a human *reads*. **W03: the Feed is
  one stream** — `HomeTestTags.section` and `startsSection` are gone; a test naming `"home:section:N"` spells the dead
  tag out on purpose, so nothing can put a header back unnoticed.
- 2026-08-08 — **U09: the bottom bar and the `NavHost` are siblings**; **Feed's `DrawerState`/`LazyListState` are
  hoisted into `PerchNavHost`** (state remembered inside Feed dies on a tab switch). §0's back policy is the pure
  `nextBackStep(BackState)` in `BackChain.kt`, the enum's order *being* the priority.
- 2026-08-08 — **U09a: the selection `BackHandler` must live inside `ModalDrawerSheet`** — the root one wins otherwise; a batch delete's dialog is **a coroutine behind its tap**, so wait in wall-clock time.
- 2026-08-08 — **U07a: all three lists are Paging 3**, **placeholders off**; the queries live once in **`EntryQueries`**
  because each exists twice — `Flow<List>` *and* `PagingSource`. **`uiState.entries` is gone**: ask the screen.
- 2026-08-08 — **U10:** Readability-over-jsoup in `data/extract/`, **no new dependency**; fixtures in
  `fixtures/articles/`. **`ArticleLowering` deletes truncation markers as chrome**, so `FullText` looks for
  "Continue reading" in the *unlowered* text; **an extraction only ever replaces a body it beats.**
- 2026-08-08 — **U14 (profile).** DB is **version 5**: `pending_entry_state`, keyed `(feedUrl, guid)`, **no FK to
  `feeds`** — its job is outliving a source that does not exist yet. **`EntryDao.upsertAll` consumes parked rows**,
  so the refresh after a restore cannot undo it; a restore turns a flag **on** and never off, so it is idempotent.
  Codec is `org.json` — **its tests need Robolectric**; on a bare JVM `JSONObject` stubs.
- 2026-08-09 — **V01/#1: Robolectric builds `PerchApp` for every test** — a store cancels only a scope it *owns*. **Every full-suite flake so far: waiting on Room is not waiting on the screen. Poll in wall-clock time**, not `waitForIdle`.
- **V02/#9: a `Clock` carries a zone**; `AppContainer` injects `systemDefaultZone()`, **`DateParser` stays UTC
  deliberately**; a zone test pins `TimeZone.setDefault`. Since W02 the zone decides only what a human *reads*.
- **V06/#11: folder order is alphabetical (`COLLATE NOCASE`), Uncategorized pinned by `(id = 1) ASC`.** It governs
  the **drawer only** since W03 — `FolderDao.observeAll` and `.getAll`, which must agree; `EntryQueries.LIST_ITEMS`
  left the rule and is pure recency; `sortIndex` decides nothing (NOCASE-folding quirk: `FolderDao.kt`'s own KDoc).
- 2026-08-18 — **W11: the live gate is thirteen gates** — **5c** is #17's Hugging Face page fetched *live* (a fixture
  cannot notice a Tailwind class rename), held to beating the page's own teaser: 9355 vs 51 chars. **The home shot is
  staged freshest-first, not quietest-first** — quietest was right while a small folder bought a section header, and
  after W03 it put every category off-screen. 38/38 pull, 1038 entries, 0 rows out of order, 7 inside 24 h.
- **V05/#12: "Unread" is gone from every reader-facing string** (identifiers keep it); **pin `HomeTestTags.TITLE`,
  never `onNodeWithText("Feed")`** — the tab has read "Feed" since U09. **W04/#20: a row's meta is now the bare
  source name** (`EntryRowTestTags.META`, category dimmed after a `·`, Uncategorized unlabelled; `DATE` beneath),
  so a drawer row is `hasClickAction() and !hasTestTag(HomeTestTags.ENTRY)`.
- **V08/#10: the scoped list is state, not a route.** `HomeScope` is **hoisted into `PerchNavHost`** — third such
  state — `BackStep.LeaveScope` a rung above `ScrollFeedToTop`. **`selectTab` is a silent no-op from the article
  route**: `popUpTo(start){saveState}` saves it and `restoreState` puts it back — pop first, switch tabs only if
  needed. Scoping **does not touch the time window**. Residual (T29): a scoped list repeats the source on every row.
- **`research.checkpoint.com` answers 202 empty when live runs come too close together** (Cloudflare) — wait ~10 quiet minutes and rerun. Healthy, not an exclusion.
- 2026-08-25 — **Y01/#23: `PageMetadataExtractor`**, standards-only, measured over all 23 `fixtures/articles/`
  files: title 17/23 (74%), date 5/23 (22%) — the 6 untitled have no `<head>` at all; gpuopen's `<time>` has
  neither `pubdate` nor `itemprop="datePublished"` so §0.2 correctly declines it. JSON-LD via `org.json` needs
  Robolectric (U14).
- 2026-08-25 — **Y02/#23: `feeds.isSynthetic` → DB v6**, seeded row `perch:saved-links` (`FeedEntity`
  companion), both `SEED_SAVED_LINKS` (fresh install) and `MIGRATION_5_6` (upgrade). Seeding it into
  `PerchDatabase.inMemory()` — used by ~every test — broke 70 tests across 18 files that read `feedDao`'s
  general queries as "every subscribed source." Fixed at the root instead of patching each assertion:
  `observeAll`/`getAll`/`getByFolder`/`countAll`/`observeCount` all gained `WHERE isSynthetic = 0`, so refresh,
  OPML export, profile export and the drawer's source count skip it **for free** — no call-site branch needed
  beyond `FeedRepository.remove`/`removeAll`, which still refuse it explicitly since delete goes straight to
  `deleteById`. A caller that wants the row back (Y04's drawer) reaches it via `findByUrl(SAVED_LINKS_FEED_URL)`,
  deliberately unfiltered. `./gradlew test` (debug+release): 917+652=1569, 0 failures.
- 2026-08-25 — **Y03/#23: `SavedLinkRepository.saveLink(url): Result<Long>`**, `SaveLinkFailure`
  (`IsFeed`/`Unreachable`) copying `SourceResolution`'s shape. Lifted `ArticleTextRepository`'s
  fetch→extract→sanitize→image chain into `data/extract/PageContent.kt`
  (`PageContentExtractor`, reuses Y01's `PageMetadataExtractor`) so both read a page the same
  way. A pasted feed is caught by parsing the **fetched bytes**, not discovery — discovery
  would flag every blog post as "a feed" via its own autodiscovery `<link>`. Duplicate paste
  reuses `EntryDao.upsertAll`'s idempotent-on-`(feedId,guid)` write, no hand-rolled check.
  `./gradlew test` (debug+release): 924+659=1583, 0 failures.
- 2026-08-25 — **Y04/#23: `SaveLinkViewModel`/`SaveLinkSheet`**, one step shorter than
  `AddSourceViewModel`/`-Sheet` (no confirm). Text into a *real* `ModalBottomSheet` hits the
  tap trap above too — `SaveLinkSheetTest` composes `SaveLinkSheetContent` directly, and the
  screenshot tests seed the pasted row via `container.savedLinks.saveLink()` rather than
  typing into the sheet. `./gradlew test` (debug+release): 931+659=1590, 0 failures.
