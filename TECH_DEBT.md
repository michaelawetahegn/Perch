# TECH_DEBT.md — what the tech-debt pass deliberately left alone

The v0.6.1 pass (`docs/plans/PLAN-10-v0.6.1.md`) fixed only what a failing test could demonstrate and refactored
only where behaviour stayed identical. Everything below would need a **behaviour change** to
justify, a **human decision**, or more room than one session — so it is recorded here rather
than done. A loop session that finds another such item appends it; the re-survey task (D27)
turns the ones worth doing into issues for the next plan.

Format: one bullet per item, the anchor, why it was left, what doing it would change.

## Behaviour changes waiting for a decision

- **A page that is gone for good is retried every batch.** `PageFetcher.fetch` answers null for
  a 404 and a 503 alike, so `BackfillRepository.run` (PLAN-12 F07) cannot stamp a 4xx as dealt
  with the way §0.4 asks; it leaves every failed fetch unstamped for a later batch to retry. One
  dead URL costs one slot per batch; forty dead URLs at the top of an archive would stall it.
  Fixing it means widening `PageFetcher`'s contract (status code or a typed failure) through all
  six of its implementations and callers — a behaviour change to every discovery path.
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
- **`scroll` in `ArticleExtractor.NEGATIVE` drops a scroll-driven interactive whole.** F03
  (2026-09-14): Bellingcat's Kinahan page wraps a 10-paragraph, 1.6 k-character narrative and
  13 images in `<div class="scrolly_container">`, and the token — meant for scroll-to-top
  widgets — names the whole thing chrome; it is well over `FIGURE_TEXT_CEILING`, so rule 3
  does not rescue it. Readability's own unlikely-candidate list has no `scroll`. Dropping the
  token changes which subtrees every corpus page keeps; needs a corpus run and a decision.

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

**PLAN-12 (v0.8.0, 2026-09-14) took everything D27 filed:** #60–#64 are F10–F14, E03's
scroll-write finding was filed as **#71** and closed by F06, and the two smaller items recorded
beside them — the back arrow drawn twice, `STOP_TIMEOUT_MS` declared three times — went with
F14 and F13. F15's review of `git diff v0.7.0..HEAD` re-ran D27's greps: no hostname literal
under `data/`, no `FeedEntity(`/`EntryEntity(` literal outside `support/Entities.kt`, one
`MIN_COLOURS`, one `STOP_TIMEOUT_MS`, one back arrow, no test deleted, ignored or loosened.
What is left, none of it a session on its own:

- **`EntryStateRow` and `PendingEntryStateEntity` declare the same eight fields** in the same
  order (`EntryStateRow.kt:9-16`, `PendingEntryStateEntity.kt:23-30`). Sharing them means an
  interface over an entity Room owns, so it waits for someone touching the schema anyway.
- **`FeedRepository.observeSourceCount` and `FeedDao.observeCount` are release code with one
  debug caller** (`DebugSeeder.kt:48`). Not dead — D01's rule is satisfied — but the only thing
  that reads them ships in no release build, and no test names either.
- **`EntryDao.findByGuid` is release code with test-only callers** since F01 moved `upsertAll`
  onto `findByGuidOrLink`: `PerchMigration8To9Test` and `LiveAcceptanceTest` still read it.
  Same standing as `observeSourceCount`; the tests could ask `findByGuidOrLink(feedId, guid,
  null)` instead and the query could go.
- **Two lists of lazy-image attributes.** `HtmlSanitizer.LAZY_SRC` (F03: `data-src`,
  `data-lazy-src`, `data-original`, and a lazy attribute beats `src`) and
  `LeadImage.LAZY_SRC_ATTRS` (`src` first, then the same three). One list means `LeadImage`
  preferring the lazy attribute over `src` for thumbnails — a behaviour change under PLAN-10
  §0.2's rule, so it waits for a decision; the corpus would show whether any thumbnail moves.

**Closed by D28:** SPEC.md §3's package tree now lists the four files this plan created —
`data/parse/ItemMapping.kt` (D18), `data/repo/FolderResolver.kt` (D19), `ui/home/EmptyState.kt`
(D24), `ui/source/UrlForm.kt` (D25). Every path named in README.md, SPEC.md, DESIGN.md,
CLAUDE.md, `docs/RALPH.md` and this file resolves to a file that exists.
