# Task 01 — Harness bring-up (W0.1)

- **Wave:** 0
- **Depends on:** none
- **Status:** todo
- **Executor:** codex (persistent implementation thread, workspace-write)

## Objective
Stand up the runtime harness for this repo: automated Folia server boot + a first smoke
scenario (`//set` over a known region, block-level verification, watchdog/thread-violation
detection), adapted from the SS2 harness. Deliverable = scripts + config committed under
`harness/`, executable by the orchestrator.

## Out of scope
- No FAWE source changes (worldedit-*, adapters). No Gradle build file changes.
- Folia 26.1.1 jar acquisition (orchestrator provides later; parameterize version).
- Perf gates and full scenario pack (W0.8 / later waves) — smoke only.

## Binding references
- spec §6 (harness module), §7.3 (what certification will verify — design for extension).
- architecture.md §2 (harness location/naming), §5 (exclusive resources: ports 25601/25602).
- SS2 harness to adapt (READ ONLY, different repo): `/Users/falistos/Workspace/forks/SuperiorSkyblock2/harness/`
  — `run.sh`, `scenario.sh`, `scan-logs.sh`, `versions.env`, `bots/`, `scenarios/`, `README.md`.

## Files to touch
- `harness/` — CREATE (this repo). Adapt the SS2 structure: `versions.env` (folia 26.1.2
  build 8 now, 26.1.1 placeholder), `run.sh` (boot server on port 25601, offline mode,
  accept EULA, install the FAWE jar from `worldedit-bukkit/build/libs/`), `scenario.sh` +
  `scenarios/smoke-set.md` (console/RCON-driven: create flat test world region, run
  `//pos1 //pos2 //set stone` as a bot or console actor, dump target blocks, verify),
  `scan-logs.sh` (detect: exceptions, Folia thread-ownership violations
  ("thread failed main thread check" / TickThread assertions), watchdog stalls, FAWE errors).
- `harness/.gitignore` — CREATE (exclude .cache/, logs/, servers/ runtime state).

## Context & decisions
- Server jars pre-staged: copy `folia-26.1.2-8.jar` from
  `/Users/falistos/Workspace/forks/SuperiorSkyblock2/harness/.cache/` into `harness/.cache/`.
  Network is unavailable in your sandbox — never attempt downloads.
- FAWE has no Folia support yet: the FIRST harness run will exercise only server boot +
  plugin fail-clean behavior. The smoke scenario must be runnable both with and without
  the plugin installed (flag), so the harness lands before the port does.
- WorldEdit commands via console are limited (no console selections) — the SS2 harness
  solved actor-driven scenarios with bots (`bots/`); reuse its bot driver if it is
  self-contained, else document what W0.2 needs and provide the console-reachable subset
  (e.g. `/fawe` diagnostics, a paste-at-coords path) for the smoke.
- Java 25 required at runtime for MC 26.x (verified on SS2 stack). Detect and fail with a
  clear message if the JVM is wrong.

## Executor constraints
- workspace-write sandbox: write only inside this repo. Reads elsewhere OK.
- No network. No server execution in-sandbox: you cannot run the Folia server — the
  orchestrator owns the first execution of every script and will feed results back.
  Structure scripts to be verifiable in one shot (clear logs, explicit exit codes).
- No git commits.

## Escape hatch
If the SS2 bot driver is too coupled to SS2 to reuse, STOP at that boundary: deliver the
server-boot + console-scenario layers, and report the bot-layer gap with your analysis
(NEEDS-ANALYSIS) instead of writing a speculative bot framework.

## Acceptance criteria
- `harness/run.sh --version 26.1.2` boots Folia to "Done" on port 25601 (orchestrator-run),
  with EULA/world pre-config automated; exit code reflects boot success.
- `harness/scenario.sh smoke-set --without-plugin` completes green on the booted server.
- `scan-logs.sh` correctly flags a seeded fake violation line (self-test mode).
- README section documenting usage (short).

## Test / verification strategy
Orchestrator runs: `harness/run.sh` then `scenario.sh`, feeds full output back. Iterate
until green. Your delivery is unvalidated until that run happens — say so in your report.

---
## Dev record (worker fills this in on completion)

- **Status:** DONE_WITH_CONCERNS
- **File List:**
  - `harness/.gitignore`
  - `harness/README.md`
  - `harness/probe/build.sh`
  - `harness/probe/src/main/java/com/fastasyncworldedit/harness/FoliaHarnessProbe.java`
  - `harness/probe/src/main/resources/plugin.yml`
  - `harness/probe/src/stubs/java/`
  - `harness/run.sh`
  - `harness/scenario.sh`
  - `harness/scan-logs.sh`
  - `harness/scenarios/smoke-set.md`
  - `harness/versions.env`
- **Completion Notes:**
  - Added offline-only Folia 26.1.2 build 8 provisioning with SHA-256 verification, Java
    25+ enforcement, EULA/offline/flat-world configuration, port 25601 default, optional
    FAWE deployment from `worldedit-bukkit/build/libs/`, readiness/exit status, and clear logs.
  - Pre-staged the supplied Folia jar in ignored runtime cache `harness/.cache/`.
  - Added a no-plugin smoke scenario that fills and verifies every block in a 4x4x4 target
    volume, plus log scanning and a seeded ownership-violation self-test.
  - Initial static checks passed (`bash -n`, scanner self-test, pinned jar SHA-256); the
    orchestrator subsequently verified boot and exposed the console-ownership flaw recorded
    under Corrective 1.
- **Deviations:**
  - The smoke does not yet execute player-owned `//pos1 //pos2 //set stone`. The referenced
    SS2 bot is not self-contained: it requires a separate Gradle build and external
    MCProtocolLib artifacts. Per the task escape hatch, W0.1 stops at the server/console layer;
    W0.2 needs a prebuilt self-contained Minecraft 26.1 actor driver.
- **Attack points:**
  - First challenge the corrected probe command at runtime: compilation is locally verified,
    but only the orchestrator rerun can prove scheduling, mutation, and readback on Folia.
  - Challenge whether the generic exception scanner is too broad for clean Folia startup or
    still misses an upstream TickThread wording variant.
  - Challenge plugin-jar discovery if the build emits multiple Paper artifacts; the harness
    intentionally fails and requires `HARNESS_FAWE_JAR` rather than guessing.

### Corrective 1

- **What changed:** Replaced every console-side vanilla `fill` and `execute if block(s)`
  operation with the `fawe-harness fill-verify` command from a harness-only probe. The probe
  is compiled offline against the Folia API nested in the pre-staged server jar, installed
  for both FAWE modes, and dispatches the complete air-to-stone mutation plus all 64
  verification reads through Folia's `RegionScheduler` at the target location.
- **Why:** The orchestrator's first smoke run showed that console commands execute from
  `RegionizedServer.globalTick`. The three verification reads therefore called
  `Level.getCurrentWorldData()` with no owned region and produced NPEs. Changing command
  position or dimension cannot change thread ownership; the probe creates the required
  region hop explicitly and asserts `Bukkit.isOwnedByCurrentRegion(world, 0, 0)` before
  touching blocks.
- **Evidence:** Orchestrator baseline: boot, scanner self-test, and scanner failure exit were
  green; smoke log `harness/logs/folia-26.1.2-20260717-143433.log` was correctly rejected for
  NPEs at lines 60/85/110. Corrective static evidence: the probe compiles from the staged
  Folia jar plus compile-only signature stubs (verified absent from the output jar), scripts
  pass `bash -n`, scanner self-test remains green, and the run path preserves scan failures
  (`scan-logs.sh ... || run_rc=1`, followed by `exit "$run_rc"`; `scenario.sh` uses `exec`,
  so that status is returned unchanged). Runtime evidence for the corrected owner-thread
  mutation is pending the orchestrator rerun.

### Perf extensions

- **Status:** DONE_WITH_CONCERNS — the G1-G8 measurement extension is implemented and its
  offline self-check is green; probe compilation and Folia runtime behavior await the
  orchestrator as required by the task constraints.
- **File List:**
  - `harness/lib/perf-runtime.sh` — CREATE: harness/server clocks, RSS, GC, jstack, and
    process-exit sampling across one or more boots.
  - `harness/lib/perf-report.pl` — CREATE: source-aware JSON aggregation with absent-source
    omission, percentiles, throughput, queue/timer summaries, region samples, RSS/GC,
    attribution, shutdown drain, and version coverage.
  - `harness/perf-report.sh` — CREATE: stable report entrypoint.
  - `harness/scenario-expand.pl` — CREATE: executable-code-block extraction, environment
    substitution, and inline/block `@repeat` expansion.
  - `harness/perf-self-check.sh` — CREATE: one-shot offline syntax, expansion, actor-jar,
    scanner, and synthetic report verification.
  - `harness/run.sh` — UPDATE: sequential log cursors, timestamped `@mark`, same-directory
    `@restart`, multi-boot artifact retention, opt-in samplers, and actor spawn/run/despawn.
  - `harness/scenario.sh` — UPDATE: probe measurement scenario and explicit wave-1 response
    for the real-FAWE `perf-01` through `perf-07` driver slots.
  - `harness/scenarios/perf-probe.md` — CREATE: harness-only detached-prepare/owner-commit
    measurement scenario.
  - `harness/probe/src/main/java/com/fastasyncworldedit/harness/FoliaHarnessProbe.java` —
    UPDATE: `perf-mark`, `perf-run`, and `perf-queue` command routing.
  - `harness/probe/src/main/java/com/fastasyncworldedit/harness/PipelineProbe.java` — UPDATE:
    exact schedule/commit/visibility timers, queue counters, per-region summaries, and
    rolling tick samples at four separated anchors.
  - `harness/probe/src/main/resources/plugin.yml` — UPDATE: measurement command usage.
  - `harness/scan-logs.sh` — UPDATE: non-ok performance results and actor-control errors are
    scan violations.
  - `harness/README.md` — UPDATE: measurement usage, source labels, and pending driver scope.
  - `.orchestrate/folia-port/tasks/01-harness-bringup.md` — UPDATE: this Perf extensions
    Dev record subsection only.
- **Completion Notes:**
  - `@mark` now writes the frozen `epoch_ms<TAB>iso<TAB>label` format, emits a server-clock
    marker, and requests four owner-region rolling tick samples. The report keeps those
    rolling proxies separate from exact probe schedule, commit-task, and visibility timers.
  - `HARNESS_PERF=1` adds per-boot unified GC logs and RSS sampling; restart legs are appended
    into one run artifact. The sampler records process-exit observation so stop-mark drain is
    an upper bound. `HARNESS_PERF_JSTACK=1` records only real FAWE/WorldEdit frames and
    deliberately excludes the harness probe package.
  - Waits search only log lines produced after the preceding action, preventing repeated
    trials and the second boot from matching stale output. `@restart` stops/reaps the current
    JVM, reuses the same server directory, starts a new JVM, and continues the scenario.
  - The prebuilt actor jar is invoked through its stdin control channel. Spawn waits for all
    actors, places them at separated anchors, and `run` issues player-owned selection/edit
    commands. Completion remains the responsibility of future `FAWE_PERF` instrumentation.
  - `perf-report.sh` never promotes `FAWE_PROBE_*` records into `FAWE_PERF` metrics. It omits
    missing metric sources, reports the real driver as pending wave 1 when absent, and exposes
    the intentionally unstaged Folia 26.1.1 leg in version coverage.
  - Offline evidence: `harness/perf-self-check.sh` passes, including `bash -n`, both Perl
    syntax checks, scanner self-test, actor-jar integrity, repeat expansion, source separation,
    marked-window duration, RSS, GC, two-region attribution, and shutdown-drain aggregation.
- **Deviations:**
  - Real EditSession operations cannot run before a Folia-capable FAWE exists. The frozen
    `fawe-perf` slots are therefore explicit wave-1 pending paths; the delivered probe path is
    separately named and labeled rather than presented as an equivalent FAWE measurement.
  - Folia 26.1.1 remains `UNSTAGED`; the report and self-check expose that state instead of
    producing a comparison value.
  - No Gradle build or server was run in this workspace, per the orchestrator-only execution
    constraint. Runtime/build evidence is pending its rerun.
- **Review focus points:**
  1. First verify the probe still compiles against the pinned 26.1 mappings and that all four
     `FAWE_PROBE_REGION_SAMPLE` callbacks arrive for both begin and end marks.
  2. Exercise `perf-probe` with `HARNESS_PERF=1` through a clean stop and a synthetic
     `@restart` scenario; confirm GC append order, sampler-exit timing, and scan propagation.
  3. Treat rolling five-second tick samples only as before/after context. The exact
     `FAWE_PROBE_COMMIT` task timers are the load-bearing probe measurements.
  4. Confirm the certified Folia `tps` output wording against the report parser; absent or
     changed output must remain absent rather than become a zero.
  5. When wave 1 supplies `FAWE_PERF`, verify the actor completion marker and jstack attribution
     under 1/2/4/8 actors before using the concurrent-player scaling report.

#### Corrective 2

- **Observed result:** The orchestrator's pipeline run returned 1 after one boot. Log
  `harness/logs/folia-26.1.2-20260717-165311.log` contained both
  `FAWE_HARNESS_ENV_OK` and `FAWE_HARNESS_ASYNC_CHUNK_OK`, then began clean shutdown exactly
  120 seconds later. The log scan was clean, but the readback boot never started.
- **Cause:** Both environment markers came from one command. After the first `@waitfor`
  matched `ENV_OK`, the new cursor logic advanced to the current end of the log. Because
  `ASYNC_CHUNK_OK` had already arrived, the second wait excluded it and timed out. That made
  the initial `run.sh` return 1; `scenario.sh` still used top-level `set -e`, so it exited
  before invoking the frozen readback phase and added no phase-level diagnostic.
- **What changed:** Added `harness/lib/log-cursor.sh`. A successful wait now records the
  absolute line it matched, and `feed_scenario` advances only to the following line. Later
  markers already present remain eligible for subsequent waits. `pipeline-probe` now captures
  each boot's status explicitly, reports `phase=initial|readback gate=run-or-scan exit=N` on
  stderr, strips `--fresh` only from readback, and returns 0 only after both run/scan gates.
  `run.sh` also emits a final phase plus failed-gate summary for every nonzero path.
- **Static evidence:** `harness/perf-self-check.sh` now proves that two pre-existing markers
  are consumed in sequence, that pipeline-probe invokes exactly `initial` then `readback`,
  that only initial retains `--fresh`, and that a seeded readback status 7 is propagated with
  the explicit readback diagnostic. The complete self-check and `bash -n` pass.
- **Runtime status:** Pending the orchestrator rerun; no server was executed locally.

#### Corrective 3

- **Observed result:** The orchestrator's run-or-scan gate failed `phase=initial` on an
  otherwise healthy pipeline boot. The detached-prepare/owner-commit edit committed and read
  back correctly, but the actor (`pipeline_01`) was kicked for floating before it emitted its
  `BOT-CHAT … FAWE_PACKET_DELIVERED` confirmation, so the gate saw an incomplete actor run.
- **Cause:** Folia's default `allow-flight=false` kicks the hovering harness actor
  ("was kicked for floating too long" / "Flying is not enabled on this server"). The scanner
  did not treat that kick as fatal, so the failure surfaced only as a missing marker rather
  than a named zero-tolerance violation.
- **What changed:**
  - `harness/run.sh` (~line 185) now writes `allow-flight=true` into the generated server
    properties, so the stationary actor is not kicked mid-scenario.
  - `harness/scan-logs.sh` promotes both kick wordings to fatal zero-tolerance patterns
    (`was kicked for floating too long`, `Flying is not enabled on this server` —
    `scan-logs.sh:56-57`) and self-tests them via a seeded `actor-kick.log`
    (`scan-logs.sh:17-27`), so a future silent kick fails the scan loudly instead of only
    dropping a marker.
- **Validation (rerun 2026-07-17, green):**
  - Initial run `harness/logs/folia-26.1.2-20260717-170629.log`: boot "Done", actor
    `BOT-CHAT pipeline_01 FAWE_PACKET_DELIVERED chunk=96,96` (:94),
    `FAWE_HARNESS_PIPELINE_SAFETY_OK owner=true visible_blocks=4096 expected=4096` (:68).
  - Readback run `harness/logs/folia-26.1.2-20260717-170704.log`:
    `FAWE_HARNESS_PIPELINE_READBACK_OK owner=true blocks=4096 expected=4096 material=EMERALD_BLOCK`
    (:65).
  - Both boots exit 0; `scan-logs.sh` OK on both phases; restart readback 4096/4096.
- **File List superseded:** the initial dev record's File List names
  `harness/probe/src/stubs/java/`. The stub *files* were deleted by task 02
  (`02-spike-chunk-pipeline.md:111`: `harness/probe/src/stubs/java/** — DELETE`, obsolete
  API-only javac stubs, now provided by paperweight); only empty directory skeletons remain
  on disk (`find harness/probe/src/stubs -type f` returns nothing). That File List entry is
  superseded.
- **Status:** DONE — corrective 3 validated green on disk; the harness bring-up deliverable is
  closure-grade. (Wave-1 `FAWE_PERF` driver slots remain by-design deferrals, not open
  concerns of this task.)
