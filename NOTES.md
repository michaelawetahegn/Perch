# NOTES.md

Working memory for unattended sessions, per CLAUDE.md §NOTES.md discipline. **Under 100 lines.**

## Environment facts (measured at bootstrap, 2026-08-07)

- Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM. No physical device; WHPX
  **enabled**. Paths/JDKs/wrappers are in CLAUDE.md §Environment — do not re-record them here.
- **Host froze twice (v0.1 #11, v0.2 #5): the `.wslconfig` 7 GB cap only took effect at the
  second `wsl --shutdown`. Confirm it is live with `/proc/meminfo` MemTotal ~6.9 GB — if it
  reads ~9.9 GB the cap is not applied and a freeze is coming.**

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
  PLAN-2 U06 restates the two Robolectric traps verbatim; `awaitInRealTime` lives in
  `ui/screenshot/ScreenshotSupport.kt`.
- 2026-08-07 — T25/T25a done: `ui/article/*`. `ArticleLowering`'s input **must** be `HtmlSanitizer`
  output — the mapper covers that allowlist, so `ArticleLoweringCorpusTest` asserts **0
  `Unsupported`** (T32 gate 2 allows 2%). `ArticleBody` is total over the nine blocks with **no
  source-specific branch**; a source that renders wrong is an `ArticleLowering` bug. An image
  **collapses the whole figure on a load error** (right there, wrong in U08's row), so testing one
  needs a Coil loader that succeeds (`Coil.setImageLoader` + a stub `Mapper`).
- 2026-08-07 — T26 done: pull-to-refresh, undo snackbar, `HomeBanner`, `ConnectivityMonitor`
  (`AlwaysOnline` is the `AppContainer` default, so no test needs a shadow network). Gesture traps:
  `PullToRefreshBox` ignores a swipe unless its child scrolls, and refresh-prepended rows compose
  above the viewport — assert on list state, never `assertIsDisplayed`. **Residual:** the empty
  state cannot be pulled to refresh.
- 2026-08-07 — T28 done: debug seed data goes through **`FeedRepository.add`** (sanitized/deduped
  like fetched rows, keeping the **real** feed URL), via a debug-manifest `ContentProvider` that
  seeds only when zero sources exist and never runs under Robolectric — build your own DB for it.
- 2026-08-07 — T29 done: `ui/screenshot/*` → 6 PNGs in `screenshots/`. **Never `captureToImage()`**
  (CLAUDE.md's §Environment line is wrong on this): it goes via `PixelCopy`, which blocks on a
  frame-commit callback a Robolectric window never delivers. Under `@GraphicsMode(NATIVE)` a plain
  `View.draw(Canvas)` gives the same pixels synchronously; a sheet or dialog is its **own window**,
  so draw its `rootView` over the decor view. **Residual:** zero window insets, so app bar and
  drawer sit flush at y=0.
- 2026-08-07 — T30 done: `maestro/regression.yaml`, green, driven from Windows (stage it to
  `/mnt/c/perch-stage/maestro/`; `device.sh stage` will **not** overwrite an existing dir).
  `testTagsAsResourceId` (`PerchNavHost`) does **not** reach sheets/dialogs — address those by
  label; a Maestro text selector matches a node's text *entirely* (merged rows need `.*…*.`).
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
  feed omits the spaces around inline `<code>`/`<a>` (`However,until`) — do not "repair" it.
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
  `MigrationTestHelper` is neither used nor needed. Why 1→2 `ALTER`s `feeds` is argued in
  `PerchDatabase.MIGRATION_1_2`'s comment — do not "simplify" it. `observeUnreadCountsByFolder()`
  has the same `GROUP BY` trap. **`WorkSchedulerTest` "choosing manual cancels the periodic
  refresh" is flaky in a full-suite run** (the T31 trap) and passes alone — re-run before
  calling it a regression.
- 2026-08-07 — **U04 done: DB is version 3** (`isSaved`/`savedAt`/`starredAt`). Three independent
  reader-owned flags: read, saved (*Read later*), starred (*Liked*); a flag going off nulls its
  timestamp. **Two places erase them if you add a fourth and forget:** `EntryDao.upsertAll` must
  copy every flag *and* timestamp from the existing row (a parsed entry always arrives with them at
  their defaults), and `deleteReadOlderThan` exempts `isSaved`/`isStarred` — retention bounds
  storage, it does not empty a queue the reader filled.
- 2026-08-07 — **U05 BLOCKED on its gate, not its code.** `data/parse/LeadImage.kt` implements
  §0's chain and resolves **337/339 (99.4%)** of corpus entries that carry an image; the 2 misses
  are the 64px floor correctly refusing a 528×34 banner and a 42×42 avatar. But **only 339 of
  1038 corpus entries carry any image markup at all**, so U05's "≥60% of entries" is unreachable
  — the rung that would close it is `og:image`, which §0 defers to U10. Do not chase this with a
  feed-level `<image>`/`<logo>` fallback: shipping the site logo as every entry's thumbnail is the
  guessed URL §0 forbids, and it games the metric. `ThumbnailCorpusTest` therefore gates on
  *share of available* (≥95%), which measures our code instead of the corpus's shape, and prints
  both numbers. **Re-gated live at U15 gate 4, after U10.**
- 2026-08-07 — `FeedXml.kt` held a **literal NUL byte** in `stableGuid`'s separator since T07, so
  git diffed it as binary and no session could review it. Now `"\u0000"` (escaped, not the raw byte) — identical string, so
  GUIDs are unchanged (FeedCorpusTest confirms). Don't reintroduce a raw control char in a literal.
