#!/usr/bin/env bash
# Static/offline check for the measurement extension. Does not boot a server.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/fawe-perf-self-check.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

while IFS= read -r script; do
    bash -n "$script"
done < <(rg --files "$SCRIPT_DIR" -g '*.sh' | sort)
perl -c "$SCRIPT_DIR/scenario-expand.pl" >/dev/null
perl -c "$SCRIPT_DIR/lib/perf-report.pl" >/dev/null
"$SCRIPT_DIR/scan-logs.sh" --self-test >/dev/null

[[ -f "$SCRIPT_DIR/lib/ssb2-bots-26.1.jar" ]]
unzip -tqq "$SCRIPT_DIR/lib/ssb2-bots-26.1.jar"
grep -q '^FOLIA_26_1_1_BUILD=UNSTAGED$' "$SCRIPT_DIR/versions.env"

# One command can emit several markers before the first wait runs. Advancing
# to the matched line must preserve later markers already present in the log.
# shellcheck source=lib/log-cursor.sh
source "$SCRIPT_DIR/lib/log-cursor.sh"
printf 'first marker\nsecond marker\n' > "$TMP_DIR/cursor.log"
SERVER_PID="$$"
WAIT_MATCH_LINE=0
wait_for_log_since "$TMP_DIR/cursor.log" 'first marker' 1 1
[[ "$WAIT_MATCH_LINE" == "1" ]]
wait_for_log_since "$TMP_DIR/cursor.log" 'second marker' 1 "$((WAIT_MATCH_LINE + 1))"
[[ "$WAIT_MATCH_LINE" == "2" ]]

# Prove pipeline-probe invokes both external boots, removes --fresh only from
# readback, and reports the phase explicitly when either run/scan gate fails.
MOCK_RUNNER="$TMP_DIR/mock-runner.sh"
printf '%s\n' \
    '#!/usr/bin/env bash' \
    'printf '\''%s\t%s\n'\'' "${HARNESS_SCENARIO_PHASE:-unset}" "$*" >> "$MOCK_PHASE_LOG"' \
    'if [[ "${MOCK_FAIL_PHASE:-}" == "${HARNESS_SCENARIO_PHASE:-}" ]]; then' \
    '    exit 7' \
    'fi' \
    'exit 0' \
    > "$MOCK_RUNNER"
chmod +x "$MOCK_RUNNER"
MOCK_PHASE_LOG="$TMP_DIR/phases.log" HARNESS_SCENARIO_RUNNER="$MOCK_RUNNER" \
    "$SCRIPT_DIR/scenario.sh" pipeline-probe --version 26.1.2 --fresh \
    > /dev/null 2> "$TMP_DIR/phases.stderr"
[[ "$(wc -l < "$TMP_DIR/phases.log" | tr -d ' ')" == "2" ]]
awk -F '\t' 'NR == 1 && $1 == "initial" && $2 ~ /--fresh/ { found = 1 } END { exit !found }' \
    "$TMP_DIR/phases.log"
awk -F '\t' 'NR == 2 && $1 == "readback" { found = 1 } END { exit !found }' \
    "$TMP_DIR/phases.log"
! sed -n '2p' "$TMP_DIR/phases.log" | grep -q -- '--fresh'
grep -q 'pipeline-probe complete phases=initial,readback gates=run,scan' "$TMP_DIR/phases.stderr"

: > "$TMP_DIR/phases-fail.log"
set +e
MOCK_PHASE_LOG="$TMP_DIR/phases-fail.log" MOCK_FAIL_PHASE=readback \
    HARNESS_SCENARIO_RUNNER="$MOCK_RUNNER" \
    "$SCRIPT_DIR/scenario.sh" pipeline-probe --version 26.1.2 --fresh \
    > /dev/null 2> "$TMP_DIR/phases-fail.stderr"
phase_rc=$?
set -e
[[ "$phase_rc" == "7" ]]
[[ "$(wc -l < "$TMP_DIR/phases-fail.log" | tr -d ' ')" == "2" ]]
grep -q 'pipeline-probe failed phase=readback gate=run-or-scan exit=7' "$TMP_DIR/phases-fail.stderr"

PERF_TRIALS=2 PERF_WARMUP=1 PERF_SUSTAIN_OPS=3 PERF_PLAYERS=4 REGION_SEP=33600 \
    "$SCRIPT_DIR/scenario-expand.pl" "$SCRIPT_DIR/scenarios/perf-01-small-edit.md" \
    > "$TMP_DIR/expanded"
[[ "$(grep -c '^@mark op:perf01:begin$' "$TMP_DIR/expanded")" == "2" ]]
! grep -q '^```' "$TMP_DIR/expanded"

LOG="$TMP_DIR/run.log"
printf '%s\n' \
    '[00:00:00 INFO]: FAWE_PROBE_PERF op=probe elapsed_ms=12.5 blocks=4096 regions=4 result=ok source=probe' \
    '[00:00:00 INFO]: FAWE_PROBE_COMMIT op=probe max_schedule_delay_us=50000 max_commit_task_us=700 visibility_us=51000 source=probe' \
    '[00:00:00 INFO]: FAWE_PROBE_QUEUE op=probe depth=0 inflight=0 outstanding=0 depth_highwater=4 inflight_highwater=2 source=probe' \
    '[00:00:00 INFO]: FAWE_PROBE_REGION_SAMPLE label=op:probe:begin group=0 region_id=4 owner=true tps_5s=20.000,20.000,20.000 tick_avg_ns=1000 tick_max_ns=2000 source=rolling_5s thread=Folia_Region_Scheduler_Thread_#0' \
    '[00:00:00 INFO]: FAWE_PROBE_REGION_SAMPLE label=op:probe:end group=0 region_id=4 owner=true tps_5s=19.900,20.000,20.000 tick_avg_ns=1500 tick_max_ns=2500 source=rolling_5s thread=Folia_Region_Scheduler_Thread_#0' \
    > "$LOG"
printf '1000\t1970-01-01T00:00:01Z\top:probe:begin\n2000\t1970-01-01T00:00:02Z\top:probe:end\n2100\t1970-01-01T00:00:02Z\top:stop:begin\n' > "$LOG.marks.tsv"
printf '1000\t100000\n2000\t110000\n2300\t\tprocess_exit\n' > "$LOG.perf.tsv"
printf '1000\tFolia Region Scheduler Thread #0\n1001\tFolia Region Scheduler Thread #1\n' > "$LOG.threads.tsv"
printf '[1.000s][info][gc] GC(0) Pause Young 1.250ms\n' > "$LOG.gc.log"

"$SCRIPT_DIR/perf-report.sh" "$LOG" --out "$TMP_DIR/report.json" \
    --require-region-threads 2 >/dev/null
perl -MJSON::PP -0777 -e '
    my $r = decode_json(<>);
    die "missing probe metrics" unless $r->{probe_operations}{operations} == 1;
    die "probe mislabeled as FAWE" if exists $r->{fawe_operations};
    die "missing marked window" unless $r->{marked_windows}[0]{elapsed_ms} == 1000;
    die "missing RSS" unless $r->{rss_kb}{peak} == 110000;
    die "missing GC" unless $r->{gc_pause_ms}{samples} == 1;
    die "missing attribution" unless $r->{region_thread_attribution}{distinct_region_threads} == 2;
    die "missing shutdown drain" unless $r->{shutdown_drain_upper_bound_ms} == 200;
    die "missing region window" unless $r->{probe_region_windows}[0]{tick_avg_delta_ns} == 500;
' < "$TMP_DIR/report.json"

REAL_LOG="$TMP_DIR/real.log"
printf '[00:00:00 INFO]: FAWE_PERF op=real elapsed_ms=10 blocks=100 region=r0 result=ok\n' > "$REAL_LOG"
"$SCRIPT_DIR/perf-report.sh" "$REAL_LOG" --out "$TMP_DIR/real.json" >/dev/null
perl -MJSON::PP -0777 -e '
    my $r = decode_json(<>);
    die "missing FAWE metrics" unless $r->{fawe_operations}{operations} == 1;
    die "FAWE mislabeled as probe" if exists $r->{probe_operations};
    die "invented RSS" if exists $r->{rss_kb};
    die "unexpected pending driver" if exists $r->{pending};
' < "$TMP_DIR/real.json"

printf '[00:00:00 INFO]: FAWE_PROBE_PERF op=bad result=fail\n' > "$TMP_DIR/perf-fail.log"
if "$SCRIPT_DIR/scan-logs.sh" "$TMP_DIR/perf-fail.log" >/dev/null 2>&1; then
    echo 'perf self-check: scanner accepted a failed performance record' >&2
    exit 1
fi

printf 'perf self-check: PASS\n'
