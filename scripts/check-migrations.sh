#!/usr/bin/env bash
# Flyway migration guard (CI job "Migrations", also runnable locally from any directory of the repo).
#
#   scripts/check-migrations.sh [base-ref]
#
# Checks the SQL files under backend/carenexus-api/src/main/resources/db/migration and db/seed:
#   1. Released files are immutable. A .sql file that exists at the merge base with the base ref
#      (default origin/main, or $MIGRATIONS_BASE_REF) must not be modified, deleted or renamed in the
#      working tree. Only additions are allowed: a fix to a released migration is a new, higher version.
#      Flyway would otherwise reject every database that already ran the old file (checksum mismatch).
#   2. Every .sql file follows Flyway naming (V<version>__<description>.sql or R__<description>.sql);
#      a misnamed file is silently skipped by Flyway and never applied.
#   3. No version is used twice across both folders (V7 and V07 and V7.0 are the same version to Flyway),
#      and no repeatable description is used twice.
#   4. No new versioned file in db/seed: new dev data is a repeatable R__dev_seed_<NN>_<slice>.sql
#      (see db/seed/README.md). MigrationPathsTest (run by `./mvnw verify`) checks the same rule and
#      migrates both Flyway paths against a real PostgreSQL.
# Non-SQL files in those folders (README.md) are documentation and are not checked.
#
# Exit codes: 0 = all checks pass, 1 = a rule is violated, 2 = the check could not run (bad base ref,
# shallow clone, not a git repository).
set -euo pipefail

MIGRATION_DIR="backend/carenexus-api/src/main/resources/db/migration"
SEED_DIR="backend/carenexus-api/src/main/resources/db/seed"

usage() {
  sed -n '2,/^set -euo pipefail/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
esac

BASE_REF="${1:-${MIGRATIONS_BASE_REF:-origin/main}}"

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "check-migrations: not inside a git repository" >&2; exit 2; }
cd "$ROOT"

for d in "$MIGRATION_DIR" "$SEED_DIR"; do
  [ -d "$d" ] || { echo "check-migrations: missing folder $d" >&2; exit 2; }
done

if ! git rev-parse --verify --quiet "${BASE_REF}^{commit}" >/dev/null; then
  echo "check-migrations: base ref '$BASE_REF' not found. Fetch it first (git fetch origin main) or pass another ref." >&2
  exit 2
fi
if ! MERGE_BASE="$(git merge-base "$BASE_REF" HEAD 2>/dev/null)"; then
  echo "check-migrations: no merge base between '$BASE_REF' and HEAD (shallow clone? use fetch-depth: 0)." >&2
  exit 2
fi

failures=0
fail() {
  echo "  - $*"
  failures=$((failures + 1))
}

echo "Flyway migration guard"
echo "  base: $BASE_REF (merge base $(git rev-parse --short "$MERGE_BASE"))"
echo

# ---- 1. released files are immutable -------------------------------------------------------------
# --no-renames reports a rename as a deletion plus an addition, so a renamed released file fails too.
# Comparing against the working tree also covers uncommitted local edits.
echo "1. Released migration and seed files are unchanged"
section_fail=$failures
while IFS=$'\t' read -r status path; do
  [ -n "${status:-}" ] || continue
  case "$path" in
    *.sql) ;;
    *) continue ;;
  esac
  case "$status" in
    A) ;;
    M) fail "$path was modified. Released migrations are immutable: revert it and add a new version instead." ;;
    D) fail "$path was deleted or renamed. Released migrations must stay: restore it." ;;
    *) fail "$path changed (git status $status). Released migrations are immutable." ;;
  esac
done < <(git diff --name-status --no-renames "$MERGE_BASE" -- "$MIGRATION_DIR" "$SEED_DIR")
[ "$failures" -eq "$section_fail" ] && echo "  ok"

# The files present now: tracked files plus untracked ones that are not ignored.
current_files() {
  git ls-files --cached --others --exclude-standard -- "$1" | while IFS= read -r f; do
    # Deleted-but-still-indexed files are reported by rule 1; only list files that exist.
    [ -f "$f" ] && printf '%s\n' "$f"
  done
}

# Flyway version normalisation: separators . and _ are equivalent, leading zeros and trailing zero
# components do not count (1.0 == 1 == 01).
normalise_version() {
  local raw="$1" out="" part
  local IFS='._'
  # shellcheck disable=SC2086
  set -- $raw
  local parts=()
  for part in "$@"; do
    part="$(printf '%s' "$part" | sed 's/^0*//')"
    parts+=("${part:-0}")
  done
  local n=${#parts[@]}
  while [ "$n" -gt 1 ] && [ "${parts[$((n - 1))]}" = "0" ]; do
    n=$((n - 1))
  done
  local i
  for ((i = 0; i < n; i++)); do
    out="${out:+$out.}${parts[$i]}"
  done
  printf '%s' "$out"
}

VERSIONS_FILE="$(mktemp)"
REPEATABLES_FILE="$(mktemp)"
trap 'rm -f "$VERSIONS_FILE" "$REPEATABLES_FILE"' EXIT

# ---- 2. naming ---------------------------------------------------------------------------------------
echo "2. Every SQL file follows Flyway naming"
section_fail=$failures
for dir in "$MIGRATION_DIR" "$SEED_DIR"; do
  while IFS= read -r f; do
    name="$(basename "$f")"
    case "$name" in
      *.sql) ;;
      *) continue ;;
    esac
    if [[ "$name" =~ ^V([0-9]+([._][0-9]+)*)__([^/]+)\.sql$ ]]; then
      printf '%s\t%s\n' "$(normalise_version "${BASH_REMATCH[1]}")" "$f" >> "$VERSIONS_FILE"
    elif [[ "$name" =~ ^R__([^/]+)\.sql$ ]]; then
      desc="${BASH_REMATCH[1]//_/ }"
      printf '%s\t%s\n' "$desc" "$f" >> "$REPEATABLES_FILE"
    else
      fail "$f does not match V<version>__<description>.sql or R__<description>.sql; Flyway would skip it."
    fi
  done < <(current_files "$dir")
done
[ "$failures" -eq "$section_fail" ] && echo "  ok"

# ---- 3. unique versions and repeatable descriptions ------------------------------------------------
echo "3. Versions are unique across db/migration and db/seed"
section_fail=$failures
while IFS= read -r version; do
  [ -n "$version" ] || continue
  files="$(awk -F'\t' -v v="$version" '$1 == v { print $2 }' "$VERSIONS_FILE" | paste -sd ',' - | sed 's/,/, /g')"
  fail "version $version is used more than once: $files"
done < <(cut -f1 "$VERSIONS_FILE" | sort | uniq -d)
while IFS= read -r desc; do
  [ -n "$desc" ] || continue
  files="$(awk -F'\t' -v d="$desc" '$1 == d { print $2 }' "$REPEATABLES_FILE" | paste -sd ',' - | sed 's/,/, /g')"
  fail "repeatable description '$desc' is used more than once: $files"
done < <(cut -f1 "$REPEATABLES_FILE" | sort | uniq -d)
[ "$failures" -eq "$section_fail" ] && echo "  ok"

# ---- 4. no new versioned seed files -----------------------------------------------------------------
echo "4. No new versioned seed file"
section_fail=$failures
while IFS=$'\t' read -r status path; do
  [ "${status:-}" = "A" ] || continue
  name="$(basename "$path")"
  if [[ "$name" =~ ^V[0-9] ]] && [[ "$name" == *.sql ]]; then
    fail "$path is a new versioned seed file. Dev data goes in a repeatable R__dev_seed_<NN>_<slice>.sql (db/seed/README.md)."
  fi
done < <(
  git diff --name-status --no-renames "$MERGE_BASE" -- "$SEED_DIR"
  git ls-files --others --exclude-standard -- "$SEED_DIR" | while IFS= read -r f; do printf 'A\t%s\n' "$f"; done
)
[ "$failures" -eq "$section_fail" ] && echo "  ok"

echo
if [ "$failures" -gt 0 ]; then
  echo "check-migrations: $failures problem(s) found."
  exit 1
fi
echo "check-migrations: all checks passed."
