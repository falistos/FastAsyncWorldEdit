# Task 15 — C5 requalification of Fawe.isMainThread() call sites (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1
- **Depends on:** task 10 (FaweThreadContext resolver + Bukkit backend — the replacement API).
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: queue pipeline /
  thread-ownership. Each site's requalification is a semantic decision, not a mechanical swap —
  double-reviewer on thread-ownership.

## Mandate
Requalify every `Fawe.isMainThread()` call site in the **Folia-enabled source sets**
(worldedit-core, worldedit-bukkit main, adapter-26.1, folia module) onto the
`FaweThreadContext` predicate (architecture §3.1 / C5), and add the build-time check that
rejects new production references to `Fawe.isMainThread()` from those source sets. **Produce a
per-site requalification record artifact** — this record IS the C3/C5 review deliverable.

## In scope
- For each site (~20 in the Folia-enabled sets — inventory in
  `spikes/recon-queue-threading.md`), requalify to exactly one of:
  `isTickThread()`, `ownsChunk(world, cx, cz)`, `ownsEntity(entity)`, `isFaweWorker()`,
  `isGlobalContext()`, or **remove** (assertion-only sites subsumed by ticket-required
  signatures). Sites that carry a world/chunk/entity MUST use the ownership-specific predicate,
  never a location-free fallback (architecture §3.5).
- **Requalification record artifact** (CREATE — propose path, e.g.
  `.orchestrate/folia-port/c5-requalification-record.md`): one row per site — file:line, the
  old expression, the chosen predicate/removal, and the rationale (what ownership question the
  site actually asks). This is the C5 "review artifact" required by architecture §3 C5.
- **Build-time guard:** reject new production `Fawe.isMainThread()` references from
  Folia-enabled source sets (architecture C5). Prefer an existing FAWE/Gradle mechanism
  (checkstyle/forbidden-apis/custom task) — investigate `gradle/` and existing static-analysis
  config first; propose the diff (build files are orchestrator-owned per architecture §5).

## Out of scope
- The other adapter versions (adapter-26.2, adapter-1_21*) — they remain Paper/Spigot-only
  (spec §2b); their `isMainThread()` sites are NOT in the Folia-enabled sets and MUST NOT be
  touched. (The repo-wide grep shows ~59 raw occurrences across all adapters + the definition;
  only the ~20 in Folia-enabled sets are in scope — enumerate precisely, do not swap blindly.)
- Removing/renaming `Fawe.isMainThread()` itself (the Bukkit path still uses it internally via
  the backend from task 10). Keep the method; guard only NEW references from Folia sets.
- Any behavior change on Paper: the Bukkit backend collapses every predicate to the old
  main-thread identity (task 10), so requalified sites must be behavior-identical on Paper.

## Binding references
- architecture.md §3 C5 (the six requalification targets + build-time check + record =
  review artifact), §3.1 (`FaweThreadContext` predicates), §3.5 (location-carrying lambdas may
  not use the location-free fallback), C6 (global-signal replacement — related but separate).
- `spikes/recon-queue-threading.md` (the site inventory: `QueueHandler.run()` L109 +
  `sync(...)` variants; `SingleThreadQueueExtent.submitUnchecked` L258; `FaweCache` L159;
  `SlowExtent` L30; `LazyBaseEntity` L27; `AbstractChangeSet` L415; `AbstractPlayerActor`;
  `LocalSession`; `EditSessionBuilder`; `PlatformCommandManager`; `TaskManager`; adapter-26.1
  `PaperweightFaweWorldNativeAccess` / `PaperweightPlatformAdapter`; `BukkitWorld` L398;
  `NMSAdapter`; `FaweDelegateSchematicHandler`).
- spec §4b (correctness > performance), §9 (fail-fast internal).

## Signature / structural flags
- Some sites are inside `QueueHandler.sync(...)` private helpers that will be reworked by
  task 16 (FoliaQueueHandler routing). Coordinate: requalify the *predicate* here; leave the
  *routing* redesign to task 16. Flag any site where requalification cannot be done without the
  routing change (hand it to task 16 with a note).
- The Bukkit backend `isFaweWorker()` marker must exist (task 10 escape hatch). If a site needs
  a predicate task 10 did not deliver, escalate NEEDS_CONTEXT.

## Files to touch
- The ~20 site files across worldedit-core / worldedit-bukkit main / adapter-26.1 / folia
  module (enumerate exactly in your record).
- CREATE the requalification record artifact under `.orchestrate/folia-port/`.
- Propose (do not commit) the build-guard diff for orchestrator-owned build files.

## Context & decisions
- Correctness > performance (spec §4b): a mis-requalified site (e.g. `isTickThread` where
  `ownsChunk` is meant) is a latent ownership bug invisible on Paper and catastrophic on Folia.
  When in doubt about which ownership question a site asks, record the doubt and escalate rather
  than guess.
- No FQN inline; resolve `FaweThreadContext.current()` via the core static resolver.

## Executor constraints
- worldedit-core / worldedit-bukkit main / adapter-26.1 = Java 21; folia module = Java 25.
  No folia-api type leaks into core (F1). No git commits. English. `// Folia port:` marker at
  each replaced call (grep-able touch-point inventory, architecture §4).
- Gradle always `--no-configure-on-demand`.

## Escape hatch
If a site's correct requalification is genuinely ambiguous from the code (the ownership question
is not determinable locally), DO NOT guess — record it in the artifact as UNRESOLVED with your
analysis and escalate NEEDS_CONTEXT. A wrong ownership predicate is worse than an escalation.

## Acceptance criteria
- Graph compile proof green:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava
  :worldedit-bukkit:compileJava --no-configure-on-demand`
- Every in-scope site requalified or explicitly UNRESOLVED-with-rationale in the record.
- Zero behavior change on Paper (Bukkit backend identity — reviewer confirms).
- Build guard rejects a seeded new `Fawe.isMainThread()` reference from a Folia-enabled set
  (self-test), and passes on the requalified tree.
- adapter-26.2 / adapter-1_21* untouched (grep confirms).

## Test / verification strategy
Orchestrator runs the compile proof + build-guard self-test. Paper behavior-equivalence is a
regression-matrix concern (spec §7.4) — record as pending the harness/regression run. The
requalification record is reviewed site-by-site by the Opus cross-reviewer.

---
## Dev record (worker fills this in on completion)

- **Status:** DONE_WITH_CONCERNS — all 30 semantic call sites are requalified, the two special
  references are explicitly retained, the guard is wired and negatively proved, and direct
  compilation passed. Orchestrator-owned Gradle/regression gates and Task-16/C1 routing remain.
- **File List:**
  - `.orchestrate/folia-port/c5-requalification-record.md` — 32-row C3/C5 review artifact,
    inventory correction, guard proof, verification, and routing handoff.
  - `.orchestrate/folia-port/checks/NoFaweIsMainThread.java` — production-source guard and
    exemption self-test.
  - `build.gradle.kts` — uncommitted orchestrator-review diff wiring the guard ahead of the four
    enabled `compileJava` tasks.
  - Core task files: `FaweCache.java`, `LazyBaseEntity.java`, `SlowExtent.java`,
    `AbstractChangeSet.java`, `QueueHandler.java`, `SingleThreadQueueExtent.java`, and
    `TaskManager.java` — core predicate replacements and the lazy entity target.
  - Core WorldEdit files: `EditSessionBuilder.java`, `LocalSession.java`,
    `AbstractPlayerActor.java`, and `PlatformCommandManager.java` — core-facing tick/liveness
    requalifications.
  - `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/BukkitWorld.java` and
    Bukkit-main `NMSAdapter.java` and `FaweDelegateSchematicHandler.java`
    — Bukkit chunk/tick requalifications and the split certified/compatibility adapter seam.
  - adapter-26.1 files: `PaperweightPlatformAdapter.java`,
    `PaperweightFaweWorldNativeAccess.java`, `PaperweightFaweAdapter.java`, and
    `PaperweightGetBlocks.java` — certified adapter ownership predicates and cached target
    propagation.
  - `.orchestrate/folia-port/tasks/15-c5-requalification.md` — this dev record.
- **Deviations:**
  - The stale estimate resolved to 31 qualified invocations plus the `Fawe.java` declaration:
    32 review rows, 30 requalified call sites, two retained special references, zero unresolved.
  - No Gradle command was run per dispatch instruction. Direct `javac` used JDK 25 with
    `--release 21` for core/Bukkit because the repository's Piston runtime is classfile 69;
    adapter-26.1 used the same JDK 25 toolchain with `--release 21`.
  - The build-file change is intentionally uncommitted and presented for the orchestrator's
    single-writer review. The user explicitly required the guard to be added and wired.
  - No files under `worldedit-bukkit/folia/` or uncertified adapter source trees were edited by
    this task. Concurrent workers' existing Folia changes remain outside this file list.
- **Attack points:**
  - The normal guard path rejected a planted production call at file/line with exit 1 and passed
    the real tree with exit 0. It scans `Fawe.java` without matching its declaration, excludes
    only `BukkitThreadContext.java`, omits test roots, and names the semantic replacement surface.
  - Task 16/C1 must replace the remaining location-free false branches for entity sync,
    QueueHandler/TaskManager sync, and the WNA multi-chunk flush. The NMS locked non-owner CAS is
    not ownership permission and must move behind the ticketed dispatcher.
  - Task 16 must not schedule the legacy `QueueHandler.run()` drain on Folia; the new
    `isGlobalContext()` assertion is defense only.
  - `TaskManager.runUnsafe` remains degraded before callback execution on Folia because
    `FoliaQueueHandler.startUnsafe` throws before `runUnsafe` reaches `run.run()`; Task 16 owns
    that backend seam.
  - adapter-26.2 and adapter-1_21* retain 28 legacy calls by signed scope. They need the same pass
    only if a future signed amendment certifies them for Folia.
  - The shared `NMSAdapter` String overload retains `isTickThread()` and its old no-lookup Paper
    behavior because only excluded Paper adapters call it. Certified adapter-26.1 passes a World
    to the exact `ownsChunk(...)` overload; the Folia path cannot reach the compatibility branch.
  - The orchestrator still owes the graph compile, Paper regression matrix, and runtime harness.
- **Escalation:** AUTHORIZED — every predicate was determinable from the frozen contracts; no
  site is `NEEDS_CONTEXT`. The listed routing hazards are already assigned to Task 16/C1 rather
  than guessed around here.

### Corrective 1 (2026-07-20)

This section supersedes the completion claim in the original dev record above.

- **Status:** BLOCKED — 26 rows are requalified, two definition/backend rows are retained, and
  six ownership-sensitive/raw rows are withheld. Runtime completion depends on Tasks 16 and 17.
- **File List:**
  - `.orchestrate/folia-port/c5-requalification-record.md` — widened 34-row disposition record,
    funnel audit, registration audit, Paper fixes, guard design, and dependency gates.
  - `build-logic/src/main/kotlin/buildlogic.common.gradle.kts` — per-project in-process C5 guard,
    exact blocked-site allowances, output stamp, and local `compileJava` wiring.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/Fawe.java` — deprecated legacy
    definition with a `FaweThreadContext` migration pointer.
  - Core queue files `QueueHandler.java` and `SingleThreadQueueExtent.java` — Paper exception
    parity and the null-world predicate split.
  - adapter-26.1 `PaperweightPlatformAdapter.java` — weak-key, non-recursive WorldEdit-world
    cache for the two safe ownership predicates.
  - `LazyBaseEntity.java`, `NMSAdapter.java`, `PaperweightFaweAdapter.java`,
    `PaperweightFaweWorldNativeAccess.java`, and `PaperweightGetBlocks.java` were restored exactly
    to their pre-task source; they therefore no longer appear as corrective code diffs.
- **Deviations:**
  - r12 and the live concurrent tree widen the census from 32 to 34 rows. The new rows are
    `BukkitBlockCommandSender.java:194` and `FoliaTickThreadGuard.java:27`; both are withheld.
  - Four original ownership rows are also withheld: Lazy entity snapshot, NMS section CAS, WNA
    direct set, and WNA multi-chunk flush. All six blockers remain exact guard allowances.
  - The old standalone checker and root cross-project wiring were removed. Guard implementation
    and wiring now live together in the existing common build-logic plugin.
  - No Gradle command, commit, Folia-module edit, uncertified-adapter edit, or
    `worldedit-core/.../util/task/` edit was made.
- **Attack points:**
  - Location-free `TaskManager`/`QueueHandler` sync stays `isTickThread()` per r12. It is safe only
    after Task 16 removes every foreign-target caller; narrowing it here would violate §3.5.
  - `ContextResolver` remains deliberately partial and has no production registration. Task 17
    must close bootstrap ordering before any runtime or Paper-equivalence claim is valid.
  - `FaweCache` construction-time classification and `AbstractChangeSet` inline disk I/O are
    explicitly preserved pre-existing hazards, not silently repaired.
  - The legacy global drain, WNA retained live references, NMS off-owner CAS, and command-sender
    BukkitScheduler fallback remain non-certifiable until Task 16 replaces them.
- **Escalation:** BLOCKED — no new predicate choice needs a contract ruling, but this task cannot
  close until the already-assigned Task-16 routes and Task-17 registration/order proof land.

#### Scope-point dispositions

1. **Paper 26.1 recursion:** fixed by restoring the original two-argument WNA construction.
   `createWorldNativeAccess` no longer calls `BukkitAdapter.adapt(world)`, so the cycle has a base
   case. Source equality to the pre-task files and direct compilation are the bounded proof.
2. **Inert ownership funnels:** Lazy entity, WNA flush, and the additionally found WNA direct-set
   row are restored and classified `WITHHELD — TASK 16`. The record names each required target.
3. **NMS authorization:** the World overload was removed and the legacy row restored. It is
   withheld until adapter-26.1 can dispatch a `ChunkTarget` under a fresh ticket.
4. **Registration/totality:** no permissive default was added. The record audits early paths,
   names the per-tick QueueHandler failure, and gates runtime qualification on Task 17. It also
   records concurrent Task-11 completion tests that still reach the partial resolver without an
   explicit context; Task 11/17 must correct those tests rather than rely on the caught failure.
5. **Widened C5 perimeter:** the census and guard now include primary-thread, same-thread, raw
   platform tick/ownership predicates, and direct server-thread comparisons. The newly found
   command-sender route and concurrent Folia raw method reference are recorded, not fixed here.
   Wildcard static imports and bare calls inside `Fawe.java` are rejected.
6. **Paper majors:** recursive world adaptation, per-chunk `FaweBukkitWorld` construction,
   `WorldUnloadedException` from the NMS overload, and LazyBaseEntity's null-target branch change
   were removed. PPA now adapts once per weakly keyed `ServerLevel`, without the synchronized
   `FaweBukkitWorld` cache or its reference-refresh exception path on each probe.
7. **Guard hygiene:** checker and wiring are one tracked build-logic edit; each project owns its
   task dependency; the guard has path-sensitive inputs, a cacheable stamp output, and no forked
   JVM.
8. **Minors:** all eight semantic-review minors have explicit dispositions in the C5 record.
   The two pre-existing unsoundnesses are named as preserved; null-world handling and deprecation
   are fixed; routing/identity obligations are handed to their owning tasks.

#### Required Task 16 closure

- Route Lazy entity serialization through `EntityTarget`/the entity dispatcher and return only
  detached NBT; never use location-free `sync` for the false branch.
- Route adapter-26.1 section replacement through `ChunkTarget` and a ticketed owner callback;
  eliminate every off-owner live-section CAS.
- Route WNA direct-set and automatic/explicit flush work per target chunk under fresh tickets;
  do not retain live `LevelChunk` state for a location-free/global drain.
- Replace `BukkitBlockCommandSender`'s `Bukkit.isPrimaryThread` plus BukkitScheduler fallback with
  the command block's target-bearing owner route.
- Remove the raw `Bukkit::isPrimaryThread` cross-check from `FoliaTickThreadGuard`; the approved
  Task-17 context implementation is the only legal home for the platform predicate.
- Remove the six exact guard allowances and prove no target-bearing callback reaches the
  location-free TaskManager/QueueHandler sync surface.

#### Required Task 17 closure

- Implement the Folia `FaweThreadContext`; select and register exactly one backend before
  `FaweBukkit`/`Fawe`, TaskManager, QueueHandler, adapter, cache/supplier, listener, command, or
  edit construction can reach a predicate.
- Normalize every accepted WorldEdit Bukkit-world wrapper to genuine Folia owner queries.
- Keep the context available through stop-accepting, drain, and disable completion.
- Add ordering tests covering QueueHandler's first per-tick line, cache/supplier construction,
  `FoliaTickThreadGuard.production()` construction, bootstrap TaskManager calls, adapter
  publication, and command/edit entry points. Every test that reaches a predicate must install
  an explicit context.
- Coordinate with Task 11 so its default `OperationCompletionService` post-termination tests
  install an explicit context instead of succeeding through a caught missing-registration error.

#### Corrective verification

- Direct `javac --release 21` passed for 12 Task-15 core files, four Task-15 Bukkit-main files,
  and all four reviewed adapter-26.1 files. Only existing deprecation/unchecked warnings remained.
- A direct mirror of the widened build-logic matcher passed the current tree and its wildcard,
  unqualified, primary-thread, same-thread, raw-tick, raw-owner, and direct-comparison cases. A
  planted production `Fawe.isMainThread()` printed its path and exited 1.
- Exact `git diff --quiet HEAD` source equality passed for the five restored files. A focused
  assertion proved `createWorldNativeAccess` has no `BukkitAdapter.adapt(world)` edge and calls
  only the original two-argument WNA constructor.
- `git diff --check`, changed-line length checks, and the uncertified-adapter no-diff check passed.
- No Gradle task or runtime harness was run. The orchestrator owns the actual task-graph,
  configuration-cache, Paper runtime, and post-Task-16 Folia proofs.

### Closure (2026-07-20)

- **Status:** DONE-PENDING-17 — task 16 Corrective 3 closes every task-16-owned C5 row with no
  routing mismatch. Task 17 remains the sole completion gate.
- **Final census:** 34 rows, all dispositioned: 31 requalified or target-routed, two retained
  definition/backend rows, and one task-17-pending raw predicate row. The widened production
  scan found no unrecorded legacy, primary-thread, same-thread, raw platform, or direct
  server-thread authorization predicate.
- **Five final task-16 rows:**
  - Row 16 (`LazyBaseEntity`) uses the adapter-supplied `EntityTarget` and
    `QueueHandlerRouting.syncOn(EntityTarget, EntityTask)` with an exact entity-ticket assertion.
  - Row 27 (`NMSAdapter`) uses the World overload and
    `QueueHandlerRouting.syncOn(ChunkTarget, RegionCall<Boolean>)`; the only CAS is inside the
    asserted chunk-ticket callback.
  - Rows 31/32 (`PaperweightFaweWorldNativeAccess`) retain exact-owner inline access where legal,
    partition all other changes/sends by `ChunkTarget`, and dispatch one ticket-asserted callback
    per partition. `isTickThread()` controls only whether the caller waits.
  - Row 33 (`BukkitBlockCommandSender`) routes its liveness read through the command block's exact
    `ChunkTarget`; the raw primary-thread predicate and Bukkit scheduler fallback are absent.
- **Task 17 gate:** `FoliaTickThreadGuard.java:27` remains the sole temporary C5 allowance, and
  the approved `FoliaThreadContext.java` slot is still unfilled. Task 17 must move the platform
  predicate into that backend, remove the guard allowance, register the context before every
  audited early call, and prove bootstrap/runtime ordering. That landing changes this task to
  `DONE`.
- **Verification:** source remained read-only except these two records; no Gradle command ran.
  The orchestrator-reported graph compile is green including adapter-26.1, all five task-16
  allowance entries are absent, and the closure census found no routing mismatch.
