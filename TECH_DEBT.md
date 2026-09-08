# TECH_DEBT.md — what the tech-debt pass deliberately left alone

The v0.6.1 pass (`PLAN-10.md`) fixed only what a failing test could demonstrate and refactored
only where behaviour stayed identical. Everything below would need a **behaviour change** to
justify, a **human decision**, or more room than one session — so it is recorded here rather
than done. A loop session that finds another such item appends it; the re-survey task (D27)
turns the ones worth doing into issues for the next plan.

Format: one bullet per item, the anchor, why it was left, what doing it would change.

## Behaviour changes waiting for a decision

- **Imports and folder deletes are not transactional.** `OpmlRepository.import`,
  `ProfileRepository.import` and `FolderRepository.deleteFolders` loop over single-row DAO calls
  with no `@Transaction`; a throw mid-way commits half an import, and a partial folder delete
  returns no undo. Left because atomicity cannot be reproduced by a failing test without fault
  injection, and "half an import" vs "no import" is a behaviour the reader would notice.
- **A redirect-colliding refresh stores a raw message in `feeds.lastError`.**
  `FeedRepository.pollFrom` checks `findByUrl(finalUrl)` then `mutate`s; with four feeds in
  flight two can redirect to one address and the second `update` hits the unique index, and the
  SQLite constraint text is what the drawer shows. D05 stopped cancellation being recorded
  (`FeedRepository.kt:322`); the redirect race and the wording are a product decision.
- **`HomeViewModel` collects `backfillProgress` from `init` forever**, which makes the
  `WhileSubscribed` on that flow (and the WorkManager `getWorkInfosForUniqueWorkFlow` behind
  it) decorative while a backfill id is set, and re-queries `entries.reach()` on every tick.
  Correct today; changing it changes when the reach sentence refreshes.
- **Three overflow menus, one of which forgets it was open on rotation.** `HomeOverflow` uses
  `remember`; `TimeRangeControl` and the article `Overflow` use `rememberSaveable`. Extracting a
  shared menu means choosing one — a (tiny) behaviour change — so it was not extracted.
- **`EntryActionsSheet`'s call site is duplicated in Home and Collection** with six identical
  lambdas, but Collection's `setSaved(item, …)` arms an undo that Home's `setSaved(id, …)` does
  not. Sharing the call site means aligning the two ViewModel signatures first.
- **Error handling has three conventions.** Sealed outcome types (`FeedRepository`,
  `OpmlRepository`, `ProfileRepository`), `Result<T>` over a sealed `Exception`
  (`SavedLinkRepository`), and a silent `null` for four distinct causes
  (`ArticleTextRepository.loadFullText`, `BackfillRepository`). Picking one changes what every
  caller can show the reader.
- **No dispatcher is injected.** `Dispatchers.IO`/`Default` are hard-coded at `PerchApp.kt:40`,
  `SettingsStore.kt:130`, `FeedFetcher.kt:62`, `ArticleTextRepository.kt:46` (`Default` since
  D16 moved the parse into `PageContentExtractor.parse`), and
  `SettingsViewModel` does its own `withContext(Dispatchers.IO)` for file transfer. Tests pass
  because Robolectric tolerates it; a `CoroutineDispatcher` on `AppContainer` is the fix and it
  touches every repository constructor.
- **DESIGN.md §8 promises an editorial link underline** (1 dp, 3 dp below the baseline).
  `ArticleType.link` is Compose's plain `TextDecoration.Underline`; the two `Dimens` tokens for
  the editorial one were never read and D01 deleted them. Either the doc or the rendering
  should move; that is a design call.
- **`unitTests.all` forwards `-Pperch.live` to every unit-test task** and disables up-to-date
  checks for all of them (`app/build.gradle.kts:97-104`), so one live run invalidates the whole
  suite rather than `LiveAcceptanceTest` alone.

## Deliberate, and staying that way

- **`ui/` imports Room entities and DAO row types directly** (15 sites after D21: `EntryListItem`,
  `FeedEntity`, `FolderEntity`, `FeedReach`, `FtsQuery`). Consistent and cheap; a mapping layer
  would be a hundred lines of copying for no behaviour. D21 moves only the *shared enums and
  seams*, not the entities.
- **Test-only production surface**: `EntryRepository.searchEntries` (the `Flow<List>` twin of
  `pagedSearch` — every query exists twice by rule, `EntryDao.kt:16-24`), `toggleRead`,
  `FolderRepository.findFolder`, `EntryDao.observeAll`/`observeByFeed`/`countAll`,
  `FeedDao.countAll`, `PerchDatabase.inMemory`, `PerchBrand.VIEWPORT`/`CENTRE`/`SAFE_RADIUS`.
  Each is a test contract with a KDoc saying so.
- **`LiveAcceptanceTest` is 2,581 lines and one `@Test`** with 15 sequenced gates that share
  the corpus they just fetched. Splitting it into one test per gate would refetch the corpus
  per gate; the shape is a choice.
- **Seven test tags are applied in main and asserted nowhere**: `AddSourceTestTags.CONFIRMATION`
  /`FOLDER`/`NEW_FOLDER`, `ArticleTestTags.FULL_TEXT_PROGRESS`, `SearchTestTags.LIST`,
  `FolderActionTestTags.CANCEL`, `BackfillTestTags.PROGRESS_LABEL`. Tags are free; they stay.
  (D14 covers the three that were user-facing controls.)
- **Duplicate assertions at different levels**: `HomeTimeFilterTest` vs `HomeTimeRangeTest` on
  the remembered range; `EntryFtsIndexTest` vs `EntrySearchTest` on the first three search
  behaviours; `SearchSurfaceTest` vs `SearchFromEverySurfaceTest` on widening and leaving.
  Each pair has a stated reason (write path vs query path, surface vs NavHost).
- **Naming**: `SearchState` is the one screen state not named `*UiState`; `BackfillOfferUi.kt`
  and `SearchSurface.kt` are the two screen files not named `*Screen`/`*Sheet`. Renames for
  their own sake are churn.

## Ask the human

- **`scripts/harvest.sh`** (158 lines, the T04 fixture harvester) is referenced by nothing —
  no script, plan or doc. It looks like a manual tool for refreshing `fixtures/snapshots/`.
  Delete it or document it; a session should not decide.
- **`maestro/regression.yaml`** is named only by the frozen v0.1 and v0.3 plans; nothing runs
  it. Same question.

## Next plan

D27 re-ran the six surveys against `ece0ddd`, the tree as D01–D26 left it, using greps rather
than a full read. **The pass held**: no function or constant in `app/src/main` is without a
caller, no hostname literal is under `data/`, no `Color(0x`/`N.dp`/`N.sp` is outside `ui/theme/`,
nothing in `data/`, `work/` or `model/` imports `ui`, and no doc names a file that does not
exist. Five findings are worth a session; each is an issue with its anchors and its fix shape.

- **#60 — the ViewModel `runCatching`s still swallow cancellation.** `AddSourceViewModel.kt:128`
  and `:152`, `ArticleViewModel.kt:144`, `SettingsViewModel.kt:274`. D05 rethrew
  `CancellationException` in the two repositories and D06 in the five ViewModel `catch` blocks;
  these four `runCatching`s were in neither task. **No reader-visible failure was found** — all
  four run in `viewModelScope`, so only `onCleared()` cancels them — so this is the rule being
  finished, not a bug report.
- **#61 — one seeder, not twelve.** 25 private `seedFeed`/`seedEntry`/`seedFolder` declarations
  across 12 test files, **322 lines**; ten of them still spell a `FeedEntity`/`EntryEntity`
  literal out instead of calling D09's `testFeed`/`testEntry`. They belong on `PerchRule`, which
  already owns the database.
- **#62 — `capture(name)` is copied into six screenshot tests**, byte-identical in three, and
  `const val MIN_COLOURS = 8` is declared four times. `Screenshots` (`ScreenshotSupport.kt`)
  should own it. Thresholds must not move — the design screenshots are the pixel gate.
- **#63 — `SettingsViewModel`'s four transfer actions repeat one shape**, twice each
  (`:167-178`/`:219-230` export, `:189-200`/`:241-252` import). D06 widening the catches made the
  duplication exact.
- **#64 — the new-folder dialog's call site exists twice**, identically: `HomeScreen.kt:582-591`
  and `AddSourceSheet.kt:84-93`, each with its own `creatingFolder` flag.

Smaller than a session, recorded so a passing task can take them:

- **The back arrow is drawn twice.** `SettingsScreen.kt:147-154` and `ArticleScreen.kt:97-104`
  are an identical `navigationIcon = { IconButton(onClick = onBack) { Icon(ArrowBack, …) } }`.
- **`EntryStateRow` and `PendingEntryStateEntity` declare the same eight fields** in the same
  order (`EntryStateRow.kt:9-16`, `PendingEntryStateEntity.kt:23-30`). Sharing them means an
  interface over an entity Room owns, so it waits for someone touching the schema anyway.
- **`private const val STOP_TIMEOUT_MS = 5_000L` is declared three times** —
  `HomeViewModel.kt:741`, `SettingsViewModel.kt:279`, `AddSourceViewModel.kt:169`.
- **`FeedRepository.observeSourceCount` and `FeedDao.observeCount` are release code with one
  debug caller** (`DebugSeeder.kt:48`). Not dead — D01's rule is satisfied — but the only thing
  that reads them ships in no release build, and no test names either.

**For D28, not for the next plan:** SPEC.md §3's package tree does not list the four files this
plan created — `data/parse/ItemMapping.kt` (D18), `data/repo/FolderResolver.kt` (D19),
`ui/home/EmptyState.kt` (D24), `ui/source/UrlForm.kt` (D25). Every other path named in
README.md, SPEC.md, DESIGN.md, CLAUDE.md, `docs/RALPH.md` and this file resolves to a file that
exists. That is a four-line mechanical fix and it belongs to the review pass, not here.
