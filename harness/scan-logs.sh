#!/usr/bin/env bash
# Scan a Folia harness log for exceptions, ownership violations, watchdog stalls, and FAWE errors.
#
# Usage:
#   scan-logs.sh <logfile>
#   scan-logs.sh --self-test
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "${1:-}" == "--self-test" ]]; then
    tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/fawe-harness-scan.XXXXXX")"
    trap 'rm -rf "$tmp_dir"' EXIT
    printf '[Server thread/INFO]: Done (1.000s)! For help, type "help"\n' > "$tmp_dir/clean.log"
    printf '[Folia Region Scheduler Thread/ERROR]: Thread failed main thread check: seeded violation\n' \
        > "$tmp_dir/violation.log"
    printf '[Folia Region Scheduler Thread/WARN]: bot_01 was kicked for floating too long!\n' \
        > "$tmp_dir/actor-kick.log"
    "$0" "$tmp_dir/clean.log" >/dev/null
    if "$0" "$tmp_dir/violation.log" >/dev/null 2>&1; then
        echo "scan-logs self-test FAIL: seeded ownership violation was not detected" >&2
        exit 1
    fi
    if "$0" "$tmp_dir/actor-kick.log" >/dev/null 2>&1; then
        echo "scan-logs self-test FAIL: seeded actor completion failure was not detected" >&2
        exit 1
    fi
    echo "scan-logs self-test OK"
    exit 0
fi

LOG="${1:-}"
[[ -n "$LOG" && -f "$LOG" ]] || { echo "usage: scan-logs.sh <logfile> | --self-test" >&2; exit 2; }

PATTERNS=(
    'thread failed main thread check'
    'TickThread[^[:alnum:]]*(assert|check|fail|ensure|own)'
    'not owned by (the )?current (region|thread)'
    'owned by current region'
    'may not be ticked from'
    'asynchronous (chunk|entity|world|block)'
    'server (has not responded for|has stopped responding|is not responding)'
    'watchdog.*(stall|timeout|thread dump|crash|fatal)'
    'Watchdog Thread'
    'a single server tick took'
    '(^|[[:space:]])[[:alnum:]_.$]+(Exception|Error)(:|[[:space:]]|$)'
    'Error occurred while (enabling|loading)'
    'Could not (load|enable).*plugin'
    'Failed to (load|enable).*plugin'
    '(FastAsyncWorldEdit|\[FAWE\]|WorldEdit).*(ERROR|SEVERE|Exception|Error)'
    'ERROR.*(FastAsyncWorldEdit|\[FAWE\]|WorldEdit)'
    'com\.(fastasyncworldedit|sk89q\.worldedit)\.[[:alnum:]_.$]*(Exception|Error)'
    'FAWE_HARNESS_[A-Z_]*FAIL'
    '(FAWE_PERF|FAWE_PROBE_PERF).*result=(partial|fail)'
    'BOT-ERR'
    'was kicked for floating too long'
    'Flying is not enabled on this server'
)

REGEX="$(IFS='|'; printf '%s' "${PATTERNS[*]}")"
hits="$(grep -niE "$REGEX" "$LOG" || true)"

if [[ -n "$hits" ]]; then
    printf '\033[1;31mFAIL\033[0m %s — zero-tolerance log violation(s):\n' "$LOG" >&2
    printf '%s\n' "$hits" >&2
    exit 1
fi

printf '\033[1;32mOK\033[0m %s — no exceptions, ownership violations, watchdog stalls, or FAWE errors\n' "$LOG"
