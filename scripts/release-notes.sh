#!/usr/bin/env bash
# release-notes.sh — assemble the *draft* of a release page.
#
#   scripts/release-notes.sh [since-tag]     (default: the most recent tag)
#
# Prints the skeleton from docs/RELEASE-NOTES.md with every issue closed since
# <since-tag> slotted into a bucket, so the writer starts from the list of what
# actually shipped and not from `git log`.
#
# Buckets are guessed from labels — `release` → Installing/upgrading, `tests` →
# held back (a reader cannot see the test suite), `bug` → Fixed, any other label
# → New, no label at all → Unsorted. The guess is a starting point; the rules in
# docs/RELEASE-NOTES.md are the job. This script does not write release notes and
# its output must never be shipped as-is.
#
# Needs `gh` authenticated against the repo (jq is not required — gh's own -q is).
set -uo pipefail
cd "$(dirname "$0")/.."

REPO_URL=https://github.com/michaelawetahegn/Perch

command -v gh >/dev/null || { echo "release-notes.sh: gh is not installed" >&2; exit 1; }

SINCE_TAG="${1:-$(git describe --tags --abbrev=0 2>/dev/null)}"
[ -n "$SINCE_TAG" ] || { echo "release-notes.sh: no tags in this repo — pass one explicitly" >&2; exit 1; }
git rev-parse -q --verify "refs/tags/$SINCE_TAG" >/dev/null || {
  echo "release-notes.sh: no such tag: $SINCE_TAG. Tags:" >&2
  git tag >&2
  exit 1
}

# Everything closed strictly after the tag's commit landed, in UTC to match GitHub.
SINCE_AT=$(date -u -d "$(git log -1 --format=%cI "$SINCE_TAG")" +%Y-%m-%dT%H:%M:%SZ)

ISSUES=$(gh issue list --state closed --limit 300 \
  --json number,title,labels,closedAt \
  -q ".[] | select(.closedAt > \"$SINCE_AT\") |
      (.labels | map(.name)) as \$l |
      (if (\$l | index(\"release\")) then \"upgrade\"
       elif (\$l | index(\"tests\")) then \"invisible\"
       elif (\$l | index(\"bug\")) then \"fixed\"
       elif (\$l | length) > 0 then \"new\"
       else \"unsorted\" end) as \$bucket |
      \"\(\$bucket)\t\(.number)\t\(.title)\t\(\$l | join(\", \"))\"" \
  2>/dev/null | sort -t"$(printf '\t')" -k2,2n)

[ -n "$ISSUES" ] || echo "release-notes.sh: warning — no closed issues found since $SINCE_TAG" >&2

# bucket_lines <bucket> <fallback text> [plain]
# `plain` renders the labels as text rather than an HTML comment — a nested
# comment's `-->` would close the block this bucket is printed inside.
bucket_lines() {
  local bucket=$1 empty=$2 plain=${3:-} found= note=
  while IFS=$'\t' read -r b n title labels; do
    [ "$b" = "$bucket" ] || continue
    found=1
    if [ -n "$plain" ]; then note="${labels:+  [$labels]}"; else note="${labels:+  <!-- $labels -->}"; fi
    printf -- '- **%s** <what a reader sees now>%s ([#%s](%s/issues/%s))\n' \
      "$title" "$note" "$n" "$REPO_URL" "$n"
  done <<< "$ISSUES"
  [ -n "$found" ] || printf -- '%s\n' "$empty"
}

count=$(printf '%s\n' "$ISSUES" | grep -c . )

cat <<EOF
<!-- ============================================================ -->
<!-- DRAFT ONLY — assembled by scripts/release-notes.sh.          -->
<!-- $count issue(s) closed since $SINCE_TAG ($SINCE_AT).
     One line per issue is a checklist, not an outline: merge them,
     split them, and rewrite every line in a reader's language.
     Do not ship this file without the pass described in
     docs/RELEASE-NOTES.md. -->
<!-- ============================================================ -->

<One or two sentences: what this release is for, and what a reader gets that
they did not have yesterday.>

### Installing / upgrading

<Does it install over the previous version and keep read state, likes and the
to-read queue? Say exactly that. If the reader must do something, number it.>

$(bucket_lines upgrade '<nothing labelled `release` closed since '"$SINCE_TAG"' — confirm the plain in-place-upgrade sentence is still true and say it anyway.>')

### New

$(bucket_lines new '<nothing bucketed here — check Unsorted below.>')

### Fixed

$(bucket_lines fixed '<nothing labelled `bug` closed since '"$SINCE_TAG"' — check Unsorted below.>')

### Known issues

- <Anything shipped with a rough edge, and the workaround if there is one.
  Check NOTES.md for residual-polish items and any BLOCKED box in the plan.>

<!-- ---------- not part of the release page ---------- -->
<!--
Unsorted — these issues carry no label, so the script cannot tell new from
fixed. Move each one into New or Fixed, then delete this block:

$(bucket_lines unsorted 'none.' plain)

Held back — labelled \`tests\`, so nothing here is visible to a reader (rule 7).
Include one only if it changed something felt, and then describe the felt thing:

$(bucket_lines invisible 'none.' plain)

Rules, in short (docs/RELEASE-NOTES.md has them in full):
  · a reader's language; no task IDs, no commit hashes, no file paths
  · every Fixed line names the symptom the reader saw, and links its issue
  · Installing / upgrading is never omitted
  · nothing goes in that a reader cannot see
-->
EOF
