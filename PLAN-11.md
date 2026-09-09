# PLAN-11 — v0.7.0: pick up where you left off, and move sources in a batch

Two reader requests, filed as issues **#65** and **#66**, shipped as one **MINOR** release.
Sessions read this file cold; every task carries its anchors so a session **reads, never
searches**. The whole plan is four sessions and must finish inside about an hour of wall
clock — the human set a hard deadline — so every task is sized to one `./gradlew test`.

## §0 — Decisions for this version (authoritative; do not re-derive)

### §0.1 The version is `0.7.0`, `versionCode` **9**, database **7 → 8**

Both features are user-visible behaviour, so the **MINOR** digit moves (SPEC.md §1).
`perchVersionCode` 8 → 9, `perchVersionName` `0.6.1` → `0.7.0`, at `app/build.gradle.kts:12-13`
and **nowhere else**. E01 adds one column, so `PerchDatabase.VERSION` becomes **8** with a real
`MIGRATION_7_8` and an exported `app/schemas/dev.mkiros.perch.data.db.PerchDatabase/8.json`
(Room writes it on the next compile through `room.schemaLocation`, `app/build.gradle.kts:113`;
**commit it**). `fallbackToDestructiveMigration()` never comes back — v0.5.0 is on the human's
phone. E02 changes no schema: a new `@Query` on a DAO is not a schema change.

### §0.2 Reading position (#65): one column, pixels, written when scrolling settles and on leave

- **Column:** `entries.scrollPosition INTEGER NOT NULL DEFAULT 0` — the `ScrollState.value`
  (pixels) of the article body when the reader last stopped scrolling or left. `EntryEntity`
  gains `val scrollPosition: Int = 0` (`EntryEntity.kt:53-73`). `MIGRATION_7_8` is one
  `ALTER TABLE entries ADD COLUMN scrollPosition INTEGER NOT NULL DEFAULT 0`, the same shape as
  `MIGRATION_5_6` (`PerchDatabase.kt:162-170`).
- **Pixels, not a fraction.** `rememberScrollState(initial = px)` restores at first layout with no
  extra effect and no wait; a fraction needs `maxValue`, which the U10 full-text load changes
  under the reader. Known limit, accepted and stated in the issue's closing comment: `ScrollState`
  clamps to `maxValue`, so a body whose images are still loading may restore short of the exact
  line. Do not build a "wait for maxValue" effect to fix that here.
- **Written twice, cheaply.** In `Article` (`ArticleScreen.kt:295-306`), the `rememberScrollState`
  becomes `rememberScrollState(initial = state.scrollPosition)`; a `LaunchedEffect(scroll)`
  collects `snapshotFlow { scroll.isScrollInProgress }` and, on each `true → false` edge, calls
  `onScrollSettled(scroll.value)`; a `DisposableEffect(Unit)` calls the same in `onDispose`. Never
  write per frame. `Article` gains that one callback parameter, wired from the screen to
  `viewModel.saveScrollPosition(px)`.
- **The write outlives the screen.** `ArticleViewModel.saveScrollPosition(px: Int)` launches with
  `viewModelScope.launch(NonCancellable) { entries.setScrollPosition(entryId, px) }` — the
  `onDispose` write races `onCleared`, and a child of `viewModelScope` would be cancelled with it.
  `EntryRepository.setScrollPosition` → `EntryDao.setScrollPosition`, the shape of `setSaved`
  (`EntryRepository.kt:216-222`, `EntryDao.kt:354-355`). `ArticleUiState.Loaded`
  (`ArticleViewModel.kt:61-73`) gains `val scrollPosition: Int = 0`, filled in `loaded(entry)`
  (`:155`).
- **It is a reader-owned column, so the two guards apply.** `EntryDao.upsertAll`'s `existing`
  branch (`EntryDao.kt:577-587`) must carry `scrollPosition = existing.scrollPosition` — NOTES.md's
  "a fourth reader-owned flag needs two edits" trap; `deleteReadOlderThan` needs nothing, a
  position is not worth keeping an article for. `ArticleTextRepository.loadFullText` writes
  through `EntryDao.setFullText` (`EntryDao.kt:372-376`), a column-list `UPDATE`, so it cannot
  clobber the new column — confirm, do not change it.
- **Not in the profile.** `ProfileJson` / `pending_entry_state` (U14) export read, saved and liked;
  a scroll offset is device- and font-specific and is **not** exported or restored. Do not touch
  `PendingEntryStateEntity` or `mergedWith`.
- **No UI beyond the restore.** No "resume" banner, no "jump to top" affordance, no setting.

### §0.3 Batch move (#66): the move action stops being single-only; rename and backfill stay single

- **The rule that changes:** `SelectionBar.kt:98-104` shows `MOVE` only behind `single &&
  selection is DrawerSelection.Sources`. It becomes `selection is DrawerSelection.Sources` alone.
  `RENAME` (`:91`) and `BACKFILL` (`:104-109`) keep `single` — a batch rename means nothing, and
  the backfill offer names one archive's page count (PLAN-7 §0.3). Update the KDoc at
  `SelectionBar.kt:40-48` and DESIGN.md:178's "(and move, for a source) at exactly one ticked
  row" in the same commit.
- **State widens from one id to a set.** `HomeScreen.kt:173`'s `movingId: Long?` becomes
  `movingIds: Set<Long>` (empty = no dialog), saved through `rememberSaveable` with a
  `listSaver`, the way `DrawerSelection.Saver` (`DrawerSelection.kt:73`) already persists a set
  as a `List<Long>`. `moveSelection()` (`HomeScreen.kt:251-254`) sets it to `ticked.ids`.
  `creatingFolderFor: Long?` (`:181`) widens the same way so "New folder…" from the dialog files
  the whole batch under the folder it creates (`:626-632`).
- **One dialog, one title rule.** `MoveSourceDialog` (`FolderActions.kt:150-157`) keeps its shape
  but takes `title: String` instead of `sourceTitle`, and `currentFolderId: Long?` instead of
  `Long`. The caller (`HomeScreen.kt:501-515`) builds the title: one source → the existing
  `folder_move_title` ("Move %1$s to"); more → a new `<plurals name="folder_move_sources_title">`
  ("Move %1$d source to" / "Move %1$d sources to"). `currentFolderId` is the folder every ticked
  source shares, or **null when they differ** — then no row carries the "current" mark
  (`FolderActions.kt:175`).
- **One repository method, chunked, replacing the single one.** `FolderRepository.moveSource`
  (`FolderRepository.kt:123`) becomes `moveSources(feedIds: Set<Long>, folderId: Long)`, backed by
  a new `FolderDao.setFolderForAll(feedIds: List<Long>, folderId: Long)` —
  `UPDATE feeds SET folderId = :folderId WHERE id IN (:feedIds)` — called in
  `MAX_IDS_PER_STATEMENT` chunks like `EntryDao.kt:325`. `HomeViewModel.moveSource`
  (`HomeViewModel.kt:624-626`) becomes `moveSources(feedIds, folderId)`. Delete the single-id
  forms rather than keeping both (PLAN-10 §0.2: prefer deleting to adding); the dialog's
  one-source path passes a singleton set. `undoDeleteFolders`' per-id loop
  (`FolderRepository.kt:117-119`) may use the new DAO method too, but only if the change is a
  one-liner.
- **Behaviour otherwise unchanged.** Moving leaves selection mode exactly as it does today
  (`leaveSelection()` after arming the dialog); cancelling the dialog moves nothing; the drawer's
  folder order stays alphabetical (V06).

### §0.4 The test floor is 1932 and may only rise

v0.6.1 shipped 1932 tests (1126 debug + 806 release). Every task adds tests; none may delete one.
Tests that pin "move only at one selected row" — none were found by grep of
`SelectionTestTags.MOVE` in `app/src/testDebug` (`FolderDrawerTest.kt:298,313,340` all *use* it
at one row) — but if E02 finds one, it is **rewritten to pin the new rule**, and the commit names
the assertion it replaced. `FeedCorpusTest` is untouchable.

### §0.5 Traps that will look like bugs

- **`PerchDatabaseMigrationTest`** (`app/src/test/.../data/db/PerchDatabaseMigrationTest.kt:19-58`)
  fails the moment `VERSION` is 8 and `8.json` does not exist — that is E01's RED, not a broken
  build. The JSON appears after a compile (`./gradlew :app:kspDebugKotlin` is the cheapest task
  that writes it).
- **Room validates the exported schema on open, not on migration** (NOTES.md, S08). An `ALTER
  TABLE … ADD COLUMN … NOT NULL DEFAULT 0` matches what Room exports for a non-null `Int` with a
  Kotlin default when the entity also declares `@ColumnInfo(defaultValue = "0")` — exactly as
  `FeedEntity.isSynthetic` does (`FeedEntity.kt:59-60`, mirrored in `7.json`'s `"defaultValue":
  "0"`). Declare it the same way; do not invent a different shape.
- **Injected taps do not reach a node inside a dialog, drawer sheet or dropdown** (NOTES.md) —
  use `performSemanticsAction(SemanticsActions.OnClick)`, or the existing `tap(...)` helper in
  `DrawerMultiSelectTest` / `FolderDrawerTest`, which already knows.
- **Waiting on Room is not waiting on the screen** (V01/#1): poll in wall-clock time through the
  shared wait helper (D08), never `waitForIdle`.
- **`ArticleScreenTest` seeds through `PerchRule`** (`ArticleScreenTest.kt:57`); a scroll test
  needs a body tall enough to scroll in Robolectric's default window — thirty paragraphs of
  `<p>` is plenty. Read the saved position back from `perch.db.entryDao().findById(...)`; read
  the restored one from the semantics tree: `SemanticsProperties.VerticalScrollAxisRange` on the
  scrolling node.
- **Two known full-suite-only flakes** (NOTES.md): `WorkSchedulerTest > choosing manual…` and
  `SettingsViewModelTest`. Both green alone. Re-run once before diagnosing.

### §0.6 Rungs, and the deadline

`./gradlew test` in the **foreground** is the rung for E01–E03 — `unit`, about 3–7 minutes.
E04 is `build`. **No task takes a screenshot**, and live acceptance is a bounded pre-step of
E04 rather than its own session: this version adds no gate the fifteen from S12 do not
already cover, the suite runs in ~90 s, and there is no session budget for a fifth task.
The review box (E03) stays second from last, as CLAUDE.md requires.

---

## The tasks

- [x] **E01 — An article reopens where the reader stopped. TDD. Schema 7 → 8. Issue #65.**
      `gh issue view 65 --json body` — the reader's words: "when you go back or you close the
      app and then you come back to the article, you can start reading where you left off."
      Everything is decided in §0.2; this task is the execution, in this order:
      1. **RED, data:** `PerchMigration7To8Test` beside `PerchMigration6To7Test`
         (`app/src/test/.../data/db/PerchMigration6To7Test.kt` — copy its shape: seed a real
         version-7 file through `ExportedSchemas.seedVersion(file, 7) { … }`, then
         `ExportedSchemas.openAtCurrentVersion(context, file)`): every feed and entry survives,
         read/saved/liked are untouched, and `scrollPosition` reads 0. A DAO test in the existing
         `EntryDao` test file: `setScrollPosition` then `findById` reads it back, and `upsertAll`
         of a refreshed copy of that entry **keeps** it (the U04 trap).
      2. **GREEN, data:** `EntryEntity.scrollPosition` with `@ColumnInfo(defaultValue = "0")`,
         `VERSION = 8`, `MIGRATION_7_8` appended to `MIGRATIONS` (`PerchDatabase.kt:194-201`),
         `EntryDao.setScrollPosition`, `EntryRepository.setScrollPosition`, `upsertAll` carries
         the column, compile to export `8.json`, run the migration tests.
      3. **RED, UI:** in `ArticleScreenTest` — `leaving an article partway and reopening it
         resumes where the reader stopped`: seed a tall body, show the screen, swipe up, wait
         until the row's `scrollPosition` is > 0, dispose the content, show it again, assert the
         scroll node's `VerticalScrollAxisRange.value()` equals the stored value. In
         `ArticleViewModelTest`: `saveScrollPosition` writes through the repository and `Loaded`
         carries the stored position on open.
      4. **GREEN, UI:** §0.2's `Article` changes, the `Loaded` field, `saveScrollPosition`.
      5. SPEC.md:241-242's schema history: "version 8 adds `entries.scrollPosition` — the body
         offset an article reopens at (PLAN-11 E01). Current version: **8**." SPEC.md:198/213's
         entity listing gains the field if that block is the entity's mirror.
      - Done: RED shown for both layers in the commit message, then `./gradlew test` green and
        above 1932; `app/schemas/dev.mkiros.perch.data.db.PerchDatabase/8.json` committed and
        `PerchDatabaseMigrationTest` green; issue #65 closed naming the commit, the tests, and
        §0.2's stated limit; pushed.
      - Rung: unit

- [x] **E02 — Ticked sources move together. TDD. Issue #66.**
      `gh issue view 66 --json body` — the reader's words: "there's only the delete option when
      you select multiple items. You should be able to move multiple sources at once to a new
      folder." Everything is decided in §0.3; this task is the execution:
      1. **RED:** in `DrawerMultiSelectTest` (`app/src/testDebug/.../ui/home/DrawerMultiSelectTest.kt`,
         its `seedFeed`/`showHome`/`longPress`/`checkbox` helpers and `SelectionTestTags`):
         `selecting two sources offers to move them` (the `MOVE` tag is displayed at count 2,
         `RENAME` and `BACKFILL` are not); `moving a batch files every ticked source under the
         chosen folder` (two sources in Uncategorized, a seeded second folder, tick both, `MOVE`,
         pick the folder in the dialog, assert both rows' `folderId` through `perch.db.feedDao()`,
         and selection mode has ended); `a batch from different folders marks no folder as
         current`. A `FolderRepository`/DAO test: `moveSources` of more than
         `MAX_IDS_PER_STATEMENT` ids files them all (chunking). Existing `FolderDrawerTest`
         single-source move tests (`:298,313,340`) must stay green **unchanged**.
      2. **GREEN:** §0.3, top to bottom — `SelectionBar`, `HomeScreen` state and `moveSelection`,
         `MoveSourceDialog`'s two parameters and the new plural, `HomeViewModel.moveSources`,
         `FolderRepository.moveSources`, `FolderDao.setFolderForAll`. Delete `moveSource` at all
         three layers.
      3. DESIGN.md:178 and the `SelectionBar` KDoc say the new rule.
      - Done: RED shown in the commit message, then `./gradlew test` green and above E01's count;
        `grep -rn "fun moveSource\b" app/src` returns nothing; issue #66 closed naming the commit
        and the tests; pushed.
      - Rung: unit

- [x] **E03 — The review pass. The whole of v0.7.0, read at once.**
      Read `git diff v0.6.1..HEAD` — **the whole of it** — and answer, in the commit message and
      in NOTES.md where it outlives the plan:
      1. Does any doc still describe v0.6.1? README.md, SPEC.md (§1 version, §4 schema history
         says 8), DESIGN.md:178, NOTES.md, CLAUDE.md, `docs/RALPH.md`, `TECH_DEBT.md`, against
         what E01 and E02 shipped. CLAUDE.md still names PLAN-11 active — that is E04's edit.
      2. Did either task leave a helper, string, dimension or test tag orphaned? `moveSource`
         (three layers) and `movingId` were scheduled to die — confirm, everywhere, with grep.
         `folder_move_title` must still have a caller.
      3. Was any test weakened rather than rewritten? Name every changed assertion.
      4. Is the suite above 1932, and did either feature land without its RED in the commit?
      5. Does `8.json` match `MIGRATION_7_8` — `PerchMigration7To8Test` opens a migrated file
         under Room's validation, so name the test that proves it, and confirm `upsertAll` keeps
         `scrollPosition`.
      Fix what is small and mechanical **in this session**. Anything larger becomes an issue for
      the next plan and a line in `TECH_DEBT.md` "## Next plan" — do not start a feature in a
      review.
      - Done: the five answered in the commit message, each with the command that settled it;
        `./gradlew test` green; any new issue linked; pushed.
      - Rung: unit

- [ ] **E04 — Release v0.7.0.** Bump `perchVersionCode` 8 → **9** and `perchVersionName`
      `0.6.1` → **`0.7.0`** at `app/build.gradle.kts:12-13`, **the one place they live**. §0.1
      settles the digit; it is not this task's judgement call.
      - **Live acceptance first, bounded:** `./gradlew :app:testDebugUnitTest -Pperch.live=true
        --tests '*LiveAcceptance*'` in the **foreground**, ~90 s, **at most two runs**; gate 1 has
        no quota (V12/#8), `quantpedia.com` stays excluded, `research.checkpoint.com` answers 202
        when runs come too close. Paste every gate's count into the commit message. If the second
        run still fails on a network gate, say which and release anyway — this version changed
        no fetch path — and file the failure as an issue.
      - `./gradlew test assembleRelease` — **not `clean`** (runs `lintVitalRelease`). Signing from
        `~/.perch/signing.properties` (U02) — **absent it the build silently debug-signs**, so
        verify the certificate on the file, not the build log.
      - **Gradle writes `app-release.apk`; the rename to `perch-0.7.0.apk` is this task's own**
        (W12).
      - Release notes through `docs/RELEASE-NOTES.md`'s template; `scripts/release-notes.sh
        v0.6.1` drafts from #65 and #66 — write them in the reader's words. "Installing /
        upgrading": installs in place over v0.6.1 and keeps read state, likes and To-Read; the
        database moves 7 → 8 by adding one column. **Verify the upgrade only if the emulator is
        already up:** `./scripts/device.sh check` first; if it is running, `./scripts/device.sh
        install` over D30's v0.6.1 and confirm To-Read still holds its rows through the UI. If it
        is not running, **do not boot it** (11 minutes the deadline does not have) — say so in
        the commit and let `PerchMigration7To8Test` stand as the upgrade proof.
      - Tag `v0.7.0`, push, `gh release create v0.7.0` with the notes and `perch-0.7.0.apk`.
        Then the turnover edits so the next session is not a loop session: CLAUDE.md's
        active-plan section says v0.7.0 shipped and there is no active plan; `loop.sh:18` and
        `scripts/progress.sh:11` keep naming `PLAN-11.md` (a stray launch fails loudly on the
        missing root file, as PLAN-10 did); NOTES.md pruned under 100 lines with this version's
        floor and APK path. **Do not move this file** — the watching session archives it into
        `docs/plans/` after the loop reports complete, as it did for PLAN-10.
      - Done: `gh release view v0.7.0 --json assets` lists the APK; `aapt2 dump badging` reads
        `versionCode='9' versionName='0.7.0'`; `apksigner verify --print-certs` prints U02's
        digest `61367c0499de5c49c824f4d7ba7b4e692d33960cc57c0622772227a8b7fce489`; `git status`
        clean and pushed; `gh issue list --state open` no longer lists #65 or #66.
      - Rung: build
