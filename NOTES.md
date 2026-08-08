# NOTES.md

Working memory for unattended sessions, per CLAUDE.md §NOTES.md discipline. **Under 100 lines.**

## Environment facts (measured at bootstrap, 2026-08-07)

- Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM. No physical device; WHPX **enabled**.
  Paths/JDKs/wrappers are in CLAUDE.md §Environment — do not re-record them here.
- **Host froze twice: the `.wslconfig` 7 GB cap only took effect at the second `wsl --shutdown`. Confirm with
  `/proc/meminfo` MemTotal ~6.9 GB — ~9.9 GB means the cap is off and a freeze is coming.**

## Log
- 2026-08-07 — T04–T09: 42 manifest rows, **39 snapshots** (`scripts/harvest.sh`). **3 exclusions:** `danluu.com`
  (11.1 MB) and `projectzero.google` (13.2 MB) bust SPEC §6's 8 MiB cap; `research.nccgroup.com` has no feed.
- 2026-08-07 — T12–T18 (storage, HTTP, sync, worker). **Never Room `@Upsert` for entries** — it resolves on the
  *primary key*, 0 on a fresh parse, so the row is silently dropped; `EntryDao.upsertAll` matches `(feedId, guid)`.
  Sanitizing lives in `FeedRepository`.
- 2026-08-07 — T19: screens address `colorScheme` roles, never a tone. **Standing grep gate: no `Color(0x`, `N.dp`,
  `N.sp` outside `ui/theme/`.**
- 2026-08-07 — T20/T22. **Compose UI tests live in `app/src/testDebug/`** (`ui-test-manifest` is
  `debugImplementation`). Two standing Robolectric traps: an injected tap/long-press **never reaches a node inside a
  drawer sheet, bottom sheet or dropdown** (use `performSemanticsAction(OnClick/OnLongClick)`), and
  `compose.waitUntil` advances only the *virtual* clock — wait on Room in wall-clock time (`awaitInRealTime`).
- 2026-08-07 — T25: `ArticleLowering`'s input **must** be `HtmlSanitizer` output (`ArticleLoweringCorpusTest` asserts
  **0 `Unsupported`**); a source that renders wrong is an `ArticleLowering` bug, never a branch in `ArticleBody`.
- 2026-08-07 — T26: `PullToRefreshBox` ignores a swipe unless its child scrolls — assert on list state, never
  `assertIsDisplayed`. **Residual:** the empty state cannot be pulled. T28: the debug seed goes through
  **`FeedRepository.add`**, via a `ContentProvider` Robolectric never runs.
- 2026-08-07 — T29: `ui/screenshot/*` → PNGs in `screenshots/`. **Never `captureToImage()`** (CLAUDE.md's
  §Environment line is wrong on this): `PixelCopy` blocks on a frame-commit callback a Robolectric window never
  delivers. Under `@GraphicsMode(NATIVE)` a plain `View.draw(Canvas)` gives the same pixels synchronously; a sheet or
  dialog is its **own window**, so draw its `rootView` over the decor view, **translated by `getLocationOnScreen`**
  or a dropdown lands in the top-left corner. **Residual:** zero window insets (app bar flush at y=0).
- 2026-08-07 — T30: `maestro/regression.yaml`, staged to `/mnt/c/perch-stage/maestro/`, run from Windows.
- 2026-08-07 — T31: `fallbackToDestructiveMigration()` is **gone for good** — `PerchDatabaseMigrationTest` fails the
  build on a version bump with no migration or a stale `app/schemas/N.json`. (`WorkManagerTestInitHelper`'s
  `SynchronousExecutor` misses WorkManager's own executor — poll in wall-clock time.)
- 2026-08-07 — **T32.** `acceptance/LiveAcceptanceTest` is in `testDebug`. Re-run: `./gradlew :app:testDebugUnitTest
  -Pperch.live=true --tests '*LiveAcceptance*'`. **Gate 1 landed on the 38/42 floor** — the 3 T04 exclusions plus
  `rachelbythebay.com` (port 443 times out from this host) — so expect a red run if one more source dies. **§8
  residual, not ours:** the LLVM feed omits the spaces around inline `<code>`/`<a>`; do not "repair" it. **v0.1 APK
  (on the phone, debug-signed):** `app/build/outputs/apk/debug/app-debug.apk`.
- 2026-08-07 — **U01: the repo is public** (`github.com/michaelawetahegn/Perch`, MIT). Do not un-redact the
  `apiKey` in `fixtures/homepages/research-nccgroup-com.html`.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future install a data
  wipe** — you cannot rotate to a key you no longer have. Both `chmod 600`, outside the repo, **not backed up
  anywhere yet**. Cert SHA-256 `61367c04…fce489` (valid to 2053) *is* the update identity; `apksigner verify
  --print-certs` must keep printing it. Absent it, release falls back to debug signing with a warning. Version lives
  only in `perchVersionCode`/`perchVersionName` atop `app/build.gradle.kts`. **`assembleRelease` runs
  `lintVitalRelease`; `assembleDebug` does not.** The manifest drops WorkManager's `WorkManagerInitializer` via
  `tools:node="merge"` on `InitializationProvider` — never `node="remove"`.
- 2026-08-07 — **U03: DB is version 2** (folders). **Build test databases with `PerchDatabase.inMemory(context)`,
  never `Room.inMemoryDatabaseBuilder`** — it attaches the callback seeding Uncategorized, without which the
  `feeds.folderId` FK rejects the first feed. **A migration test builds the old DB from `app/schemas/N.json` via
  `ExportedSchemas.createStatements`** — no hand-copied DDL can drift. **`WorkSchedulerTest` "choosing manual
  cancels the periodic refresh" is flaky in a full-suite run** and passes alone.
- 2026-08-07 — **U04: DB is version 3** (`isSaved`/`savedAt`/`starredAt`). Three independent reader-owned flags;
  a flag going off nulls its timestamp. **Two places erase them if you add a fourth and forget:**
  `EntryDao.upsertAll` must copy every flag *and* timestamp from the existing row (a parsed entry arrives with them
  at their defaults), and `deleteReadOlderThan` exempts `isSaved`/`isStarred`.
- 2026-08-07 — **U05 BLOCKED on its gate, not its code.** `LeadImage.kt` resolves **337/339** of corpus entries
  carrying an image, but **only 339 of 1038 carry any image markup**, so "≥60% of entries" is unreachable until
  U10's `og:image` rung. No feed-level `<image>` fallback — that is the guessed URL §0 forbids. **Re-gated at U15.**
- 2026-08-07 — **U06 done: folders are in the drawer.** Scope is a `HomeScope` (All / Folder / Source); folder is a
  **second SQL predicate on `feeds.folderId`**, never a resolved list of feed ids — a move would invalidate that
  list. `observeListItems`, `unreadIds` and `FeedRepository.refreshFolder` all take the same scope. **Room rejects a
  `@Query` whose parameter it cannot see used**, so an always-true predicate must still mention `:folderId`. Drawer
  folder headers carry three targets (chevron / name / ⋮), deliberately **not** merged.
- 2026-08-07 — `FeedXml.kt`'s `stableGuid` separator must stay `"\u0000"` **escaped**: a raw NUL byte in a literal
  makes git diff the file as binary, so no session can review it.
- 2026-08-07 — **U07 done.** The window is a *calendar* one (`TimeFilter.since(clock)` = local midnight, never `now
  - n`), lives in DataStore and **defaults to Today** — so a UI test that seeds anything older than today must pin
  `TimeFilter.AllTime` via its own `SettingsStore` or it asserts against an empty screen. Sections fall out of the
  row: `observeListItems` orders folder-then-recency and carries `folderId`/`folderName`, so "a header is due" asks
  only about this row and the one before it — **keep it so; it is what makes U07a's page boundaries work.** The
  drawer composes even while closed, so a folder name is on screen twice: address list headers by
  `HomeTestTags.section(id)`, never by text. `uiState` is `WhileSubscribed`, so an action needing the current window
  reads `settings.current()`, not `uiState.value`.
- 2026-08-07 — **U08: the row is title + `Source / 5h` + a 96dp thumbnail, no snippet.** The thumbnail square is
  **always reserved** — absent, loading and failed all draw the same outlined placeholder through
  `SubcomposeAsyncImage`'s slots, so an arriving image never reflows the list (unlike `ArticleFigure`, which
  collapses by design). Coil states are reproducible offline: a `Mapper` succeeds, an `Interceptor` returning
  `ErrorResult` fails, one that `awaitCancellation()`s stays loading. **A Robolectric screenshot of the list needs
  `stubThumbnails()`.** **Residual polish:** a one-line title leaves dead space under its metadata (the thumbnail
  sets the row height), and home keeps hairline dividers where the reference uses whitespace alone.
- 2026-08-08 — **U08a done: the time range is a dropdown** (`TimeRangeControl`, tags `HomeTestTags.TIME_RANGE` /
  `RANGE_MENU` / `rangeItem`); the chip row and its Dimens tokens are gone. Two Compose-test traps: a `TextButton`
  **merges its descendants**, so the label is only addressable with `useUnmergedTree = true`; and
  **`hasVisualOverflow` is not a clipping assertion** — it also goes true when a paragraph's fractional height
  rounds past the integer layout height. Assert `lineCount` plus `size.width >= maxIntrinsicWidth`, under
  **`@GraphicsMode(NATIVE)`**: Robolectric's default text measurement is ~1px per character, so every string "fits".
- 2026-08-08 — **U09 done: the bottom bar is the top-level navigation** (Feed · To-Read · Liked). The bar and the
  `NavHost` are **siblings**, not nested: only the shell can say which tab is selected, and only the shell can leave
  the bar off `article/{id}`. **Feed's `DrawerState` and `LazyListState` are hoisted into `PerchNavHost`** — a scroll
  offset remembered inside the Feed composable is torn down on every tab switch, which is the one thing §0 says must
  survive one. §0's back policy is the pure `nextBackStep(BackState)` in `BackChain.kt` — the enum's declaration
  order *is* the priority, and the one root `BackHandler` is disabled at `Exit`, which is what lets the platform quit.
  `EntryListItem` now carries `isSaved`/`isStarred`/`link`, so the sheet opens showing the right verb without a
  second query. **`EntryRow` owns its own `combinedClickable`** — an inner `clickable` eats the pointer stream, so a
  long press wrapped *around* the row never arrives.
