# PLAN-4.md — Perch v0.4, the reader's-own-words pass

**This is the active plan.** v0.1 (T01–T32), v0.2 (U01–U16) and v0.3 (V01–V16) are complete,
frozen, and history only, archived in `docs/plans/` — do not reopen a box in any of them.
The process this plan is executed by is written down in `docs/RALPH.md`. Same rules as
before, restated because they still bind:

Ordered. **Do the single next unchecked `[ ]` task, then stop.** Never check a box unless
its Done-condition literally passed in this session. Failure → 2 attempts max, then rewrite
the box as `- [BLOCKED: one-line diagnosis]`, log it to NOTES.md, and move on.

Rungs, cheapest first: **unit** < **build** < **maestro** < **screenshot**.
Use the cheapest rung that actually answers the question.

**TDD is the method, not a suggestion.** Failing test first (`RED`), minimum code to pass
(`GREEN`), then tidy (`REFACTOR`). A commit whose diff adds production code but no test is
a defect — reopen the box.

**v0.3.0 is installed on a real phone.** Every schema change ships a real Room `Migration`
and a matching `app/schemas/N.json`. `PerchDatabaseMigrationTest` already fails the build
if you forget. **Never `fallbackToDestructiveMigration()`** — that is someone's read state.
Nothing in this plan is expected to need a migration; if a task believes it does, that is a
sign the task grew, so say so on its issue before writing one.

---

## §0 — Decisions for v0.4 (authoritative; do not re-derive)

PLAN-2 §0 and PLAN-3 §0 still hold except where this section says otherwise. Where this
contradicts them, SPEC.md or DESIGN.md, **this section wins**, and the task that touches it
updates the older doc **in the same commit**.

**Every task in this plan is a GitHub issue.** Read it (`gh issue view N --json body`) before
touching code — this `gh` is old and a bare `gh issue view N` dies on a Projects GraphQL
field (NOTES.md). The task is not done until the issue is closed with a comment naming the
commit and how it was verified, and the commit is pushed.

**No bug is fixed until a failing test reproduced it.** Cannot reproduce → comment the
finding on the issue, log it in NOTES.md, mark the box `- [BLOCKED: cannot reproduce — …]`,
move on. A guessed fix is worse than an open issue because it looks closed.

**Verification baseline, measured on the untouched tree at 2026-08-18:** `./gradlew test`
BUILD SUCCESSFUL, **1489 tests, 0 failures, 0 errors, 1 skipped**, across 128 result files.
A session that ends with fewer tests than it started with has deleted coverage; say so in
the commit message or put it back.

**A time window is a rolling one, measured from now** (#15). "Today" means the last 24
hours, "This week" the last 7 days, counted back from the moment the query runs — not from
a calendar boundary. This **overrides PLAN-2 §0 / U07's "the window is a *calendar* one
(local midnight)"** and the note in NOTES.md that records it. The reason is the reader's:
a calendar cutoff empties the feed just after midnight, which is exactly when they look.
V02 is *not* reverted — the injected clock keeps its zone, because the labels a human reads
are still zoned; the bucket boundary simply stops asking what day it is.

**The Feed is one mixed list** (#20). No folder sections, no section headers, no per-bucket
grouping in any entry list: entries are ordered newest-first and nothing else. Each row
carries its source's name, its **category (folder) as a label beside that name**, and the
**published date under the entry**. This overrides U07's section headers and retires the
section-header half of V06 — folder order still governs the **drawer**, which is the only
place a folder is now a heading.

**Sharing is the system's, not ours** (#16). Perch never draws its own share sheet: a share
action fires `Intent.ACTION_SEND` through a chooser and the OS decides what is on it; "copy
link" is the one affordance we own. The intent is built by a **pure function** so it is
testable on the JVM without launching anything — the screen only fires what that function
returns.

**The extractor is widened by rule, never by host** (#17). No `if (host == "huggingface.co")`
anywhere. Whatever Hugging Face needs must be stated as a structural heuristic that names no
site, must be justified by what the page's HTML actually is, and must not regress the corpus.
A host-shaped fix is a defect even when it makes the fixture pass.

**Versioning, stated once and for real** (#19). `MAJOR.MINOR.PATCH`. While Perch is 0.x:
**MINOR (0.N.0) for a release carrying any notable new feature or user-visible behaviour
change; PATCH (0.N.M) for a release that is only bug fixes, polish and docs.** MAJOR is
reserved for 1.0 and for a release that breaks a reader's data or workflow. `versionCode`
increments by exactly 1 on every release regardless, because it is the update identity and
never resets. This release carries new features, so it is **v0.4.0 / versionCode 5**. The
policy is written into CLAUDE.md and SPEC.md — not left in a plan file that gets archived.

**README.md is for a reader, not for us** (#18). A general section at the top saying what
Perch is, then what it does, then how to build it and add a feature — scannable in under a
minute. Everything else belongs in SPEC.md, DESIGN.md, docs/RALPH.md and NOTES.md, which is
where a contributor is sent, not where the README repeats them. **No version-specific claim
in the README beyond a single line pointing at the latest release**, because that is the
sentence that went stale between v0.2 and v0.3 and is the reason this issue exists.

**Every loop ends with a review pass before its release** (#18, second half). The last two
boxes of a plan have been live acceptance and release; a **review** box now comes before
them, and `docs/RALPH.md` says so for every future plan. It reads the whole plan's diff
against the plan's own promises and against the docs, and its job is to find what a
per-task session structurally cannot see: a doc that still describes the version before
this one, a helper left orphaned, a test weakened to pass, a claim in the README that is no
longer true.

---

## Phase 0 — The rule that decides the release

- [x] **W01 — The versioning rule is written down where it binds. Issue #19.**
      `gh issue view 19 --json body` — the reader remembers "a note in the claude.md file about
      versioning"; planning searched CLAUDE.md, SPEC.md, DESIGN.md, docs/RALPH.md, every plan
      and `git log -S` for it: **it has never existed**. Say so on the issue rather than
      hunting for it, then write the rule §0 states, in two places and no more:
      `CLAUDE.md` (a bullet under the non-negotiable rules) and `SPEC.md` (beside the release
      mechanics), each pointing at the other rather than restating it twice in full.
      The version itself lives at `app/build.gradle.kts:12-13` (`perchVersionCode = 4`,
      `perchVersionName = "0.3.0"`) and nowhere else — say that in the rule, because the one
      thing a future release must not do is bump it in two places.
      `docs/RALPH.md:141` already says every plan ends in a release; add the sentence that
      says *which digit moves* and why, so the release task cannot guess. Do not touch
      `app/build.gradle.kts` in this task — W12 owns the bump.
      - Done: `grep -n "MINOR" CLAUDE.md SPEC.md` finds the rule in both; `./gradlew test`
        still green at ≥1489 tests; issue #19 closed with a comment saying the note it
        remembered never existed and now does.
      - Rung: unit

---

## Phase 1 — What the reader asked for

- [x] **W02 — "Today" becomes the last 24 hours, and every window is measured from now. TDD. Issue #15.**
      `gh issue view 15 --json body` — the reader's reason: a calendar cutoff "would leave us
      with an empty feed sometimes", and a week should be "a week from this exact moment in
      time when the refresh is done".
      The whole arithmetic is **one function**: `TimeFilter.since(clock)` at
      `ui/home/TimeFilter.kt:37` — `:38` takes `LocalDate.now(clock)` and `:46` snaps it to
      `atStartOfDay(clock.zone)`. Replace the date arithmetic with instant arithmetic on
      `clock.instant()`: 24 hours, 7 days, 30 days, 365 days, null. Only **two** production
      callers — `HomeViewModel.kt:297` (the paged query) and `:588` (`markAllRead`) — and
      both already resolve `since` once per query, which is the behaviour to keep.
      The KDoc at `TimeFilter.kt:10-13` and `:30-36` argues *for* the calendar window in so
      many words; it is now wrong and must be rewritten, not left contradicting the code.
      Rename the narrowest constant's label: `home_filter_today` at
      `res/values/strings.xml:39` becomes **"Past 24 Hours"** (the enum constant may keep the
      name `Today`; §0's language rule is about what a human reads). `TimeFilter.labelRes()`
      is at `HomeScreen.kt:1266-1273`.
      **Traps, all four found while planning:**
      1. `HomeTimeRangeTest.kt:184` asserts the longest label still fits on one line at font
         scale 1.3 and currently pins `PastMonth`. "Past 24 Hours" is longer — repin the test
         to whichever label is now longest and keep the assertion, do not delete it.
      2. `TimeFilterTest.kt` is written *about* calendar semantics: `:24`, `:33`, `:42`, `:51`,
         `:89` all assert midnight and all must be rewritten to the rolling window. `:97`'s
         UTC half (`isGreaterThan(thatMorning)`) becomes **vacuous** under a rolling window —
         it was issue #9's regression guard. Do not delete the zone coverage: move it to what
         still depends on the zone (the reader-facing relative time / date labels), and say in
         the test's KDoc why the window no longer can regress that way.
      3. `AppContainerClockTest.kt:41` and `HomeTimeFilterTest.kt:82` both assert last night's
         23:59 entry is *excluded*; under a rolling window it is included. Rewrite them to
         assert the new boundary (an entry 25 h old is out, one 23 h old is in) — a rolling
         window with no boundary test is untested.
      4. `LiveAcceptanceTest.kt:928` gate 8 **hard-fails when `utcWouldDrop == 0`** (`:972-975`)
         and that margin collapses to zero by construction here. Rewrite gate 8 to ask the
         question the reader actually has: across the live corpus, every entry published
         within the last 24 h is inside Today's window and nothing older is, and the count of
         entries in the window is > 0. Gate 8 is W09's to run; this task only makes it correct.
      Update `NOTES.md:32-34` (it records the calendar rule), `DESIGN.md:198-203`/`:245`, and
      PLAN-2 §0's window sentence — §0 of this plan says which way the conflict resolves.
      - Done: `./gradlew :app:testDebugUnitTest --tests '*TimeFilter*' --tests '*HomeTime*'
        --tests '*AppContainerClock*'` green, then the full `./gradlew test` green at ≥1489
        tests; the pasted line from each in the commit message; issue #15 closed.
      - Rung: unit

- [x] **W03 — The Feed is one chronological stream, not a pile of folders. TDD. Issue #20.**
      `gh issue view 20 --json body` — "Feed should not be split into different sections.
      Should all be mixed together."
      **The ordering is the sectioning.** `EntryQueries.LIST_ITEMS` at `EntryDao.kt:48-55`
      orders `(fo.id = 1) ASC, fo.name COLLATE NOCASE ASC, e.publishedAt DESC, e.id DESC`;
      it becomes `e.publishedAt DESC, e.id DESC` — which is exactly what `SAVED` (`:57-61`)
      and `LIKED` (`:63-67`) already do, so copy them rather than inventing an order.
      Then the render-time half: `startsSection` (`PagedList.kt:35-36`) and `SectionHeader`
      (`HomeScreen.kt:1168-1186`) go, with their call sites at `HomeScreen.kt:1137-1140`,
      the `endsSection` divider condition at `:1138`/`:1149-1154` (a divider now falls between
      every pair), `HomeUiState.showSections` (`HomeViewModel.kt:204`), the argument at
      `HomeScreen.kt:392-399`, the test tag `HomeTestTags.section` (`:1091`) and the
      now-unused `Dimens.sectionHeaderTop`/`sectionHeaderBottom` (`Dimens.kt:188-189`).
      `CollectionScreen.kt:178-209` is the same list already unsectioned — make Feed look
      like it. **`EntryListItem` keeps `folderId`/`folderName`** (`EntryListItem.kt:33-34`);
      W04 needs them and `EntryRepositoryTest.kt:392-402` guards them.
      **Folder order is not dead** — V06 still governs the drawer (`FolderDao.observeAll`,
      `.getAll`); only the list stops obeying it. Update NOTES.md's V06 line, which currently
      says all three statements must agree.
      **Tests that must be rewritten, not deleted** — each asserts folder-first order and each
      has a chronological counterpart worth keeping:
      `EntryRepositoryTest.kt:358-377` and `:379-390`; `EntryPagingTest.kt:194-211` (imports
      `startsSection`); `HomeTimeFilterTest.kt:225-244`, `:246-259`, `:261-276`, `:278-288`
      (harness `topOfSection()` `:336-337`); `FolderDrawerTest.kt:109-131` (the list half only
      — keep the drawer half); `HomeScreenTest.kt:125-137` (`entries from every source are
      interleaved newest first` passes today only because one folder is involved — it becomes
      the real assertion: seed **two** folders and require strict newest-first across them).
      `LiveAcceptanceTest.kt:1363-1371` gate 7 counts section nodes and fails under
      `MIN_SECTIONS`; rewrite it to assert the live list is in non-increasing `publishedAt`
      order across at least one full page. `DesignScreenshotTest.kt:298-317`'s
      `sortIntoFolders()` exists to make sections show up — keep the multi-folder seeding
      (W04's labels need it), drop the reason.
      Update `DESIGN.md`'s home wireframe (`:128`, `:167`) and NOTES.md's U07 line.
      - Done: a test asserting two folders' entries strictly interleave newest-first, and one
        asserting no section node exists in the Feed; `./gradlew test` green at ≥1489 tests
        (net count named in the commit message if it moved); issue #20 **stays open** for W04.
      - Rung: unit

- [x] **W04 — A row says who published it, in what category, and when. TDD + screenshot. Issue #20.**
      Second half of #20: "add a new label next to the name of the blog and the date posted
      under the post with the category". §0 fixes the reading: **the category sits beside the
      source name; the date posted goes on its own line beneath.**
      One attach point, already there: the meta line at `EntryRow.kt:116-127` — currently
      `R.string.home_entry_meta` (`strings.xml:15`, `"%1$s / %2$s"`) filled with
      `item.sourceTitle` and `RelativeTime.format(item.publishedAt, now)`, drawn in
      `labelMedium` / `onSurfaceVariant`, `maxLines = 1`, ellipsised. The `Column` it lives in
      ends at `:128`; a second line attaches after `:127`. `folderName` is already on the row
      (`EntryListItem.kt:34`) — **no join, no DAO change**.
      Reuse the theme's tokens; the standing grep gate means no `Color(0x`, no bare `N.dp` /
      `N.sp` outside `ui/theme/` — a new dimension goes in `Dimens.kt`. The category must read
      as quieter than the source name, and the row must survive a long source name *and* a
      long folder name at font scale 1.3 without pushing the thumbnail (`:130-131`,
      `Dimens.thumbnail = 96.dp`) off the row.
      **Uncategorized is a real folder id 1, not a category a reader wants to read** — decide
      once and state it in the commit: a row in Uncategorized shows **no** category label
      rather than the word "Uncategorized".
      **Traps:** `EntryRowTest.kt:76-83` and `:84-103` assert the exact meta strings
      `"Null Program / 5h"` and `"Simon Willison / <label>"`, and `HomeScreenTest.kt:122`
      asserts `"Chris Wellons / 2d"` — all three break by design; rewrite them to the new
      shape. The row fixture `EntryRowTest.kt:304-320` hardcodes `folderName = "Uncategorized"`
      with no parameter — give it one, or every new case tests the unlabelled path only.
      `RelativeTime.format` (`RelativeTime.kt:31-49`) already yields `now`/`47min`/`5h`/`3d`/
      `30 Jul`/`30 Jul 2025`; reuse it, do not add a second date format.
      Refresh `home-dark` / `home-light` (`DesignScreenshotTest.kt:120-134`) and **look at the
      PNGs** in `build/perch-screenshots/` — the point of this box is what the reader sees.
      Critique against DESIGN.md; **max 2 iterations**, residuals to NOTES.md.
      - Done: tests for a labelled row, an Uncategorized row, and the date line; the two PNGs
        regenerated and eyeballed; `./gradlew test` green at ≥1489 tests; issue #20 closed
        naming both W03 and W04.
      - Rung: screenshot

- [x] **W05 — An article can be shared and its link copied. TDD. Issue #16.**
      `gh issue view 16 --json body` — "a share option where it opens up the menu where you
      can either copy the article as a link or you can share to different places".
      **Most of this already exists and must be reused, not rewritten.**
      `shareEntry(context, title, link)` is at `ui/home/EntryActions.kt:159-170`: `ACTION_SEND`,
      `text/plain`, subject = title, text = link ?: title, wrapped in `createChooser` and
      guarded against `ActivityNotFoundException`. It is already wired to the row's long-press
      sheet (`EntryActions.kt:115-120`, `HomeScreen.kt:522-525`, `CollectionScreen.kt:161-164`).
      What is missing is (a) the **article screen has no share action at all** and (b) **copy
      link does not exist anywhere** — there is no clipboard use in the app.
      Add a share `IconButton` to `ArticleScreen.kt`'s `actions` between the open-in-browser
      button (ends `:144`) and `Overflow` (`:145-147`), following the `openInBrowser` pattern
      at `:405-412` (a local call taking `LocalContext.current`, declared at `:86`) rather than
      plumbing a new callback through the ViewModel. Title and link come from
      `ArticleUiState.Loaded` — `title` at `ArticleViewModel.kt:61`, `link` at `:67`, already
      null-guarded at `ArticleScreen.kt:133`. Add **Copy link** as a second `DropdownMenuItem`
      in `Overflow` (`:207-234`, after `:231`), and give it the same treatment in the row's
      `EntryActionsSheet` beneath Share (`EntryActions.kt:115-120`) so the two paths agree.
      New test tags go in the objects at `ArticleScreen.kt:414-444` and
      `EntryActions.kt:172-178`; new strings beside `entry_action_share`.
      **Traps:** the assertion pattern is `shadowOf(compose.activity).nextStartedActivity`
      (`ArticleScreenTest.kt:185-187`) — but `shareEntry` fires through `createChooser`, so the
      action is `ACTION_CHOOSER` and the `ACTION_SEND` payload is under `Intent.EXTRA_INTENT`;
      assert on the inner intent, not the outer. A tap on a `DropdownMenuItem` or a
      `ModalBottomSheet` row never lands from an injected gesture — use
      `performSemanticsAction(OnClick)` (`HomeEntryActionsTest.kt:182-185`). Clipboard under
      Robolectric: assert the clip's text through the shadow clipboard, not through a toast.
      The existing sheet share path has **no test at all** — this task adds one.
      - Done: a test asserting the article share fires `ACTION_SEND` carrying the entry's link,
        one asserting copy-link puts that link on the clipboard, and one covering the row
        sheet's existing share; `./gradlew test` green at ≥1489 tests; issue #16 closed.
      - Rung: unit

- [x] **W06 — Why the Hugging Face page loses its body, written down and pinned. TDD. Issue #17.**
      `gh issue view 17 --json body` — the reader's own framing is the task's: "the big question
      is why those websites and why everything else works", and the fix must not be "just a
      narrow implementation just for this website".
      This box **reproduces and diagnoses only**; W07 fixes. Splitting them keeps the tree
      green, because `ArticleExtractorTest.kt:25-39` requires **every** fixture in
      `ArticleFixtures.all` to yield prose — a broken page added to that list is a red suite.
      1. Capture the page by hand into `fixtures/articles/` under the existing convention
         `<host-slug>-<article-slug>.html`, i.e. `huggingface-efficient-knowledge-distillation.html`
         (`fixtures/articles/hexacorn-msconfig-secret.html` is the model). **Fetch it the way
         the app does** — same user agent and headers as `data/net/PerchHttp.kt` / `FeedFetcher.kt:71-77`
         — because a page served to a bare `curl` is not the page the reader's phone got, and a
         fixture that differs from production diagnoses the wrong thing.
      2. Declare it in `ArticleFixtures.kt` in a **new `pending` list**, not in `other`
         (`:161-213`) and not in `all` (`:215`). W07 promotes it.
      3. Write the reproduction test beside `ArticleExtractorTest`'s existing shape, asserting
         what actually happens today with the number that causes it, and say in its KDoc that
         it is a pinned defect W07 flips. The candidates planning identified, to be confirmed
         or ruled out **with a measurement each**, not by argument:
         - both give-up gates: `top.value < MIN_CANDIDATE_SCORE` (`ArticleExtractor.kt:53`,
           `= 10.0` `:322`) and `article.text().length < MIN_PROSE_CHARS` (`:58`, `= 200` `:334`);
         - `PROSE_TAGS = "p, pre, blockquote, td"` (`:308`) — **lists never score, and `li`
           carries a −3 base** (`:128-138`); a page whose body is largely `<ul>`/`<ol>` scores
           near zero by construction;
         - the `LANDMARKS` sweep at `:77-80` removes `header`/`footer`/`aside`/`[role=…]`
           **everywhere, not just at document level** — inside a content container that is
           destructive;
         - no `<article>` / `<main>` / `itemprop` / JSON-LD short-circuit exists at all;
         - client-rendered markup: if the body arrives as a JSON payload or `data-target`
           props rather than HTML, no scoring change can ever reach it — that is a finding,
           not a failure, and it changes W07 completely.
      Record the answer on issue #17 and in NOTES.md in one line. If the body genuinely is not
      in the HTML the app receives, W07 becomes "say so in the UI honestly" — mark that on the
      issue and let W07 be rewritten rather than guessing a scoring change.
      - Done: the fixture exists and is byte-identical to what the app's fetcher receives; the
        reproduction test names the mechanism and passes; `./gradlew test` green at ≥1489
        tests; the diagnosis commented on issue #17, which **stays open** for W07.
      - Rung: unit

- [x] **W07 — The extractor reaches pages it was structurally blind to. TDD. Issue #17.**
      Flip W06's pinned defect using a rule that **names no host** (§0). Whatever W06 measured
      decides the change; the shapes planning judged most likely, in order of generality:
      1. **A list can be prose.** `PROSE_TAGS` (`ArticleExtractor.kt:308`) excludes `ul`/`ol`
         and `:128-138` gives `li` a −3 base. That is right for navigation and wrong for an
         article whose argument is a list. If this is the cause, the general rule is a
         *link-density-gated* one: a list whose items are text rather than links counts as
         prose, a linky one still does not — `linkDensity()` `:155-160` and `LINKY = 0.5`
         (`:330`) already express exactly that distinction; reuse them.
      2. **`header`/`footer` inside the content container are not landmarks.** The sweep at
         `:77-80` is document-wide; scoping it to what is outside the top candidate, or to
         landmark-*role* elements only, is a general correction — an article's own
         `<header>` holding its standfirst is content in any CMS.
      3. **Honour the explicit signals before guessing.** `<article>`, `<main>`,
         `[itemprop=articleBody]` and JSON-LD `articleBody` are what a publisher says the body
         is; the scorer currently ignores all four. Preferring a declared body over a scored
         one is the most general rule available and helps every site that emits it.
      **The corpus is the contract and may not be weakened** (`FeedCorpusTest.kt:20-23` says so
      in the file). Every one of these widens what counts as content, so the risk is the
      opposite of the bug: chrome creeping into other articles. Before and after, run the whole
      extraction corpus and compare — `ArticleExtractorTest.kt:25-39` (mid/last prose),
      `:42-53` (**`excludes` chrome must still not survive** — this is the guard that catches a
      too-greedy rule), `:62-77` (excerpt recovery ratio ≥ 10.0), `:86-99` (zero
      `ArticleBlock.Unsupported`), `:112-153` (the ZDI table, exactly 228 cells), plus
      `ArticleLoweringCorpusTest`, `TableCorpusTest` and `ThumbnailCorpusTest`.
      Promote the fixture from W06's `pending` list into `other` (`ArticleFixtures.kt:161-213`)
      with real `mid`/`last` strings and its `excludes` chrome, so it joins `all` (`:215`) and
      is guarded forever after by every test above.
      Note in NOTES.md, in one line, which rule changed and what it cost the corpus — a future
      session widening the extractor again needs the measurement, not the story.
      - Done: the reproduction test from W06 rewritten to assert a real body is extracted, the
        fixture promoted into `all`, and **`./gradlew test` green at ≥1489 tests with the
        excerpt ratio and the ZDI cell count unchanged** — both pasted into the commit
        message; issue #17 closed naming W06 and W07.
      - Rung: unit

---

## Phase 2 — The docs, and the process that let them go stale

- [x] **W08 — A README a reader can scan in a minute. Issue #18.**
      `gh issue view 18 --json body` — first half: "too long", must show at a glance what
      features exist, how to build and start contributing, and "a general section at the top".
      It is **157 lines** today. The shape is not bad; the proportions are: `## Install`
      (`:18-45`) spends **22 of its 28 lines** on a four-step v0.1.0→v0.2.0 bridge
      (`:31-44`) that matters to nobody who is not still on v0.1.0, and `## Status`
      (`:139-151`) is a hand-maintained changelog (`:141` "v0.3.0 is the current release …
      41 live sources", `:141-147` v0.2/v0.3 feature and fix lists) that the Releases page
      already publishes and that went stale exactly as the issue says.
      Rewrite to §0's shape: what Perch **is**, what it **does** (features, scannable), how to
      **build and add a feature**, then the rest. The v0.1.0 bridge becomes one line pointing
      at the v0.2.0 release page, which still carries `perch-0.2.0-debug.apk`
      (NOTES.md's V16 line). `## Status`'s changelog goes; one line pointing at
      `github.com/…/releases/latest` replaces it — §0: no version-specific claim beyond that
      single line. Fix `:107` vs `:120` (the fixture count disagrees with itself) and `:125`,
      which names `PLAN-3.md` as "what is being built next" when PLAN-3 is finished and this
      plan is the active one. Keep the screenshots (`:1-17`) and the doc map — the map is how
      a contributor finds SPEC/DESIGN/RALPH instead of the README repeating them.
      - Done: `wc -l README.md` under 110; `grep -nE '0\.[0-9]+\.[0-9]+' README.md` returns
        only the single latest-release line; no stale `PLAN-3.md` reference; issue #18 stays
        open for W09.
      - Rung: unit

- [x] **W09 — Every loop ends with a review, and the process says so. Issue #18.**
      Second half of #18: "add a code review pass after every Ralph loop ends, where it makes
      sure everything looks okay. Right now for example, we include some stuff in the readme
      that's stale … a code review would have helped."
      Planning confirmed the gap is real: `grep -in review loop.sh CLAUDE.md docs/RALPH.md
      PLAN-3.md` returns **zero hits**. Write the review pass into the process in three
      places: `docs/RALPH.md` §6 "Land it" (`:137-145`) gains a **review** step *before* live
      acceptance and release, with what it is for — the class of defect a per-task session
      structurally cannot see, because each session only ever reads its own task's files:
      docs describing the previous version, an orphaned helper, a weakened test, a README
      claim that is no longer true; the task anatomy in §2 gains the review box as a standard
      penultimate task; and `CLAUDE.md`'s rules gain the one-liner that a plan without a
      review box is incomplete.
      `loop.sh` should not silently keep going when a review finds something: it already
      exits cleanly at zero unchecked tasks (`:161-168`). Leave the driver's control flow
      alone and make the review a **plan task** (W10) — that is what the loop already knows
      how to run, and a review that halts the driver has no way to report what it found.
      Also record in `docs/RALPH.md` the two process facts this planning session established:
      the loop has no CI (`.github/` does not exist — every gate is local, which is *why* an
      end-of-plan review is the only thing that reads a whole version's diff), and that
      `scripts/progress.sh`'s `PLAN=` default is a **fourth** one-line edit when a plan turns
      over — `docs/RALPH.md:135` currently promises two, and planning found three.
      - Done: `grep -in review docs/RALPH.md CLAUDE.md` shows the pass documented in both;
        `scripts/progress.sh` defaults to this plan; issue #18 closed naming W08 and W09.
      - Rung: unit

---

## Phase 3 — Land it

- [x] **W10 — The review pass. Everything this plan changed, read as a whole.**
      The box W09 just wrote into the process, run for the first time on this plan.
      Read `git diff v0.3.0..HEAD` — the whole of it, not one task's worth — and answer, in a
      comment on this box and in NOTES.md where it outlives the plan:
      1. Does any doc still describe the version before this one? Check README.md, SPEC.md,
         DESIGN.md, NOTES.md, CLAUDE.md and `docs/RALPH.md` against what actually shipped —
         this is the exact defect #18 was filed about.
      2. Did any task leave a helper, string, dimension or test tag orphaned? `startsSection`,
         `SectionHeader`, `showSections`, `home_filter_today` and the section `Dimens` are all
         scheduled to die in W03/W04 — confirm they actually did, everywhere.
      3. Was any test weakened rather than rewritten? The rule is absolute for
         `FeedCorpusTest`, and `TimeFilterTest`, `EntryRowTest`, `HomeTimeFilterTest`,
         `EntryRepositoryTest` and `EntryPagingTest` all had assertions deliberately changed
         in this plan — each change must have left an assertion at least as strong. Name every
         one and say which.
      4. Is `./gradlew test` still ≥1489 tests, and did any task's fix land without a test?
      Fix what is small and mechanical **in this session**. Anything larger becomes a new
      GitHub issue for the next plan, named here — do not start a fifth feature in a review.
      - Done: the four questions answered in the commit message, each with the command that
        settled it; `./gradlew test` green; any new issue created and linked.
      - Rung: unit
      - **Reviewed 2026-08-18** over `git diff v0.3.0..HEAD` (49 files, +2335/-589).
        1. **Docs.** SPEC/DESIGN/CLAUDE/RALPH describe what shipped. **README did not**, twice:
           it offered "a row's swipe actions" (there are none — row actions are the long-press
           sheet) and "tap a source name to narrow the Feed" on the row (the row's meta is
           plain text; V08's scoping is the *article byline* and the drawer). Both rewritten,
           with the screenshot alt text. NOTES' W02 line still sent a reader to
           `HomeTestTags.section(id)`, which W03 deleted — rewritten. Four KDocs still
           explained folder section headers (`TimeFilter`, `EntryDao.observeListItems`,
           `EntryRepository`'s placeholder rationale, `FolderDrawerTest`) — rewritten.
        2. **Orphans.** `startsSection`, home's `SectionHeader`, `showSections`,
           `HomeTestTags.section` and `Dimens.sectionHeaderTop/Bottom` are gone everywhere
           (`grep -rn` finds only Settings' unrelated `SectionHeader` and the article's
           `sectionHeadAbove`); `home_filter_today` survives deliberately, relabelled
           "Past 24 Hours". Two genuinely dead strings — `home_empty_no_entries` and
           `home_empty_no_entries_body`, unreferenced since T21 — deleted.
           `ArticleFixtures.pending` was an empty val nothing read, whose KDoc claimed
           `ArticleExtractorBlindSpotTest` measured it: the test now holds every pending
           fixture to still failing, so the slot cannot keep a page that has been fixed.
        3. **Tests: none weakened.** `TimeFilterTest` traded a midnight equality for the
           rolling edge *plus* three new invariants (zone-invariance, inclusive edge, the
           window slides with the clock). `EntryRepositoryTest`/`EntryPagingTest` kept exact
           `inOrder` assertions and gained strict `publishedAt` ordering across 90 paged rows.
           `HomeTimeFilterTest` swapped four section assertions for "no section node exists",
           and its one deleted test is covered twice over. `HomeScreenTest` made
           "interleaved newest first" seed **two folders**, which is the only version of it
           that can fail. `FolderDrawerTest` lost the list half of a rule the list no longer
           obeys and kept the drawer half. `EntryRowTest` was the one real regression: the
           relative-time bands went from `"Simon Willison / 47min"` to a bare
           `onNodeWithText(label)`, which would pass on a row printing another entry's time —
           restored as a per-row `EntryRowTestTags.DATE` assertion. `FeedCorpusTest` untouched.
        4. **1524 tests, 0 failures** (`./gradlew cleanTestDebugUnitTest
           cleanTestReleaseUnitTest test`, 1 skipped = the network-gated live gate), against a
           floor of 1489. Every commit that touched `src/main` carried tests in the same
           commit (W02 4/6, W03 6/7, W04 2/6, W05 5/3, W07 1/2 main/test files).
        **Found and fixed beyond the four questions:** live **gate 9 still asserted the
        folder-grouped list W03 deleted** — it read `observeListItems`, folded it into runs of
        `folderName` and required each folder to open exactly once, which the one-stream list
        cannot satisfy. It would have failed W11 as a mystery. Now the drawer's two queries
        only, with the reason written down. `MIN_SECTIONS` renamed `MIN_NAMED_FOLDERS`.
        **No new issue filed**: nothing survived that a fifth feature would be needed for.

- [ ] **W11 — Live acceptance v4.** Re-run V15's twelve gates against the live corpus with
      `./gradlew :app:testDebugUnitTest -Pperch.live=true --tests '*LiveAcceptance*'`, with the
      two this plan invalidated **rewritten by the task that invalidated it**: gate 8 is no
      longer a UTC-versus-zone margin (W02 — a rolling window has none by construction) and
      gate 7 no longer counts folder sections (W03 — there are none). **Gate 9 lost its list
      half in W10** for the same reason: folder order is the drawer's rule now, so the gate
      asks `FolderDao`'s two queries and nothing else. Gate 1 still has no
      quota: every source in `feeds.txt` bar `EXCLUDED_SOURCES` must pull.
      Add this plan's own questions: the Feed's first page is in non-increasing `publishedAt`
      order across the live corpus; every live entry inside 24 h is in the window and nothing
      older is; and the Hugging Face URL from #17 extracts a body over the teaser it replaces.
      **`research.checkpoint.com` answers 202 with an empty body when live runs come too close
      together** (NOTES.md) — wait ~10 quiet minutes and rerun **without** probing with `curl`
      first, because the probe spends the allowance the rerun needs. **Bounded: at most three
      foreground runs**; if a source still fails after the third, exclude it with the
      measurement that settled it, or mark this box BLOCKED — never loop.
      Screenshots of the mixed Feed (dark, showing category labels and date lines, mixed
      thumbnails including placeholders) and the article screen with its new share action.
      Then `./gradlew test assembleRelease` — **not `clean`**: it deletes
      `build/perch-screenshots/`, which is this box's evidence.
      - Done: every gate's count pasted into the commit message; the screenshots exist; the
        default no-network `./gradlew test` still green.
      - Rung: screenshot

- [ ] **W12 — Release v0.4.0.** Bump `perchVersionCode` 4 → **5** and `perchVersionName`
      `0.3.0` → **`0.4.0`** at `app/build.gradle.kts:12-13` — §0's rule: this release carries
      new features, so the MINOR digit moves. Build the **release-signed** APK with U02's key;
      `assembleRelease` runs `lintVitalRelease` where `assembleDebug` does not.
      **V16's lesson, and the one way this box fails: a doc that describes a release is not the
      release.** The session before V16 bumped the version, wrote the notes and never built —
      the APK on disk was still the old versionCode. **Check `output-metadata.json` and
      `aapt2 dump badging`, never the note.**
      Refresh the README's screenshots from W11's captures. Draft the page with
      `scripts/release-notes.sh v0.3.0` and write it through `docs/RELEASE-NOTES.md`'s template
      (`:13` the template, `:44` the eight rules, `:106` the shipping commands) — an
      announcement, not a changelog: what is new in the reader's words (a feed that is one
      mixed stream, a Today that means the last 24 hours, sharing and copying a link, Hugging
      Face articles that read as articles), every fix named by the symptom the reader reported,
      and the plain statement that **v0.3.0 → v0.4.0 installs in place and keeps read state,
      likes and the to-read queue**. The script's buckets are guesses and need the pass by hand
      (V16's outcome note). Tag `v0.4.0`, push, `gh release create v0.4.0` with the notes file
      and `perch-0.4.0.apk` attached. Prune NOTES.md back under 100 lines.
      - Done: `gh release view v0.4.0 --json assets` lists the APK; `aapt2 dump badging` on the
        released file reads `versionCode='5' versionName='0.4.0'`; `apksigner verify
        --print-certs` prints U02's digest `61367c04…fce489`; `git status` clean and pushed;
        `grep -c '^- \[ \]' PLAN-4.md` returns 0; `gh issue list --state open` holds only what
        is genuinely still open, each with a comment saying why.
      - Rung: build
