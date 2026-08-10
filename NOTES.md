# NOTES.md

## Environment facts (measured at bootstrap, 2026-08-07)

Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM; no physical device, WHPX **enabled**. **The
`.wslconfig` 7 GB cap only applies after `wsl --shutdown`** — MemTotal ~6.9 GB means live, ~9.9 GB means a freeze.

## Log
- **Standing grep gate:** no `Color(0x` / `N.dp` / `N.sp` outside `ui/theme/` — screens address roles, never tones.
- 2026-08-07 — **Standing UI-test traps.** Compose UI tests live in **`app/src/testDebug/`** (`ui-test-manifest` is
  `debugImplementation`). An injected tap/long-press **never reaches a node inside a drawer sheet, bottom sheet or
  dropdown** — use `performSemanticsAction(OnClick/OnLongClick)`. `compose.waitUntil` advances only the *virtual*
  clock; wait in wall-clock time. `PullToRefreshBox` ignores a swipe unless its child scrolls — since V03 **every
  empty state is a `LazyColumn` with one `fillParentMaxSize` item** so the pull lands; keep it that way. Screenshots: **never
  `captureToImage()`** (CLAUDE.md is wrong) — `PixelCopy` waits on a frame callback Robolectric never delivers, while
  `@GraphicsMode(NATIVE)`'s `View.draw(Canvas)` is synchronous; a sheet/dialog/dropdown is its **own window**, so draw
  its `rootView` over the decor view **translated by `getLocationOnScreen`**.
- 2026-08-07 — **Live acceptance** (`acceptance/LiveAcceptanceTest`, `testDebug`): `./gradlew :app:testDebugUnitTest
  -Pperch.live=true --tests '*LiveAcceptance*'`. **Gate 1 sits on the 38/42 floor** — `danluu.com`/`projectzero.google`
  bust SPEC §6's 8 MiB cap, `research.nccgroup.com` has no feed, `rachelbythebay.com` times out (issue #8/V12).
  **Not ours:** the LLVM feed omits the spaces around inline `<code>`/`<a>` — do not "repair" it.
- 2026-08-07 — **U01: the repo is public** (MIT) — never un-redact the `apiKey` in `fixtures/homepages/research-nccgroup-com.html`.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future install a data
  wipe** — you cannot rotate to a key you no longer have. Both `chmod 600`, outside the repo, **not backed up yet**.
  Cert SHA-256 `61367c04…fce489` *is* the update identity; absent the key, release silently falls back to debug
  signing. Version lives only atop `app/build.gradle.kts`; **`assembleRelease` runs `lintVitalRelease`.**
- 2026-08-07 — **U03: build test databases with `PerchDatabase.inMemory(context)`**, never
  `Room.inMemoryDatabaseBuilder` — only the former seeds Uncategorized, whose FK the first feed needs.
- 2026-08-07 — **U04: `isRead`/`isSaved`/`isStarred` are independent reader-owned flags**, each nulling its timestamp
  when it goes off. **Add a fourth and two places erase it:** `EntryDao.upsertAll` (never Room `@Upsert` — it resolves
  on the primary key, ours on `(feedId, guid)`) and `deleteReadOlderThan`.
- 2026-08-07 — **U07: the window is a *calendar* one** (`TimeFilter.since(clock)` = local midnight) and **defaults to
  Today** — a UI test seeding anything older must pin `TimeFilter.AllTime` via its own `SettingsStore` or it asserts
  against an empty screen. Address section headers by `HomeTestTags.section(id)`, never by text (the drawer composes
  while closed). `uiState` is `WhileSubscribed`: an action needing the window reads `settings.current()`.
- 2026-08-07 — **U08: the row's 96dp thumbnail square is always reserved** (absent/loading/failed draw one
  placeholder). Coil offline: a `Mapper` succeeds, an `Interceptor` returning `ErrorResult` fails, one that
  `awaitCancellation()`s stays loading; a list screenshot needs `stubThumbnails()`.
- 2026-08-08 — **U08a.** A `TextButton` **merges its descendants** (`useUnmergedTree = true`); **`hasVisualOverflow`
  is not a clipping assertion** — assert `lineCount` + `size.width >= maxIntrinsicWidth` under `@GraphicsMode(NATIVE)`
  (Robolectric's default text measurement gives every char ~1px).
- 2026-08-08 — **U09: the bottom bar and the `NavHost` are siblings**, not nested; **Feed's `DrawerState`/
  `LazyListState` are hoisted into `PerchNavHost`** (state remembered inside Feed dies on a tab switch). §0's back
  policy is the pure `nextBackStep(BackState)` in `BackChain.kt`, the enum's declaration order *being* the priority.
  **`EntryRow` owns its own `combinedClickable`**: an inner `clickable` eats the pointer stream.
- 2026-08-08 — **U09a: the selection `BackHandler` must live inside `ModalDrawerSheet`** — the root one registers
  first and loses. A batch delete's dialog is **a coroutine behind its tap**, so wait in wall-clock time.
- 2026-08-08 — **U07a: all three lists are Paging 3.** New deps `androidx.paging:paging-runtime-ktx`/`-compose`
  (+`room-paging`, `paging-testing`). `PerchPaging.config` is shared — **placeholders off**, so `startsSection` is
  answerable at a page edge; `initialLoadSize` is one page. The three list queries live once in **`EntryQueries`**
  (`const val`, resolved by Room/KSP) because each exists twice — `Flow<List>` *and* `PagingSource`.
  **`uiState.entries` is gone**: ask the screen (`compose.rowTitles()`); `performScrollToIndex` past loaded rows throws.
- 2026-08-08 — **U10: DB is version 4** (`entries.bodyIsExcerpt`, `fullTextAt`); Readability-over-jsoup in
  `data/extract/`, **no new dependency**. Three traps. (1) **`ArticleLowering` deletes truncation markers as chrome**
  (T25's `CHROME`), so `FullText` looks for "Continue reading" in the *unlowered* text. (2) Scoring finds the
  *tightest* subtree, so a decorative single-child wrapper (ciechanow.ski's `bg_content`) wins and the article's last
  section, its sibling, is lost — hence `unwrapped()`. (3) `upsertAll` keeps the extracted body unless the feed's is
  longer — **an extraction only ever replaces a body it beats**, making auto-extract-on-open safe. Fixtures:
  `fixtures/articles/` (2 MB).
- 2026-08-08 — **U11 (code).** Bundled JetBrains Mono 2.304 (OFL 1.1, licence in `assets/`) is DESIGN.md §3's one
  exception, **ligatures off**. **`HtmlSanitizer` keeps `class` on `pre` only**, holding a `language-x` normalised
  before `Cleaner` runs from the `<code>`, the `<pre>`, or a wrapper `<div>` two levels up (Rouge/Jekyll). The gutter sits **outside** the `horizontalScroll`, inside a **`DisableSelection`**.
- 2026-08-08 — **U11a (tables). Inside a `horizontalScroll` a `fillMaxWidth` divider measures to 0** — rules are
  drawn at the summed column width. `fixtures/articles/zdi-*.html` are **feed bodies, not pages**.
- 2026-08-08 — **U12: the viewer is an overlay, not a destination** — a sibling of the article's `Scaffold` in one
  `Box`, so the reading position survives; `ZoomedImage` is hoisted to `PerchNavHost` because
  **`BackStep.CloseImageViewer` sits between `CloseOverlay` and `PopArticle`** and `BackChainTest` guards that order.
  Math is pure (`ZoomGeometry`: rubber-banded 1×–5×, pan fenced to the **fitted content**, dismiss only at fit);
  `detectTransformGestures` **has no end callback**, hence `awaitEachGesture`. An open overlay eats
  `performTouchInput` — scroll under it first; `performClick` needs `mainClock.advanceTimeBy`.
- 2026-08-08 — **U13 (OPML folders).** A folder is a **name, not an id** — ids do not survive the file (U14 inherits
  this). Export writes Uncategorized's sources **unfiled at top level**; import files a source under the **outermost**
  container and flattens deeper nesting. Two rules the counts depend on: a **duplicate is left entirely alone, folder
  included**, and a folder is created **only when a source actually goes into it**, so a re-import reports
  `0/n/k/0 folders`. Fixture: `fixtures/opml/other-reader.opml`.
- 2026-08-08 — **U14 (profile).** DB is version 5: `pending_entry_state`, keyed `(feedUrl, guid)` and with **no FK to
  `feeds`** — its job is outliving a source that does not exist yet. **`EntryDao.upsertAll` is the fourth place a
  refresh meets reader state**: it *consumes* parked rows, which is what stops the refresh straight after a restore
  undoing it. The export carries only entries a reader touched, so a restore turns a flag **on** and never off, and is
  idempotent by construction. Codec is `org.json` — **so its tests need Robolectric**; on a bare JVM `JSONObject` stubs.
- 2026-08-09 — **V01/#1: Robolectric builds `PerchApp` for every test**, and its `onCreate` launched into a scope
  nothing owned, so startup work outlived the test. `startupScope` now has a `CoroutineExceptionHandler` and dies in
  **`onTerminate()`** — which **Robolectric's `tearDownApplication()` calls after every test**, and that is what makes
  the boundary real. `SettingsStore`/`AppContainer` are `Closeable`; a store cancels only a scope it *owns*
  (`create`), never a caller's. `ProcessLifecycleTest` guards it. **`UncaughtExceptionsBeforeTest` never reproduced**
  — the leak is proven, the link to that signature is inference, so `LoudUncaughtHandler` stays to catch a recurrence.
- 2026-08-09 — **Every full-suite flake so far: waiting on Room is not waiting on the screen.** `ArticleFullTextTest`
  awaited the body reaching the DB then asserted rendered text at once, losing the emit→recompose hop under load
  (giveaway: *"could not find any node … however, the unmerged tree contains 1 node"*). `waitForIdle` cannot cover it.
  **Assert rendered text with a wall-clock poll** (`awaitText`/`awaitDisplayed`), never straight after a DB wait.
- 2026-08-09 — **V02/#9: a `Clock` carries a zone, and the container's was Greenwich's.** `TimeFilter.since` was
  always right; `AppContainer(clock = systemUTC())` was the bug — past 19:00 CDT, Today opened *after* the reader's
  whole day. Now `systemDefaultZone()`, which also dates the OPML/profile export filenames right. **`DateParser`
  stays UTC deliberately.** A zone test must pin `TimeZone.setDefault`: inheriting the JVM's cannot tell
  UTC-the-bug from UTC-the-agent.
- 2026-08-09 — **V06/#11: folder order is alphabetical (`COLLATE NOCASE`), Uncategorized still pinned
  by `(id = 1) ASC`.** The clause is stated **three** times and they must agree — `FolderDao.observeAll`,
  `FolderDao.getAll`, `EntryQueries.LIST_ITEMS` (the drawer and the section headers read *different*
  ones). `sortIndex` stays a column (OPML/profile round-trips) but decides nothing. **`COLLATE NOCASE`
  folds ASCII only**: `Émacs` sorts after every plain name, by UTF-8 byte — pinned by a test, accepted,
  not a bug to "fix" without an ICU collation.
- 2026-08-08 — **U16: v0.2.0 shipped** (versionCode 3, `app/build/outputs/apk/release/app-release.apk`). **Never
  `clean` before `test assembleRelease`** — it deletes `build/perch-screenshots/`, the evidence.
- 2026-08-09 — **Never wait on a WSL→Windows `cmd.exe /c start` — background it** (encoded in `device.sh`/`loop.sh`);
  the wrapper never returns though the emulator boots fine. Only `booted()` polling adb knows a boot worked.
- 2026-08-09 — **V04/#3: the inset contract is one doc comment in `ui/nav/PerchNavHost.kt`** — four clauses, one
  test each in `WindowInsetsTest`. Never add `.statusBarsPadding()` to a screen. Two traps. (1) **Robolectric has no
  bars and no cutout on any profile**, so an inset test that does not dispatch its own passes on a tree that handles
  nothing — `ui/WindowInsetsSupport.kt`'s `applyWindowInsets` dispatches to **every Compose root** (`WindowInsetsHolder`
  listens on the `AndroidComposeView`, not the decor view). (2) **The bottom bar and the `NavHost` are siblings, so
  both spent the bottom inset** — 24dp of dead space above the bar; the shell now `consumeWindowInsets`, but only
  while a tab is showing.
- 2026-08-09 — **v0.3 is `PLAN-3.md` (V01–V16), one task per issue.** A task ends: commit → `git push` →
  `gh issue close N` with the verification line. **No fix without a failing test that reproduced the bug first.**
- 2026-08-09 — **V05/#12: "Unread" is gone from every string a reader sees.** `home_title_unread`→
  `home_title_feed` ("Feed"), `drawer_all_unread`→`drawer_all_sources` ("All sources" — the row scopes the
  Feed to *everything*, it never promised unread-only); `home_empty_window_body` dropped its "unread" too.
  Identifiers keep the word on purpose (`HomeTestTags.ALL_UNREAD_BADGE`, `observeUnread*`, "Mark unread") —
  the flag is real. **`PerchNavHostTest` must pin `HomeTestTags.TITLE`, not `onNodeWithText("Feed")`** — the
  bottom-bar tab has read "Feed" since U09, so a text assertion passes without the top bar existing.
