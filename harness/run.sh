#!/usr/bin/env bash
# Provision, boot, and stop one pinned Folia runtime using only pre-staged artifacts.
#
# Usage:
#   run.sh --version <26.1.1|26.1.2> [--with-plugin|--without-plugin]
#          [--fresh] [--scenario <file>]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

VERSION="26.1.2"
WITH_PLUGIN=1
FRESH=0
SCENARIO=""
PORT="${HARNESS_PORT:-25601}"
BOOT_TIMEOUT="${HARNESS_BOOT_TIMEOUT:-240}"
STOP_TIMEOUT="${HARNESS_STOP_TIMEOUT:-120}"
STEP_DELAY="${HARNESS_STEP_DELAY:-1}"
HEAP="${HARNESS_HEAP:-2G}"
SCENARIO_PHASE="${HARNESS_SCENARIO_PHASE:-}"
EXTRA_WORLD="${HARNESS_EXTRA_WORLD:-0}"
PERF_TRIALS="${PERF_TRIALS:-10}"
PERF_WARMUP="${PERF_WARMUP:-2}"
PERF_SUSTAIN_OPS="${PERF_SUSTAIN_OPS:-200}"
PERF_PLAYERS="${PERF_PLAYERS:-4}"
REGION_SEP="${REGION_SEP:-33600}"

log_info() { printf '\033[1;34m[harness]\033[0m %s\n' "$*" >&2; }
log_warn() { printf '\033[1;33m[harness]\033[0m %s\n' "$*" >&2; }
log_error() { printf '\033[1;31m[harness]\033[0m %s\n' "$*" >&2; }
die() { log_error "$*"; exit 1; }

# shellcheck source=lib/perf-runtime.sh
source "$SCRIPT_DIR/lib/perf-runtime.sh"
# shellcheck source=lib/log-cursor.sh
source "$SCRIPT_DIR/lib/log-cursor.sh"

usage() {
    sed -n '2,6s/^# \{0,1\}//p' "$0"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)
            [[ $# -ge 2 ]] || die "--version needs a value"
            VERSION="$2"
            shift 2
            ;;
        --with-plugin)
            WITH_PLUGIN=1
            shift
            ;;
        --without-plugin)
            WITH_PLUGIN=0
            shift
            ;;
        --fresh)
            FRESH=1
            shift
            ;;
        --scenario)
            [[ $# -ge 2 ]] || die "--scenario needs a file"
            SCENARIO="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unexpected argument: $1"
            ;;
    esac
done

# shellcheck source=versions.env
source "$SCRIPT_DIR/versions.env"

case "$VERSION" in
    26.1.2)
        FOLIA_BUILD="$FOLIA_26_1_2_BUILD"
        FOLIA_JAR="$FOLIA_26_1_2_JAR"
        FOLIA_SHA256="$FOLIA_26_1_2_SHA256"
        ;;
    26.1.1)
        FOLIA_BUILD="$FOLIA_26_1_1_BUILD"
        FOLIA_JAR="$FOLIA_26_1_1_JAR"
        FOLIA_SHA256="$FOLIA_26_1_1_SHA256"
        ;;
    *)
        die "unsupported Folia version '$VERSION' (expected 26.1.1 or 26.1.2)"
        ;;
esac

[[ "$FOLIA_BUILD" != "UNSTAGED" && "$FOLIA_SHA256" != "UNSTAGED" ]] \
    || die "Folia $VERSION is a placeholder in versions.env; select and pre-stage its certified build first"

CACHED_JAR="$SCRIPT_DIR/.cache/$FOLIA_JAR"
SERVER_DIR="$SCRIPT_DIR/servers/$VERSION"
LOG_DIR="$SCRIPT_DIR/logs"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

resolve_java() {
    local java_bin=""
    local java_home=""
    if [[ -n "${JAVA_HOME_RUNTIME:-}" && -x "$JAVA_HOME_RUNTIME/bin/java" ]]; then
        java_bin="$JAVA_HOME_RUNTIME/bin/java"
    elif [[ -x /usr/libexec/java_home ]]; then
        java_home="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
        [[ -n "$java_home" && -x "$java_home/bin/java" ]] && java_bin="$java_home/bin/java"
    fi
    if [[ -z "$java_bin" && -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        java_bin="$JAVA_HOME/bin/java"
    fi
    if [[ -z "$java_bin" ]] && command -v java >/dev/null 2>&1; then
        java_bin="$(command -v java)"
    fi

    [[ -n "$java_bin" ]] \
        || die "Java 25+ not found; set JAVA_HOME_RUNTIME to a JDK 25 installation"

    local version_line=""
    local major=""
    version_line="$("$java_bin" -version 2>&1 | sed -n '1p')"
    major="$(printf '%s' "$version_line" | sed -nE 's/.*version "([0-9]+).*/\1/p')"
    [[ -n "$major" ]] || die "could not parse Java version from: $version_line"
    (( major >= 25 )) \
        || die "Java $major is too old for Minecraft 26.x; set JAVA_HOME_RUNTIME to JDK 25+"

    JAVA_BIN="$java_bin"
    log_info "Java runtime: $JAVA_BIN ($version_line)"
}

select_fawe_jar() {
    if [[ -n "${HARNESS_FAWE_JAR:-}" ]]; then
        [[ -f "$HARNESS_FAWE_JAR" ]] || die "HARNESS_FAWE_JAR does not exist: $HARNESS_FAWE_JAR"
        FAWE_JAR="$HARNESS_FAWE_JAR"
        return
    fi

    local libs="$REPO_DIR/worldedit-bukkit/build/libs"
    local candidates=()
    shopt -s nullglob
    candidates=("$libs"/FastAsyncWorldEdit-Paper-*.jar)
    if (( ${#candidates[@]} == 0 )); then
        candidates=("$libs"/FastAsyncWorldEdit-Bukkit-*.jar)
    fi
    shopt -u nullglob

    (( ${#candidates[@]} > 0 )) \
        || die "no FAWE runtime jar found in $libs; build worldedit-bukkit or set HARNESS_FAWE_JAR"
    (( ${#candidates[@]} == 1 )) \
        || die "multiple FAWE runtime jars found in $libs; select one with HARNESS_FAWE_JAR"
    FAWE_JAR="${candidates[0]}"
}

build_probe() {
    local probe_jar="$SCRIPT_DIR/.cache/FAWEHarnessProbe.jar"
    "$SCRIPT_DIR/probe/build.sh" "$CACHED_JAR" "${JAVA_BIN%/java}" "$probe_jar"
    cp -f "$probe_jar" "$SERVER_DIR/plugins/FAWEHarnessProbe.jar"
    log_info "region-owner probe: $probe_jar"
}

provision_server() {
    if [[ "$FRESH" == "1" && -d "$SERVER_DIR" ]]; then
        log_info "--fresh: wiping $SERVER_DIR"
        rm -rf "$SERVER_DIR"
    fi
    mkdir -p "$SERVER_DIR/plugins" "$LOG_DIR"

    printf 'eula=true\n' > "$SERVER_DIR/eula.txt"
    {
        printf 'online-mode=false\n'
        printf 'enforce-secure-profile=false\n'
        # Headless actors confirm teleports but do not continuously simulate
        # grounded movement. Keep them online long enough for completion acks.
        printf 'allow-flight=true\n'
        printf 'server-port=%s\n' "$PORT"
        printf 'motd=FAWE Folia-port harness\n'
        printf 'max-players=%s\n' "${HARNESS_MAX_PLAYERS:-16}"
        printf 'view-distance=4\n'
        printf 'simulation-distance=4\n'
        printf 'spawn-protection=0\n'
        printf 'level-name=fawe-harness\n'
        printf 'level-type=minecraft:flat\n'
        printf 'generator-settings={}\n'
        printf 'enable-command-block=false\n'
        printf 'enable-rcon=false\n'
        printf 'sync-chunk-writes=false\n'
    } > "$SERVER_DIR/server.properties"

    if [[ "$EXTRA_WORLD" == "1" ]]; then
        {
            printf '# FAWE harness W0.2 startup-world probe\n'
            printf 'worlds:\n'
            printf '  fawe-scratch:\n'
            printf '    generator: FAWEHarnessProbe\n'
        } > "$SERVER_DIR/bukkit.yml"
        log_info "startup-world probe: bukkit.yml declares fawe-scratch -> FAWEHarnessProbe"
    elif [[ "$FRESH" == "1" ]]; then
        rm -f "$SERVER_DIR/bukkit.yml"
    fi

    rm -f "$SERVER_DIR/plugins/FastAsyncWorldEdit.jar"
    rm -f "$SERVER_DIR/plugins/FAWEHarnessProbe.jar"
    build_probe
    if [[ "$WITH_PLUGIN" == "1" ]]; then
        select_fawe_jar
        cp -f "$FAWE_JAR" "$SERVER_DIR/plugins/FastAsyncWorldEdit.jar"
        log_info "FAWE plugin: $FAWE_JAR"
    else
        log_info "FAWE plugin: disabled"
    fi
}

console_send() {
    printf '%s\n' "$1" >&3 2>/dev/null || log_warn "console write failed: $1"
}

console_action() {
    WAIT_LINE=$(( $(log_line_count "$LOG_FILE") + 1 ))
    console_send "$1"
}

start_bots() {
    local count="$1"
    local bot_jar="$SCRIPT_DIR/lib/ssb2-bots-26.1.jar"
    [[ -f "$bot_jar" ]] || {
        log_error "pre-staged bot driver missing: $bot_jar"
        return 1
    }
    [[ -z "$BOT_PID" ]] || {
        log_error "bot driver is already running (pid=$BOT_PID)"
        return 1
    }

    BOT_FIFO="$SERVER_DIR/bots.in"
    rm -f "$BOT_FIFO"
    mkfifo "$BOT_FIFO"
    exec 4<>"$BOT_FIFO"
    (cd "$SERVER_DIR" && exec "$JAVA_BIN" -jar "$bot_jar" \
        --host 127.0.0.1 --port "$PORT" --count "$count" \
        --stagger 500 --no-idle --name-prefix pipeline_0) \
        < "$BOT_FIFO" > >(tee -a "$LOG_FILE") 2>&1 &
    BOT_PID=$!
    BOT_COUNT="$count"
    log_info "bot driver: $bot_jar count=$count pid=$BOT_PID"
}

bot_name() {
    local index="$1" count="$2"
    local width="${#count}"
    printf 'pipeline_0%0*d' "$width" "$index"
}

spawn_perf_bots() {
    local count="$1" separation="$2"
    [[ "$count" =~ ^[1-9][0-9]*$ ]] || { log_error "invalid bot count: $count"; return 2; }
    [[ "$separation" =~ ^[1-9][0-9]*$ ]] || { log_error "invalid region separation: $separation"; return 2; }
    WAIT_LINE=$(( $(log_line_count "$LOG_FILE") + 1 ))
    start_bots "$count"
    BOT_REGION_SEP="$separation"
    local last
    last="$(bot_name "$count" "$count")"
    if ! wait_for_log_since "$LOG_FILE" "$last joined the game" 180 "$WAIT_LINE"; then
        log_error "bot driver did not join all $count actors"
        return 1
    fi
    local i name x z
    for ((i = 1; i <= count; i++)); do
        name="$(bot_name "$i" "$count")"
        x=$(( (i - 1) * separation ))
        z=$(( (i % 2) * separation ))
        console_send "op $name"
        console_send "execute in minecraft:overworld run tp $name $x 82 $z"
    done
    sleep "$STEP_DELAY"
}

run_perf_bots() {
    local spec="$1"
    [[ "$spec" == *"edit=set"* ]] || { log_error "unsupported @bots run workload: $spec"; return 2; }
    local block="stone" loops="10" footprint="medium"
    [[ "$spec" =~ block=([^[:space:]]+) ]] && block="${BASH_REMATCH[1]}"
    [[ "$spec" =~ loops=([0-9]+) ]] && loops="${BASH_REMATCH[1]}"
    [[ "$spec" =~ footprint=([^[:space:]]+) ]] && footprint="${BASH_REMATCH[1]}"
    [[ "$footprint" == "medium" ]] || { log_error "unsupported bot footprint: $footprint"; return 2; }

    local i n name base_x base_z
    for ((i = 1; i <= BOT_COUNT; i++)); do
        name="$(bot_name "$i" "$BOT_COUNT")"
        base_x=$(( (i - 1) * BOT_REGION_SEP ))
        base_z=$(( (i % 2) * BOT_REGION_SEP ))
        bot_send "exec $name //pos1 ${base_x},-64,${base_z}"
        bot_send "exec $name //pos2 $((base_x + 127)),127,$((base_z + 127))"
        for ((n = 1; n <= loops; n++)); do
            bot_send "exec $name //set $block"
        done
    done
    log_info "  bot workload issued: actors=$BOT_COUNT footprint=$footprint loops=$loops"
}

bot_send() {
    [[ -n "$BOT_PID" ]] || {
        log_error "bot control requested before @bots start"
        return 1
    }
    printf '%s\n' "$1" >&4 2>/dev/null || {
        log_error "bot control write failed: $1"
        return 1
    }
}

stop_bots() {
    [[ -n "$BOT_PID" ]] || return 0
    if kill -0 "$BOT_PID" 2>/dev/null; then
        kill -TERM "$BOT_PID" 2>/dev/null || true
        local waited=0
        while kill -0 "$BOT_PID" 2>/dev/null && (( waited < 15 )); do
            sleep 1
            waited=$((waited + 1))
        done
        if kill -0 "$BOT_PID" 2>/dev/null; then
            log_warn "bot driver did not stop in 15s; killing pid $BOT_PID"
            kill -KILL "$BOT_PID" 2>/dev/null || true
        fi
        wait "$BOT_PID" 2>/dev/null || true
    fi
    exec 4>&- 2>/dev/null || true
    rm -f "$BOT_FIFO"
    BOT_PID=""
    BOT_FIFO=""
    BOT_COUNT=0
    BOT_REGION_SEP=0
}

wait_for_exit() {
    local timeout="$1"
    local waited=0
    while (( waited < timeout )); do
        kill -0 "$SERVER_PID" 2>/dev/null || return 0
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

stop_server() {
    [[ -n "$SERVER_PID" ]] || return 0
    kill -0 "$SERVER_PID" 2>/dev/null || return 0
    console_action "stop"
    if ! wait_for_exit "$STOP_TIMEOUT"; then
        log_error "server did not stop within ${STOP_TIMEOUT}s; killing process $SERVER_PID"
        kill "$SERVER_PID" 2>/dev/null || true
        sleep 2
        kill -9 "$SERVER_PID" 2>/dev/null || true
        return 1
    fi
}

reap_server() {
    [[ -n "$SERVER_PID" ]] || return 0
    local rc=0
    wait "$SERVER_PID" || rc=$?
    # The JVM has flushed its unified log. Let the sampler observe process exit
    # before aggregating this boot's GC file.
    perf_sampler_stop
    if (( rc != 0 )); then
        log_error "server process exited with status $rc"
        SERVER_FAILURE=1
    fi
    SERVER_PID=""
    exec 3>&- 2>/dev/null || true
    rm -f "$FIFO"
}

start_server() {
    BOOT_SEQUENCE=$((BOOT_SEQUENCE + 1))
    rm -f "$FIFO"
    mkfifo "$FIFO"
    exec 3<>"$FIFO"

    local boot_line gc_arg=""
    boot_line=$(( $(log_line_count "$LOG_FILE") + 1 ))
    if [[ "${HARNESS_PERF:-0}" == "1" ]]; then
        gc_arg="$(perf_gc_arg "$BOOT_SEQUENCE")"
        # perf_gc_arg runs in command substitution; retain its selected path here.
        PERF_BOOT_GC="$LOG_FILE.gc.boot-${BOOT_SEQUENCE}.log"
    fi

    local java_args=(
        "-Xms${HEAP}" "-Xmx${HEAP}"
        -DPaper.IgnoreJavaVersion=false
        -Dcom.mojang.eula.agree=true
    )
    [[ -n "$gc_arg" ]] && java_args+=("$gc_arg")
    (cd "$SERVER_DIR" && exec "$JAVA_BIN" "${java_args[@]}" -jar "$CACHED_JAR" --nogui) \
        < "$FIFO" > >(tee -a "$LOG_FILE") 2>&1 &
    SERVER_PID=$!
    perf_sampler_start
    log_info "booting Folia $VERSION build $FOLIA_BUILD on port $PORT (pid=$SERVER_PID boot=$BOOT_SEQUENCE)"

    if ! wait_for_log_since "$LOG_FILE" 'Done \(|For help, type "help"' "$BOOT_TIMEOUT" "$boot_line"; then
        log_error "server did not reach Done within ${BOOT_TIMEOUT}s on boot $BOOT_SEQUENCE"
        return 1
    fi
    WAIT_LINE="$boot_line"
    log_info "server READY: Folia $VERSION build $FOLIA_BUILD boot=$BOOT_SEQUENCE"
}

restart_server() {
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        log_info "  @restart stopping the current boot"
        stop_server || return 1
    fi
    reap_server
    log_info "  @restart starting the same server directory"
    start_server
}

feed_scenario() {
    local file="$1"
    local log="$2"
    local lineno=0
    local active=1
    local expanded="$SERVER_DIR/scenario-expanded.$$"
    [[ -f "$file" ]] || { log_error "scenario not found: $file"; return 2; }
    log_info "scenario: $file"

    export PERF_TRIALS PERF_WARMUP PERF_SUSTAIN_OPS PERF_PLAYERS REGION_SEP
    if ! "$SCRIPT_DIR/scenario-expand.pl" "$file" > "$expanded"; then
        rm -f "$expanded"
        return 2
    fi

    while IFS= read -r raw || [[ -n "$raw" ]]; do
        lineno=$((lineno + 1))
        local line="${raw#"${raw%%[![:space:]]*}"}"
        [[ -z "$line" || "${line:0:1}" == "#" ]] && continue
        case "$line" in
            @phase\ *)
                local phase="${line#@phase }"
                if [[ -z "$SCENARIO_PHASE" || "$SCENARIO_PHASE" == "$phase" ]]; then
                    active=1
                else
                    active=0
                fi
                continue
                ;;
            @endphase)
                active=1
                continue
                ;;
        esac
        (( active == 1 )) || continue
        case "$line" in
            @sleep\ *)
                local seconds="${line#@sleep }"
                log_info "  sleep ${seconds}s"
                sleep "$seconds"
                ;;
            @waitfor\ *)
                local rest="${line#@waitfor }"
                local last="${rest##* }"
                local regex="$rest"
                local timeout=60
                if [[ "$last" =~ ^[0-9]+$ && "$last" != "$rest" ]]; then
                    timeout="$last"
                    regex="${rest% *}"
                fi
                log_info "  waitfor /$regex/ (<=${timeout}s)"
                if ! wait_for_log_since "$log" "$regex" "$timeout" "$WAIT_LINE"; then
                    log_error "scenario line $lineno timed out waiting for /$regex/"
                    rm -f "$expanded"
                    return 1
                fi
                # Advance past only the matched line. A single command may emit
                # several markers before the first wait observes any of them.
                WAIT_LINE=$((WAIT_MATCH_LINE + 1))
                ;;
            @expect\ *)
                local regex="${line#@expect }"
                if ! grep -Eq "$regex" "$log"; then
                    log_error "scenario line $lineno did not find /$regex/"
                    rm -f "$expanded"
                    return 1
                fi
                ;;
            @mark\ *)
                perf_mark "${line#@mark }" || { rm -f "$expanded"; return 1; }
                sleep "$STEP_DELAY"
                ;;
            @restart)
                restart_server || { rm -f "$expanded"; return 1; }
                ;;
            @with-plugin\ *)
                if [[ "$WITH_PLUGIN" == "1" ]]; then
                    local command="${line#@with-plugin }"
                    log_info "  > $command"
                    console_action "$command"
                    sleep "$STEP_DELAY"
                fi
                ;;
            @bots\ start\ *)
                local count="${line#@bots start }"
                log_info "  bots start count=$count"
                start_bots "$count"
                ;;
            @bots\ stop)
                log_info "  bots stop"
                stop_bots
                ;;
            @botcmd\ *)
                local bot_command="${line#@botcmd }"
                log_info "  bot> $bot_command"
                bot_send "$bot_command"
                ;;
            @bots\ spawn\ *)
                local bot_args="${line#@bots spawn }"
                local count="${bot_args%% *}"
                local separation="$REGION_SEP"
                [[ "$bot_args" =~ regionsep=([0-9]+) ]] && separation="${BASH_REMATCH[1]}"
                log_info "  bots spawn count=$count regionsep=$separation"
                spawn_perf_bots "$count" "$separation" || { rm -f "$expanded"; return 1; }
                ;;
            @bots\ run\ *)
                run_perf_bots "${line#@bots run }" || { rm -f "$expanded"; return 1; }
                ;;
            @bots\ despawn)
                log_info "  bots despawn"
                stop_bots
                ;;
            *)
                log_info "  > $line"
                console_action "$line"
                sleep "$STEP_DELAY"
                ;;
        esac
    done < "$expanded"
    rm -f "$expanded"
}

[[ -f "$CACHED_JAR" ]] \
    || die "missing pre-staged Folia jar: $CACHED_JAR (the harness does not download artifacts)"
actual_sha="$(sha256_of "$CACHED_JAR")"
[[ "$actual_sha" == "$FOLIA_SHA256" ]] \
    || die "sha256 mismatch for $CACHED_JAR: expected $FOLIA_SHA256, got $actual_sha"
[[ -z "$SCENARIO" || -f "$SCENARIO" ]] || die "scenario file not found: $SCENARIO"

resolve_java
provision_server

timestamp="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$LOG_DIR/folia-${VERSION}-${timestamp}.log"
rm -f "$LOG_FILE"
FIFO="$SERVER_DIR/console.in"
SERVER_PID=""
BOT_PID=""
BOT_FIFO=""
BOT_COUNT=0
BOT_REGION_SEP=0
PERF_SAMPLER_PID=""
PERF_BOOT_GC=""
BOOT_SEQUENCE=0
SERVER_FAILURE=0
WAIT_LINE=1
WAIT_MATCH_LINE=0
perf_prepare_files
cleanup() {
    stop_bots || true
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        stop_server || true
    fi
    perf_sampler_stop || true
    exec 3>&- 2>/dev/null || true
    rm -f "$FIFO"
}
trap cleanup EXIT INT TERM

log_info "log: $LOG_FILE"
start_server

run_rc=0
failed_gates=""
if [[ -n "$SCENARIO" ]]; then
    feed_scenario "$SCENARIO" "$LOG_FILE" || {
        run_rc=$?
        failed_gates="scenario"
    }
fi
stop_bots || {
    run_rc=1
    failed_gates="${failed_gates:+$failed_gates,}bots"
}
stop_server || {
    run_rc=1
    failed_gates="${failed_gates:+$failed_gates,}shutdown"
}
reap_server

if [[ -n "$SCENARIO" ]]; then
    # Give the stdout tee a moment to drain the final shutdown lines before scanning.
    sleep 1
    "$SCRIPT_DIR/scan-logs.sh" "$LOG_FILE" || {
        run_rc=1
        failed_gates="${failed_gates:+$failed_gates,}log-scan"
    }
fi
if (( SERVER_FAILURE != 0 )); then
    run_rc=1
    failed_gates="${failed_gates:+$failed_gates,}server-process"
fi
if (( run_rc != 0 )); then
    log_error "run failed phase=${SCENARIO_PHASE:-all} gates=${failed_gates:-unknown} exit=$run_rc"
fi

printf 'HARNESS_LOG=%s\n' "$LOG_FILE"
exit "$run_rc"
