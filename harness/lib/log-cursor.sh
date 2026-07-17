#!/usr/bin/env bash
# Sequential log matching helpers. Sourced by run.sh and the static self-check.

log_line_count() {
    [[ -f "$1" ]] && wc -l < "$1" | tr -d ' ' || printf '0\n'
}

wait_for_log_since() {
    local log="$1"
    local regex="$2"
    local timeout="$3"
    local first_line="${4:-1}"
    local waited=0
    local relative_line=""
    while (( waited < timeout )); do
        relative_line=""
        if [[ -f "$log" ]]; then
            relative_line="$(tail -n "+$first_line" "$log" | grep -nEm1 "$regex" | cut -d: -f1 || true)"
        fi
        if [[ -n "$relative_line" ]]; then
            WAIT_MATCH_LINE=$((first_line + relative_line - 1))
            return 0
        fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            if [[ -f "$log" ]]; then
                relative_line="$(tail -n "+$first_line" "$log" | grep -nEm1 "$regex" | cut -d: -f1 || true)"
            fi
            if [[ -n "$relative_line" ]]; then
                WAIT_MATCH_LINE=$((first_line + relative_line - 1))
                return 0
            fi
            return 1
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}
