# Task 02 — SPIKE go/no-go: regionized chunk write pipeline prototype (W0.2)

- **Wave:** 0
- **Depends on:** 01 (verified)
- **Status:** todo
- **Executor:** codex (persistent implementation thread, workspace-write; orchestrator owns builds & server runs)

## Objective
THE architectural bet, proven or broken by measurement: a standalone probe that implements
the detached-prepare → region-commit chunk write model against live Folia 26.1.2 NMS,
measures competing commit strategies, and answers the runtime questions accumulated by the
other spikes. Output: `spikes/w02-pipeline-results.md` + the probe code + harness scenario.

## Out of scope
- NO FAWE source changes. The probe is a standalone plugin under `harness/probe/` — FAWE
  cannot boot on Folia yet (legacy scheduler calls throw); the spike proves the MECHANISM,
  not the integration.
- No lighting engine salvage work (only the specific lighting questions listed below).
- No perf-budget definition (W0.8 owns that; you produce raw measurements).

## Binding references
- architecture.md §1 (the model), §3 C1/C2 (compliance bars), spec §1b, §4b, §8.
- Runtime questions to answer, from: `spikes/w03-lighting.md` §6 (Q1-Q5),
  `spikes/w05-physics-packets.md` (Q1-Q5 + hazards a/b + A1-A5 assumptions),
  `spikes/w04-regen.md` (Q1 scratch-world), task 09 dev record (Folia
  `Bukkit.getMinecraftVersion()` string + fail-closed path check).
- User directive 2026-07-17 (plan.md decision log): measure COMPETING commit strategies —
  per-chunk / batched-neighbor / region-sweep — not one design.
- Harness: `harness/run.sh`, `harness/scenario.sh` (green at 26.1.2), your corrective-1
  region-owned probe mechanism (reuse/extend it).
- Prebuilt actor driver: orchestrator is building the SS2 bot driver for pre-staging under
  `harness/lib/` (MCProtocolLib-based). If present, wire it for player-actor legs; if its
  build failed, console/probe legs only (note it, don't block).

## Files to touch
- `harness/probe/` — CREATE: standalone probe plugin (own Gradle build, `folia-supported:
  true`). NMS access strategy: paperweight-userdev with the Folia dev-bundle if the SS2
  precedent (`NMS/v26_1_folia` module in /Users/falistos/Workspace/forks/SuperiorSkyblock2)
  shows a workable dependency route; else version-pinned reflection (spike-grade fragility
  acceptable — document which). Orchestrator resolves all network deps at build time.
- `harness/scenarios/pipeline-probe.md` + scenario wiring — CREATE.
- `spikes/w02-pipeline-results.md` — CREATE (filled after orchestrator runs; structure it
  now with the experiment matrix and empty result slots).

## Experiments (minimum matrix)
1. **Safety**: off-thread section PREPARE (build LevelChunkSection from palette data on a
   probe worker thread) then COMMIT swap on the owning region thread via RegionScheduler;
   verify: blocks visible and correct after commit, save, restart (harness readback);
   zero TickThread/ownership violations in logs; concurrent region tick during prepare.
2. **Commit strategies under load** (the tournament data): fill N chunks across ≥4 regions
   with (a) per-chunk commit tasks, (b) batched neighbor groups per region task,
   (c) region-sweep (one task per region commits all its chunks). Measure: wall-clock to
   full visibility, per-region tick-time impact (Folia exposes per-region tick data —
   probe it), scheduler queue depth.
3. **Cross-region hazards** (W0.5 a/b): packet send to a viewer owned by another region;
   neighbor updates crossing a region border (with and without suppression flags).
4. **Lighting legs** (W0.3): `serverRelightChunks` called from a region thread — legal?
   cross-border light correctness under concurrent per-region relight; `queueSectionData`
   from commit context.
5. **Environment answers**: `Bukkit.getMinecraftVersion()` exact string on Folia 26.1.2;
   fail-closed path of the W0.9 guard (boot the real FAWE jar once — it must abort cleanly
   with the operator message, not half-start; this also validates INV-LIFE boot items).
6. **W0.4 Q1**: does Folia load a plugin-declared extra world at startup? (one boot flag
   experiment; result feeds the deferred //regen DEGRADE option.)

## Executor constraints
Workspace-write, no network, no server execution, no gradle runs — the orchestrator runs
every build and every harness scenario and feeds full output back; iterate. Your first
delivery = buildable probe + scenario + experiment doc skeleton.

## Escape hatch
If NMS access from a standalone plugin proves impossible at spike quality (no dev-bundle
route AND reflection blocked by module encapsulation), STOP and report with evidence —
the fallback (minimal FAWE-internal experimental branch) is an orchestrator decision, not
yours to take.

## Acceptance criteria
- Probe builds green (orchestrator-run), loads on Folia 26.1.2, all experiment legs
  runnable via `harness/scenario.sh pipeline-probe`.
- `spikes/w02-pipeline-results.md` filled with real measurements for the full matrix, each
  runtime question answered with evidence (or explicitly UNANSWERABLE + why).
- Explicit GO / NO-GO recommendation on the architecture §1 model, and a ranked verdict on
  the three commit strategies (input to the W0.10a design tournament).

## Test / verification strategy
Orchestrator runs builds + scenarios at each iteration; the harness scan (zero-tolerance)
is the safety gate; measurements go into the results doc verbatim.

---
## Dev record (worker fills this in on completion)

**Status:** DONE_WITH_CONCERNS — the core architecture decision and strategy tournament are
complete; explicitly qualified runtime questions remain for later deterministic or targeted
tests.

**File List:**
- `harness/probe/build.gradle` — CREATE: standalone Java 25 paperweight-userdev build pinned
  to Folia `26.1.2.build.8-stable`, Mojang-production runtime mappings, embedded PaperLib.
- `harness/probe/settings.gradle` — CREATE: standalone plugin/dependency repository settings.
- `harness/probe/build.sh` — UPDATE: compatibility entrypoint now invokes the standalone
  Gradle build with `--no-configure-on-demand` and publishes the fixed probe jar.
- `harness/probe/src/main/java/com/fastasyncworldedit/harness/FoliaHarnessProbe.java` —
  UPDATE: retained the verified region-safe smoke command; added command routing,
  environment/async-chunk/startup-world probes, event-thread capture, and worker lifecycle.
- `harness/probe/src/main/java/com/fastasyncworldedit/harness/PipelineProbe.java` — CREATE:
  detached section prepare, owner-region section swap/readback, three-strategy tournament,
  packet/neighbor boundary checks, Starlight and `queueSectionData` legs, raw metrics/markers;
  corrected 26.1 `ChunkPos.x()` / `z()` mapped accessors during orchestrator build iteration.
- `harness/probe/src/main/resources/plugin.yml` — UPDATE: probe version/commands,
  `load: STARTUP`, retained `folia-supported: true`.
- `harness/probe/src/stubs/java/**` — DELETE: obsolete API-only javac stubs; paperweight now
  supplies the complete compile model.
- `harness/run.sh` — UPDATE: startup scratch-world flag, scenario phases, and lifecycle-safe
  pre-staged bot-driver control; existing scan-failure propagation remains unchanged.
- `harness/scenario.sh` — UPDATE: `pipeline-probe` runs initial and restart/readback boots,
  strips `--fresh` only for readback, and propagates either run's non-zero exit via `set -e`.
- `harness/scenarios/pipeline-probe.md` — CREATE: all first-pass experiment commands and
  bounded evidence waits.
- `spikes/w02-pipeline-results.md` — CREATE: filled measurement matrix, per-question evidence
  and implications, architecture GO, ranked strategy verdict, and evidence limitations.
- `.orchestrate/folia-port/tasks/02-spike-chunk-pipeline.md` — UPDATE: this Dev record only.

**Completion Notes:**
- NMS route chosen from the cited SS2 26.1 Folia precedent: paperweight userdev with the exact
  Folia dev bundle; no reflection fallback. The probe uses direct mapped
  `LevelChunkSection[]`, `ClientboundLevelChunkWithLightPacket`,
  `ThreadedLevelLightEngine.starlight$serverRelightChunks`, and `queueSectionData` APIs.
- C1/C2 shape is explicit: owner region copies the section and holds a chunk ticket; the worker
  builds/mutates a new detached section only; the owner region swaps, rebuilds heightmaps,
  marks unsaved, updates light-section status, verifies all 4096 blocks, and removes the ticket.
- Orchestrator build green after the mapped `ChunkPos` accessor correction. Both Folia boots
  exited cleanly; both zero-tolerance scans were clean; restart readback was 4096/4096.
- **GO** for architecture §1's detached-prepare → owner-region-commit model: three target-region
  ticks overlapped detached preparation, immediate visibility was 4096/4096, persistence was
  4096/4096, and all tournament runs were exact across four observed regions with zero owner
  mismatches.
- Strategy ranking: **(1) region-sweep, (2) batched-neighbor, (3) per-chunk**. Visibility was
  scheduler-delay dominated (~51-55 ms), while maximum commit-task cost was 364/705/551 us.
  Region-sweep used 4 tasks versus per-chunk's 16 at equal visibility.
- Starlight submission and `queueSectionData` were legal from owner tasks, but both chunk and
  completion callbacks ran on `Paper_Common_Worker_#0` with `owner=false`. The commit contract
  must require a region hop before any live callback finalization.
- Exact runtime environment confirmed: `Bukkit.getMinecraftVersion()` = `26.1.2`, Bukkit =
  `26.1.2.build.8-stable`, server name = Folia, `PaperLib.isPaper()` = true; async chunk
  completion was owner-region in this run.
- The `bukkit.yml` generator declaration did not instantiate `fawe-scratch`; W0.4's tested
  scratch-world route is unavailable and its regen DISABLE disposition stands.

**Deviations:**
- The real-FAWE in-matrix boot and unsupported-version abort were not run. Orchestrator decision:
  the out-of-matrix abort is deterministic code and will be covered by wave-3 deterministic
  tests.
- The packet sender and player had different owners, but `tracked_viewer=false`; post-send bot
  chat succeeded, while client-visible block payload confirmation remains unmeasured.
- Neighbor and light checks crossed chunk edges but not a proven Folia region boundary. The
  neighbor was owned by the caller, and the lighting leg had no two-owner adjacent pair or
  Paper oracle.
- NMSRelighter-versus-Starlight throughput was not measured because the standalone probe did
  not load FAWE. This remains a W0.8/repeated-baseline input, not part of the core mechanism GO.
- `EntityChangeBlockEvent` was not present in the captured event markers; owner-region dispatch
  was confirmed for `BlockPhysicsEvent` and `ItemSpawnEvent` only.

**Review focus points:**
1. The strategy ranking is a single-run architectural signal, not a budget. Rolling pre-commit
   tick reports include chunk generation/warm-up; direct sub-millisecond task timings and exact
   visibility are the stronger data.
2. The probe's four logical clusters matched four observed region IDs, but production
   region-sweep grouping must be derived and revalidated at execution because Folia regions
   split and merge dynamically.
3. Starlight callback ownership is the most important integration constraint: any callback
   path touching live state without an explicit owner dispatch would violate the proven model.
4. Do not promote the packet result to a tracked-viewer guarantee or the chunk-edge results to
   a cross-region guarantee; the log fields explicitly disallow both claims.
5. `probe_queue_highwater` is the probe's scheduled-not-started count, not Folia's private
   scheduler queue depth. Compare strategies using the same instrumentation only.

### Consolidation

- **Status:** results consolidated by a separate worker — the probe author's sessions were
  cut by an unrelated content filter and could not write the results document.
- `spikes/w02-pipeline-results.md` written entirely from the raw harness evidence
  (`FAWE_HARNESS_*` markers in `harness/logs/folia-26.1.2-20260717-151811.log` and the
  `FAWE_HARNESS_PIPELINE_READBACK_OK` line in `harness/logs/folia-26.1.2-20260717-151846.log`),
  quoted verbatim; no claims beyond the log fields.
- Contents: full experiment-matrix results, question ledger over W0.3 §6 / W0.5 Q+A1-A5 /
  W0.4 Q1 / task-09 version-string sources, strategy ranking (region-sweep >
  batched-neighbor > per-chunk), **GO** on the architecture §1 model conditional on the
  non-owner relight-callback re-dispatch contract, and review focus points.
- A first consolidation attempt was interrupted by a connection error before the results
  file reached disk; the artifact has now been rewritten and verified present (323 lines).

### Compile-proof skeleton ([W0-FREEZE] F1 compile proof)

Transcribed the frozen SPI of architecture v3 §3 into real skeleton code plus the new Gradle
module, so the declared module graph can be compiled. Elided bodies use
`throw new UnsupportedOperationException("wave 1")`; nothing is wired into the runtime.

**File List (CREATE unless noted):**

worldedit-core — `com/fastasyncworldedit/core/util/task/`:
- `FaweThreadContext.java` — core seam interface (§3.1, verbatim; no `sealed`/`permits` per F1).
- `ContextResolver.java` — single-registration resolver backing `FaweThreadContext.current()`
  (support type; register/resolve are `UnsupportedOperationException`).
- `WrongOwnerException.java` — `RuntimeException` thrown by `requireOwns` (ctor
  `(FaweThreadContext, World, int, int)`).
- `RegionTicket.java` / `EntityTicket.java` — core-owned final capability classes,
  package-private constructors (§3.2, verbatim).
- `TicketAuthority.java` — same-package final class, non-public constructor, sole minting path
  (§3.2). Mint/retire methods are non-frozen skeletons.
- `RegionTask.java` / `RegionCall.java` / `EntityTask.java` / `GlobalTask.java` — ticket-typed
  callback interfaces (§3.3, verbatim).
- `ChunkTarget.java` / `EntityTarget.java` — dispatch-target records (§3.5, verbatim).
- `TerminalStatus.java` / `AppliedReceipt.java` / `ChunkTerminalRecord.java` /
  `OperationCompletion.java` — exactly-once completion types (§3.6, verbatim).
- `OperationResult.java` / `EntityAction.java` / `PacketPhaseResult.java` — support types
  referenced by §3.6 signatures but not defined there; minimal legal skeletons (empty final
  classes, shapes deferred to wave 1).

worldedit-core — `com/fastasyncworldedit/core/queue/implementation/QueueHandler.java` (UPDATE):
- Added the four context-carrying `protected CompletionStage<...> syncOn(...)` / `syncOnGlobal`
  overloads (§3.5, verbatim) + imports. The ten existing public descriptors are untouched.

worldedit-bukkit/folia — new module `com/fastasyncworldedit/bukkit/folia/`:
- `build.gradle.kts` — Java 25 toolchain, `compileOnly(project(":worldedit-core"))`,
  `compileOnly(libs.foliaApi)`; applies `buildlogic.common`, `disableAutoTargetJvm`, release 25.
- `FoliaRegionDispatcher.java` — interface (§3.3, verbatim; `TaskKind`, `DrainReport`).
- `FoliaBackpressure.java` — interface (§3.4, verbatim; Priority/Stage/Demand/Limits/Pressure/
  Permit + full admission methods).
- `RegionKey.java` — internal lane metadata (F2), non-frozen skeleton.
- `FoliaSupport.java` — reflective detection (`RegionizedServer` `Class.forName`), matrix guard.
- `FoliaTaskManager.java` — extends core `TaskManager`; 7 abstract methods stubbed.
- `FoliaQueueHandler.java` — extends core `QueueHandler`; `startUnsafe`/`endUnsafe` stubbed.
- `FoliaCommitBroker.java` / `FoliaSnapshotCache.java` — non-frozen engine skeletons.

Root — `settings.gradle.kts` (UPDATE): `include("worldedit-bukkit:folia")`.

**FREEZE-DEFECTs:**
- **FD-1 (task-list vs frozen §3.6): `CommitOutcome` does not exist in the frozen SPI.** The
  task scope lists `CommitOutcome` among the core-owned completion types, but architecture v3
  §3.6 has no such declaration — F5 explicitly *replaced* Proposal C's two-record
  `sealed CommitOutcome permits Committed, CommitFailed` sink with the keyed
  `ChunkTerminalRecord` protocol (`spikes/tournament/judgment.md:123`:
  "C's two-record `CommitOutcome` sink — rejected by r1 F5"). Creating it would reintroduce a
  rejected design and pollute the frozen SPI, so it was **not** created. `TerminalStatus`,
  `AppliedReceipt`, `ChunkTerminalRecord`, `OperationCompletion` cover the frozen protocol.
  Adjudication needed: strike `CommitOutcome` from the task scope, or (if a distinct type is
  intended) freeze its signature in §3.6.

**Non-defect notes (support types the freeze references but does not define — minimal skeletons,
shapes open for wave 1):** `ContextResolver` (implied by `FaweThreadContext.current()` +
"single core resolver"); `OperationResult`, `EntityAction`, `PacketPhaseResult` (referenced by
§3.6 signatures); `RegionKey` (F2, explicitly non-frozen internal metadata). None alter a frozen
signature.

**Build-graph notes for the compile iteration:** the folia module takes `worldedit-core` as
`compileOnly` (core is Java-21 output; folia is Java-25) and applies only `buildlogic.common`
(toolchain 25) — not `buildlogic.common-java`, which would pin `sourceCompatibility = 21`.
`folia-api` (`dev.folia:folia-api`) resolves from the PaperMC repo. No `plugin.yml`, no shading,
no `WorldEditPlugin`/`FaweBukkit` edits — nothing depends on the folia module yet.
