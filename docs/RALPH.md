# RALPH.md — how work gets done in this repo

Perch is not built by one long session. It is built by a **loop of short ones**:

```
claude -p "Read CLAUDE.md and continue" --dangerously-skip-permissions
```

run over and over by [`loop.sh`](../loop.sh). Each session starts with **no memory of the
last one**, reads `CLAUDE.md`, does the *single next unchecked task* in the active plan,
commits, and exits. The loop then starts another.

Three versions have been built this way: v0.1 (T01–T32), v0.2 (U01–U16) and v0.3
(V01–V16, one task per GitHub issue, sixteen tasks in about five hours with zero blocked).

**If the human asks for a feature, a bug fix, or a batch of issues, this is the process to
use** — not a single long session. Read this file, then follow "Starting new work" below.

---

## Why it works

A long session degrades: context fills with dead ends, early decisions get re-litigated,
and the last hour is worse than the first. The loop trades that for four invariants.

1. **One task per session.** A session's whole job is one box. It cannot sprawl, because
   the standing orders tell it to stop when the box is checked.
2. **The plan file is the memory.** `- [ ]` / `- [x]` / `- [BLOCKED: …]` on disk is what
   survives a session ending. Nothing important lives in a context window. This is why the
   plan is a *file in the repo* and not a chat message.
3. **Every task ends in a commit.** The commit is the unit of progress, the audit trail,
   and the loop's own health signal — it compares `HEAD` before and after each session and
   counts a no-commit session as a stall.
4. **Done-conditions are machine-checkable.** "Looks right" cannot check a box; a pasted
   passing command output can. A session that cannot produce the output must mark the box
   `BLOCKED` and move on, which keeps a bad task from eating the whole run.

The loop also handles the boring realities that otherwise end an unattended run: it naps
and retries forever on usage limits, stops loudly after three consecutive no-commit
sessions, puts the JVMs down between sessions and refuses to start one when the host is
short of memory (see the memory guard's comment — an over-provisioned build freezes the
whole desktop, not just the build).

---

## Starting new work

**First decide whether it needs a loop at all.** One small fix, one file, one test — just
do it in the session. A loop is for work that is *several* tasks: a batch of issues, a
feature with real design surface, a version's worth of changes. The overhead of a plan is
only worth paying when context would otherwise be the bottleneck.

### 1. Make each piece of work an issue

`gh issue create`. The issue is where the symptom lives in the reader's own words, plus any
diagnosis, code anchors and traps found while triaging. A task in the plan then says
"`gh issue view N` first" instead of restating it, and the human can watch progress from
the tracker while AFK.

### 2. Write `PLAN-N.md`

At the repository root, because that is where `loop.sh` and `CLAUDE.md` look. Two parts:

**§0 — Decisions (authoritative; do not re-derive).** Everything a session might otherwise
deliberate about: naming, ordering rules, what a state means, what wins when this plan
contradicts `SPEC.md` or an older plan. §0 exists so that sixteen independent sessions make
the *same* choices without talking to each other. Where it overrides older text, say so —
and make the task that touches it update the older doc in the same commit.

**The tasks.** Ordered, cheap and isolated first, live/network/release last. One session
each. Anatomy of a task that a cold session can actually execute:

```markdown
- [ ] **V07 — One quiet placeholder for a missing thumbnail. TDD + screenshot. Issue #13.**
      `gh issue view 13` — the reader's words: the empty square "looks like it's still
      trying to load the thumbnail".
      All three states already funnel into one composable — `EntryRow.kt:175`'s
      `Placeholder`, reached from `url == null` (`:153`), Coil's `loading` (`:161`) and its
      `error` (`:162`) — and it draws only a hairline border around nothing.
      The mark already exists as `PerchMarkVector` (`ui/theme/Brand.kt:142`) — reuse it, do
      not restate the path data.
      - Done: a test asserting the placeholder is drawn for absent/failed; screenshots in
        both light and dark under `build/perch-screenshots/`; `./gradlew test` green;
        issue #13 closed.
      - Rung: screenshot
```

What makes that task work, and what to copy:

- **A title that states the finished state**, not the activity.
- **The issue number**, so the session reads the reader's own words before touching code.
- **Code anchors — `file.kt:line` — found during planning.** This is the single highest-value
  thing planning can do for a session: it converts an open-ended search into a read.
- **The traps, named.** Every "this will look like a bug but isn't" you already know.
- **A Done-condition that is a command with an output**, and a **rung**: `unit` < `build` <
  `maestro` < `screenshot`. Use the cheapest that actually answers the question.

Rules that belong in every plan's preamble, because all three versions needed them:

- **TDD.** Failing test first, then the minimum code, then tidy. Production code with no
  test in the same commit is a defect.
- **No speculative fixes.** A bug is not fixed until a failing test reproduced it. If it
  cannot be reproduced: comment the findings on the issue, mark the box
  `[BLOCKED: cannot reproduce — …]`, move on. A guessed fix looks closed and is not.
- **Bounded verification.** "Run it until it fails" is never a Done-condition — it has no
  end, so the only way to obey it is to background the run and stop, which produces no
  commit. Say "at most three foreground runs", then fix the invariant regardless.
- **Two attempts, then `BLOCKED`.** Never loop on a failing task.
- **Close the issue, push the commit.** The task is not done until both happen.

### 3. Point the loop at it

Two one-line edits: `PLAN=${PLAN:-PLAN-N.md}` in `loop.sh`, and the active-plan section at
the top of `CLAUDE.md`. Move the finished plan into `docs/plans/`.

### 4. Launch it detached

```bash
nohup setsid ./loop.sh >/dev/null 2>&1 </dev/null &
```

Detached so it survives the launching session ending. It logs to `loop-plan-N.log`.
**Check `pgrep -af loop.sh` first** — two loops in one repo will fight over commits.

### 5. Watch, don't drive

```bash
tail -f loop-plan-3.log                 # everything
grep -E "session #|committed|no new commit|STALLED" loop-plan-3.log   # just the beats
./scripts/progress.sh                   # plan state
gh issue list --state open              # what's left, from the reader's side
```

Do **not** commit to the repo while the loop runs. Sessions end with `git add -A`, so an
uncommitted edit of yours will be swept into an unrelated commit.

### 6. Land it

The last two tasks of every plan so far have been a **live acceptance pass** (the real
corpus, real network, screenshots critiqued against `DESIGN.md`) and a **release**
(version bump, release-signed APK, `gh release create` with notes written to
[`RELEASE-NOTES.md`](RELEASE-NOTES.md)). Keep that shape: the loop should end with
something installable, not with the last bug fix.

---

## When it goes wrong

Observed failure modes, and what each one actually means.

| What you see | What it means | What to do |
|---|---|---|
| `⚠ no new commit` once | A session hit something open-ended, or ran out of room mid-task | Read the last lines of its transcript in the log. Usually the *task* is at fault, not the session |
| `🛑 STALLED` (3 in a row) | The loop stopped itself rather than thrash | Fix or `BLOCKED` the offending box, then relaunch. Never just rerun and hope |
| `⏳ usage limit (nap #n)` | Normal. A limited session fails instantly and costs nothing | Nothing. It retries every 10 minutes, forever |
| A box comes back `BLOCKED` | Working as designed | Read the diagnosis in `NOTES.md`, decide whether it becomes a new issue |
| Everything hangs on one line for an hour | Something is *waiting*, not working | Find the blocked process (`pgrep -af`, `ps --ppid`). A WSL→Windows interop call that is never backgrounded is the known cause |
| Host desktop gets sluggish | WSL2 never gives ballooned memory back | Let the memory guard do its job; never raise the `-Xmx` caps to buy speed |

Two lessons worth more than the table:

- **A session's failure is usually the task's fault.** Every no-commit stall in v0.3 traced
  to a Done-condition that could not be satisfied in one session, not to a session doing
  the wrong thing. Fix the plan, not the session.
- **Verify the thing the human will see.** Tests going green is not the same as the screen
  looking right. For visual tasks, open the actual PNG in `build/perch-screenshots/`.

---

## Cost discipline

The loop can burn a week of quota in a day if it is allowed to explore. Every rule in
`CLAUDE.md` about short reads, narrow greps, the cheapest rung and "never read the whole
repo" exists because of that. Planning is where you spend tokens on thinking; sessions are
where you spend them on doing. A well-anchored task is cheaper *and* better than a vague
one, in that order of importance.
