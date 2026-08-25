# PLAN-7.md — v0.5, slice 3: a blog's past, not just its present

**This is the active plan.** v0.1–v0.4 are complete, frozen, and history only, archived in
`docs/plans/`. `PLAN-5.md` (#22) and `PLAN-6.md` (#23) are finished and archived too — this
slice **depends on `PLAN-6`'s `PageMetadata`** and must not restart it. The process is
`docs/RALPH.md`.

Ordered. **Do the single next unchecked `[ ]` task, then stop.** Never check a box unless
its Done-condition literally passed in this session. Failure → 2 attempts max, then rewrite
the box as `- [BLOCKED: one-line diagnosis]`, log it to NOTES.md, and move on.

Rungs, cheapest first: **unit** < **build** < **maestro** < **screenshot**.

**TDD is the method, not a suggestion.** Failing test first (`RED`), minimum code to pass
(`GREEN`), then tidy (`REFACTOR`).

**v0.4.0 is installed on a real phone.** Backfilled entries belong to the **real** feed, so
`entries.feedId` is already satisfied and **this plan needs no migration**. If a task
believes it does, the task grew — say so on the issue before writing one.

---

## §0 — Decisions for this slice (authoritative; do not re-derive)

### 1. What #21 actually is — measured, not guessed

The reporter added `https://fzakaria.com` and saw almost none of its history. **Perch was
behaving correctly.** Measured 2026-08-24:

- `https://fzakaria.com/feed.xml` — HTTP 200, 263,230 bytes, Atom, **exactly 10 `<entry>`
  elements**, newest `2026-08-23`, oldest **`2026-07-30`**.
- The **site** has **142 dated posts** back to `2020/03/31` (`sitemap.xml`): 36 in 2026, 42
  in 2025, 26 in 2024, 23 in 2020.
- **The post the issue links — `https://fzakaria.com/2026/07/27/the-mean-means-nothing` —
  is HTTP 200 on the site and appears ZERO times in the feed.** It is three days older than
  the feed's oldest entry.

Ruled out with anchors, so no session re-investigates: no ingest cap
(`FeedRepository.kt:337` only de-duplicates; `EntryDao.upsertAll` `:388` is unbounded);
retention not implicated (`EntryDao.kt:266-272` requires `isRead = 1`); "All time" genuinely
unbounded (`TimeFilter.kt:48` returns null, `EntryDao.kt:55` short-circuits); the 8 MiB cap
untriggered and never silent (`FeedFetcher.kt:107`, `:118`).

**So the fix is not in the ingest path. It is to reach past the feed.**

### 2. The rule that outranks every other decision in this plan

**No site-specific parsing. Ever.** Perch must not become a set of blogs it knows about,
where a new blog means a new layout means a code change and a PR. **Every** discovery
mechanism here is a **published standard**; there is no per-host branch, no per-engine
branch, and no "if it looks like Jekyll".

- **robots.txt `Sitemap:`** — RFC 9309 §2.2.3.
- **`sitemap.xml` and sitemap *index* files** — the sitemaps.org protocol, including
  `<lastmod>`, and index files that point at further sitemaps (recurse, bounded).
- **RFC 5005 archived feeds** — `<link rel="prev-archive">` / `rel="next-archive"` in the
  feed itself. fzakaria.com does **not** publish these, so it will not help *this* feed —
  implement it anyway, because where it exists it is the most correct and cheapest source,
  and it costs one link-relation lookup.
- **`<link rel="alternate">`** — already how Perch discovers feeds from a homepage
  (`data/parse/FeedDiscovery.kt`); reuse it, do not restate it.

Titles and dates for backfilled posts come from **`PLAN-6`'s `data/extract/PageMetadata.kt`**,
which is already standards-only and already held to the 23-fixture corpus. **Do not add a
second metadata path here.** If a backfilled page parses badly, fix `PageMetadata` and the
corpus test that covers it — every other site gains from the same change. That is the whole
point.

Enforced, as Done-conditions:

- **The standing grep gate extends to this plan's packages.** No hostname or brand literal:
  `grep -rnoE '"[a-z0-9.-]+\.(com|org|net|io|dev|me|ski|ca|xyz|blog)"'` over
  `data/extract/` and any new package must return **nothing**. `fixtures/` and tests are
  exempt — a fixture *is* a site, naming one there is the point.
- **A rule that lifts one site and moves no other is aimed at a site. Delete it.**

### 3. Backfill is opt-in, bounded, and polite

**Never automatic.** A source added over mobile data must not silently pull 142 pages.
Perch *offers*; the reader accepts.

- **The offer is earned, not constant.** After a source is added, Perch knows the feed's
  reach (§0.4). It offers a backfill only when the archive plainly holds materially more
  than the feed did.
- **Bounded by a stated number of pages**, decided by the task and named in `§0` of the
  commit, with the reader told how many will be fetched **before** it starts.
- **Polite:** requests are serialised with a delay between them, never parallel; `robots.txt`
  is honoured, including `Disallow`; the existing `User-Agent`
  (`PerchHttp.kt:35`) identifies Perch. The 8 MiB per-page cap (`FeedFetcher.kt:118`) already
  applies and already fails loudly.
- **Interruptible and resumable.** It runs as work, not as a screen — a backfill must
  survive the reader leaving the screen, and must stop when they ask. Progress is visible.
- **Idempotent.** A page already stored is `(feedId, guid)`-identical and is skipped, so
  running a backfill twice costs fetches and changes nothing. `guid` = the final URL, the
  same convention `PLAN-6` Y03 set for pasted links.

### 3a. A backfilled post without a date must never claim to be new

Measured in `PLAN-6` Y01: page metadata yields a date for only **5 of 23** corpus fixtures,
because many blogs publish no `article:published_time`, no JSON-LD `datePublished` and no
dated URL path. That is fine for a single pasted link. It is **not** fine for a backfill:
`publishedAt = fetchedAt` on 132 archived posts would date the whole archive "now" and bury
the reader's actual feed under years of old writing pretending to be today's.

So, for backfill only, the date chain gains a rung and loses its default:

1. `PageMetadata`'s own chain (§0.2 of `PLAN-6`) — unchanged, it wins.
2. **The sitemap's `<lastmod>` for that URL**, which Z01 already collects and which is a
   published part of the sitemaps.org protocol. Carry it through discovery into the write.
3. If both decline, the entry is stored with `publishedIsEstimated = true` and a date that
   **sorts it as old, never as new** — it must not appear above a genuinely recent entry.
   A backfill is history; an undated piece of history is still history.

**Never** stamp a backfilled entry with `fetchedAt` as though it were a publication date.
Test it directly: a backfilled entry with no discoverable date does not appear above a
feed entry published today.

### 4. "All time" must stop over-promising

Even with backfill, a reader deserves to know what they are looking at. A source records
**how far its feed actually reaches** and Perch can say so — the honest version of the
sentence #21 was really about. This is small, and it is the part that helps every short feed
whether or not anyone runs a backfill.

### 5. This plan is one slice of v0.5

No version bump, no release; both live in `PLAN-8` with the version-wide review over
`git diff v0.4.0..HEAD`. The review box here is scoped to this plan's own diff.

---

## The tasks

- [x] **Z01 — A site's archive is discoverable, by standards alone. TDD. Issue #21.**
      `gh issue view 21 --json body` first (bare `gh issue view` dies — NOTES.md).
      New pure module — `data/archive/ArchiveDiscovery.kt` — that turns a site URL into a
      list of candidate post URLs with optional `lastmod` dates. **No network in this
      task's own code path beyond the injected fetcher; no DB.** Read §0.2 first.
      - Implement, in this order of preference: RFC 5005 `prev-archive` from the feed →
        `robots.txt` `Sitemap:` → `/sitemap.xml` → sitemap **index** recursion (bounded depth
        and count, both named constants with the reason in a comment).
      - Reuse the injected `PageFetcher` (`FeedDiscovery.kt:16-18`,
        `FetchedPage(bytes, contentType, finalUrl)` at `:8-13`) so this is testable with no
        network, and `FeedFetcher.fetch(url)` (`FeedFetcher.kt:72`) in production.
      - RED first, and test the **shapes**, not one host: a flat sitemap; a sitemap index
        pointing at two children; a `robots.txt` naming a sitemap at a non-default path; a
        gzipped sitemap (`.xml.gz` is normal and in the protocol); a site with none of them
        (→ empty, not an exception); a sitemap containing non-post URLs (tags, pages) that
        must not be mistaken for posts.
      - **Distinguishing a post from a page is the one genuinely hard call — make it
        structural.** A dated URL path, `<lastmod>`, and presence in the feed are all general
        signals. **Do not** hardcode path prefixes that happen to work for one engine.
      - Store nothing yet. This task ends with URLs and dates in memory and tests over them.
      - Done: the six shape-tests named and green; §0.2's grep gate returns nothing, command
        pasted; `./gradlew test` green with the count in the commit message.
      - Rung: unit

- [ ] **Z02 — A feed's reach is known, and a source can be filled in behind it. TDD. Issue #21.**
      §0.3 and §0.4. The repository and worker path; still no UI.
      - **Reach first** (§0.4): after a fetch, a source knows its feed's oldest entry and how
        many entries the feed carried. Prefer deriving it from rows already stored over adding
        a column; if a column is genuinely needed, that is a migration and this plan says it
        should not be — raise it on the issue rather than writing one quietly.
      - **Backfill**: given a source, run Z01's discovery, drop URLs already stored
        (`(feedId, guid)`, `guid` = final URL), then for each remaining URL fetch → jsoup →
        `PageMetadata` (title, date) + `ArticleExtractor` + `HtmlSanitizer` for the body.
        **That chain is the function `PLAN-6` Y03 lifted out of `ArticleTextRepository.kt:47-67`
        — call it. Do not clone it, and do not add a second metadata path (§0.2).**
      - Entries are written under the **real** feed's `feedId`, with
        `publishedIsEstimated = true` where the date was inferred. They are **not** marked
        saved and **not** marked read — they are ordinary history.
      - **§0.3a is a Done-condition, not advice**: carry the sitemap's `<lastmod>` from Z01's
        discovery into the write as the second date rung, and never stamp a backfilled entry
        with `fetchedAt` as if it were a publication date. The named test: a backfilled entry
        with no discoverable date does not sort above an entry published today.
      - Bounded, serialised, polite, interruptible, resumable, idempotent — every clause of
        §0.3 gets a test, including: a second run over the same site stores nothing new; a
        cancelled run leaves what it had already stored; `robots.txt` `Disallow` is obeyed;
        the page cap stops it; one page failing does not abandon the rest.
      - Run it as work (`work/`, alongside the existing refresh worker) so it survives the
        screen — mirror that worker's shape rather than inventing scheduling.
      - **Bounded verification:** unit tests with a stubbed fetcher only. **No live network
        in this task** — Z04 does that, once, deliberately.
      - Done: every §0.3 clause named as a passing test; `./gradlew test` green with the
        count in the commit message.
      - Rung: unit

- [ ] **Z03 — Perch offers the archive, says what it will cost, and shows it working. TDD. Issue #21.**
      §0.3's offer and §0.4's honesty, in the UI.
      - The offer after adding a source whose archive holds materially more than its feed —
        naming **how many pages** will be fetched before the reader agrees. Also reachable
        later from the source's long-press sheet in the drawer, so a reader who declined can
        change their mind. Drawer source rows and their long-press live in `HomeScreen.kt:969`
        (`SourceRow`) — and note **`PLAN-5` made folders start collapsed**, so any test must
        open the folder first with that plan's `expandInDrawer` helper.
      - Progress while it runs, and a way to stop it. A reader must never be unable to tell
        whether Perch is working.
      - §0.4's sentence where a reader meets the confusion: the source shows how far its feed
        reaches, so "All time" stops implying "all history".
      - Strings in `res/values/strings.xml`; no hardcoded copy. Standing gate: no `Color(0x`
        / `N.dp` / `N.sp` outside `ui/theme/`.
      - Traps: an injected tap never reaches a node inside a drawer sheet or bottom sheet —
        `performSemanticsAction(OnClick/OnLongClick)` (NOTES.md). Waiting on Room is not
        waiting on the screen — poll in wall-clock time (NOTES.md, V01).
      - Done: tests for the offer appearing only when earned, for the count being stated, for
        cancellation, and for the reach sentence; `./gradlew test` green; a screenshot of the
        offer and of a backfill in progress under `build/perch-screenshots/`, **opened and
        looked at**.
      - Rung: screenshot

- [ ] **Z04 — The reporter's own blog, live, and the post they linked. Issue #21.**
      The one task in this plan that touches the network. **Bounded: at most three
      foreground runs**; if it still fails after the third, mark this box BLOCKED with the
      measurement — never loop.
      - Add `https://fzakaria.com/feed.xml` to `fixtures/feeds.txt`. **This is a commitment:**
        live gate 1 has no quota, so every source in that file bar `EXCLUDED_SOURCES` must
        pull forever (NOTES.md, V12/#8).
      - Add a live gate that asserts the thing #21 is about: discovery finds **materially more
        posts than the feed's 10** for that site, and **`https://fzakaria.com/2026/07/27/the-mean-means-nothing`
        — the exact URL from the issue — is among them and extracts a real body**. That URL
        is the acceptance criterion in the reporter's own words; name it in the gate.
      - Assert the *shape*, not the number 142 — a blog gains posts. "Materially more than
        the feed carried" is the invariant; a hardcoded count would rot.
      - **Respect §0.3 in the gate itself**: serialise, delay, obey robots.txt, and fetch a
        small bounded sample of pages, not the whole archive. A live gate that pulls 142 pages
        on every run is a bad citizen and a slow suite.
      - Note for the session: `research.checkpoint.com` answers 202-empty when live runs come
        too close together, and **a `curl` probe spends the allowance the rerun needs** — wait
        ~10 quiet minutes and rerun without probing (NOTES.md, V15).
      - Done: the gate's counts pasted into the commit message, including how many posts
        discovery found versus the feed's entry count and that the reporter's URL was among
        them; the default no-network `./gradlew test` still green.
      - Rung: build

- [ ] **Z05 — Review of this slice. Everything PLAN-7 changed, read as a whole.**
      Scoped to this plan; `PLAN-8` reads `v0.4.0..HEAD` entire. Read this plan's diff and
      answer, in the commit message and in NOTES.md:
      1. **Is anything in this slice true of one website?** Re-run §0.2's grep gate over every
         package this plan touched, and read `ArchiveDiscovery.kt` line by line against
         "would this fire on a site I have never seen?" Name every heuristic and say which
         standard or cross-site convention it rests on. **This is the question the human asked
         for by name; the grep alone does not answer it.**
      2. Did Z02 add a second metadata or extraction path beside `PageMetadata` /
         `ArticleExtractor`? Name the one function every caller shares.
      3. Does any doc still say Perch reaches only what a feed publishes? README.md, SPEC.md,
         DESIGN.md, NOTES.md, CLAUDE.md.
      4. Was any test weakened rather than rewritten? Is the suite still growing, and did any
         change land without a test?
      Fix what is small and mechanical **in this session**; anything larger becomes a new
      issue named here.
      - Done: the four questions answered in the commit message with the command that settled
        each; `./gradlew test` green; issue #21 closed with a comment naming the commits, the
        measurement from §0.1, and how it was verified; everything pushed.
      - Rung: unit
