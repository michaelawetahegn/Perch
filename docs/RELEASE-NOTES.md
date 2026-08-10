# How to write a Perch release page

A release page is an **announcement**, not a changelog. Someone lands on it from the
Releases list, wants to know whether to install it and what changed for *them*, and
leaves. It is the only page most readers will ever read about a version.

`scripts/release-notes.sh <last-tag>` assembles the raw material — every issue closed
since that tag, bucketed by label. It drafts; it does not write. The pass below is the
work.

---

## The template

Copy this, fill it, delete any section that has nothing true to say (except the first
two, which always have something).

```markdown
<One or two sentences: what this release is *for*. What a reader gets that they
did not have yesterday, in their words.>

### Installing / upgrading

<One line: does it install over the previous version and keep read state, likes and
the to-read queue? If yes, say exactly that. If anything is required of the reader —
a bridge build, an export, an uninstall — number the steps and say what is lost if
they are skipped.>

### New

- **<The thing, named as a reader would name it.>** <What it does, one or two
  sentences. Say where it is if it is not obvious.>

### Fixed

- **<The symptom the reader saw.>** <What was wrong and what happens now.>
  ([#N](https://github.com/michaelawetahegn/Perch/issues/N))

### Known issues

- <Anything shipped with a rough edge, and the workaround if there is one.>
```

## The rules

1. **A reader's language.** "Tapping a blog's name opens that blog's articles", not
   "wire `HomeScope.Source` to the byline". Nothing in the page requires having read
   the repository.
2. **No task IDs, no commit hashes, no file paths.** `V08`, `b7e66ef` and
   `ui/article/ArticleScreen.kt` mean nothing to someone holding a phone. Issue
   numbers are the one exception — they link to something a reader can actually read.
3. **Every "Fixed" line names the symptom, not the cause,** and links its issue.
   "No articles after about 7 p.m." — not "clock zone defaulted to UTC". The reader
   recognises the symptom; that recognition is the whole point of the line.
4. **"Installing / upgrading" is never omitted.** A reader with the previous version
   installed is asking one question — *will this eat my read state?* — and an unanswered
   question reads as a yes.
5. **Lead with the release's reason.** If one change is why the version exists, the
   headline sentence is about that change and it goes first under **New**.
6. **Group by what it is, not by when it landed.** Several issues can collapse into one
   bullet, and one issue can be worth two. The draft's one-line-per-issue shape is a
   checklist, not an outline.
7. **Nothing goes in that a reader cannot see.** Refactors, test-suite work and build
   plumbing are invisible and stay out — unless they changed something felt, in which
   case describe the felt thing.
8. **Say what is still broken.** A known issue named in the release page is a limitation;
   the same one discovered by a reader is a bug report.

## A worked example

From v0.2.0 — the shape to aim for. The headline sentence says what the release is for,
the upgrade section answers the data question before it is asked, and each bullet leads
with the noun a reader would use.

```markdown
The v0.1 release read feeds. This one is the pass that made Perch the thing I
actually reach for: folders, a to-read queue, and — the point of the whole
release — **an article you can read without ever opening the site**.

### Installing over v0.1.0 — read this once

v0.1.0 was **debug-signed**, so Android will not accept v0.2.0 as an update to
it: uninstall v0.1.0 first, which does erase its read state. That is a one-time
cost of fixing the signing. **v0.2.0 onward is signed with a stable release key
and installs over itself**, keeping your read state, likes and to-read queue.
Before you uninstall, Settings → Export profile writes one JSON file …

### Reading

- **Full text for feeds that don't ship it.** A Readability-style extraction over
  the article page, run on open (never on refresh) … It never replaces text with
  less text, and *Load full article* in the overflow forces it when the heuristic
  guesses wrong.
- **Tables that look like tables** — a distinguished header row, hairline rules,
  and horizontal scroll rather than columns crushed to fit.
```

And the same three issues as the draft script hands them over, versus as they ship:

| Draft line | Ships as |
| --- | --- |
| `#9 Shows no articles` | **The Feed emptied out in the evening.** After about 7 p.m. Central, "Today" was measured against UTC's midnight rather than yours, so everything published that day fell outside it. Today is now your today. ([#9](https://github.com/michaelawetahegn/Perch/issues/9)) |
| `#6 Pull-to-refresh does nothing on an empty list` | **Pull-to-refresh works on an empty list.** On a fresh install — or any list with nothing in it — the pull gesture was ignored. ([#6](https://github.com/michaelawetahegn/Perch/issues/6)) |
| `#10 Show articles from a specific blog when clicking the name.` | **Tap a blog's name to read just that blog.** The source name above an article's title is now a link into that source's list. |

## Shipping it

```sh
scripts/release-notes.sh v0.2.0 > /tmp/draft.md   # raw material
$EDITOR /tmp/draft.md                             # the actual writing
gh release create v0.3.0 --title "Perch v0.3.0 — <the release's reason>" \
  --notes-file /tmp/draft.md app/build/outputs/apk/release/perch-0.3.0.apk
```

The title follows the same rule as the headline sentence: `Perch v0.2.0 — the
daily-driver pass`, not `Release 0.2.0`.
