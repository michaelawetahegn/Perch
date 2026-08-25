# PLAN-5.md — v0.5, slice 1: the drawer opens quiet

**This is the active plan.** v0.1 (T01–T32), v0.2 (U01–U16), v0.3 (V01–V16) and v0.4
(W01–W12) are complete, frozen, and history only, archived in `docs/plans/` — do not
reopen a box in any of them. The process is `docs/RALPH.md`.

Ordered. **Do the single next unchecked `[ ]` task, then stop.** Never check a box unless
its Done-condition literally passed in this session. Failure → 2 attempts max, then rewrite
the box as `- [BLOCKED: one-line diagnosis]`, log it to NOTES.md, and move on.

Rungs, cheapest first: **unit** < **build** < **maestro** < **screenshot**.

**TDD is the method, not a suggestion.** Failing test first (`RED`), minimum code to pass
(`GREEN`), then tidy (`REFACTOR`). A commit whose diff adds production code but no test is
a defect — reopen the box.

**v0.4.0 is installed on a real phone.** Every schema change ships a real Room `Migration`
and a matching `app/schemas/N.json`. **Never `fallbackToDestructiveMigration()`.** Nothing
in *this* plan needs a migration; if a task believes it does, the task grew — say so on the
issue first.

---

## §0 — Decisions for this slice (authoritative; do not re-derive)

PLAN-3 §0 and PLAN-4 §0 still hold except where this section says otherwise.

**This plan is one slice of v0.5, not a version.** v0.5 is being built as four sequential
plans — `PLAN-5` (#22), `PLAN-6` (#23), `PLAN-7` (#21), `PLAN-8` (review, live acceptance,
release). **There is no version bump and no release in this plan.** The version-wide review
over `git diff v0.4.0..HEAD` lives in `PLAN-8`; the review box at the end of *this* plan is
scoped to *this* plan's own diff.

**The decisions this slice is allowed to make, made once, here:**

1. **Every folder section in the source drawer starts collapsed** — including
   **Uncategorized**. Uniformly. A reader who opens the drawer sees a list of category
   headers and nothing else. This is what #22 asks for and there is no exception for the
   built-in folder.
2. **The state's polarity inverts.** Today `HomeViewModel` holds `_collapsedFolders`
   (`HomeViewModel.kt:234`), and the empty set means *everything open*. That cannot express
   "everything shut" as a default. It becomes **`_expandedFolders: MutableStateFlow<Set<Long>>`,
   default `emptySet()`**, and the drawer renders children when `folder.id in expandedFolders`.
3. **A folder the reader creates comes up expanded.** The old KDoc
   (`HomeViewModel.kt:230-233`) kept collapsed-ids specifically so a folder created while the
   drawer is open appears open. That intent survives the inversion, but it is now **explicit**:
   creating a folder adds its id to `expandedFolders`. The KDoc is rewritten to say so.
4. **Expansion is not persisted.** It stays ViewModel-scoped, as today. "Start collapsed"
   means collapsed on every launch, which is exactly what the issue asks for. Do **not** add a
   `SettingsStore` key.
5. **A collapsed header still shows its badge.** `HomeTestTags.folderBadge`
   (`HomeScreen.kt:1075`) already exists, so a shut folder is not a dead row — the reader can
   see how much is inside. Confirm it reads sensibly when collapsed; do not invent a new badge.
6. **Tests reach a source row by opening its folder, never by assuming it is open.** The
   fix is one shared helper, not forty ad-hoc taps — see X02.

---

## The tasks

- [x] **X01 — The drawer's expansion is expressed as what is open, with behaviour unchanged. TDD. Issue #22.**
      `gh issue view 22 --json body` first — this `gh` is old and a bare `gh issue view 22`
      dies on a Projects GraphQL field (NOTES.md).
      **Pure refactor: at the end of this task the drawer still opens with every folder
      expanded.** Only the polarity changes. Flipping the default is X02's job, and keeping
      the two apart is what stops one session from owning both a model change and a
      forty-site test sweep.
      - `HomeViewModel.kt:234-235` — `_collapsedFolders` / `collapsedFolders` become
        `_expandedFolders` / `expandedFolders`. To keep behaviour identical **this task seeds
        the set with every folder id** as folders arrive, so "all open" is still what a reader
        sees. The seeding is deliberately temporary — X02 deletes it — so say that in a comment
        naming X02.
      - `HomeViewModel.kt:439-443` — `toggleFolderExpanded(folderId)` flips membership of the
        new set. Keep the name; it is already the right name.
      - `HomeViewModel.kt:230-233` — rewrite the KDoc. The old text explains the collapsed-ids
        polarity and is now false. It must state §0.3: a folder the reader creates comes up
        expanded, and that this is now explicit rather than a side effect of an empty set.
      - `HomeScreen.kt:153` (collection), `:295-306` (the `ModalNavigationDrawer` wiring),
        `:754` and `:763` (the two `folder.id !in collapsedFolders` checks) — all become
        `in expandedFolders`. **There are exactly two render checks; find both.**
      - RED first: a test that a folder **created while the drawer is open comes up expanded**.
        That is §0.3, it is the invariant the old polarity existed to protect, and nothing
        currently pins it. Put it in
        `app/src/testDebug/java/dev/mkiros/perch/ui/home/FolderDrawerTest.kt`.
      - Traps: an injected tap never reaches a node inside the drawer sheet — use
        `performSemanticsAction(OnClick/OnLongClick)`, via the file's own `tap` helper
        (`FolderDrawerTest.kt:301-303`) and `longPressInDrawer` (`:293-297`). `waitUntil`
        advances only the virtual clock, so Room emissions are awaited in wall-clock time
        (`awaitState`/`awaitDb`, KDoc at `FolderDrawerTest.kt:44-51`).
      - Done: the new test named and passing; `grep -rn 'collapsedFolders' app/src` returns
        nothing; full `./gradlew test` green at **≥1524 tests** with the count pasted into the
        commit message.
      - Rung: unit

- [ ] **X02 — The drawer starts collapsed, and every test opens what it needs. TDD. Issue #22.**
      The behaviour change #22 actually asks for, plus the sweep it forces. **One commit:
      the default flip turns ~40 assertions red, so the flip and the fix cannot be separate
      sessions without leaving `HEAD` broken.**
      - The change itself is small: delete X01's seeding so `_expandedFolders` starts
        `emptySet()`, and add the new folder's id on create (§0.3).
      - RED first: a test that **opening the drawer shows the category headers and none of
        their sources**, and that opening one folder reveals only that folder's sources.
        `FolderDrawerTest.kt`. Assert absence with `assertDoesNotExist()` — a collapsed
        folder's children genuinely leave the tree (`FolderDrawerTest.kt:167` already does
        this), so text matchers fail with "no node", not "not displayed".
      - **Then the sweep.** Source rows only render inside an expanded folder
        (`HomeScreen.kt:763`), so every test that reaches a drawer *source* row without first
        opening its folder breaks. Add **one shared helper** — `expandInDrawer(folderId)`,
        `performSemanticsAction(OnClick)` on `HomeTestTags.folderExpand(folderId)`
        (`HomeScreen.kt:1076`) — and route the existing helpers through it rather than
        editing forty call sites by hand: `longPressInDrawer` (`FolderDrawerTest.kt:293`),
        `tap` (`:301`), `DrawerMultiSelectTest.kt:434-437` and `:400-407`,
        `HomeScreenTest.kt:494-508`, and `drawerRow` (`HomeScreenTest.kt:518`).
      - Known-affected, so nothing is discovered by surprise —
        `FolderDrawerTest.kt`: `:93`, `:158` (its premise inverts: "collapsing hides" becomes
        "expanding reveals"; the pre-assert at `:163` and the tap at `:165` both change),
        `:250`, `:268`.
        `DrawerMultiSelectTest.kt`: `:103`, `:117`, `:134`, `:146`, `:160`, `:178`, `:200`,
        `:240`, `:257`, `:277`, `:301`, `:321`, `:333`, `:348`, `:369`.
        `HomeScreenTest.kt`: `:242`, `:260`, `:271`, `:287`, `:307`, `:320`, `:342`, `:353`,
        `:365`, `:380`, `:397`, `:418`, `:442`, `:457`.
        `HomeRefreshTest.kt`: `:139`, `:184`, `:196`, `:210`, `:318`.
        `HomeTimeFilterTest.kt:277` only taps `folderHeader` and is expected to stay green —
        confirm rather than edit it.
      - **The trap that will cost a session if it is not read.** In *folder* selection mode the
        chevron is replaced by a `Checkbox` (`HomeScreen.kt:869-877`), so a collapsed folder
        cannot be opened once selection has started. `DrawerMultiSelectTest.kt:178`
        (`longPressFolder` then `tapRow("GPUOpen")`) must **expand before entering selection
        mode**. `DrawerMultiSelectTest.kt:200` asserts the chevron stays enabled mid-*source*
        selection — that assertion is still true and must not be weakened.
      - **Never weaken a test to make it pass.** Adding an `expandInDrawer` step is not
        weakening; deleting an assertion is. Every changed assertion is named in the commit
        message with the reason.
      - Done: the new collapsed-by-default test named and passing; full `./gradlew test`
        green at **≥1525 tests**, count pasted into the commit message; `git grep -c
        expandInDrawer` shows the helper used, not copy-pasted per site.
      - Rung: unit

- [ ] **X03 — The shots and the live gate open a folder before reading a source. Issue #22.**
      Two screenshot tests and one live gate reach drawer source rows and are not covered by
      X02's helper because they live outside those files.
      - `app/src/testDebug/java/dev/mkiros/perch/ui/screenshot/DesignScreenshotTest.kt:164`
        (`the source drawer over the list`) — with folders shut this shot is headers only.
        **Decide and say which the shot is for:** the drawer's real resting state is now
        collapsed, so the honest shot is one folder opened, showing both a shut header and an
        open one. Do that; it is the drawer a reader actually meets.
      - `DesignScreenshotTest.kt:183-201` (`the drawer in selection mode with three sources
        ticked`) uses `drawerRow` and will throw. Expand first, then select — §0.6 and X02's
        selection-mode trap apply here too.
      - `app/src/testDebug/java/dev/mkiros/perch/acceptance/LiveAcceptanceTest.kt:1502-1516`
        — gate 7 long-presses a `drawerRow` inside a named folder. It must expand that folder
        first or gate 7 fails in `PLAN-8` as a mystery, which is exactly the failure W10 was
        written to prevent. **This is a code edit to the gate, not a live run** — do not run
        the live suite in this task; `PLAN-8` does that.
      - `BrandScreenshotTest.kt:217` opens the drawer for the wordmark only — expected safe,
        confirm rather than edit.
      - Done: both screenshots exist under `build/perch-screenshots/` and were **opened and
        looked at**, not merely produced — say in the commit what the drawer shot shows;
        `./gradlew test` green; gate 7's edit quoted in the commit message.
      - Rung: screenshot

- [ ] **X04 — Review of this slice. Everything PLAN-5 changed, read as a whole.**
      Scoped to this plan, not the version — `PLAN-8` reads `v0.4.0..HEAD` entire. Read
      `git diff` for this plan's commits only and answer, in the commit message and in
      NOTES.md where it outlives the plan:
      1. Does any doc or KDoc still describe the drawer as opening expanded? Check
         `HomeViewModel.kt:230-233`, `FolderDrawerTest`'s KDoc, README.md's drawer
         screenshot alt text, DESIGN.md and SPEC.md.
      2. Is `collapsedFolders` gone everywhere — the flow, the parameter names, the KDocs,
         the test helpers? `grep -rn 'collapsedFolders\|collapsed' app/src`.
      3. Was any test weakened rather than rewritten? X02 touched ~40 sites; name every
         assertion that changed and say which replacement is at least as strong.
      4. Is the suite still ≥1525 tests, and did any change land without a test?
      Fix what is small and mechanical **in this session**. Anything larger becomes a new
      GitHub issue named here — do not start a feature in a review.
      - Done: the four questions answered in the commit message, each with the command that
        settled it; `./gradlew test` green; issue #22 closed with a comment naming the
        commits and how it was verified; everything pushed.
      - Rung: unit
