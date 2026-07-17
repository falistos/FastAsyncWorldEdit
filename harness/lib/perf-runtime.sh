#!/usr/bin/env bash
# Runtime measurement helpers. Sourced by harness/run.sh.

epoch_ms() {
    perl -MTime::HiRes=time -e 'printf "%.0f\n", time() * 1000'
}

iso_utc() {
    date -u '+%Y-%m-%dT%H:%M:%SZ'
}

perf_prepare_files() {
    [[ "${HARNESS_PERF:-0}" == "1" ]] || return 0
    : > "$LOG_FILE.perf.tsv"
    : > "$LOG_FILE.gc.log"
    if [[ "${HARNESS_PERF_JSTACK:-0}" == "1" ]]; then
        : > "$LOG_FILE.threads.tsv"
    fi
}

perf_gc_arg() {
    local boot_number="$1"
    [[ "${HARNESS_PERF:-0}" == "1" ]] || return 0
    # Each JVM gets its own unified-log target. The sampler appends it to the
    # contract file when that boot ends, so @restart preserves both legs.
    PERF_BOOT_GC="$LOG_FILE.gc.boot-${boot_number}.log"
    rm -f "$PERF_BOOT_GC"
    printf '%s\n' "-Xlog:gc*:file=${PERF_BOOT_GC}:tags,time,level"
}

perf_sampler_start() {
    [[ "${HARNESS_PERF:-0}" == "1" ]] || return 0
    local interval="${HARNESS_PERF_INTERVAL:-2}"
    [[ "$interval" =~ ^[1-9][0-9]*$ && "$interval" -le 60 ]] || {
        log_error "HARNESS_PERF_INTERVAL must be an integer from 1 to 60 seconds"
        return 2
    }
    local jstack_bin=""
    if [[ "${HARNESS_PERF_JSTACK:-0}" == "1" ]]; then
        jstack_bin="${JAVA_BIN%/java}/jstack"
        if [[ ! -x "$jstack_bin" ]]; then
            log_warn "jstack not found at $jstack_bin; attribution sampling disabled"
            jstack_bin=""
        fi
    fi

    (
        while kill -0 "$SERVER_PID" 2>/dev/null; do
            local now rss
            now="$(epoch_ms)"
            rss="$(ps -o rss= -p "$SERVER_PID" 2>/dev/null | tr -d ' ')"
            [[ -n "$rss" ]] && printf '%s\t%s\n' "$now" "$rss" >> "$LOG_FILE.perf.tsv"
            if [[ -n "$jstack_bin" ]]; then
                "$jstack_bin" "$SERVER_PID" 2>/dev/null | awk -v ts="$now" '
                    function flush() {
                        if (name != "" && has_fawe && !seen[name]++) {
                            printf "%s\t%s\n", ts, name
                        }
                    }
                    /^"/ {
                        flush()
                        name = $0
                        sub(/^"/, "", name)
                        sub(/".*/, "", name)
                        has_fawe = 0
                    }
                    /com\.sk89q\.worldedit/ { has_fawe = 1 }
                    /com\.fastasyncworldedit/ && !/com\.fastasyncworldedit\.harness/ {
                        has_fawe = 1
                    }
                    END { flush() }
                ' >> "$LOG_FILE.threads.tsv" || true
            fi
            sleep "$interval"
        done
        printf '%s\t\tprocess_exit\n' "$(epoch_ms)" >> "$LOG_FILE.perf.tsv"
    ) &
    PERF_SAMPLER_PID=$!
    log_info "perf sampler: interval=${interval}s jstack=$([[ -n "$jstack_bin" ]] && echo on || echo off)"
}

perf_sampler_stop() {
    if [[ -n "${PERF_SAMPLER_PID:-}" ]]; then
        if [[ -n "${SERVER_PID:-}" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
            kill "$PERF_SAMPLER_PID" 2>/dev/null || true
        fi
        wait "$PERF_SAMPLER_PID" 2>/dev/null || true
        PERF_SAMPLER_PID=""
    fi
    if [[ -n "${PERF_BOOT_GC:-}" && -f "$PERF_BOOT_GC" ]]; then
        {
            printf '# boot=%s source=%s\n' "${BOOT_SEQUENCE:-0}" "$(basename "$PERF_BOOT_GC")"
            sed -n '1,$p' "$PERF_BOOT_GC"
        } >> "$LOG_FILE.gc.log"
        rm -f "$PERF_BOOT_GC"
        PERF_BOOT_GC=""
    fi
}

perf_mark() {
    local label="$1"
    [[ "$label" =~ ^[A-Za-z0-9._:-]+$ ]] || {
        log_error "invalid @mark label '$label' (allowed: A-Z a-z 0-9 . _ : -)"
        return 2
    }
    printf '%s\t%s\t%s\n' "$(epoch_ms)" "$(iso_utc)" "$label" >> "$LOG_FILE.marks.tsv"
    log_info "  mark: $label"
    # The say line is the server-clock marker. The probe command adds one
    # owner-thread sample for each fixed region anchor.
    console_action "say PERF_MARK $label"
    console_send "fawe-harness perf-mark $label"
}
