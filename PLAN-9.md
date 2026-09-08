# PLAN-9.md — v0.6: find it, delete it, and say when you are guessing

**This is the active plan.** v0.1–v0.5 are complete, frozen, and history only, archived in
`docs/plans/`. The process is `docs/RALPH.md`.

Ordered. **Do the single next unchecked `[ ]` task, then stop.** Never check a box unless
its Done-condition literally passed in this session. Failure → 2 attempts max, then rewrite
the box as `- [BLOCKED: one-line diagnosis]`, log it to NOTES.md, and move on.

---

## §0 — Decisions for this version (authoritative; do not re-derive)

This plan is one batch of reader-filed issues: **#31, #33, #30, #29, #32, #25, #28**. Six are
small and local; **#28 (search) is the headline feature and is three tasks, not one.**

### §0.1 The version is `0.6.0`, `versionCode` **7**

Not the release task's judgement call (SPEC.md §1, CLAUDE.md): this plan lands search, a
delete-source shortcut and visible date provenance — notable new user-visible features — so
the **MINOR** digit moves. `versionCode` goes up by exactly 1. Both live at
`app/build.gradle.kts:12-13` (`perchVersionCode` / `perchVersionName`) and **nowhere else**.

**The database also goes to version 7 (S08). These two 7s are unrelated** — `versionCode` is
the update identity, `PerchDatabase.VERSION` is the schema. Do not reason from one to the
other; they merely coincide this once.

### §0.2 Standing gates, unchanged from v0.5

- No `Color(0x` / `N.dp` / `N.sp` outside `ui/theme/` — screens address roles, never tones.
- **No hostname literal under `data/`**
  (`grep -rnoE '"[a-z0-9.-]+\.(com|org|net|io|dev|me|ski|ca|xyz|blog)"' app/src/main/java/dev/mkiros/perch/data/`),
  fixtures exempt. Parse by standards, never a table of known sites.
- **TDD**, two attempts then `BLOCKED`, commit and push every task, close the issue it names.
- **A bug is not fixed until a failing test reproduced it.** #30, #31, #32 and #33 are all
  filed as bugs: each of those tasks must show RED before GREEN, and the RED must fail for
  the reported reason, not an adjacent one.
- The test floor is **1669** (v0.5.0's `./gradlew test`, 976 debug + 693 release). It only
  ever grows.
- **`fallbackToDestructiveMigration()` never comes back.** v0.5.0 is installed on the
  human's real phone; S08's schema change ships a real `Migration` plus `app/schemas/7.json`.

### §0.3 #31 — a pasted link belongs to To-Read, and to nothing else

**Root cause, already established — do not re-investigate.** `WHERE isSynthetic = 0` exists
**only in `FeedDao`** (`FeedDao.kt:29,35,40,68,75`, its KDoc at `:15-21` scopes the rule to
*feed* queries). **No query in `EntryQueries` filters it** (`EntryDao.kt:25-70`). The seeded
synthetic feed is filed under Uncategorized (`PerchDatabase.kt:192-200`), so both of `ROW`'s
inner joins succeed and a pasted entry is an ordinary Feed row. NOTES.md's v0.5 line claiming
"every general feed query gained `WHERE isSynthetic = 0`" **overstates what shipped** — S01
corrects that line in the same commit.

**The fix is a predicate on the feed join, not a new column on `entries`.** Adding a fourth
reader-owned flag would need edits in both `EntryDao.upsertAll` and `deleteReadOlderThan`
(U04) and buys nothing: `feeds.isSynthetic` already carries exactly this meaning.
**`ROW`'s two INNER JOINs are load-bearing** — `docs/plans/PLAN-6-v0.5-slice2.md:82-95`
explicitly *rejected* making `entries.feedId` nullable. Add a predicate; never loosen a join.

Which queries gain `AND f.isSynthetic = 0`, decided here so S01 does not deliberate:

| Query | Anchor | Filter synthetic? |
|---|---|---|
| `LIST_ITEMS` (the Feed) | `EntryDao.kt:51-57` | **Yes** — this is the bug |
| `observeUnreadCount` (the badge) | `EntryDao.kt:161-162` | **Yes** — a pasted link must not inflate it |
| `observeUnreadCountsByFeed` | `EntryDao.kt:170-177` | **Yes** — it emits a `feedId` the drawer has no row for |
| `unreadIds` (mark-all-read) | `EntryDao.kt:187-200` | **Yes** — its doc already says the scope must match `observeListItems` exactly |
| `SAVED` (To-Read) | `EntryDao.kt:59-63` | **No** — this is where a pasted link lives |
| `LIKED` | `EntryDao.kt:65-69` | **No** — you may like a pasted article |
| `statesToExport` | `EntryDao.kt:306-316` | **No, deliberately** — a restore should bring the to-read queue back. Out of scope; do not "fix" it. |
| search (S09) | new | **No** — a pasted article is a stored article and must be findable |

### §0.4 #33 — the sheet closes when the link is in, and not before

The busy spinner **already exists** (`SaveLinkSheet.kt:134-139`) and the failure copy already
renders (`:116-124`). The bug is the **container**: `onDismissRequest` (`SaveLinkSheet.kt:58-61`)
calls `viewModel.reset()` unconditionally, so a scrim tap, a swipe, or the sheet settling
Hidden after the IME collapses on `ImeAction.Go` (`:107-110`) closes it mid-flight, wipes
`isBusy` and `error`, and leaves the `viewModelScope` coroutine running — the row just appears
later. A failure arriving after that writes `error` into a sheet nobody is looking at, and
because `reset()` already ran, **that stale error is what the next opening shows.**

Decided: **while `isBusy`, the sheet is not dismissible** — `onDismissRequest` is a no-op and
does not `reset()`. On success it closes and To-Read shows a snackbar naming the saved title,
through the `onAdded(id)`-before-`reset()` hook `AddSourceSheet.kt:60-77` already demonstrates.
On failure it stays open with the reason. `CollectionScreen`'s `snackbarHostState`
(`CollectionScreen.kt:82,97-109`) already exists for the remove/undo message — reuse it.

**Testing tactic, decided:** `SaveLinkSheetTest` drives `SaveLinkSheetContent` directly and
never the `ModalBottomSheet` container (an injected tap never reaches a node inside a sheet —
NOTES.md), so **the dismissal rule must live somewhere pure and be tested there**: a
`SaveLinkUiState.canDismiss` (false while `isBusy`) asserted in a `SaveLinkViewModel` unit
test, with the container merely reading it. Do not try to drive the sheet container.

### §0.5 #30 / #29 — deleting a source clears the scope that pointed at it

**Root cause, already established — do not re-investigate.** The hoisted
`homeScope` (`PerchNavHost.kt:157-159`) is written by exactly three places (`:185`, `:268`,
`HomeScreen.kt:189/194`) and **nothing in the delete path touches it**:
`HomeScreen.deleteSelection` (`:242-250`) → `HomeViewModel.promptRemoveSources` (`:614-621`) →
`confirmRemoveSources` (`:623-627`) → `FeedRepository.removeAll` (`:222-228`) →
`FeedDao.deleteByIds` (`FeedDao.kt:61-66`). So `scope` stays `HomeScope.Source(deletedId)`,
`LIST_ITEMS`' `:feedId` is a dangling id, and the list is empty.

**The bar reverts to "Feed" while the query stays scoped, and that gap is the whole illusion.**
`HomeViewModel.kt:463-471` computes a *resolved* scope that drops a filter pointing at a
missing source — but it feeds `HomeUiState` (title/banner) only and is **never written back**
to `homeScope` or to the raw `scope` that `pagedEntries` reads (`:297-307`). That duality is
deliberate (T24) and is **not** the bug. Do not fix this by making the VM a second owner of
scope: NOTES.md V08 is explicit that the nav shell is the single owner. **Clear `homeScope` in
the shell/screen when the scoped source or folder is removed.**

**`deleteFolders` has the identical defect** (`HomeScreen.kt:246`) — a scoped folder deleted
from the drawer strands `HomeScope.Folder(id)` the same way. S03 fixes both; it is the same
one-line class of bug and splitting it across two sessions would be waste.

**`HomeScreenTest.kt:396` currently masks #30.** `` `removing the source being filtered on
drops the filter` `` asserts the title, then does `selectInDrawer("All sources", …)` — which is
*the reader's workaround* — before it ever looks at the rows. It passes with the bug live.
**S03's RED must assert the list before any drawer interaction.** Strengthening that test is
required, and is not "weakening a test" in reverse: say so in the commit message.

**#29 needs no new ViewModel API.** `promptRemoveSources(setOf(feedId))` already exists and
`DeleteSourcesDialog` (`SelectionBar.kt:145-205`) is already rendered off
`viewModel.sourceDeletePrompt` (`HomeScreen.kt:477-487`) with strings at `strings.xml:96-109`.
S04 adds a `DropdownMenuItem` to `HomeOverflow` (`HomeScreen.kt:590-620`, today two items) and
plumbs the scoped `feedId` in. **Hide the item when the scope is not a source, and when the
scoped feed is synthetic** — `FeedRepository.remove` early-returns on `isSynthetic` (`:207`)
and the drawer already hides overflow for undeletable rows so that "cannot be deleted reads as
a rule rather than as a missing control" (`HomeScreen.kt:906-912`). Follow that precedent.

**S04 depends on S03's fix**, because confirming from the overflow lands in the same
`confirmRemoveSources` path and would strand the scope in exactly the same way. Order is fixed.

### §0.6 #32 — the byline is two lines, not a ragged row

`Byline` (`ArticleScreen.kt:362-404`) is a `Row` of three independently-measured `Text`s: the
source (`:370-384`, no `maxLines`, no `weight`), the `" · "` separator (`:386-390`), and the
rest (`:394-401`, `weight(1f, fill = false)`). When the three exceed the measure the last child
wraps inside its own box while the first two stay vertically centred against it — the ragged
result in the issue's screenshot. All three are `ArticleType.byline`, uppercased and
letter-spaced in the VM (`ArticleViewModel.byline`, `:260-264`), which makes the overrun worse.

Decided: **`Byline` becomes a `Column` of two lines** — the source on its own line (still
tappable, still `Dimens.touchTarget` tall, `maxLines = 1`, `TextOverflow.Ellipsis`), and
`AUTHOR · DATE` beneath it (`maxLines = 2`, ellipsis). This mirrors `EntryRow`'s own
established shape, where META sits above DATE (W04, `EntryRow.kt:126-141`).

**Do not collapse SOURCE and BYLINE into one `AnnotatedString`.** It is tempting —
`EntryRow.sourceAndCategory` (`:164-177`) argues for exactly that — but here it would merge two
semantics nodes into one and break `ArticleScreenTest.kt:92,106,126,138,157`,
`PerchNavHostTest.kt:321` and `LiveAcceptanceTest.kt:1671-1673`, several of which assert the
source's *touch-target bounds* and its navigation click. Keep two nodes and two test tags.
The `" · "` between source and rest disappears; `ArticleViewModel.SEPARATOR` stays for
author·date and the VM is not touched.

**The existing article screenshot cannot show this bug**: `DesignScreenshotTest.kt:222-228`
renders a `nullprogram.com` article whose byline is short, and `gijn.org` is in neither
`fixtures/feeds.txt` nor `fixtures/snapshots/`. `showArticle` (`:503-520`) builds
`ArticleViewModel` directly, so **S05 adds a long-source/long-author scene in-process** — no
new corpus entry, no network.

### §0.7 #25 — a guessed date reads as a guess

Decided here so S06 and S07 do not have to hold a UX debate. **Yes, the reader should be able
to tell.** The marker is a leading tilde: **`~3d`, `~3 Aug 2026`**.

- **The rule lives in one place.** `RelativeTime.format` (`RelativeTime.kt:31`) gains
  `isEstimated: Boolean = false` and prefixes `~` when true. Every renderer inherits it.
  Its doc comment at `:13-15` explains dates deliberately live outside `strings.xml` (v1 is
  English-only); `~` is a glyph, not a word, so it **does not depart from that** — say so in
  the KDoc rather than moving anything into `strings.xml`.
- **The list row (S06)** needs the flag projected. Add `publishedIsEstimated` to
  `EntryQueries.ROW` (`EntryDao.kt:31-40`) and to `EntryListItem` (`EntryListItem.kt:24-38`) —
  one column in the one shared constant covers all six DAO methods.
  **Issue #25's own body overstates the blast radius**: `EntryListItem(` is constructed at
  exactly **two** sites repo-wide — the declaration, and one factory at `EntryRowTest.kt:375-395`.
  Every other test builds `EntryEntity` and reads back through Room. Give the new field **no
  default**, so the compiler proves every construction site was considered.
- **The article byline (S06)** — `entry.publishedIsEstimated` is already in scope at
  `ArticleViewModel.kt:106-110`; pass it through to the date segment.
- **The reach sentence (S07)** is the one that needs a data-model decision, because
  `MIN(publishedAt)` (`EntryDao.kt:144-148` → `FeedReach.kt:15`) aggregates across rows that
  are individually estimated or not, so no single flag survives it. Decided: **the sentence
  prefers the oldest *known* date.** Add a second aggregate —
  `MIN(CASE WHEN publishedIsEstimated = 0 THEN publishedAt END)` (**not** SQL `FILTER`, whose
  availability varies with the device's bundled SQLite) — as `oldestKnownPublishedAt` on
  `FeedReach`. `ReachSentence` (`BackfillOfferUi.kt:124-136`) renders that when present, and
  only falls back to the overall `MIN` with a `~` when a source has no known date at all.

### §0.8 #28 — search

Three tasks: the index (S08), the query (S09), the screen (S10). Decisions:

**There is no FTS anywhere today.** Zero `@Fts4`/`MATCH` in `app/src` or `app/schemas`; the
schema is at 6 with four tables (`folders`, `feeds`, `entries`, `pending_entry_state`).

**Where the text is.** There is **no article-text side table**: full text lives on `entries`
itself as `contentHtml` (`EntryEntity.kt:63`), 1:1 with the row, alongside plain-text `summary`
(`:62`, ≤300 chars) and `title` (`:57`). `contentHtml` is **HTML, nullable, and patchy** — it
is whatever the feed shipped until `ArticleTextRepository.loadFullText`
(`ArticleTextRepository.kt:42-77`) replaces it on article open, and only when the extraction
beats what is there. Search must therefore work on partial coverage and must not pretend a
never-opened article has a body.

**Do not index raw HTML.** SQLite's tokenizer would happily match inside `<p>Strava</p>`, but it
also indexes tag names, class names and URLs — so a search for `class`, `img` or `https` would
return the whole database. The FTS row stores **plain text**.

**Shape, decided: a standalone FTS4 table whose rows Perch writes itself.**
`entries_fts(title, body)` with `rowid = entries.id`. **Not `@Fts4(contentEntity = …)`** — an
external-content table needs sync triggers that Room does not reliably generate for you, and
the failure mode is a silently stale index. Not an extra plain-text column on `entries` either:
the FTS table *is* the plain-text store, so the body is not held twice.

- **Inserts and updates are Kotlin**, where the HTML is already in hand and `HtmlSanitizer`
  can flatten it: `EntryDao.upsertAll` (`:375-449`, hand-written, never Room `@Upsert` — U04)
  and wherever `ArticleTextRepository` lands full text (`:72`).
- **Deletes are a SQL trigger** on `entries`, because a feed delete reaches entries by
  `ON DELETE CASCADE` (`FeedDao.kt:61-66`) and never passes through Kotlin at all. A
  Kotlin-only index would leak orphans on every source removal.
- The trigger must be created **both** in `MIGRATION_6_7` and on fresh install, via a
  `Callback` beside `SEED_UNCATEGORIZED` / `SEED_SAVED_LINKS` (`PerchDatabase.kt:177-184`).
- `MIGRATION_6_7` **backfills every existing row** — an upgrade whose index starts empty is a
  search feature that finds nothing until the next refresh.

**Query syntax is sanitized, never passed through.** Raw reader input reaches `MATCH` and a
stray `"`, `*` or `AND` throws at runtime. A pure `FtsQuery.from(raw): String?` strips
non-alphanumerics, joins the tokens with `AND`, suffixes the last token with `*` for
prefix-matching, and returns `null` for empty — unit-tested on its own, with quotes, operators
and emoji among the cases.

**Results are ordered by recency, not relevance.** `publishedAt DESC, e.id DESC`, like every
other list in the app. FTS4 has no `bm25`, and `matchinfo` ranking is a second feature; the
house rule is recency and consistency wins.

**Search ignores the time window and the read filter, always.** You are looking for something
you may have read months ago; a "Past 24 Hours" window that hides it defeats the point.

**Search inherits the surface it was opened from**, shown as a label in the search field:
- from the Feed unscoped → every stored article, **including pasted links** (§0.3);
- from a source- or folder-scoped list → that source or folder;
- from To-Read → `isSaved = 1`; from Liked → `isStarred = 1`.

One action, **"Search everything"**, widens a narrowed search to all stored articles. That is
the only scope control; do not build a filter panel.

**Search is state, not a route.** `PerchNavHost.kt:260-266` says a scoped list is state and not
a route, for reasons that apply identically here. Hoist the search state beside `homeScope`
(`:157-159`), give it a `Saver`, and add a `BackStep` that leaves search **before**
`LeaveScope` in `BackChain.kt`.

---

## The tasks

- [x] **S01 — A pasted link is on To-Read and nowhere else. TDD. Issue #31.**
      `gh issue view 31 --json body` — the reader's words: it "also adds the article into your
      feed… there's no reason to keep it in the feed".
      §0.3 carries the root cause and **the exact table of which queries gain
      `AND f.isSynthetic = 0` and which must not** — implement that table, do not re-derive it.
      - RED first, in `EntryRepositoryTest.kt` (`app/src/test/.../data/repo/`): seed an entry on
        the synthetic saved-links feed and assert it is absent from the Feed list and present on
        To-Read. Neither `EntryRepositoryTest` nor `EntryPagingTest` mentions `isSynthetic`
        today — that is the gap this fills. Add the badge/mark-all-read cases too.
      - `ROW`'s inner joins stay exactly as they are (§0.3). One predicate per query.
      - **Correct NOTES.md in this same commit**: its v0.5 line says "every general feed query
        gained `WHERE isSynthetic = 0`", which was never true of `EntryQueries`. That wrong note
        is why this bug survived a version.
      - Done: the new tests named in the commit message; `./gradlew test` green and ≥1669;
        issue #31 closed with a comment naming the commit.
      - Rung: unit

- [x] **S02 — The link sheet stays open until the link is in. TDD. Issue #33.**
      `gh issue view 33 --json body` — the reader's words: "no loading, no confirmation… it
      should have errored out in the dialog before closing it".
      §0.4 carries the root cause and the decided behaviour. The spinner and the error copy
      already exist — **the bug is the container**, `SaveLinkSheet.kt:58-61`'s unconditional
      `reset()` on dismiss.
      - RED first **in `SaveLinkViewModelTest`** on `SaveLinkUiState.canDismiss` — false while
        `isBusy`, and a failure arriving after a dismissal attempt must still be readable.
        §0.4 explains why the RED cannot live in the sheet container: an injected tap never
        reaches a node inside a `ModalBottomSheet`, which is why `SaveLinkSheetTest` drives
        `SaveLinkSheetContent` directly. **Do not spend the session fighting the container.**
      - Success closes the sheet and To-Read shows a snackbar naming the title, via the
        `onAdded(id)`-before-`reset()` hook at `AddSourceSheet.kt:60-77`; the snackbar host
        already exists at `CollectionScreen.kt:82,97-109`.
      - Copy in `strings.xml` (`strings.xml:162-167` is the existing save-link group), no
        hardcoded text.
      - Done: the VM tests named in the commit message; a content-level test that a failure
        leaves the reason on screen; `./gradlew test` green and growing; issue #33 closed.
      - Rung: unit

- [x] **S03 — Deleting the source you are reading leaves you on a Feed with articles in it. TDD. Issue #30.**
      `gh issue view 30 --json body` — the reader's words: "it shows 0 elements… If I open the
      left-hand menu again and choose Feed, all of a sudden the articles populate."
      §0.5 carries the root cause: the hoisted `homeScope` (`PerchNavHost.kt:157-159`) is never
      cleared by the delete path, so `LIST_ITEMS`' `:feedId` is a dangling id. **Do not
      re-investigate, and do not make the ViewModel a second owner of scope** — V08 says the
      nav shell owns it. Clear it in the shell/screen.
      - **Fix `deleteFolders` too** (`HomeScreen.kt:246`) — a scoped folder strands
        `HomeScope.Folder(id)` identically. Same class, same session (§0.5).
      - **RED first, and the RED must assert the list, not the title.**
        `HomeScreenTest.kt:396` `` `removing the source being filtered on drops the filter` ``
        passes with the bug live because it taps "All sources" — the reader's own workaround —
        before looking at any row. Strengthen it: assert rows are present **before** any drawer
        interaction. Say in the commit message that this test was strengthened and why; it is
        the inverse of weakening one.
      - The end-to-end home for a hoisted-scope regression is
        `PerchNavHostTest.kt` (`:275`, `:299` already drive the hoisted scope).
      - UI-test traps (NOTES.md): a tap never reaches a node inside a drawer sheet — use
        `performSemanticsAction(OnClick/OnLongClick)`; address nodes by test tag, never by the
        text "Delete"/"Remove", which appears in several places.
      - Done: the strengthened test failing before and passing after, both outputs in the commit
        message; the folder twin covered; `./gradlew test` green and growing; issue #30 closed.
      - Rung: unit

- [x] **S04 — Remove this source from the list you are reading. TDD. Issue #29.**
      `gh issue view 29 --json body` — the reader's words: an option in "the context menu that's
      in the top right", a confirmation, then back to the feed.
      **Depends on S03** — confirming from the overflow lands in the same `confirmRemoveSources`
      path and would strand the scope the same way (§0.5). Do not start this before S03 is green.
      - **No new ViewModel API.** `promptRemoveSources(setOf(feedId))` (`HomeViewModel.kt:614-621`)
        and `DeleteSourcesDialog` (`SelectionBar.kt:145-205`, already rendered at
        `HomeScreen.kt:477-487`, strings at `strings.xml:96-109`) do the whole job. Add a third
        `DropdownMenuItem` to `HomeOverflow` (`HomeScreen.kt:590-620`) and plumb the scoped
        `feedId` in — the menu takes no scope argument today.
      - **Hide the item when the scope is not a source, and when the scoped feed is synthetic.**
        `FeedRepository.remove` early-returns on `isSynthetic` (`:207`); the drawer's precedent
        for hiding rather than disabling is `HomeScreen.kt:906-912`.
      - New tag on `HomeTestTags` (`HomeScreen.kt:1080-1132`), convention `"home:overflow:<thing>"`.
      - Done: tests for present-when-scoped, absent-when-unscoped, absent-when-synthetic, and
        confirm-removes-and-widens; `./gradlew test` green and growing; issue #29 closed.
      - Rung: unit

- [x] **S05 — The byline wraps like a subheading, not like a paragraph. TDD + screenshot. Issue #32.**
      `gh issue view 32 --json body` — the reader's words and the screenshot: "where it goes to
      the next line, it looks wrong".
      §0.6 carries the decided layout — **`Byline` (`ArticleScreen.kt:362-404`) becomes a
      `Column` of two lines**, source above, `AUTHOR · DATE` beneath — and the reason **not** to
      merge the two `Text`s into one `AnnotatedString`, which would break five existing
      assertions including two on the source's touch-target bounds and its navigation click.
      - `ArticleViewModel` is **not** touched: `SEPARATOR` still joins author and date.
      - **The existing screenshot cannot show the bug** — `DesignScreenshotTest.kt:222-228` uses
        a short `nullprogram.com` byline and `gijn.org` is in no fixture. Add a long-source,
        long-author scene in-process; `showArticle` (`:503-520`) builds the VM directly, so this
        needs no corpus entry and no network (§0.6).
      - Screenshots through `Screenshots` (`ScreenshotSupport.kt:21`) — **never
        `captureToImage()`**, its KDoc at `:12-20` says why. **Open the PNG and look at it.**
      - **Max 2 critique-fix iterations**, then log residual polish to NOTES.md and move on.
      - Done: the long-byline screenshot exists under `build/perch-screenshots/` and was opened
        and looked at; the five assertions above still pass unchanged; `./gradlew test` green
        and growing; issue #32 closed.
      - Rung: screenshot

- [x] **S06 — A guessed date reads as a guess, in the row and in the byline. TDD. Issue #25.**
      `gh issue view 25 --json body` — but read §0.7 first: **it decides what #25 left open**,
      and it corrects the issue body's blast-radius estimate.
      - `RelativeTime.format` (`RelativeTime.kt:31`) gains `isEstimated: Boolean = false` and
        prefixes `~`. Explain in its KDoc why a glyph does not breach the `strings.xml` rule its
        doc comment at `:13-15` sets out.
      - Project the flag: one column added to `EntryQueries.ROW` (`EntryDao.kt:31-40`) covers all
        six DAO methods; add the field to `EntryListItem` (`EntryListItem.kt:24-38`) **with no
        default**, so the compiler proves every construction site was considered. There are
        **two** repo-wide — the declaration and `EntryRowTest.kt:375-395`'s factory (§0.7).
      - Byline: `entry.publishedIsEstimated` is already in scope at `ArticleViewModel.kt:106-110`.
      - The writers to test against: `AtomParser.kt:67` and `SavedLinkRepository.kt:91`.
      - **The reach sentence is S07's, not this task's.** Do not touch `FeedReach`.
      - Done: new cases in `RelativeTimeTest`, `EntryRowTest` and the article screen tests, named
        in the commit message; `./gradlew test` green and growing.
      - Rung: unit

- [x] **S07 — The reach sentence prefers a date it actually knows. TDD. Issue #25.**
      The third of #25's three places, and the only one needing a data-model change (§0.7):
      `MIN(publishedAt)` (`EntryDao.kt:144-148` → `FeedReach.kt:15` → `HomeViewModel.kt:368-369`
      → `HomeScreen.kt:392-397` → `BackfillOfferUi.ReachSentence:124-136`) aggregates over rows
      that are individually estimated or not, so no per-row flag survives it.
      - Add `oldestKnownPublishedAt` to `FeedReach`, from
        `MIN(CASE WHEN publishedIsEstimated = 0 THEN publishedAt END)`. **Not SQL `FILTER`** —
        its availability varies with the device's bundled SQLite (§0.7).
      - `ReachSentence` renders the known date when there is one, and only falls back to the
        overall `MIN` with S06's `~` when a source has no known date at all.
      - Done: DAO tests for all-known, all-estimated and mixed sources; a UI test for each
        branch of the sentence; `./gradlew test` green and growing; **issue #25 closed** with a
        comment naming both S06's and S07's commits and what each settled.
      - Rung: unit

- [x] **S08 — An index of every stored article that stays true. TDD. Issue #28 (1 of 3).**
      `gh issue view 28 --json body` — the reader wants to find an article by remembering
      keywords. §0.8 carries every decision; **implement it, do not re-litigate the shape.**
      - `entries_fts(title, body)` FTS4, `rowid = entries.id`, **standalone — not
        `@Fts4(contentEntity = …)`** (§0.8 says why: an external-content table needs sync
        triggers Room does not reliably generate, and the failure mode is a silently stale index).
      - **Body is plain text, never raw HTML** — flatten with `HtmlSanitizer`. §0.8: indexing
        markup makes a search for `class`, `img` or `https` return the whole database.
      - **Inserts/updates in Kotlin** — `EntryDao.upsertAll` (`:375-449`, hand-written, never
        Room `@Upsert`) and where `ArticleTextRepository` lands full text (`:72`).
        **Deletes are a SQL trigger** on `entries`, because a feed delete reaches entries by
        `ON DELETE CASCADE` and never passes through Kotlin — a Kotlin-only index leaks orphans
        on every source removal. Test that: delete a *feed* and assert the index shed its rows.
      - `PerchDatabase.VERSION` 6 → 7 (`PerchDatabase.kt:45`), `MIGRATION_6_7` in the companion
        beside its siblings (`:159`), registered in `MIGRATIONS` (`:169-170`), plus a
        `Callback` creating the trigger on fresh install beside `SEED_UNCATEGORIZED` /
        `SEED_SAVED_LINKS` (`:177-184`) **and** in `inMemory()` (`:215-220`), which is the only
        way a test may build a database (U03).
      - **`MIGRATION_6_7` backfills every existing row.** An upgrade whose index starts empty is
        a search feature that finds nothing until the next refresh. Assert the backfill in
        `PerchMigration6To7Test.kt`, following the five existing one-per-step migration tests.
      - `app/schemas/7.json` is committed. `PerchDatabaseMigrationTest` already gates the
        unbroken 1→VERSION path, the one-json-per-version rule and the no-destructive-fallback
        rule — it must stay green untouched.
      - **`fallbackToDestructiveMigration()` never comes back** (§0.2): v0.5.0 is on a real phone.
      - Done: `PerchMigration6To7Test` green including the backfill and the cascade-delete case;
        `PerchDatabaseMigrationTest` green unchanged; `app/schemas/7.json` committed;
        `./gradlew test` green and growing.
      - Rung: unit

- [x] **S09 — Asking the index a reader's question. TDD. Issue #28 (2 of 3).**
      Depends on S08. §0.8 carries the decisions.
      - `EntryQueries.SEARCH` built on `ROW` (`EntryDao.kt:31-40`) joined to `entries_fts` on
        `rowid = e.id` with `entries_fts MATCH :query`, plus the scope predicates. **It must
        exist twice — `Flow<List>` and `PagingSource`** — like every other query here; the
        rationale is `EntryDao.kt:16-24` and the twin is exercised in `EntryPagingTest.kt`.
      - **Search does not filter `isSynthetic`** (§0.3's table) — a pasted article is a stored
        article and must be findable. **Search ignores the time window and the read filter**
        (§0.8). Scope parameters mirror `LIST_ITEMS`' `:feedId` / `:folderId`, plus the
        To-Read / Liked cases.
      - **`FtsQuery.from(raw): String?`** is a pure function, unit-tested on its own: strips
        non-alphanumerics, joins tokens with `AND`, suffixes the last with `*`, returns `null`
        for empty. Cases must include a bare `"`, a bare `*`, the word `AND`, and emoji —
        unsanitized input reaching `MATCH` throws at runtime (§0.8).
      - Order by `publishedAt DESC, e.id DESC` — recency, not relevance (§0.8; FTS4 has no
        `bm25` and `matchinfo` ranking is a separate feature).
      - Done: DAO/repository tests for title hits, body hits, a hit in a never-opened article's
        feed excerpt, scope narrowing, a pasted link being findable, and the paging twin
        agreeing with the flow; `FtsQuery` tests named in the commit message; `./gradlew test`
        green and growing.
      - Rung: unit

- [x] **S10 — A search icon at the top of the feed. TDD + screenshot. Issue #28 (3 of 3).**
      Depends on S09. §0.8 carries the decisions — **search is state, not a route**, it inherits
      the surface it was opened from, and one "Search everything" action widens it.
      - Icon into `TopAppBar.actions` (`HomeScreen.kt:356-361`, today just `HomeOverflow`);
        `Icons.Default.Search` needs no dependency change (material-icons-extended is already
        on, `app/build.gradle.kts:127`). Size with `Dimens.icon`; every `IconButton` gets a
        `contentDescription`. Collection surfaces get theirs beside the To-Read save-link icon
        (`CollectionScreen.kt:120-130`).
      - Hoist the search state beside `homeScope` (`PerchNavHost.kt:157-159`) with a `Saver`, and
        add a `BackStep` in `BackChain.kt` that leaves search **before** `LeaveScope`. `BackChain`
        is pure — its test is `app/src/test/.../ui/nav/BackChainTest.kt`.
      - Empty states: **a `LazyColumn` with one `fillParentMaxSize` item** (V03) or
        `PullToRefreshBox` ignores the swipe — NOTES.md. One state per cause, as DESIGN.md §7
        requires: nothing typed yet vs. nothing found.
      - Strings in `strings.xml` under a new `<!-- Search -->` group, `search_*` naming; tags on
        `HomeTestTags` (`HomeScreen.kt:1080-1132`).
      - UI-test traps: `testDebug`, tags not text, poll in wall-clock time (V01).
      - **A new top-bar icon changes the design screenshot baselines** (`DesignScreenshotTest.kt`)
        — expect it, retake them, and look at them.
      - **Max 2 critique-fix iterations**, then log residual polish to NOTES.md.
      - Done: tests for opening search from Feed / scoped / To-Read / Liked and for widening;
        a results screenshot opened and looked at; `./gradlew test` green and growing;
        **issue #28 closed** with a comment naming all three commits.
      - Rung: screenshot

- [x] **S11 — The review pass. The whole of v0.6, read at once.**
      Read `git diff v0.5.0..HEAD` — **the whole of it**, not one task's worth — and answer, in
      the commit message and in NOTES.md where it outlives the plan:
      1. **Does any doc still describe v0.5?** README.md (feature list and screenshot strip —
         S10's search icon makes several stale by construction), SPEC.md, DESIGN.md, NOTES.md,
         CLAUDE.md, `docs/RALPH.md`, against what actually shipped. CLAUDE.md will still name
         `PLAN-8.md` as active and the DB as version 6.
      2. **Did any task leave a helper, string, dimension or test tag orphaned?** Name the ones
         this plan scheduled to die and confirm they did, everywhere.
      3. **Was any test weakened rather than rewritten?** Name every changed assertion and say
         which is at least as strong as the one it replaced. **S03 deliberately strengthened
         `HomeScreenTest.kt:396`** — confirm it is stronger, and that no other test acquired a
         workaround tap of the same kind.
      4. **Is the suite still ≥1669 and growing, and did any fix land without a test?**
      5. **Version-specific:** is `isSynthetic` filtered in exactly the queries §0.3's table
         names, no more and no fewer? Does the FTS index survive a feed delete, a profile
         restore and a `deleteReadOlderThan` sweep? Is `~` rendered in every place a date is,
         or only some?
      6. **Reconcile the drawer claim.** `PLAN-6` §0.3 says the synthetic saved-links feed "is
         visible in the drawer, and that is deliberate", and `FeedDao.kt:20-21` says "Y04's
         drawer looks it up by URL" — **no such call exists in `ui/`**. One of those statements
         is wrong; say which, and fix the comment or the code.
      Fix what is small and mechanical **in this session**. Anything larger becomes a new issue
      for the next plan, named here — do not start a feature in a review.
      - Done: all six answered in the commit message, each with the command that settled it;
        `./gradlew test` green; any new issue created and linked.
      - Rung: unit

- [ ] **S12 — Live acceptance for v0.6.**
      The real corpus, the real network, screenshots critiqued against DESIGN.md.
      - `JAVA_HOME=$HOME/.jdks/temurin-17 PATH=$JAVA_HOME/bin:$PATH ./gradlew
        :app:testDebugUnitTest -Pperch.live=true --tests '*LiveAcceptance*'`
      - **Gate 1 has no quota** (V12/#8): every source in `fixtures/feeds.txt` bar
        `EXCLUDED_SOURCES` must pull, so a break arrives as a URL and any new exclusion carries
        the measurement that settled it. `quantpedia.com` is already excluded — its own TLS cert
        expired, confirmed independently with `curl -v` (NOTES.md).
      - **`research.checkpoint.com` answers 202 with an empty body when live runs come too close
        together** (V15) — wait ~10 quiet minutes and rerun **without** probing with `curl`
        first, because the probe spends the allowance the rerun needs.
      - Add this version's own gates: a keyword the reader would actually remember finds its
        article across a real corpus (#28); a pasted link is on To-Read and **absent from the
        Feed** (#31); a source removed from the overflow leaves a populated Feed (#29/#30).
      - **Run it in the FOREGROUND and wait, however long it takes.** Do not background it, do
        not arm a monitor, do not end your turn intending to be woken — this is a headless
        `claude -p` session and **nothing will wake it**; when your turn ends the loop reclaims
        the JVMs and kills the run. One blocking `Bash` call with a generous timeout (the live
        suite needs ~15–25 min; the session budget is 2 h).
      - **Bounded: at most three foreground runs.** If a source still fails after the third,
        exclude it with the measurement that settled it, or mark this box BLOCKED — never loop.
        If a run genuinely cannot finish, commit the gate code on a `- [BLOCKED: …]` box and say
        what was left running.
      - Screenshots: search results, the article byline on a long-titled source, To-Read with a
        pasted article. Then `./gradlew test assembleRelease` — **not `clean`**, which would
        delete `build/perch-screenshots/`, this box's evidence.
      - Done: every gate's count pasted into the commit message; the screenshots exist and were
        **opened and looked at**; the default no-network `./gradlew test` still green.
      - Rung: screenshot

- [ ] **S13 — Release v0.6.0.** Bump `perchVersionCode` 6 → **7** and `perchVersionName`
      `0.5.0` → **`0.6.0`** at `app/build.gradle.kts:12-13`, **the one place they live**. §0.1
      settles the digit; it is not this task's judgement call.
      - `./gradlew assembleRelease` (runs `lintVitalRelease`). Signing comes from
        `~/.perch/signing.properties` (U02) — **absent it the build silently debug-signs**,
        which is why the Done-condition verifies the certificate on the file rather than
        trusting the build.
      - **Gradle writes `app-release.apk`; the rename to `perch-0.6.0.apk` is this task's own**,
        and `output-metadata.json` will still name the unrenamed file — the gap V16 fell into
        and W12 recorded. Do not be surprised by it.
      - Release notes through `docs/RELEASE-NOTES.md`'s template; `scripts/release-notes.sh
        v0.5.0` drafts from the issues closed since that tag, and **its buckets are guesses that
        need a pass by hand**.
      - **v0.6 changes the schema (S08), so "v0.5.0 → v0.6.0 installs in place and keeps read
        state, likes and the to-read queue" is load-bearing and must be *verified*, not
        asserted** — and the search index must be populated after the upgrade, not empty.
        **`run-as` dies on a release build — verify through the UI, not sqlite3** (W12).
      - Refresh README's screenshots from S12's captures.
      - Tag `v0.6.0`, push, `gh release create v0.6.0` with the notes file and `perch-0.6.0.apk`
        attached. Prune NOTES.md back under 100 lines.
      - Done: `gh release view v0.6.0 --json assets` lists the APK; `aapt2 dump badging` on the
        released file reads `versionCode='7' versionName='0.6.0'`; `apksigner verify
        --print-certs` prints U02's digest
        `61367c0499de5c49c824f4d7ba7b4e692d33960cc57c0622772227a8b7fce489`; the v0.5.0 → v0.6.0
        in-place upgrade verified on the device with read state intact **and search returning
        hits on pre-upgrade articles**; `git status` clean and pushed; `grep -c '^- \[ \]'
        PLAN-9.md` returns 0; `gh issue list --state open` holds only what is genuinely still
        open, each with a comment saying why.
      - Rung: build
