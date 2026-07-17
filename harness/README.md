# FAWE Folia runtime harness

This harness boots only pre-staged Folia jars; it never downloads artifacts. Minecraft
26.x requires Java 25 or newer. Set `JAVA_HOME_RUNTIME` when Java 25 is not the default.

Folia 26.1.2 build 8 is pinned in `versions.env` and expected at
`.cache/folia-26.1.2-8.jar`. The 26.1.1 entry is intentionally blocked until the
orchestrator selects and stages its certified build. Runtime state lives under `servers/`
and logs under `logs/`; both are ignored.

Boot once with the FAWE jar from `worldedit-bukkit/build/libs/`, then stop after `Done`:

```sh
harness/run.sh --version 26.1.2
```

Use `HARNESS_FAWE_JAR=/absolute/path/to.jar` if more than one runtime jar exists. Use
`--without-plugin` to omit FAWE; the harness-only region-owner probe is always installed.
Port 25601 is the default; set
`HARNESS_PORT=25602` for the other exclusive harness port.

Run the deterministic flat-world smoke without FAWE:

```sh
harness/scenario.sh smoke-set --without-plugin --version 26.1.2 --fresh
```

The scenario invokes a harness-only command bridge. The command schedules through Folia's
`RegionScheduler`, changes a 4x4x4 target from air to stone, and reads all 64 blocks back
on the owning region thread. Direct vanilla `fill`/`execute if block` from the console are
not used: Folia handles console input on the global region, where per-region world data is
unavailable. `--with-plugin` also invokes the console-reachable `fawe` diagnostic before
running the same oracle. It does not claim player-path coverage: WorldEdit console sessions
cannot own the required selection. A self-contained Minecraft 26.1 actor driver is now
pre-staged for the performance scenarios, but its `//pos1`, `//pos2`, and `//set` leg still
requires a Folia-capable FAWE.

## Performance measurement extension

The runner implements the frozen `scenarios/perf-index.md` measurement mechanics. Set
`HARNESS_PERF=1` to collect RSS and unified GC logs; add `HARNESS_PERF_JSTACK=1` to record
region scheduler threads whose stacks contain real FAWE/WorldEdit frames. `@mark` writes
`epoch_ms<TAB>iso<TAB>label`, emits a server-clock marker, and asks the probe to sample the
rolling five-second tick report at four separated region anchors. `@repeat`, sequential
`@waitfor`, and same-directory `@restart` are handled by the scenario runner.

The available measurement seed is deliberately named `perf-probe`:

```sh
HARNESS_PERF=1 HARNESS_PERF_JSTACK=1 \
  harness/scenario.sh perf-probe --without-plugin --version 26.1.2 --fresh
harness/perf-report.sh harness/logs/folia-26.1.2-YYYYMMDD-HHMMSS.log \
  --out harness/logs/perf-probe.json
```

Probe output uses `FAWE_PROBE_*` records and appears under separate `probe_*` report keys.
It measures detached preparation and owner-region commits, including schedule delay, commit
task duration, queue high-water marks, visibility, and per-region tick samples. It is not a
substitute for the real `FAWE_PERF` EditSession driver. The `perf-01` through `perf-07`
driver slots therefore exit with a pending-wave-1 message until a Folia-capable FAWE jar
and its completion instrumentation exist. Their actor syntax is already wired to the
prebuilt `lib/ssb2-bots-26.1.jar` (`@bots spawn/run/despawn`) for that later player leg.

The report omits sources that did not run, reports the Folia 26.1.1 entry as unstaged, and
can enforce attribution with `--require-region-threads N`. Run the offline one-shot check
without starting Folia:

```sh
harness/perf-self-check.sh
```

The scenario runner scans its completed log automatically. The scanner can also be run or
self-tested directly:

```sh
harness/scan-logs.sh harness/logs/folia-26.1.2-YYYYMMDD-HHMMSS.log
harness/scan-logs.sh --self-test
```
