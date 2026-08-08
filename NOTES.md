# NOTES.md

Working memory for unattended sessions, per CLAUDE.md §NOTES.md discipline. **Under 100 lines.**

## Environment facts (measured at bootstrap, 2026-08-07)

- Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM. No physical device; WHPX
  **enabled**. Paths/JDKs/wrappers are in CLAUDE.md §Environment — do not re-record them here.
- **The memory fix for the session-#11 host freeze is in `.wslconfig`, `gradle.properties` and
  `loop.sh`; the `.wslconfig` half still needs a `wsl --shutdown` that has not happened.**

## Log
- 2026-08-07 — T04–T09: 42 manifest rows, **39 snapshots** (`scripts/harvest.sh`); shared parser
  plumbing in `data/parse/FeedXml.kt`. **3 exclusions:** `danluu.com` (11.1 MB) and
  `projectzero.google` (13.2 MB) bust SPEC §6's 8 MiB cap; `research.nccgroup.com` has no feed.
- 2026-08-07 — T12–T18 done (storage, HTTP, sync, worker). Three rules that outlive them:
  **never Room `@Upsert` for entries** — it resolves on the *primary key*, still 0 on a freshly
  parsed row, so the row is silently dropped; `EntryDao.upsertAll` matches `(feedId, guid)`.
  **Sanitizing lives in `FeedRepository`.** And `observeUnreadCountsByFeed()` is a `GROUP BY`
  multimap: a **fully-read source is absent from it, not 0**.
- 2026-08-07 — T19 done: `ui/theme/*`. Tonal palettes from `#3F6E5A` are **private to Color.kt** —
  screens address `colorScheme` roles, never a tone — and `ArticleType` (serif) is out of
  `PerchTypography` (sans) so furniture cannot render serif by accident. **Standing grep gate: no
  `Color(0x`, `N.dp`, `N.sp` outside `ui/theme/`.** `PerchTheme(dynamicColor = false)` for screenshots.
- 2026-08-07 — T20/T22 done. **Compose UI tests must live in `app/src/testDebug/`** —
  `ui-test-manifest` is `debugImplementation`, so the release manifest has no `ComponentActivity`.
  Two standing Robolectric traps (restated in PLAN-2 U06): `compose.waitUntil` advances only the
  *virtual* clock, so use `awaitInRealTime` from `ui/screenshot/ScreenshotSupport.kt`; and an
  injected tap never reaches a node inside an opened drawer sheet or a scrolling column — drive
  those with `performSemanticsAction(...)`.
- 2026-08-07 — T25/T25a done: `ui/article/*`. `ArticleLowering`'s input **must** be `HtmlSanitizer`
  output — the mapper covers that allowlist, so `ArticleLoweringCorpusTest` asserts **0
  `Unsupported`** (T32 gate 2 allows 2%). `ArticleBody` is total over the nine blocks with **no
  source-specific branch**; a source that renders wrong is an `ArticleLowering` bug. An image
  **collapses the whole figure on a load error**, so testing one needs a Coil loader that succeeds
  (`Coil.setImageLoader` + a stub `Mapper`). **Residual:** §8's link underline is plain.
- 2026-08-07 — T26 done: pull-to-refresh, undo snackbar, `HomeBanner`, `ConnectivityMonitor`
  (`AlwaysOnline` is the `AppContainer` default, so no test needs a shadow network). Gesture traps:
  `PullToRefreshBox` ignores a swipe unless its child scrolls, and refresh-prepended rows compose
  above the viewport — assert on list state, never `assertIsDisplayed`. **Residual:** the empty
  state cannot be pulled to refresh.
- 2026-08-07 — T28 done: debug seed data goes through **`FeedRepository.add`**, so seeded rows are
  sanitized/deduped like fetched ones and keep their **real** feed URL. The hook is a
  **debug-manifest `ContentProvider`** (`NoSeederInReleaseTest`) that seeds only when zero sources
  exist; under Robolectric it never runs, so construct `DebugSeeder` against your own DB.
- 2026-08-07 — T29 done: `ui/screenshot/*` → 6 PNGs in `screenshots/`. **Never `captureToImage()`**
  (CLAUDE.md's §Environment line is wrong on this): it goes via `PixelCopy`, which blocks on a
  frame-commit callback a Robolectric window never delivers. Under `@GraphicsMode(NATIVE)` a plain
  `View.draw(Canvas)` gives the same pixels synchronously; a sheet or dialog is its **own window**,
  so draw its `rootView` over the decor view. **Residual:** zero window insets, so app bar and
  drawer sit flush at y=0.
- 2026-08-07 — T30 done: `maestro/regression.yaml`, green, driven from Windows. Re-run: copy the yaml
  to `/mnt/c/perch-stage/maestro/` (`device.sh stage` will **not** overwrite an existing dir), then
  from `/mnt/c`: `cmd.exe /c "C:\perch-stage\maestro.bat --device emulator-5554 test C:\perch-stage\maestro\regression.yaml"`.
  `testTagsAsResourceId` (`PerchNavHost`) is an out-of-process driver's only handle on a Compose node
  and does **not** reach sheets/dialogs — address those by label; and a Maestro text selector matches
  a node's text *entirely*, so merged rows need a `.*…*.` regex.
- 2026-08-07 — T31 done: `fallbackToDestructiveMigration()` is **gone for good**;
  `PerchDatabaseMigrationTest` fails the build on a version bump with no migration, a stale
  `app/schemas/N.json`, or the fallback reappearing. `WorkManagerTestInitHelper`'s `SynchronousExecutor`
  misses WorkManager's own task executor, so `cancelUniqueWork` lands async — poll in wall-clock time.
- 2026-08-07 — **T32 done.** `acceptance/LiveAcceptanceTest` is in `testDebug`, not `test` (gate 3
  needs a Compose rule). Re-run: `./gradlew :app:testDebugUnitTest -Pperch.live=true --tests
  '*LiveAcceptance*'`. **Gate 1 landed on the 38/42 floor** — the 3 T04 exclusions plus
  `rachelbythebay.com`, whose port 443 times out from this host — so expect a red run if one more
  source dies. `ArticleViewModel.standfirst` compares the summary against the body's opening
  **prose across blocks**, not its first block. **§8 residuals, cosmetic and not ours:** the LLVM
  feed's markup omits the spaces around inline `<code>`/`<a>` (`However,until`) — do not "repair" it
  in the lowering; an inline WordPress-LaTeX image becomes a full-measure block mid-sentence.
  **v0.1 APK (on the phone, debug-signed):** `app/build/outputs/apk/debug/app-debug.apk`.
- 2026-08-07 — **U01: the repo is public** — `github.com/michaelawetahegn/Perch`, MIT, tag `v0.1.0`.
  Do not un-redact the third-party `apiKey` in `fixtures/homepages/research-nccgroup-com.html`.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future
  install a data wipe** — you cannot rotate to a key you no longer have. Both `chmod 600`, outside
  the repo, **not backed up anywhere yet**. Cert SHA-256 `61367c04…fce489` (valid to 2053) *is* the
  update identity; `apksigner verify --print-certs` must keep printing it. Absent it, release falls
  back to debug signing with a warning. Version lives only in `perchVersionCode`/`perchVersionName`
  atop `app/build.gradle.kts`. **`assembleRelease` runs `lintVitalRelease`; `assembleDebug` does
  not.** The manifest drops WorkManager's `WorkManagerInitializer` (which would ignore
  `PerchWorkerFactory`) via `tools:node="merge"` on the shared `InitializationProvider` — never
  `node="remove"`, which takes other libraries' too.
- 2026-08-07 — **U03 done: DB is version 2** (folders). **Build test databases with
  `PerchDatabase.inMemory(context)`, never `Room.inMemoryDatabaseBuilder`** — it attaches the
  callback that seeds Uncategorized, without which the `feeds.folderId` FK rejects the first feed.
  **A migration test builds the old DB from `app/schemas/N.json` via
  `ExportedSchemas.createStatements`**, sets `version`, then opens with Room — Room's validation
  against the new entities is half the assertion and no hand-copied DDL can drift;
  `MigrationTestHelper` is neither used nor needed. Why 1→2 `ALTER`s `feeds` instead of rebuilding
  it is argued in `PerchDatabase.MIGRATION_1_2`'s comment — do not "simplify" it.
  `observeUnreadCountsByFolder()` has the same `GROUP BY` trap as the per-feed one: a fully-read
  folder is **absent**, not 0. **`WorkSchedulerTest` "choosing manual cancels the periodic refresh"
  is flaky in a full-suite run** (the T31 `cancelUniqueWork` trap) and passes alone — re-run before
  calling it a regression.
- 2026-08-07 — **U04 done: DB is version 3** (`isSaved`/`savedAt`/`starredAt`). Three independent
  reader-owned flags: read, saved (*Read later*), starred (*Liked*); a flag going off nulls its
  timestamp. **Two places erase them if you add a fourth and forget:** `EntryDao.upsertAll` must
  copy every flag *and* timestamp from the existing row (a parsed entry always arrives with them at
  their defaults), and `deleteReadOlderThan` exempts `isSaved`/`isStarred` — retention bounds
  storage, it does not empty a queue the reader filled.
