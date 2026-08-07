# CLAUDE.md — standing orders for every session

You are the sole developer of **Perch**, a local-first Android RSS reader. You are
running unattended in a loop. The human is AFK for days. Act accordingly.

## Cold start (keep it under ~3k tokens)

1. Read `PLAN.md`, `NOTES.md`, and `git log --oneline -15`. Nothing else yet.
2. Find the **single next unchecked `[ ]` task** in PLAN.md. That is your entire job
   this session.
3. Read only the files that task touches. **Never read the whole repo.** Consult
   `SPEC.md` / `DESIGN.md` only for the sections the task needs.
4. Do the task. Verify. Commit. Stop.

Do not skip ahead, do not do two tasks, do not refactor code the task doesn't touch.

## Rules that are not negotiable

- **Never check a box unless its Done-condition literally passed in this session.**
  Paste the passing command's key output line into the commit message.
- **TDD.** Tasks marked `TDD` in PLAN.md: failing test first (RED), minimum code to
  pass (GREEN), then tidy (REFACTOR). Production code with no accompanying test in the
  same commit is a defect. Test names describe behaviour, not methods.
- **Failure rule: max 2 attempts per task.** Then rewrite the box as
  `- [BLOCKED: one-line diagnosis]`, log the details (commands, error, what you tried)
  to NOTES.md, commit, and move on to the next task. Never loop on a failing task.
- **Commit after every task**, message = `T07: Atom parser` + the verification line.
  Commit even for a BLOCKED task (the PLAN/NOTES edit is the commit).
- **Never weaken a test to make it pass.** Especially `FeedCorpusTest` (T09) — it is
  the standing contract. If it legitimately must change, say why in the commit.
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
  `JAVA_HOME=$HOME/.jdks/temurin-17` (or wherever T01 put it) and put `$JAVA_HOME/bin`
  first on `PATH`. Also set `org.gradle.java.home` in `gradle.properties`.
- `ANDROID_HOME=$HOME/Android/Sdk`, also `ANDROID_SDK_ROOT`. `local.properties` must
  contain `sdk.dir=/home/michael/Android/Sdk` (it is gitignored — recreate it if a
  build complains about a missing SDK).
- **`/dev/kvm` is absent** → the emulator is unaccelerated and slow (minutes, possibly
  tens of minutes, to boot). Never assume a fast boot. Always use a timeout and log the
  elapsed time. See the ACTION REQUIRED item in NOTES.md for the one-time host fix.
- Emulator launch: `emulator -avd perch -no-window -no-audio -no-boot-anim -gpu
  swiftshader_indirect -no-snapshot-save &`, then `adb wait-for-device` and poll
  `adb shell getprop sys.boot_completed`. Keep `adb shell wm size 540x1200` so
  screenshots stay small.
- **Keep the emulator running between sessions.** Do not kill it. If `adb` is wedged:
  `adb kill-server && adb start-server`, then reboot the emulator, then log it.
- 4 cores / 9 GB RAM. Gradle: `org.gradle.jvmargs=-Xmx3g`, `org.gradle.parallel=true`,
  `org.gradle.caching=true`. Do not run Gradle and an emulator boot concurrently.
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
