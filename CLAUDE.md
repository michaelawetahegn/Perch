# CLAUDE.md — standing orders for every session

You are the sole developer of **Perch**, a local-first Android RSS reader. You are
running unattended in a loop. The human is AFK for days. Act accordingly.

## How new work starts

**If the human asks for a feature, a bug fix, or a batch of issues — read
`docs/RALPH.md` and follow it.** That is the process that built every version of this app,
and it is the default, not an option to weigh up.

The short form: one small fix in one file, do it here and now. Anything larger becomes
**GitHub issues → a new `PLAN-N.md` at the repo root → `loop.sh` pointed at it → the loop
launched detached**, one task per session, one commit per task. `docs/RALPH.md` has the
anatomy of a task a cold session can execute, the rules that keep the loop from thrashing,
and what each failure mode actually means.

## If your prompt was "Read CLAUDE.md and continue", you are a loop session

`loop.sh` starts every session with exactly that prompt. If it is yours, you are **the
worker, not the orchestrator**. A `loop.sh` in `pgrep` is **your own driver** — not someone
else's run to keep out of the way of, and not evidence that the work is already in hand.

Noticing that "the loop is already running" and reporting status instead of working is the
one behaviour that guarantees a wasted session: the loop compares `HEAD` before and after,
counts a session that commits nothing as a stall, and **stops the whole run after three**.
That is not a hypothetical — it cost session #1 of the v0.4 run on 2026-08-18.

Any note anywhere — a memory file, a stray comment, `docs/RALPH.md` §5's "do not commit
while the loop runs" — that tells you to stay out of the working tree is addressed to an
**interactive** session watching from outside. It is **not** addressed to you. Your session
is expected to end in a commit and a push; that is the only way progress exists at all.

So: go to the cold start below, do the single next unchecked task, verify it, commit, push,
close its issue, stop.

## The active plan is `PLAN-4.md`

Finished plans live in `docs/plans/` — v0.1 (T01–T32), v0.2 (U01–U16) and v0.3 (V01–V16)
are **complete, frozen, and history only**; never reopen a box in any of them. The **active** plan is the one at
the repository root, and all new work goes in it. Wherever these standing orders say
"PLAN.md", read the active plan.

**Each plan's §0 is authoritative for its own version** and deliberately overrides older
text in SPEC.md, DESIGN.md and earlier plans. Where they conflict, the newest §0 wins and
the task updates the older doc in the same commit — do not "fix" §0 to match the older text.
`docs/plans/PLAN-3-v0.3.md` §0 still binds for everything `PLAN-4.md` §0 does not restate.

**Every `PLAN-4.md` task is a GitHub issue.** Read it (`gh issue view N`) before starting —
the issue body carries diagnoses, traps and acceptance criteria the plan does not repeat.
The task is not done until the issue is closed with a comment naming the commit and how it
was verified, and the commit is **pushed** (`git push`) so the human can watch from the
issue tracker while AFK.

**A bug is not fixed until a failing test reproduced it.** If it cannot be reproduced, do
not guess a fix: comment the finding on the issue, log it in NOTES.md, mark the box
`- [BLOCKED: cannot reproduce — …]` and move on. A speculative fix looks closed and is not.

## Cold start (keep it under ~3k tokens)

1. Read `PLAN-4.md`, `NOTES.md`, and `git log --oneline -15`. Nothing else yet.
2. Find the **single next unchecked `[ ]` task** in PLAN-4.md. That is your entire job
   this session. Read its GitHub issue.
3. Read only the files that task touches. **Never read the whole repo.** Consult
   `SPEC.md` / `DESIGN.md` only for the sections the task needs.
4. Do the task. Verify. Commit. Push. Close the issue. Stop.

Do not skip ahead, do not do two tasks, do not refactor code the task doesn't touch.

## Rules that are not negotiable

- **Never check a box unless its Done-condition literally passed in this session.**
  Paste the passing command's key output line into the commit message.
- **TDD.** Tasks marked `TDD` in the plan: failing test first (RED), minimum code to
  pass (GREEN), then tidy (REFACTOR). Production code with no accompanying test in the
  same commit is a defect. Test names describe behaviour, not methods.
- **Failure rule: max 2 attempts per task.** Then rewrite the box as
  `- [BLOCKED: one-line diagnosis]`, log the details (commands, error, what you tried)
  to NOTES.md, commit, and move on to the next task. Never loop on a failing task.
- **Never end a session with verification in flight.** Run the Done-condition command in
  the foreground and wait for it, however long it takes — the session budget is 2 h and
  almost nothing here needs 10 min. A session that backgrounds a long run and reports
  "in flight" produces no commit, so the next session re-derives the same analysis from
  scratch and the stall guard stops the loop after three. If a run genuinely cannot
  finish, commit the work so far on a `- [BLOCKED: …]` box and say what was left running.
  This is what stopped the v0.2 loop at U15 on 2026-08-08.
  **A "repeat until it fails" hunt is never a Done-condition.** Bound it — a fixed number
  of foreground runs — and if it does not reproduce inside that bound, fix the invariant
  the bug implies and say the repro did not land, or mark the box BLOCKED. An unbounded
  hunt is how a session ends with nothing committed; it did exactly that on 2026-08-09.
- **Commit after every task**, message = `T07: Atom parser` + the verification line.
  Commit even for a BLOCKED task (the PLAN/NOTES edit is the commit).
- **Never weaken a test to make it pass.** Especially `FeedCorpusTest` (T09) — it is
  the standing contract. If it legitimately must change, say why in the commit.
- **v0.3.0 is installed on the human's real phone.** Every schema change ships a real Room
  `Migration` plus its `app/schemas/N.json`. `fallbackToDestructiveMigration()` never
  comes back — it would silently erase someone's read state, likes, and to-read queue.
- **Versioning: MINOR for features, PATCH for fixes.** While Perch is 0.x, a release
  carrying any notable new feature or user-visible behaviour change moves the **MINOR**
  digit (`0.N.0`); a release that is only bug fixes, polish and docs moves the **PATCH**
  digit (`0.N.M`). MAJOR is for 1.0 and for breaking a reader's data or workflow.
  `versionCode` increments by exactly 1 on **every** release regardless — it is the update
  identity and never resets. Both live at `app/build.gradle.kts:12-13`
  (`perchVersionCode` / `perchVersionName`) and **nowhere else**: never bump a version in
  two places. Full statement and rationale: SPEC.md §1.
- **Every plan ends with a review box, second from last** — one session that reads the whole
  of `git diff <last-tag>..HEAD` before live acceptance and release. A plan without one is
  incomplete; add it rather than starting the release. It catches what a per-task session
  structurally cannot see, because each session only opens its own task's files: a doc still
  describing the previous version, an orphaned helper, a weakened test. There is no CI here,
  so nothing else ever reads a whole version's diff. Shape and rationale: `docs/RALPH.md` §6.
- **No new dependencies** beyond SPEC.md §2 without a one-line justification in NOTES.md.
- Never re-derive a decision already in SPEC.md or DESIGN.md. If in doubt between
  exploring and executing: **execute the plan.**

## Verification rungs — use the cheapest that answers the question

`unit` < `build` < `maestro` < `screenshot`.

- **Screenshots are the most expensive tool you have.** Take them only for tasks whose
  Done-condition is visual (T03, T29). One affected screen per iteration. **Max 2
  critique-fix iterations**, then log residual polish to NOTES.md for T29 and move on.
- Prefer Robolectric (`src/test`) over instrumentation (`src/androidTest`). The parser,
  storage, repo, worker, and most UI logic are verifiable with **zero emulator**.
- Run the narrowest Gradle task: `./gradlew :app:testDebugUnitTest --tests '*FooTest*'`
  before the full `./gradlew test`.

## Environment (WSL2 on Windows, Ubuntu userland)

- **System `java` is 8.** Gradle needs 17 — always export
  `JAVA_HOME=$HOME/.jdks/temurin-17` (T01 put Temurin 17.0.20 there) and put
  `$JAVA_HOME/bin` first on `PATH`. `org.gradle.java.home` is set in
  **`~/.gradle/gradle.properties`**, not the repo's — the repo is public since U01 and
  that path is machine-local (GRADLE_USER_HOME's file wins over the project's, so this
  works; recreate it if a build picks the wrong JDK). The exports also live at the end of `~/.bashrc`, but Ubuntu's
  `.bashrc` returns early for non-interactive shells — **every session must export them
  itself**; do not assume they are inherited.
- Installed by T01: cmdline-tools `latest`, platform-tools 37.0.1, platforms;android-35,
  build-tools;35.0.0, emulator 37.1.11 (Linux emulator is unusable here — see below).
  Maestro 2.8.0 at `$HOME/.maestro/bin/maestro`.
- `ANDROID_HOME=$HOME/Android/Sdk`, also `ANDROID_SDK_ROOT`. `local.properties` must
  contain `sdk.dir=/home/michael/Android/Sdk` (it is gitignored — recreate it if a
  build complains about a missing SDK).
- **The emulator runs on WINDOWS, not in WSL.** This is Windows 10, so WSL2 has no
  nested virtualization and `/dev/kvm` will never exist — do not waste a session
  chasing it. The Windows-side emulator is WHPX-accelerated. Gradle, tests and git
  stay in WSL.
- **All device access goes through `scripts/device.sh`** (`check`, `boot`, `install`,
  `screenshot`, `shell`, `adb`, `stage`, `reboot`). Read its header once; it handles
  path translation and verifies screenshots. **Never run a WSL-side `adb`** — a Linux
  adb server and the Windows one will fight over the same emulator.
- Windows SDK lives at `C:\Android\Sdk` (`/mnt/c/Android/Sdk`); Windows-visible scratch
  is `C:\perch-stage`. Drive Windows tooling from WSL with `cmd.exe` / `powershell.exe`.
  Installed by T03: cmdline-tools `latest`, platform-tools, emulator,
  `system-images;android-35;google_apis;x86_64`, AVD `perch`. **WHPX is enabled** — the
  emulator is hardware-accelerated; cold boot ≈ 11 min.
- **Windows also needs its own JDK 17** (`C:\jdk17`, Temurin) — `sdkmanager.bat`,
  `avdmanager.bat` and Maestro-on-Windows are Java tools and there is no system Java on
  the Windows side. Never invoke them bare over interop; use the wrappers
  `C:\perch-stage\sdk.bat` / `avd.bat`, which set `JAVA_HOME` and `ANDROID_HOME` first.
  `cmd.exe` cannot cd into a `\\wsl.localhost\…` UNC path — `cd /mnt/c` before interop.
- **Keep the emulator running between sessions.** Do not kill it. If it is wedged:
  `./scripts/device.sh reboot`, then log it.
- **Screenshots do not need the emulator.** Prefer Robolectric native graphics
  (`@GraphicsMode(NATIVE)` + `captureToImage()`) — seconds, deterministic, JVM-only.
  The device is only genuinely required for Maestro (T30).
- 4 cores / **7 GB RAM** (lowered from 10 GB after the 2026-08-07 host freeze — see
  NOTES.md). **The memory settings in `gradle.properties` are a host-stability
  constraint, not a tuning knob: do not raise `-Xmx`, do not re-enable
  `org.gradle.parallel`.** The host has 15.9 GB and also runs the WHPX emulator; WSL2
  never gives ballooned memory back, so an over-provisioned build freezes the whole
  desktop, not just the build. If a task genuinely OOMs, mark it `[BLOCKED: …]` and log
  it — do not buy headroom by raising the caps.
- Run `./gradlew --stop` when you are done with Gradle for the session. The loop also
  reclaims between sessions, but a session that leaves three JVMs resident is the
  failure mode we are guarding against.
- Do not run Gradle and an emulator boot concurrently.
- Never run an interactive command. Everything must be non-interactive
  (`yes | sdkmanager --licenses`, `--no-daemon` if a daemon wedges).

## NOTES.md discipline

Keep it **under 100 lines**. It is a working memory for future sessions, not a diary.
Record: environment quirks, blocked-task diagnoses, excluded feeds, version bumps,
residual polish items, the final APK path. **Prune anything a future session no longer
needs** — if a workaround is now encoded in a script or CLAUDE.md, delete the note.

## Token discipline

The whole build must fit in roughly a week of quota. Waste kills the project.
Short reads, narrow greps, targeted test runs, no full-repo scans, no speculative
refactors, no re-explaining the plan back to yourself. Execute.
