# PLAN-8.md — v0.5, slice 4: read the whole thing, then ship it

**This is the active plan.** v0.1–v0.4 are complete, frozen, and history only, archived in
`docs/plans/`. `PLAN-5.md` (#22), `PLAN-6.md` (#23) and `PLAN-7.md` (#21) are the three
feature slices of v0.5 and are finished and archived too. The process is `docs/RALPH.md`.

Ordered. **Do the single next unchecked `[ ]` task, then stop.** Never check a box unless
its Done-condition literally passed in this session. Failure → 2 attempts max, then rewrite
the box as `- [BLOCKED: one-line diagnosis]`, log it to NOTES.md, and move on.

---

## §0 — Decisions for this slice (authoritative; do not re-derive)

**This plan carries the furniture the three feature slices deliberately left out**: the
version-wide review, live acceptance, and the release. Slices 1–3 each ran a review scoped
to their own diff; **none of them has read v0.5 as a whole**, and that is precisely the
defect class `docs/RALPH.md` §6 exists for — a per-task session only ever opens its own
task's files, so nobody has been looking at the version.

**The version is `0.5.0`, `versionCode` 6.** This is not the release task's judgement call
(SPEC.md §1, CLAUDE.md): v0.5 landed notable new user-visible features — a drawer that opens
collapsed (#22), pasting a link straight onto To-Read (#23), and reaching a blog's archive
behind its feed (#21) — so the **MINOR** digit moves. `versionCode` goes up by exactly 1.
Both live at `app/build.gradle.kts:12-13` (`perchVersionCode` / `perchVersionName`) and
**nowhere else**.

**v0.4.0 is installed on a real phone, and v0.5 ships a schema change** (`PLAN-6` took the
DB to version 6). The release is not done until an installed v0.4.0 is shown to upgrade in
place with its read state, likes and to-read queue intact.

---

## The tasks

- [x] **R00 — The forty pages a capped backfill fetches are the newest forty, and the offer says two numbers because there are two. TDD. Issue #24.**
      `PLAN-7` Z04's live run measured what no fixture could: `fzakaria.com` discovers **143**
      posts, **133** not yet stored, against `MAX_PAGES = 40`. Every fixture archive we have is
      *smaller* than that cap, so both of these read as correct in every existing test. Z05's
      slice review did not ask either question. Read issue #24 before starting — it carries the
      measurement and the reasoning.
      1. **`BackfillRepository.plan()` takes the first 40 in discovery order**
         (`fresh.take(MAX_PAGES)`, `BackfillRepository.kt:82`), and discovery order is *sitemap
         document order*. Z04 proved on the real site that this is not date order: the
         reporter's own 2026-07-27 post sorted **past** position 40, and that gate failed until
         it stopped asking the capped plan. A reader who accepts the offer on a large archive
         therefore receives an arbitrary 40 of 133 — possibly without the post they came for.
         Sort `fresh` by `ArchivePost.lastmod` descending before `.take(MAX_PAGES)`, unknown
         `lastmod` last (a sitemap entry may omit it), stable among equals so a
         cancel-and-resume run does not reshuffle. `MAX_PAGES` stays 40 — this task changes
         *which* 40, not how many.
      2. **The offer states one number twice.** `HomeViewModel.kt:390,403` pass
         `plan.toFetch.size` as `pageCount`, and `R.plurals.backfill_offer_body` spends it on
         both halves: *"Its archive holds N more posts than the feed gave us. Perch will fetch N
         pages."* On `fzakaria.com` that reads "holds 40 more posts" when it holds 133. The
         first half is `plan.newPostCount` — which `BackfillPlan` already carries and nothing
         reads — and the second is `plan.toFetch.size`. Give `BackfillOffer` both and make the
         plural true when they differ. Copy in `strings.xml`, no hardcoded text.
      - Do **not** raise `MAX_PAGES` to make the numbers agree. The cap is `PLAN-7` §0.3's
        politeness budget; the honest sentence is the fix, and the drawer's "Fetch older posts"
        action already lets a reader run it again for the next 40.
      - TDD: RED first, and **the RED must be larger than the cap** — a plan built from more
        than `MAX_PAGES` candidates with shuffled `lastmod`s, asserting the newest 40 come back
        newest-first and that a no-`lastmod` candidate sorts last; plus a UI case where
        `newPostCount` exceeds `toFetch.size`, asserting both numbers reach the dialog. Every
        existing test must pass unchanged.
      - This lands **before** R01 deliberately, so the version-wide review reads it.
      - Done: `./gradlew test` green with the new cases named in the commit message; `PLAN-7`
        §0.2's grep gate still finds no hostname literal under `data/`; the offer screenshot
        retaken with the two counts differing, **opened and looked at**; issue #24 closed with a
        comment naming the commit.
      - Rung: screenshot

- [ ] **R01 — The review pass. The whole of v0.5, read at once.**
      Read `git diff v0.4.0..HEAD` — **the whole of it**, not one slice's worth — and
      answer, in the commit message and in NOTES.md where it outlives the plan:
      1. **Does any doc still describe v0.4?** README.md (its feature list and its six
         screenshots — the drawer shot is stale by construction after `PLAN-5`), SPEC.md,
         DESIGN.md, NOTES.md, CLAUDE.md, `docs/RALPH.md`, against what actually shipped.
         CLAUDE.md still names `PLAN-4.md` as the active plan and says the DB is version 5 —
         both are now false.
      2. **Is anything in v0.5 true of one website?** The human set this as a hard constraint
         for the whole version: no site-specific parsing, a generalised and extensible parser,
         so that supporting one site means similar sites parse too. Re-run the standing grep
         gate — `grep -rnoE '"[a-z0-9.-]+\.(com|org|net|io|dev|me|ski|ca|xyz|blog)"'` over
         `app/src/main/java/dev/mkiros/perch/data/` — and then **read `PageMetadata.kt` and
         `ArchiveDiscovery.kt` line by line** against "would this rule fire on a site I have
         never seen?" Name every heuristic and the standard it rests on. The grep is
         necessary and not sufficient; a per-site rule can be written without naming a host.
      3. **Did any slice leave a helper, string, dimension or test tag orphaned?**
         `collapsedFolders` was scheduled to die in `PLAN-5` X01 — confirm it did, everywhere.
         Confirm the fetch→extract chain lifted out of `ArticleTextRepository.kt:47-67` has
         exactly one definition and three callers, not three copies.
      4. **Was any test weakened rather than rewritten?** `PLAN-5` X02 touched ~40 assertions
         and `PLAN-6` Y04 rewrote `CollectionScreenTest.kt:76`'s empty-state copy. Name every
         changed assertion across the version and say which replacement is at least as strong.
         `FeedCorpusTest` must be untouched.
      5. **Is the suite still growing, and did any fix land without a test?** The v0.4.0 floor
         was **1524 tests**. Report the count and check each commit that touched `src/main`
         carried tests in the same commit.
      6. **Does a date the app guessed look exactly like a date it was told?**
         `publishedIsEstimated` has been stored since v0.1 and is read by **nothing** in
         `ui/` — an estimated date renders identically to a real one. v0.5 makes that matter:
         page metadata yields a date for only 5 of 23 corpus pages (`PLAN-6` Y01), so a
         pasted link usually falls back to `fetchedAt` and the row reads **"now"** for an
         article written years ago — visible in `to-read-pasted-link.png`. `PLAN-7` §0.3a
         already forbids this for a *backfill*; it does not address the single paste.
         Decide whether the reader should be able to tell the two apart, and say so.
         **This is a feature, so it is an issue for the next version, not a fix in this
         session** — unless the answer is that it is a one-line format change.
      Fix what is small and mechanical **in this session**. Anything larger becomes a new
      GitHub issue for the next plan, named here — **do not start a feature in a review.**
      - Done: the five questions answered in the commit message, each with the command that
        settled it; `./gradlew test` green with the count; any new issue created and linked.
      - Rung: unit

- [ ] **R02 — Live acceptance v5.** Re-run the live gates against the real corpus:
      `./gradlew :app:testDebugUnitTest -Pperch.live=true --tests '*LiveAcceptance*'`.
      - Gate 1 still has **no quota**: every source in `fixtures/feeds.txt` bar
        `EXCLUDED_SOURCES` must pull — including `fzakaria.com`, which `PLAN-7` Z04 added.
      - The gates `PLAN-5` X03 and `PLAN-7` Z04 edited must pass **as edited**: gate 7 now
        opens a folder before reaching a drawer source row (folders start collapsed since
        `PLAN-5`), and Z04's archive gate asserts discovery finds materially more than
        fzakaria's 10-entry feed and that the reporter's own URL,
        `https://fzakaria.com/2026/07/27/the-mean-means-nothing`, is among them with a real
        body.
      - Add this version's own questions: a pasted link lands on To-Read with a real title
        (#23), and a backfilled entry is indistinguishable from a feed entry in the list.
      - **`research.checkpoint.com` answers 202 with an empty body when live runs come too
        close together** (NOTES.md, V15) — wait ~10 quiet minutes and rerun **without**
        probing with `curl` first, because the probe spends the allowance the rerun needs.
      - **Bounded: at most three foreground runs.** If a source still fails after the third,
        exclude it with the measurement that settled it, or mark this box BLOCKED — never
        loop. Never end the session with the run in flight.
      - Screenshots: the drawer in its new collapsed resting state, To-Read carrying a pasted
        article, and a source offering its archive. Then `./gradlew test assembleRelease` —
        **not `clean`**, which would delete `build/perch-screenshots/`, this box's evidence.
      - Done: every gate's count pasted into the commit message; the screenshots exist and
        were **opened and looked at**; the default no-network `./gradlew test` still green.
      - Rung: screenshot

- [ ] **R03 — Release v0.5.0.** Bump `perchVersionCode` 5 → **6** and `perchVersionName`
      `0.4.0` → **`0.5.0`** at `app/build.gradle.kts:12-13`, **the one place they live**.
      - `./gradlew assembleRelease` (runs `lintVitalRelease`). Signing comes from
        `~/.perch/signing.properties` (U02) — **absent it the build silently debug-signs**,
        which is why the Done-condition verifies the certificate on the file rather than
        trusting the build.
      - **Gradle writes `app-release.apk`; the rename to `perch-0.5.0.apk` is this task's
        own**, and `output-metadata.json` will still name the unrenamed file — that is the
        gap V16 fell into and W12 recorded in NOTES.md. Do not be surprised by it.
      - Release notes through `docs/RELEASE-NOTES.md`'s template; `scripts/release-notes.sh
        v0.4.0` drafts from the issues closed since that tag, and **its buckets are guesses
        that need a pass by hand**. Say plainly that **v0.4.0 → v0.5.0 installs in place and
        keeps read state, likes and the to-read queue** — v0.5 changed the schema, so this
        sentence is load-bearing and must be *verified*, not asserted.
      - Refresh README's screenshots from R02's captures — the drawer shot in particular is
        stale by construction.
      - Tag `v0.5.0`, push, `gh release create v0.5.0` with the notes file and
        `perch-0.5.0.apk` attached. Prune NOTES.md back under 100 lines.
      - Done: `gh release view v0.5.0 --json assets` lists the APK; `aapt2 dump badging` on
        the released file reads `versionCode='6' versionName='0.5.0'`; `apksigner verify
        --print-certs` prints U02's digest
        `61367c0499de5c49c824f4d7ba7b4e692d33960cc57c0622772227a8b7fce489`; the v0.4.0 → v0.5.0
        in-place upgrade verified on the device with read state intact (**`run-as` dies on a
        release build — verify through the UI, not sqlite3**, NOTES.md W12); `git status`
        clean and pushed; `grep -c '^- \[ \]' PLAN-8.md` returns 0; `gh issue list --state
        open` holds only what is genuinely still open, each with a comment saying why.
      - Rung: build
