# Plan archive

Perch is built by the Ralph loop described in [`../RALPH.md`](../RALPH.md): a plan file is
a list of checkbox tasks, and an unattended loop of Claude Code sessions does the single
next unchecked one, commits, and stops. The checkbox file *is* the loop's memory — it is
why a session that starts with no chat history knows what to do.

Finished plans live here. The **active** plan stays at the repository root, because
`loop.sh` and `CLAUDE.md` point at it and a session's cold start reads it.

| Plan | Version | Tasks | What it was |
|---|---|---|---|
| [`PLAN-1-v0.1.md`](PLAN-1-v0.1.md) | v0.1.0 | T01–T32 | Build the reader: environment, parser, storage, the screens, first release. |
| [`PLAN-2-v0.2.md`](PLAN-2-v0.2.md) | v0.2.0 | U01–U16 | The daily-driver pass: folders, to-read and liked, full-text extraction, code and tables, the image viewer, OPML and profile backup. |
| [`PLAN-3.md`](../../PLAN-3.md) | v0.3.0 | V01–V16 | The issue-tracker pass: one task per open GitHub issue. |

[`bootstrap-prompt.md`](bootstrap-prompt.md) is the original prompt that produced
`SPEC.md`, `DESIGN.md`, `PLAN-1` and `loop.sh` in a single session, before any code
existed. It is kept as history — **it is not instructions for a current session**, and its
"do NOT start Task 1 yourself" refers to a session that ended in August 2026.

A plan's `§0` is authoritative for its own version and deliberately overrides older text in
`SPEC.md`, `DESIGN.md` and earlier plans. Read the newest one first; the older ones explain
how the code got its shape, not what it should be now.
