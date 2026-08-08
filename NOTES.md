# NOTES.md

Working memory for unattended sessions, per CLAUDE.md §NOTES.md discipline. **Under 100 lines.**

## Environment facts (measured at bootstrap, 2026-08-07)

- Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM. No physical device; WHPX
  **enabled**. Paths/JDKs/wrappers are in CLAUDE.md §Environment — do not re-record them here.
- **Host froze twice (v0.1 #11, v0.2 #5): the `.wslconfig` 7 GB cap only took effect at the
  second `wsl --shutdown`. Confirm it is live with `/proc/meminfo` MemTotal ~6.9 GB — if it
  reads ~9.9 GB the cap is not applied and a freeze is coming.**

## Log
- 2026-08-07 — T04–T09: 42 manifest rows, **39 snapshots** (`scripts/harvest.sh`). **3 exclusions:**
  `danluu.com` (11.1 MB) and `projectzero.google` (13.2 MB) bust SPEC §6's 8 MiB cap;
  `research.nccgroup.com` has no feed.
- 2026-08-07 — T12–T18 done (storage, HTTP, sync, worker). **Never Room `@Upsert` for entries** —
  it resolves on the *primary key*, still 0 on a freshly parsed row, so the row is silently
  dropped; `EntryDao.upsertAll` matches `(feedId, guid)`. **Sanitizing lives in `FeedRepository`.**
- 2026-08-07 — T19 done: `ui/theme/*`. Screens address `colorScheme` roles, never a tone.
  **Standing grep gate: no `Color(0x`, `N.dp`, `N.sp` outside `ui/theme/`.**
- 2026-08-07 — T20/T22 done. **Compose UI tests must live in `app/src/testDebug/`** —
  `ui-test-manifest` is `debugImplementation`. Two standing Robolectric traps: an injected tap
  **never reaches a node inside an opened drawer sheet** (use `performSemanticsAction`), and
  `compose.waitUntil` advances only the *virtual* clock — wait on Room in wall-clock time
  (`awaitInRealTime`, in `ui/screenshot/ScreenshotSupport.kt`).
- 2026-08-07 — T25/T25a done: `ui/article/*`. `ArticleLowering`'s input **must** be `HtmlSanitizer`
  output (`ArticleLoweringCorpusTest` asserts **0 `Unsupported`**); a source that renders wrong is
  an `ArticleLowering` bug, never a branch in `ArticleBody`. An image **collapses the whole figure
  on a load error** (right there, wrong in U08's row); testing one needs a Coil loader that
  succeeds (`Coil.setImageLoader` + a stub `Mapper`).
- 2026-08-07 — T26 done. `PullToRefreshBox` ignores a swipe unless its child scrolls, and
  refresh-prepended rows compose above the viewport — assert on list state, never
  `assertIsDisplayed`. **Residual:** the empty state cannot be pulled.
- 2026-08-07 — T28: debug seed goes through **`FeedRepository.add`**, via a debug-manifest
  `ContentProvider` that never runs under Robolectric (build your own DB).
- 2026-08-07 — T29 done: `ui/screenshot/*` → 6 PNGs in `screenshots/`. **Never `captureToImage()`**
  (CLAUDE.md's §Environment line is wrong on this): it goes via `PixelCopy`, which blocks on a
  frame-commit callback a Robolectric window never delivers. Under `@GraphicsMode(NATIVE)` a plain
  `View.draw(Canvas)` gives the same pixels synchronously; a sheet or dialog is its **own window**,
  so draw its `rootView` over the decor view. **Residuals:** zero window insets (app bar and drawer
  flush at y=0); the drawer's Settings row is below the fold.
- 2026-08-07 — T30 done: `maestro/regression.yaml`, driven from Windows (stage to
  `/mnt/c/perch-stage/maestro/`; `device.sh stage` will **not** overwrite an existing dir).
  `testTagsAsResourceId` does **not** reach sheets/dialogs — address those by label; a Maestro
  text selector matches a node's text *entirely* (merged rows need `.*…*.`).
- 2026-08-07 — T31 done: `fallbackToDestructiveMigration()` is **gone for good**;
  `PerchDatabaseMigrationTest` fails the build on a version bump with no migration, a stale
  `app/schemas/N.json`, or the fallback reappearing. `WorkManagerTestInitHelper`'s
  `SynchronousExecutor` misses WorkManager's own executor — poll in wall-clock time.
- 2026-08-07 — **T32 done.** `acceptance/LiveAcceptanceTest` is in `testDebug`, not `test`. Re-run:
  `./gradlew :app:testDebugUnitTest -Pperch.live=true --tests '*LiveAcceptance*'`. **Gate 1 landed
  on the 38/42 floor** — the 3 T04 exclusions plus `rachelbythebay.com`, whose port 443 times out
  from this host — so expect a red run if one more source dies. **§8 residual, cosmetic and not
  ours:** the LLVM feed omits the spaces around inline `<code>`/`<a>` — do not "repair" it.
  **v0.1 APK (on the phone, debug-signed):** `app/build/outputs/apk/debug/app-debug.apk`.
- 2026-08-07 — **U01: the repo is public** (`github.com/michaelawetahegn/Perch`, MIT, `v0.1.0`).
  Do not un-redact the `apiKey` in `fixtures/homepages/research-nccgroup-com.html`.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future
  install a data wipe** — you cannot rotate to a key you no longer have. Both `chmod 600`, outside
  the repo, **not backed up anywhere yet**. Cert SHA-256 `61367c04…fce489` (valid to 2053) *is* the
  update identity; `apksigner verify --print-certs` must keep printing it. Absent it, release falls
  back to debug signing with a warning. Version lives only in `perchVersionCode`/`perchVersionName`
  atop `app/build.gradle.kts`. **`assembleRelease` runs `lintVitalRelease`; `assembleDebug` does
  not.** The manifest drops WorkManager's `WorkManagerInitializer` via `tools:node="merge"` on
  `InitializationProvider` — never `node="remove"`.
- 2026-08-07 — **U03 done: DB is version 2** (folders). **Build test databases with
  `PerchDatabase.inMemory(context)`, never `Room.inMemoryDatabaseBuilder`** — it attaches the
  callback seeding Uncategorized, without which the `feeds.folderId` FK rejects the first feed.
  **A migration test builds the old DB from `app/schemas/N.json` via
  `ExportedSchemas.createStatements`**, sets `version`, then opens with Room — no hand-copied DDL
  can drift. `observeUnreadCountsByFolder()` has the same `GROUP BY` trap. **`WorkSchedulerTest`
  "choosing manual cancels the periodic refresh" is flaky in a full-suite run** and passes alone.
- 2026-08-07 — **U04 done: DB is version 3** (`isSaved`/`savedAt`/`starredAt`). Three independent
  reader-owned flags: read, saved (*Read later*), starred (*Liked*); a flag going off nulls its
  timestamp. **Two places erase them if you add a fourth and forget:** `EntryDao.upsertAll` must
  copy every flag *and* timestamp from the existing row (a parsed entry always arrives with them at
  their defaults), and `deleteReadOlderThan` exempts `isSaved`/`isStarred` — retention bounds
  storage, it does not empty a queue the reader filled.
- 2026-08-07 — **U05 BLOCKED on its gate, not its code.** `data/parse/LeadImage.kt` resolves
  **337/339 (99.4%)** of corpus entries carrying an image, but **only 339 of 1038 carry any image
  markup**, so U05's "≥60% of entries" is unreachable until U10's `og:image` rung. Do not chase it
  with a feed-level `<image>`/`<logo>` fallback: the site logo as every thumbnail is the guessed
  URL §0 forbids. `ThumbnailCorpusTest` gates on *share of available* (≥95%). **Re-gated at U15.**
- 2026-08-07 — **U06 done: folders are in the drawer.** Scope is a `HomeScope`
  (All / Folder / Source); folder is a **second SQL predicate on `feeds.folderId`**, never a
  resolved list of feed ids — a move would invalidate that list. `observeListItems`, `unreadIds`
  and `FeedRepository.refreshFolder` all take the same scope. **Room rejects a `@Query` whose
  parameter it cannot see used**, so an always-true predicate must still mention `:folderId`.
  Drawer folder headers carry three targets (chevron / name / ⋮), deliberately **not** merged.
- 2026-08-07 — `FeedXml.kt`'s `stableGuid` separator must stay `"\u0000"` **escaped**: a raw NUL byte in a literal makes git diff the file as binary, so no session can review it.
- 2026-08-07 — **U07 done.** The window is a *calendar* one (`TimeFilter.since(clock)` = local
  midnight, never `now - n`), lives in DataStore and **defaults to Today** — so a UI test that
  seeds anything older than today must pin `TimeFilter.AllTime` via its own `SettingsStore` or
  it asserts against an empty screen. Sections fall out of the row: `observeListItems` orders
  folder-then-recency and carries `folderId`/`folderName`, so "a header is due" asks only about
  this row and the one before it — **keep it so; it is what makes U07a's page boundaries work.**
  The drawer composes even while closed, so a folder name is on screen twice: address list
  headers by `HomeTestTags.section(id)`, never by text. `uiState` is `WhileSubscribed`, so an
  action needing the current window reads `settings.current()`, not `uiState.value`.
