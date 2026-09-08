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
  **S02/#33: a `ModalBottomSheet` has two exits, not one** — the scrim reaches `onDismissRequest`, a swipe (and the
  settle after the IME collapses) does not: it hides the sheet through `SheetState` first. Refusing a dismissal means
  guarding **both** `onDismissRequest` and `rememberModalBottomSheetState(confirmValueChange = …)`; guarding one leaves
  an invisible sheet still composed. The rule itself lives on the state (`SaveLinkUiState.canDismiss`) so a VM test can
  assert it — the container is undrivable from a test.
  **S10/#28: a root `BackHandler` in `PerchNavHost` loses to `NavController`'s own callback** — the
  dispatcher is LIFO and the graph registers later, so on To-Read/Liked back pops the tab before the
  chain is consulted. Anything drawn *over* the NavHost (search) must answer back with its own
  handler, composed deeper; `BackChain` keeps modelling the rung as an order, not as what runs.
  **S03/#30: `HomeScope` is a bare id, so every delete path must widen it** — `HomeScreen.widenScopeIfRemoved`
  runs before `confirmRemoveSources`, `deleteFolders` and `deleteFolder`. The shell owns the scope (V08); the VM's
  *resolved* scope (`HomeViewModel.kt:463-471`) feeds the bar's title only, never the query, which is why the bar
  said "Feed" over an empty list. A test that taps "All sources" before looking at the rows tests the reader's
  workaround, not the bug.
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
- 2026-08-25 — **v0.5.0 shipped (PLAN-6/#23, PLAN-7/#21, PLAN-8), archived.** `PageContentExtractor`
  (fetch→extract→sanitize→image) is the **one** function `ArticleTextRepository`,
  `SavedLinkRepository` and `BackfillRepository` all call — do not clone it. `feeds.isSynthetic`
  → DB v6, seeded `perch:saved-links` row. **S01/#31 corrects this line: only `FeedDao` ever
  said `isSynthetic = 0`** — `EntryQueries` never did, which is why a pasted link showed up in
  the Feed for a whole version. It does now (`LIST_ITEMS`, both unread badges, `unreadIds`,
  `FolderDao.observeUnreadCountsByFolder`); `SAVED`, `LIKED`, `statesToExport` and search must
  **not** — a saved link is a stored article, just not feed traffic. `ArchiveDiscovery`/`BackfillRepository` (`data/archive/`, `data/repo/`): discovery order
  RFC 5005 `prev-archive` → `robots.txt Sitemap:` → `/sitemap.xml`; post-vs-page is a dated URL
  path *or* a shape learned from the feed's own entry links, never a table of engines. `plan()`
  sorts by `lastmod` descending (unknown last) before `.take(MAX_PAGES=40)` — discovery order is
  sitemap *document* order, not date order, so an unsorted `.take` hands back an arbitrary 40 on
  a large archive (measured live on `fzakaria.com`, 143/133 discovered). `BackfillOffer` carries
  both `newPostCount` (true) and `pageCount` (capped) so the offer never claims a small archive
  is the whole one. `quantpedia.com` excluded from live gate 1 — its own TLS cert has expired,
  confirmed independently with `curl -v`, nothing Perch-side. `./gradlew test`:
  **1669** (976 debug + 693 release), 0 failures, grew monotonically from the 1524 v0.4.0 floor.
- 2026-09-07 — **S08/#28: the search index is a standalone FTS4 table Perch writes itself.**
  `entries_fts(title, body)`, `rowid = entries.id`, DB **v7**. Body is `HtmlSanitizer.flatten`ed
  plain text (new fn; `summarize` now delegates to it) — never markup, or `class`/`https` match
  everything. **Writes are Kotlin, deletes are a SQL trigger**: `EntryDao.index()` is called from
  `upsertAll` (both branches) and `ArticleTextRepository.loadFullText`, but a source removal
  reaches entries by `ON DELETE CASCADE` and never calls Kotlin, so `entries_fts_delete` fires
  `AFTER DELETE ON entries`. The trigger needs **two** homes — `MIGRATION_6_7` *and* a `Callback`
  on `build()`/`inMemory()` — because Room creates the table from the entity on a fresh install
  and knows nothing about triggers. `MIGRATION_6_7` backfills in Kotlin (200-row pages), not
  `INSERT … SELECT`: SQL cannot flatten HTML. `INSERT OR REPLACE` **does** work on an FTS4 table,
  so re-indexing is one statement. `MIGRATION_6_7`'s `CREATE VIRTUAL TABLE` must be byte-for-byte
  what `7.json` exports or Room fails validation on the next open.
- 2026-08-25 — **v0.5.0 released** (`versionCode` 6). The v0.4→v0.5 upgrade was verified on the
  emulator only: **the human's real phone is a separate device no session can reach.**
