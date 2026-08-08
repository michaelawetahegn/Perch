# NOTES.md

## Environment facts (measured at bootstrap, 2026-08-07)

Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM; no physical device, WHPX **enabled**. **The
`.wslconfig` 7 GB cap only applies after a `wsl --shutdown`** — `/proc/meminfo` MemTotal ~6.9 GB means it is live,
~9.9 GB means a freeze is coming.

## Log
- **Standing grep gate:** no `Color(0x` / `N.dp` / `N.sp` outside `ui/theme/` — screens address roles, never tones.
- 2026-08-07 — **Standing UI-test traps (T20/T22/T26/T29).** Compose UI tests live in **`app/src/testDebug/`**
  (`ui-test-manifest` is `debugImplementation`). An injected tap/long-press **never reaches a node inside a drawer
  sheet, bottom sheet or dropdown** — use `performSemanticsAction(OnClick/OnLongClick)`. `compose.waitUntil` advances
  only the *virtual* clock; wait on Room in wall-clock time (`awaitInRealTime`). `PullToRefreshBox` ignores a swipe
  unless its child scrolls. Screenshots: **never `captureToImage()`** (CLAUDE.md is wrong) — `PixelCopy` waits on a
  frame callback Robolectric never delivers, while under `@GraphicsMode(NATIVE)` `View.draw(Canvas)` is synchronous;
  a sheet/dialog/dropdown is its **own window**, so draw its `rootView` over the decor view **translated by
  `getLocationOnScreen`**.
- 2026-08-07 — **T32.** `acceptance/LiveAcceptanceTest` (in `testDebug`): `./gradlew :app:testDebugUnitTest
  -Pperch.live=true --tests '*LiveAcceptance*'`. **Gate 1 sits on the 38/42 floor** — `danluu.com`/`projectzero.google`
  bust SPEC §6's 8 MiB cap, `research.nccgroup.com` has no feed, `rachelbythebay.com` times out here; one more death
  is a red run. **§8 residual, not ours:** the LLVM feed omits the spaces around inline `<code>`/`<a>` — do not "repair" it.
- 2026-08-07 — **U01: the repo is public** (MIT) — never un-redact the `apiKey` in `fixtures/homepages/research-nccgroup-com.html`.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future install a data
  wipe** — you cannot rotate to a key you no longer have. Both `chmod 600`, outside the repo, **not backed up yet**.
  Cert SHA-256 `61367c04…fce489` *is* the update identity; absent the key, release falls back to debug signing with a
  warning. Version lives only in `perchVersionCode`/`perchVersionName` atop `app/build.gradle.kts`; **`assembleRelease`
  runs `lintVitalRelease` where `assembleDebug` does not.**
- 2026-08-07 — **U03: build test databases with `PerchDatabase.inMemory(context)`**, never
  `Room.inMemoryDatabaseBuilder` — only the former seeds Uncategorized, without which `feeds.folderId`'s FK rejects the first feed.
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
  (Robolectric's default text measurement gives every char ~1px, so everything "fits").
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
  **`uiState.entries` is gone**: ask the screen (`compose.rowTitles()`) what the list holds, `observeEntries` the
  *query*. `performScrollToIndex` past loaded rows throws.
- 2026-08-08 — **U10: DB is version 4** (`entries.bodyIsExcerpt`, `fullTextAt`); Readability-over-jsoup in
  `data/extract/`, **no new dependency**. Three traps. (1) **`ArticleLowering` deletes truncation markers as chrome**
  (T25's `CHROME`), so `FullText` looks for "Continue reading" in the *unlowered* text. (2) Scoring finds the
  *tightest* subtree, so a decorative single-child wrapper (ciechanow.ski's `bg_content`) wins and the article's last
  section, its sibling, is lost — hence `unwrapped()`. (3) `upsertAll` keeps the extracted body unless the feed's is
  longer — **an extraction only ever replaces a body it beats**, which is what makes auto-extract-on-open safe.
  fabiensanglard: 68 of 144 ship nothing, 76 a one-line `<description>`. Fixtures: `fixtures/articles/` (2 MB).
- 2026-08-08 — **U11 (code).** Bundled JetBrains Mono 2.304 (OFL 1.1, licence in `assets/`) is DESIGN.md §3's one
  exception, **ligatures off**. **`HtmlSanitizer` keeps `class` on `pre` and nowhere else**, holding a normalised
  `language-x` hoisted before `Cleaner` runs from the `<code>`, the `<pre>`, or a wrapper `<div>` two levels up
  (Rouge/Jekyll). The gutter sits **outside** the `horizontalScroll`, inside a **`DisableSelection`**.
- 2026-08-08 — **U11a (tables). Inside a `horizontalScroll` a `fillMaxWidth` divider measures to 0** — that, not the
  lowering, is why tables looked ruleless; rules are drawn at the summed column width. `fixtures/articles/zdi-*.html`
  are **feed bodies, not pages**: `ArticleExtractor` loses a table on a Squarespace page (each block its own
  `sqs-block` div, past `assemble`'s sibling sweep).
- 2026-08-08 — **U12: the viewer is an overlay, not a destination** — a sibling of the article's `Scaffold` in one
  `Box`, so the reading position survives; `ZoomedImage` is hoisted to `PerchNavHost` because
  **`BackStep.CloseImageViewer` sits between `CloseOverlay` and `PopArticle`** and `BackChainTest` guards that order.
  Math is pure (`ZoomGeometry`: rubber-banded 1×–5×, pan fenced to the **fitted content**, dismiss only at fit);
  `detectTransformGestures` **has no end callback**, hence `awaitEachGesture`. `performClick` needs
  `mainClock.advanceTimeBy`; an open overlay eats `performTouchInput` — scroll under it first.
- 2026-08-08 — **U13 (OPML folders).** A folder is a **name, not an id** — ids do not survive the file (U14 inherits
  this). Export writes Uncategorized's sources **unfiled at top level**; import files a source under the **outermost**
  container and flattens deeper nesting. Two rules the counts depend on: a **duplicate is left entirely alone, folder
  included**, and a folder is created **only when a source actually goes into it**, so a re-import reports
  `0/n/k/0 folders`. Fixture: `fixtures/opml/other-reader.opml`.
- 2026-08-08 — **U14 (profile).** DB is version 5: `pending_entry_state`, keyed `(feedUrl, guid)` like the file
  itself and with **no FK to `feeds`** — its whole job is outliving a source that does not exist yet.
  **`EntryDao.upsertAll` is now the fourth place a refresh meets reader state**: it *consumes* parked rows, which is
  what stops the refresh straight after a restore undoing it. The export carries only entries a reader touched, so a
  restore can turn a flag **on** and never off, and is idempotent by construction. Codec is `org.json` (no new
  dependency) — **so its tests need Robolectric**; on a bare JVM every `JSONObject` method is a stub.
- 2026-08-08 — **Full-suite-only flakes (two, same shape).** `WorkSchedulerTest` "choosing manual…" and
  `ArticleTextRepositoryTest` "…og image…" fail in a full `./gradlew test`, pass alone. **`runTest` bills the previous
  test's leaked coroutine to whoever runs next** (`UncaughtExceptionsBeforeTest` at `@Before`, then `@After` dies on an
  uninitialised `db`) — the named test is the victim. Suspect **`SettingsStore.create`'s unowned
  `CoroutineScope(IO+SupervisorJob)`**, never cancelled, writing onto a wiped Robolectric data dir. **PLAN-3 box.**
- 2026-08-08 — **U15.** 38/42 pull · 0 `Unsupported`/25,882 blocks · 75.4% thumbnails · 92.5% full-text, ×39.9
  aggregate · gpuopen 194→5269 · 3 folders round-tripped · 123 tables/2080 cells · paging 30 of 1037 · 16 shots.
  **`clean` deliberately omitted** from the box's `clean test assembleRelease`: it deletes `build/perch-screenshots/`,
  the evidence the Done-condition asks for. **v0.2 APK, release-signed `61367c04…fce489` (upgrades v0.1 in place):
  `app/build/outputs/apk/release/app-release.apk`.**
- 2026-08-08 — **Residual polish, v0.2 (none blocks release).** No window insets anywhere (viewer close sits under
  the status bar); empty state cannot be pulled; mid-selection a folder header does nothing; themed launcher icon's P
  closes up at 48dp; no rule between code gutter and code; no edge affordance on a wide table.
