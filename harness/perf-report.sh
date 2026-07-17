#!/usr/bin/env bash
# Aggregate one harness run without substituting probe measurements for FAWE metrics.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec perl "$SCRIPT_DIR/lib/perf-report.pl" "$@"
