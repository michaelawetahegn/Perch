# PLAN-6.md — v0.5, slice 2: a link you paste is a thing you can read

**This is the active plan.** v0.1–v0.4 are complete, frozen, and history only, archived in
`docs/plans/` — do not reopen a box in any of them. `PLAN-5.md` (slice 1, #22) is finished
and archived too. The process is `docs/RALPH.md`.

Ordered. **Do the single next unchecked `[ ]` task, then stop.** Never check a box unless
its Done-condition literally passed in this session. Failure → 2 attempts max, then rewrite
the box as `- [BLOCKED: one-line diagnosis]`, log it to NOTES.md, and move on.

Rungs, cheapest first: **unit** < **build** < **maestro** < **screenshot**.

**TDD is the method, not a suggestion.** Failing test first (`RED`), minimum code to pass
(`GREEN`), then tidy (`REFACTOR`). A commit whose diff adds production code but no test is
a defect — reopen the box.

**v0.4.0 is installed on a real phone.** This plan **does** change the schema. It ships a
real Room `Migration` and a matching `app/schemas/6.json`. **Never
`fallbackToDestructiveMigration()`** — that is someone's read state.

---

## §0 — Decisions for this slice (authoritative; do not re-derive)

PLAN-3 §0 and PLAN-4 §0 still hold except where this section says otherwise.

**This plan is one slice of v0.5, not a version.** No version bump, no release here; both
live in `PLAN-8`, together with the version-wide review over `git diff v0.4.0..HEAD`. The
review box at the end of *this* plan is scoped to *this* plan's own diff.

### 1. The rule that outranks every other decision in this plan

**No site-specific parsing. Ever.** Perch must not accumulate a table of blogs it knows
about, where a new blog means a new layout means a code change. Everything this plan
extracts is found by a **published standard or a cross-site convention**, applied to any
page. If a fixture only parses because of something true of one host, that is a **defect in
the approach**, not a special case to add.

This is already how the codebase works — `data/extract/` contains **zero** hostname
literals today, and W07 settled the doctrine for bodies: *a page that extracts to null is
read again with every `class`/`id` erased, so structure alone decides.* This plan extends
that doctrine to titles and dates; it does not invent a second philosophy beside it.

Enforced two ways, both of which are Done-conditions, not aspirations:

- **A standing grep gate.** No hostname or brand literal may appear in
  `data/extract/`. `grep -rnoE '"[a-z0-9.-]+\.(com|org|net|io|dev|me|ski|ca|xyz|blog)"'
  app/src/main/java/dev/mkiros/perch/data/extract/` must return **nothing**. Same rule for
  any new package this plan adds. Put the gate in NOTES.md beside the existing
  `Color(0x` / `N.dp` gate so it outlives the plan.
- **A corpus test, not a sample test.** The new extraction is held to **all 23 fixtures in
  `fixtures/articles/`** — roughly ten distinct publishers with genuinely different markup —
  in one parameterised test that reports a rate, exactly as `FeedCorpusTest` does for feeds.
  A rule that lifts one fixture and moves no other is a rule aimed at a site; delete it.

**Where a site genuinely differs, prefer the more general mechanism, not a branch.** Order
your candidate sources of a fact by how standard they are, take the first that answers, and
let unknown sites fall through to the weakest general rule rather than to a special case.

### 2. What a page yields, and by which standards

New: `data/extract/PageMetadata.kt`, pure, `Document`-in / value-out, no network, no DB —
the same shape as `ArticleExtractor` (`ArticleExtractor.kt:56`), which it sits beside.

**Title**, first that answers: `og:title` (Open Graph) → `twitter:title` → JSON-LD
`schema.org` `headline` → the `<h1>` inside the extracted article body → `<title>` with a
trailing site-name suffix trimmed on a separator (` | `, ` – `, ` — `, ` · `, ` - `).
The suffix trim is a **typographic** convention, not a list of site names.

**Published date**, first that answers: `article:published_time` (Open Graph article
namespace) → JSON-LD `datePublished` → `<time datetime=…>` carrying `pubdate` or
`itemprop="datePublished"` → `<meta name="date">` / `name="DC.date"` (Dublin Core) → a
date in the **URL path** (`/2026/07/27/`), which is a convention every dated blog engine
shares. **Parse dates through the existing `DateParser`** — it already handles RFC 822 and
ISO 8601 and it stays UTC deliberately (NOTES.md, V02). Do not write a second date parser.

**A page that yields no date is not an error.** It gets `publishedAt = fetchedAt` and
`publishedIsEstimated = true` — the column already exists for exactly this
(`EntryEntity.kt:53-74`) and the UI already understands it. **Never invent a date and
present it as certain.**

### 3. A saved link needs a home, and the home is a real row

`entries.feedId` is **non-null with a FK to `feeds.id`, `ON DELETE CASCADE`**
(`EntryEntity.kt:37-44`), and every list query **INNER JOINs** feeds and folders
(`EntryQueries.ROW`, `EntryDao.kt:31-39`). A feed-less entry is therefore invisible even if
it could be inserted. Two routes were considered; the decision is made, do not re-derive it:

**Chosen: a synthetic feed row.** A seeded feed — `feedUrl = "perch:saved-links"`, title
from `R.string`, filed under Uncategorized — that saved links point at. It satisfies the FK
and both joins, and it follows the doctrine `FolderEntity.kt:10-13` already states for
Uncategorized: *making "none" a real row means one rule instead of two.*

**Rejected: making `entries.feedId` nullable.** That is a table rebuild plus rewriting
`EntryQueries.ROW`'s two INNER JOINs as LEFT JOINs, which touches every list in the app.

The synthetic row leaks in four places and **each leak is decided here**:

- **Refresh must skip it.** `perch:saved-links` is not fetchable; refreshing it would record
  a failure (`FeedRepository.kt:371`) and light the drawer's `⚠`. `refreshAll`
  (`FeedRepository.kt:248`) and `isDue` (`:264`) skip synthetic feeds.
- **Deletion is refused**, exactly as `FolderRepository` refuses to delete Uncategorized —
  the FK cascade would otherwise take every saved article with it.
- **OPML and profile export skip it.** `OpmlRepository.export()` (`:59`) and
  `ProfileRepository` (`:85-98`) must not emit a `perch:` URL; no other reader could
  subscribe to it. The saved *entries* still export their state via `(feedUrl, guid)`.
- **It is visible in the drawer, and that is deliberate.** It reads as a source called
  "Saved links", so the reader can scope to it and see what they have collected. It is not
  hidden; hiding it would mean a fifth filter and a row nobody can account for.

This needs a new `feeds.isSynthetic` column → **DB version 6**, migration 5→6,
`app/schemas/6.json`, and a `PerchMigration5To6Test`. `PerchDatabaseMigrationTest` already
fails the build for a missing schema, a broken chain, or a destructive fallback.

### 4. Where the reader does it

The paste target is the **To-Read screen**, because that is where the article lands. The
existing "add source" sheet stays what it is; a link and a subscription are different acts
and merging them makes both harder to explain. To-Read's empty-state copy changes to say so
(`CollectionScreenTest.kt:76` pins the current wording and will need rewriting **with a
reason**, not a weakening).

**A pasted link that turns out to be a feed is not an error** — it is the other feature.
Say so and offer to subscribe instead; `FeedRepository.resolve` (`:133`) already knows how
to tell.

---

## The tasks

- [x] **Y01 — A page yields its title and its date, by standards alone. TDD. Issue #23.**
      `gh issue view 23 --json body` first (bare `gh issue view` dies — NOTES.md).
      New `data/extract/PageMetadata.kt`, pure, per §0.2. **Read §0.1 before writing a line:
      no rule in this file may be true of one publisher.**
      - RED first, and make it a **corpus** test: `PageMetadataCorpusTest` over **all 23
        fixtures** in `fixtures/articles/`, reporting a rate the way `FeedCorpusTest` does.
        Set the floor from what a standards-only implementation actually achieves, and state
        the measured rate in the commit message. Fixtures that carry no metadata at all are
        evidence about the web, not a reason for a special case — record them.
      - Reuse, do not restate: `ArticleExtractor.extract` (`ArticleExtractor.kt:56`) for the
        body the `<h1>` fallback is drawn from; `DateParser` for every date (§0.2);
        `ArticleTextRepository.kt:93`'s `Document.ogImage()` is the existing precedent for
        reading a `<meta>` — follow its shape.
      - The URL-path date rule is the weakest and must be **last**; a page whose metadata
        disagrees with its URL trusts the metadata.
      - Traps: `ArticleLowering` deletes truncation markers as chrome, so read metadata from
        the **unlowered** document (NOTES.md, U10). `org.json` needs Robolectric for JSON-LD
        — on a bare JVM `JSONObject` stubs (NOTES.md, U14), so put the test where it has one.
      - Done: the corpus test green with its rate pasted into the commit message; the §0.1
        grep gate returns nothing, with the command in the message; `./gradlew test` green.
      - Rung: unit

- [x] **Y02 — A saved link has somewhere to live. TDD + migration. Issue #23.**
      §0.3, and nothing beyond it — this task adds no UI and saves no link.
      - `feeds.isSynthetic` (`INTEGER NOT NULL DEFAULT 0`) on `FeedEntity`
        (`FeedEntity.kt:36-55`); `PerchDatabase.VERSION` 5 → **6** (`PerchDatabase.kt:45`);
        migration 5→6 beside the others (`PerchDatabase.kt:139`, array at `:153`); export
        `app/schemas/6.json` by re-running KSP; `PerchMigration5To6Test` beside
        `PerchMigration4To5Test`.
      - Seed the `perch:saved-links` row the way Uncategorized is seeded
        (`PerchDatabase.kt:161-169` `SEED_UNCATEGORIZED`/`seedUncategorized`) — **both on
        create and in the migration**, or a phone that upgrades will not have it. Note that
        `PerchDatabase.inMemory()` (`:182`) exists so no test can build a DB without the seed;
        the same must become true of this row.
      - The four leaks, each with its own test: refresh skips it (`FeedRepository.kt:248`,
        `:264`), delete is refused, OPML export omits it (`OpmlRepository.kt:59`), profile
        export omits it (`ProfileRepository.kt:85-98`).
      - **Never `fallbackToDestructiveMigration()`.** `PerchDatabaseMigrationTest`
        (`:19`, `:30`, `:42`, `:58`) is the gate and must not be touched to pass.
      - Done: `PerchMigration5To6Test` green; a v5 database with real rows opens at v6 with
        its entries intact and the new row present, with that test named; `./gradlew test`
        green; `git status` shows `app/schemas/6.json` added.
      - Rung: unit

- [x] **Y03 — A pasted link becomes a readable article on To-Read. TDD. Issue #23.**
      The repository path, still no UI.
      - New `suspend fun saveLink(url: String): Result<Long>` — normalize with the existing
        `normalizePastedUrl` (`PastedUrl.kt:17`), fetch with `FeedFetcher.fetch(url)`
        (`FeedFetcher.kt:72`, 8 MiB cap at `:116`), parse with jsoup, then: title and date
        from Y01's `PageMetadata`, body from `ArticleExtractor.extract` + `HtmlSanitizer`,
        lead image from `LeadImage.fromBody`/`ogImage`. **That whole chain already exists at
        `ArticleTextRepository.kt:47-67` keyed on an entry id — lift the reusable middle into
        something that returns a value object rather than writing a row, and have both
        callers use it.** Do not clone it.
      - The row: `feedId` = the synthetic feed, **`guid` = the final URL** (after redirects —
        it is the natural key and `(feedId, guid)` is unique, `EntryEntity.kt:49`),
        `isSaved = 1` with `savedAt` (`EntryEntity.kt:67-68`) so it lands on To-Read.
      - Pasting the **same link twice** must not duplicate and must not error — it is the
        same `(feedId, guid)`. Test it.
      - A link that is really a feed → say so, don't save (§0.4). A link that 404s, times
        out, or exceeds 8 MiB → a reason, never an exception (`FeedRepository.kt`'s
        `SourceResolution` at `:48-72` is the shape to copy).
      - A page with no extractable body still saves, with its title and link — the reader
        can still open it. A page with no date gets `publishedIsEstimated = true` (§0.2).
      - Done: tests named for each of duplicate, feed-not-article, unreachable, too-large,
        no-date and no-body; `./gradlew test` green with the count in the commit message.
      - Rung: unit

- [x] **Y04 — The reader pastes a link and it is on the queue. TDD. Issue #23.**
      §0.4: the affordance lives on **To-Read**.
      - `CollectionScreen.kt:68` / `:226` (empty state) / `:288` (tags) and
        `CollectionViewModel.kt` — an action on the To-Read screen that opens a sheet taking
        a URL, with busy, error and success states. **Mirror `AddSourceSheet`'s shape rather
        than inventing one**: content split from the sheet for testability
        (`AddSourceSheet.kt:109`), inline error (`:155`), one primary button (`:189`), tags
        (`:274-283`). Only To-Read gets it; `Collection.ToRead` vs `Liked`
        (`CollectionViewModel.kt:28-35`) decides.
      - Empty-state copy changes (§0.4). `CollectionScreenTest.kt:76` pins the current
        wording — rewrite it **with the reason in the commit message**. That is a rewrite,
        not a weakening; every other assertion in that file stays.
      - Strings in `res/values/strings.xml` beside the existing To-Read strings (`:141`,
        `:159-160`, `:163`). No hardcoded copy. Standing gate: no `Color(0x` / `N.dp` /
        `N.sp` outside `ui/theme/`.
      - Traps: an injected tap never reaches a node inside a bottom sheet — use
        `performSemanticsAction(OnClick)` (NOTES.md). Waiting on Room is not waiting on the
        screen: poll in wall-clock time (NOTES.md, V01).
      - Done: a test that pasting a link puts a titled row on To-Read, and one that a bad
        link shows a reason and adds nothing; `./gradlew test` green; a screenshot of the
        sheet and of To-Read carrying a pasted article under `build/perch-screenshots/`,
        **opened and looked at**.
      - Rung: screenshot

- [x] **Y05 — Review of this slice. Everything PLAN-6 changed, read as a whole.**
      Scoped to this plan; `PLAN-8` reads `v0.4.0..HEAD` entire. Read this plan's diff and
      answer, in the commit message and in NOTES.md:
      1. **Is anything in this slice true of one website?** Re-run §0.1's grep gate over
         every package this plan touched, and read `PageMetadata.kt` line by line against the
         question "would this rule fire on a site I have never seen?" This is the question
         the human asked for by name — answer it in full, not with the grep alone.
      2. Does any doc still describe the schema as version 5, or Perch as reaching only
         subscribed sources? README.md, SPEC.md, DESIGN.md, NOTES.md, CLAUDE.md.
      3. Did Y03 leave `ArticleTextRepository`'s original chain duplicated rather than
         shared? Name the one function both paths now call.
      4. Was any test weakened rather than rewritten — `CollectionScreenTest.kt:76`
         especially? Is the suite still growing, and did any change land without a test?
      Fix what is small and mechanical **in this session**; anything larger becomes a new
      issue named here.
      - Done: the four questions answered in the commit message with the command that
        settled each; `./gradlew test` green; issue #23 closed with a comment naming the
        commits and how it was verified; everything pushed.
      - Rung: unit
