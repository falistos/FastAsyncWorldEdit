#!/usr/bin/env bash
# Boot Folia, execute one named harness scenario, scan the log, and stop the server.
#
# Usage:
#   scenario.sh <smoke-set|pipeline-probe|perf-probe> [--with-plugin|--without-plugin]
#               [--version <26.1.1|26.1.2>] [--fresh]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="${HARNESS_SCENARIO_RUNNER:-$SCRIPT_DIR/run.sh}"
[[ -x "$RUNNER" ]] || { echo "scenario runner is not executable: $RUNNER" >&2; exit 2; }

SCENARIO="${1:-}"
[[ -n "$SCENARIO" ]] || { echo "usage: scenario.sh <smoke-set|pipeline-probe|perf-probe> [run options]" >&2; exit 2; }
shift

case "$SCENARIO" in
    smoke-set)
        SCENARIO_FILE="$SCRIPT_DIR/scenarios/smoke-set.md"
        ;;
    pipeline-probe)
        SCENARIO_FILE="$SCRIPT_DIR/scenarios/pipeline-probe.md"
        RUN_ARGS=("$@")
        set +e
        HARNESS_EXTRA_WORLD=1 HARNESS_SCENARIO_PHASE=initial \
            "$RUNNER" --without-plugin "${RUN_ARGS[@]}" --scenario "$SCENARIO_FILE"
        initial_rc=$?
        set -e
        if (( initial_rc != 0 )); then
            echo "pipeline-probe failed phase=initial gate=run-or-scan exit=$initial_rc" >&2
            exit "$initial_rc"
        fi

        # A --fresh second boot would erase the section whose persistence is being tested.
        READBACK_ARGS=()
        for arg in "${RUN_ARGS[@]}"; do
            [[ "$arg" == "--fresh" ]] || READBACK_ARGS+=("$arg")
        done
        set +e
        HARNESS_EXTRA_WORLD=1 HARNESS_SCENARIO_PHASE=readback \
            "$RUNNER" --without-plugin "${READBACK_ARGS[@]}" --scenario "$SCENARIO_FILE"
        readback_rc=$?
        set -e
        if (( readback_rc != 0 )); then
            echo "pipeline-probe failed phase=readback gate=run-or-scan exit=$readback_rc" >&2
            exit "$readback_rc"
        fi
        echo "pipeline-probe complete phases=initial,readback gates=run,scan" >&2
        exit 0
        ;;
    perf-probe)
        SCENARIO_FILE="$SCRIPT_DIR/scenarios/perf-probe.md"
        : "${HARNESS_PERF:=1}"
        export HARNESS_PERF
        ;;
    perf-0[1-7])
        if [[ "${HARNESS_FAWE_DRIVER_READY:-0}" != "1" ]]; then
            echo "$SCENARIO uses the frozen real-FAWE driver slot, pending wave 1; run perf-probe for probe metrics" >&2
            exit 3
        fi
        # The files have descriptive suffixes, so resolve the exact frozen spec.
        shopt -s nullglob
        candidate=("$SCRIPT_DIR/scenarios/$SCENARIO"-*.md)
        shopt -u nullglob
        [[ ${#candidate[@]} -eq 1 && -f "${candidate[0]}" ]] || {
            echo "could not resolve scenario spec for $SCENARIO" >&2
            exit 2
        }
        SCENARIO_FILE="${candidate[0]}"
        case "$SCENARIO" in
            perf-01) : "${PERF_TRIALS:=30}"; : "${PERF_WARMUP:=5}" ;;
            perf-02) : "${PERF_TRIALS:=20}"; : "${PERF_WARMUP:=3}" ;;
            perf-03) : "${PERF_TRIALS:=10}"; : "${PERF_WARMUP:=2}" ;;
            perf-04) : "${PERF_TRIALS:=15}"; : "${PERF_WARMUP:=3}" ;;
            perf-05) : "${PERF_TRIALS:=15}" ;;
            perf-06) : "${PERF_SUSTAIN_OPS:=200}" ;;
            perf-07) : "${PERF_PLAYERS:=4}" ;;
        esac
        export PERF_TRIALS PERF_WARMUP PERF_SUSTAIN_OPS PERF_PLAYERS
        ;;
    *)
        echo "unknown scenario '$SCENARIO' (expected: smoke-set, pipeline-probe, or perf-probe)" >&2
        exit 2
        ;;
esac

exec "$RUNNER" "$@" --scenario "$SCENARIO_FILE"
