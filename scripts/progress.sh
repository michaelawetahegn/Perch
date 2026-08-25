#!/usr/bin/env bash
# progress.sh — what is the Ralph loop doing?
#
#   ./scripts/progress.sh          one-shot status
#   ./scripts/progress.sh -f       follow the live log (Ctrl-C to stop)
#   ./scripts/progress.sh -t       task checklist only
#
set -uo pipefail
cd "$(dirname "$0")/.."

PLAN=${PLAN:-PLAN-8.md}
LOG=${LOG:-"loop-$(basename "$PLAN" .md | tr 'A-Z' 'a-z').log"}

case "${1:-}" in
  -f|--follow) exec tail -f "$LOG" ;;
  -t|--tasks)
    # Titles are bold-delimited: `- [x] **U01 — Publish v0.1.0 to GitHub.** rest…`
    grep -E '^- \[' "$PLAN" | sed -E \
      -e 's/^- \[x\] \*\*([^*]*)\*\*.*/  ✔ \1/' \
      -e 's/^- \[ \] \*\*([^*]*)\*\*.*/  ○ \1/' \
      -e 's/^- \[BLOCKED: ([^]]*)\].*/  ✖ BLOCKED: \1/'
    exit 0 ;;
esac

done_n=$(grep -c '^- \[x\]' "$PLAN" 2>/dev/null) || done_n=0
todo_n=$(grep -c '^- \[ \]' "$PLAN" 2>/dev/null) || todo_n=0
blok_n=$(grep -c '^- \[BLOCKED' "$PLAN" 2>/dev/null) || blok_n=0
total=$((done_n + todo_n + blok_n))

if pgrep -f 'bash ./loop.sh' >/dev/null 2>&1 || pgrep -x -f './loop.sh' >/dev/null 2>&1; then
  state="RUNNING"
else
  state="not running  (start it with: nohup ./loop.sh >/dev/null 2>&1 &)"
fi

printf '\n  %s · %s\n' "$PLAN" "$state"
printf '  %s/%s done · %s pending · %s blocked\n\n' "$done_n" "$total" "$todo_n" "$blok_n"

# The bar, because a number you have to compare to another number is not a status.
filled=$((done_n * 40 / (total > 0 ? total : 1)))
printf '  ['
for i in $(seq 1 40); do [ "$i" -le "$filled" ] && printf '#' || printf '.'; done
printf ']\n\n'

printf '  ── working on ─────────────────────────────────────────────\n'
grep -m1 '^- \[ \]' "$PLAN" | sed -E 's/^- \[ \] \*\*//; s/\*\*//' | cut -c1-72 | sed 's/^/  /'
echo

printf '  ── last 8 commits ─────────────────────────────────────────\n'
git log --oneline -8 | sed 's/^/  /'
echo

printf '  ── loop events ────────────────────────────────────────────\n'
if [ -f "$LOG" ]; then
  grep -E 'session #|✔ committed|⏳|⚠|🛑|✅|memory after' "$LOG" | tail -8 | cut -c1-110 | sed 's/^/  /'
else
  echo "  (no $LOG yet)"
fi
echo
printf '  live log: tail -f %s\n\n' "$LOG"
