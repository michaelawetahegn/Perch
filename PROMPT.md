# PROMPT.md — Bootstrap: Local-First Android RSS Reader, Fully AFK Build

You are Claude Code, running as the sole developer of this project. Read this entire
file carefully. Your job in THIS session is NOT to build the app. Your job is to
produce the scaffolding that lets an unattended loop of future Claude Code sessions
build it while I am AFK for days. When this session ends, everything below under
"Deliverables of this bootstrap session" must exist, be consistent, and be
sufficient for a fresh session with zero chat history to make progress.

---

## 1. Mission

Build **a complete, polished, local-first Android RSS reader** that I will sideload
as an APK and use as my daily driver. No server, no accounts, no cloud sync. All
state lives on-device (Room/SQLite). The app fetches feeds directly.

**Definition of done:** `app/build/outputs/apk/debug/app-debug.apk` builds cleanly,
installs on the emulator, and every feature below works end-to-end against the real
feed list in §7, verified by tests and by your own screenshot review.

## 2. Environment — read this before writing the plan

- I am running you inside **WSL2 on Windows**. Assume a bare Ubuntu userland.
- There is **no physical Android device attached**. You must use a **headless
  emulator** for all install/UI/screenshot work.
- Therefore, **Task 1 in PLAN.md must be full environment setup**, self-driven:
  - Install JDK 17, Android cmdline-tools, `sdkmanager`, platform-tools,
    a recent platform (API 34/35), build-tools, and an emulator system image.
    Accept all licenses non-interactively (`yes | sdkmanager --licenses`).
  - Check `/dev/kvm`. If present, use an x86_64 image with
    `-no-window -no-audio -gpu swiftshader_indirect`. If absent, write the fact
    to NOTES.md, attempt the non-KVM path anyway, and record boot time — do not
    silently stall.
  - Create an AVD, boot it, wait for `adb wait-for-device` + `sys.boot_completed`,
    and prove the pipeline with a hello-world screenshot via
    `adb exec-out screencap -p`. Set `adb shell wm size 540x1200` so screenshots
    stay small.
  - Install **Maestro** for declarative UI flows.
  - The environment task is only "done" when a trivial Compose app has been built,
    installed, launched, and screenshotted headlessly. Everything else depends on
    this; do not let later tasks begin until it is checked.
- Persist any environment quirks (paths, env vars, workarounds) into CLAUDE.md so
  future sessions don't rediscover them.

## 3. Product requirements

Core features every real RSS reader needs — all required:

1. **Add source** by pasting a URL. Must handle both direct feed URLs and homepage
   URLs via feed auto-discovery (`<link rel="alternate">`).
2. **Remove source** (with confirm), and rename source.
3. **Unified unread view** — everything new across all sources, newest first.
4. **Per-source view** — tap/filter to see only one source's posts.
5. **Read state** — mark read on open, mark-all-read, unread counts per source.
6. **Refresh** — manual pull-to-refresh + periodic background refresh (WorkManager),
   conditional GET (ETag/Last-Modified) so refreshes are cheap.
7. **Article view** — render entry content readably in-app; open-in-browser action.
8. **Robust parsing** — RSS 2.0, Atom, RDF; broken dates, weird encodings, missing
   GUIDs, HTML-in-titles. Never crash on a malformed feed; surface per-source
   error states in the UI.
9. **OPML import/export** (this is how I'll bring my real subscriptions in).
10. Sensible states everywhere: empty, loading, error, offline.

**Stack (non-negotiable, do not deliberate):** Kotlin, Jetpack Compose, Material 3,
Room, WorkManager, OkHttp. Min SDK 26+. Single module is fine.

## 4. Design mandate

Design comes from **your own intuition** — I will not review mockups. Before any UI
work, write **DESIGN.md** yourself: your chosen visual direction (Material 3 dynamic
color, typography scale, list density, dark mode required), navigation structure,
and an explicit checklist of what "feels like a real app" means (touch targets,
motion, empty states, no debug artifacts, consistent spacing). Open-source readers
**Feeder** and **Read You** are your quality bar — study their layouts from memory
and aim at that tier. During UI tasks you will critique your own screenshots
**against DESIGN.md**, not against vibes.

## 5. Deliverables of this bootstrap session

Produce all of the following, then stop:

1. **SPEC.md** — condensed product spec (from §3), exact package structure, file
   layout, and library versions. Decide everything now so no future session
   deliberates.
2. **DESIGN.md** — as in §4.
3. **PLAN.md** — the entire build as ordered checkbox tasks. Requirements:
   - Task 1 = environment setup (§2). Task 2 = project skeleton + CI-style
     `./gradlew test assembleDebug` proven green. Task 3 = fixture harvest (§7).
   - Foundations before UI: parser + storage + read-state fully unit-tested
     before the first screen exists.
   - Every task small (one session), with an **unambiguous, machine-checkable
     done-condition** ("all 40 fixtures parse; `./gradlew test` green"), and a
     verification rung: unit test < build/Maestro assertion < screenshot. Use the
     cheapest rung that answers the question.
   - One consolidated **design-polish pass near the end** (screenshot-driven),
     rather than polishing every intermediate state.
   - Final task: clean build, full Maestro regression flow (add source → refresh →
     read → filter → remove → OPML export), APK path written to NOTES.md.
4. **CLAUDE.md** — standing orders auto-read by every future session:
   - Cold start: read PLAN.md, NOTES.md, `git log --oneline -15`. Do the single
     next unchecked task. Read only files relevant to it — never the whole repo.
   - Never check a box unless its done-condition passed. Commit after every task
     with a message naming the task.
   - **Failure rule:** max 2 attempts per task, then mark it `[BLOCKED: one-line
     diagnosis]` in PLAN.md, log details to NOTES.md, move on.
   - **Screenshot budget:** screenshots only for tasks whose done-condition is
     visual; one affected screen per iteration; max 2 critique-fix iterations,
     then log residual polish to NOTES.md for the polish pass.
   - Keep NOTES.md under 100 lines — prune anything a future session no longer
     needs. Keep the emulator running between sessions; if adb is wedged, run
     `adb kill-server && adb start-server`, then reboot the emulator, then log.
5. **loop.sh** — the Ralph loop. Requirements:
   - Runs `claude -p "Read CLAUDE.md and continue" --dangerously-skip-permissions`
     repeatedly, logging all output to `loop.log` with timestamps.
   - **Exit** when PLAN.md has no `[ ]` left (excluding `[BLOCKED]`).
   - **Usage-limit aware:** if output matches usage/rate-limit patterns
     (case-insensitive: "usage limit", "rate limit", "limit reached", "try again"),
     sleep 30 minutes and retry — indefinitely. This must survive both the ~5-hour
     window and the weekly reset: never crash out on a limit, just nap and retry
     until progress resumes.
   - **Stall guard:** if 3 consecutive sessions end with no new commit, stop the
     loop and print a loud message — silent thrash is worse than stopping.
   - Boot (or verify) the emulator before the first session; nightly
     `adb reboot` as insurance.
   - 30s pause between normal sessions.
6. **NOTES.md** — created empty with a header explaining its purpose.
7. **fixtures/feeds.txt** — the exact list from §7, one URL per line.

Do NOT start Task 1 yourself in this session. Bootstrap, verify the files are
mutually consistent, `git init` + first commit, print "READY — run ./loop.sh",
and stop.

## 6. Fixture protocol

Task 3 of the plan: fetch every URL in `fixtures/feeds.txt` with curl, follow
redirects, run feed auto-discovery for homepage URLs, and snapshot each resolved
feed's raw XML into `fixtures/snapshots/`. Feeds that are dead or unreachable get
one retry, then a note in NOTES.md and exclusion — do not stall on them. These
snapshots are the permanent parser test corpus: **"every snapshot parses into
correct title/date/link/content" is a standing unit test** that must stay green
for the rest of the build. Bundle a subset into the debug APK as pre-seeded
sources so the emulator UI always renders realistic content for screenshots.

## 7. Real-world source list (my actual interests: compilers, RE, quant)

Some entries are direct feed URLs, some are homepages needing auto-discovery —
that asymmetry is intentional and is itself a test of feature §3.1.

### Compilers, PL, systems
1. https://fabiensanglard.net/rss.xml
2. https://simonwillison.net/atom/everything/
3. https://blog.llvm.org/index.xml
4. https://blog.rust-lang.org/feed.xml
5. https://v8.dev/blog.atom
6. https://nullprogram.com/feed/
7. https://eli.thegreenplace.net/feeds/all.atom.xml
8. https://blog.regehr.org/feed
9. https://bernsteinbear.com/feed.xml
10. https://fasterthanli.me/index.xml
11. https://lemire.me/blog/feed/
12. https://devblogs.microsoft.com/oldnewthing/feed
13. https://lexi-lambda.github.io/feeds/all.atom.xml
14. https://xania.org (Matt Godbolt — auto-discover)
15. https://danluu.com/atom.xml
16. https://jvns.ca/atom.xml
17. https://rachelbythebay.com/w/atom.xml
18. https://ciechanow.ski/atom.xml
19. https://www.hillelwayne.com (auto-discover)

### Reverse engineering & security
20. https://googleprojectzero.blogspot.com/feeds/posts/default
21. https://blog.trailofbits.com/feed/
22. https://research.checkpoint.com/feed/
23. https://security.googleblog.com/feeds/posts/default
24. https://research.nccgroup.com (auto-discover)
25. https://www.hexacorn.com/blog/feed/
26. https://krebsonsecurity.com/feed/
27. https://secret.club/feed.xml
28. https://blog.ret2.io/feed.xml
29. https://doar-e.github.io/feeds/rss.xml
30. https://0xdf.gitlab.io/feed.xml

### Quant, math + money + programming
31. https://robotwealth.com/feed        (verified live)
32. https://financial-hacker.com/feed   (verified live)
33. https://quantocracy.com/feed/
34. https://moontower.substack.com/feed
35. https://possiblywrong.wordpress.com/feed/
36. https://entropicthoughts.com/feed.xml
37. https://quantpedia.com/feed/
38. https://qoppac.blogspot.com/feeds/posts/default   (Investment Idiocy)
39. https://epchan.blogspot.com/feeds/posts/default
40. https://alphaarchitect.com/feed/
41. https://allocatesmartly.com/feed/
42. https://gwern.net (auto-discover)

## 8. Token discipline (applies to every future session — encode it in CLAUDE.md)

The whole build must fit in roughly a week of usage quota. Waste kills the project:
- Cold starts stay under ~3k tokens: short PLAN/NOTES, no full-repo reads.
- Verify on the cheapest rung; screenshots are the most expensive tool you have.
- Never loop on a failing task; never re-derive decisions already in SPEC.md.
- If in doubt between exploring and executing: execute the plan.
