#!/usr/bin/env bash
# harvest.sh — T04 fixture harvest.
#
# For every URL in fixtures/feeds.txt:
#   · fetch with a browser-ish UA, following redirects
#   · if the body is HTML, auto-discover <link rel="alternate"> per SPEC.md §5
#     (atom > rss > rdf, then common path guesses) and fetch the discovered URL
#   · save the resolved raw bytes to fixtures/snapshots/<slug>.xml
#   · save the homepage HTML to fixtures/homepages/<slug>.html when discovery ran
#     (T11 needs these)
#   · append "<slug>\t<resolved-url>\t<http-status>\t<bytes>" to fixtures/manifest.tsv
#
# One retry per fetch. Dead feeds are recorded with their status and 0 bytes; they
# never stall the run. Re-running is safe: it rebuilds the manifest from scratch.
#
# Usage: scripts/harvest.sh [feeds-file]
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FEEDS="${1:-$ROOT/fixtures/feeds.txt}"
SNAPDIR="$ROOT/fixtures/snapshots"
HOMEDIR="$ROOT/fixtures/homepages"
MANIFEST="$ROOT/fixtures/manifest.tsv"

UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36'
GUESSES=(/feed /rss.xml /atom.xml /index.xml /feed.xml /feeds/all.atom.xml)
MAXBYTES=$((8 * 1024 * 1024))   # SPEC.md §6 body cap

mkdir -p "$SNAPDIR" "$HOMEDIR"
: > "$MANIFEST"

# slugify <url> -> host with www. dropped, non-alnum -> '-'
slugify() {
  local h="${1#*://}"; h="${h%%/*}"; h="${h%%:*}"; h="${h#www.}"
  printf '%s' "$h" | tr 'A-Z' 'a-z' | tr -c 'a-z0-9' '-' | sed 's/-\{1,\}/-/g; s/^-//; s/-$//'
}

# fetch <url> <out-body> ; echos "<status> <final-url>"; one retry
fetch() {
  local url="$1" out="$2" try line
  for try in 1 2; do
    line=$(curl -sSL --compressed \
      -A "$UA" \
      -H 'Accept: application/atom+xml, application/rss+xml, application/xml;q=0.9, text/xml;q=0.9, text/html;q=0.8, */*;q=0.5' \
      --connect-timeout 15 --max-time 60 --retry 0 \
      -o "$out" -w '%{http_code} %{url_effective}' "$url" 2>/dev/null)
    case "$line" in 2*) printf '%s' "$line"; return 0 ;; esac
    [ "$try" = 1 ] && sleep 2
  done
  printf '%s' "${line:-000 $url}"
}

# looks_like_feed <file>
looks_like_feed() {
  head -c 4096 "$1" 2>/dev/null | tr -d '\000' \
    | grep -qiE '<(rss|feed|rdf:RDF)[[:space:]>]'
}

# discover <homepage-file> <base-url> -> feed url on stdout, empty if none
discover() {
  local f="$1" base="$2" type href abs
  for type in 'atom\+xml' 'rss\+xml' 'rdf\+xml'; do
    # <link ...> tags that carry rel=alternate and the wanted type, in either order
    href=$(tr '\n' ' ' < "$f" \
      | grep -oiE '<link[^>]*>' \
      | grep -iE 'rel=["'"'"']?alternate' \
      | grep -iE "type=[\"']?application/$type" \
      | head -1 \
      | grep -oiE 'href=("[^"]*"|'"'"'[^'"'"']*'"'"'|[^ >]*)' \
      | head -1 | sed -E 's/^[Hh][Rr][Ee][Ff]=//; s/^["'"'"']//; s/["'"'"']$//')
    if [ -n "$href" ]; then
      resolve "$href" "$base"; return 0
    fi
  done
  return 1
}

# resolve <href> <base-url>  -> absolute URL
resolve() {
  local href="$1" base="$2" scheme hostpart
  case "$href" in
    http://*|https://*) printf '%s' "$href"; return ;;
    //*) printf '%s:%s' "${base%%:*}" "$href"; return ;;
  esac
  scheme="${base%%://*}"; hostpart="${base#*://}"; hostpart="${hostpart%%/*}"
  case "$href" in
    /*) printf '%s://%s%s' "$scheme" "$hostpart" "$href" ;;
    *)  local dir="${base#*://}"; dir="${dir#*/}"
        if [ "$dir" = "${base#*://}" ]; then dir=""; else dir="${dir%/*}/"; fi
        [ "$dir" = "/" ] && dir=""
        printf '%s://%s/%s%s' "$scheme" "$hostpart" "$dir" "$href" ;;
  esac
}

total=0; ok=0; failed=0
while read -r url; do
  case "$url" in ''|'#'*) continue ;; esac
  total=$((total + 1))
  slug=$(slugify "$url")
  # collision guard
  if [ -e "$SNAPDIR/$slug.xml" ] || grep -q "^$slug	" "$MANIFEST" 2>/dev/null; then
    n=2; while grep -q "^$slug-$n	" "$MANIFEST" 2>/dev/null; do n=$((n + 1)); done
    slug="$slug-$n"
  fi

  body="$(mktemp)"
  read -r status final <<<"$(fetch "$url" "$body")"

  if [ -s "$body" ] && ! looks_like_feed "$body"; then
    # HTML (or something else) — keep it for T11 and try auto-discovery
    cp "$body" "$HOMEDIR/$slug.html"
    if feedurl=$(discover "$body" "$final"); then
      :
    else
      feedurl=""
      hostroot="${final%%://*}://$(x="${final#*://}"; printf '%s' "${x%%/*}")"
      for g in "${GUESSES[@]}"; do
        cand="$hostroot$g"
        read -r gstatus gfinal <<<"$(fetch "$cand" "$body")"
        if [ -s "$body" ] && looks_like_feed "$body"; then feedurl="$gfinal"; status="$gstatus"; final="$gfinal"; break; fi
      done
    fi
    if [ -n "$feedurl" ] && [ "$feedurl" != "$final" ]; then
      read -r status final <<<"$(fetch "$feedurl" "$body")"
    fi
  fi

  if [ -s "$body" ] && looks_like_feed "$body" && [ "$(wc -c < "$body")" -gt "$MAXBYTES" ]; then
    # Over SPEC.md §6's 8 MiB body cap — the app would reject it, so the corpus must too.
    failed=$((failed + 1))
    printf '%s\t%s\t%s\t%s\n' "$slug" "$final" "oversize" 0 >> "$MANIFEST"
    printf '  SKIP %-28s %6s B  oversize\n' "$slug" "$(wc -c < "$body")"
    rm -f "$body"
    continue
  fi

  if [ -s "$body" ] && looks_like_feed "$body"; then
    cp "$body" "$SNAPDIR/$slug.xml"
    bytes=$(wc -c < "$SNAPDIR/$slug.xml" | tr -d ' ')
    ok=$((ok + 1))
    printf '%s\t%s\t%s\t%s\n' "$slug" "$final" "$status" "$bytes" >> "$MANIFEST"
    printf '  ok   %-28s %6s B  %s\n' "$slug" "$bytes" "$status"
  else
    failed=$((failed + 1))
    printf '%s\t%s\t%s\t%s\n' "$slug" "$final" "$status" 0 >> "$MANIFEST"
    printf '  FAIL %-28s        %s\n' "$slug" "$status"
  fi
  rm -f "$body"
done < "$FEEDS"

echo
echo "inputs=$total  snapshots=$ok  excluded=$failed  manifest=$MANIFEST"
[ "$ok" -ge 35 ] || exit 1
