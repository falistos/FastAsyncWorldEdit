# Task 17 — Bootstrap wiring: detection → backend selection → registration (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1 (closure task — dispatch LAST, after 10–16 are verified)
- **Depends on:** tasks 10 (ContextResolver + Bukkit backend), 11 (completion protocol), 12
  (dispatcher), 13 (backpressure), 14 (broker), 16 (FoliaTaskManager/FoliaQueueHandler).
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: adapters/bootstrap.
  This task is the wave-1 integration seam; certification review samples it.

## ADDED SCOPE — architecture ruling r12 Q2 (2026-07-20), and it is load-bearing

The task-15 double review established that **no `FaweThreadContext` is registered in production
today**: `ContextResolver.register(...)` is called only from two test classes and
`BukkitThreadContext` is never instantiated. Because `ContextResolver.resolve()` throws on null,
all ~30 requalified predicate sites currently throw at runtime — **on Paper as well as Folia** —
and `QueueHandler.run()`, a per-tick task, would throw every tick.

r12 Q2 ruled that resolution stays **partial and fail-closed** (new §3.1 paragraph): there is no
permissive default that both preserves Paper's legacy result and protects Folia ownership —
defaulting to main-thread identity is fail-open on Folia, defaulting to no-ownership changes Paper
scheduling semantics. **Bootstrap ordering is therefore contractual, and this task owns it:**

- Register each backend's context **exactly once, before constructing or starting any component,
  cache, supplier, scheduler, command path, or adapter capable of reaching a requalified
  predicate**. On Paper/Spigot that is `BukkitThreadContext`; on Folia the Folia implementation.
- Keep the registered context available through `stopAccepting`, drain, and disable completion.
- Deliver an **ordering test** proving `QueueHandler.run()` and the other requalified paths cannot
  execute before registration. A production call before registration is a bootstrap-order defect,
  not a runtime condition to tolerate.

Task 15 cannot claim runtime qualification complete until this lands.

**AND THE FOLIA CONTEXT IMPLEMENTATION DOES NOT EXIST YET (orchestrator-verified 2026-07-20).**
This task's mandate says "select and register the Folia backend services — `FaweThreadContext`
(Folia impl)", which reads as though the implementation already exists. It does not. A tree-wide
search for `implements FaweThreadContext` finds only `BukkitThreadContext` (bukkit main), the core
interface itself, and two test fakes. **This task must WRITE the Folia `FaweThreadContext`
implementation as well as register it** — `isTickThread` / `ownsChunk` / `ownsEntity` /
`isGlobalContext` / `isFaweWorker` over Folia's real ownership queries, with `ownsChunk` and
`ownsEntity` backed by genuine live ownership checks and never by region-ID comparison (region IDs
are hints, never proof — spec §1b, W0.2 §3).

This is load-bearing well beyond bootstrap: the task-16 concurrency review established that its
entire no-block property rests on `isTickThread()`, and that if `BukkitThreadContext` were ever
registered on Folia, `awaitGlobal` would park a region thread for 60 s — a guaranteed self-deadlock
if it is the global thread. Backend selection must therefore be fail-closed, not best-effort.

Note the C5 guard already reserves the name: `c5ApprovedBackends` in
`build-logic/src/main/kotlin/buildlogic.common.gradle.kts` lists
`worldedit-bukkit/folia/.../FoliaThreadContext.java` as an approved backend predicate
implementation. That entry currently points at a file that does not exist — it is the slot you
must fill. Also delete the `FoliaTickThreadGuard.java` allowance from `c5Allowances` once the real
Folia context makes that raw `Bukkit::isPrimaryThread` fallback unnecessary; an allowance that
outlives its reason silently becomes permanent.

## Mandate
Wire the runtime side of the dual-platform bootstrap: after `FoliaSupport` detection (already
in `WorldEditPlugin.onLoad`, fail-closed), select and register the Folia backend services —
`FaweThreadContext` (Folia impl), `FoliaTaskManager`, `FoliaQueueHandler`, dispatcher/broker/
backpressure graph — via reflection/`ServiceLoader` so `worldedit-bukkit` main NEVER links
folia-module types (Java-25 module, F1). On Paper/Spigot the Bukkit backend (task 10)
registers instead; behavior is equivalent within the frozen budgets (spec §2 Paper/Spigot
row — observable behavior-equivalence, the regression matrix is the gate).

## Packaging state (ALREADY DONE — do not redo)
The build-side packaging is wired and dynamically proven (certification corrective F2,
2026-07-17): `foliaBackend` configuration in `worldedit-bukkit/build.gradle.kts` (:81-88,
consumed :130, resolved by `shadowJar` only per :211-212). Paper artifact carries 18
Folia-package class files (16 backend-module + the detector pair); Spigot reobf artifact
carries only the detector pair (`FoliaSupport` + `UnsupportedFoliaVersionException`). This task consumes that state; it does not edit gradle
files except if a ServiceLoader descriptor resource must be added to the folia module.

## In scope
- A small bootstrap SPI: define how `worldedit-bukkit` main discovers the folia backend —
  `ServiceLoader` on a core-owned interface (preferred; architecture §2 SPI direction) or
  `Class.forName` on a single entry-point class name. The folia module implements the
  entry point and registers all its services through core SPI registration points
  (`ContextResolver.register`, TaskManager/QueueHandler injection points, dispatcher graph
  construction with its broker/backpressure collaborators).
- Ordering: detection (existing `onLoad` block) → certified-version check (fail-closed) →
  backend selection → registrations — ALL before any FAWE subsystem reads TaskManager/
  QueueHandler/context (audit the current `Fawe` init order and record it).
- Fail-closed path hardening: on Folia with the backend absent from the artifact (Spigot jar
  run on Folia) the plugin must fail with the clear operator message, never half-initialize
  (spec §2b). Deterministic test of the message path where feasible without a server.
- Paper/Spigot regression guard: on non-Folia servers the folia entry point must not load
  (verify no eager `Class.forName` of module classes outside the `isFolia()` branch).
- ~~Post-amendment consequential edit~~ **DONE by orchestrator 2026-07-17** (A2.1 signed):
  `FoliaSupport.CERTIFIED_MINECRAFT_VERSIONS` is now `Set.of("26.1.2")`. Nothing to do here
  beyond not regressing it.

## Out of scope
- Gradle/shading changes (done, see above).
- The chunk pipeline (wave 2). Bootstrap registers services; it does not start operations.
- plugin.yml changes (`folia-supported: true` already in tree, W0.9).

## Binding references
- architecture.md §2 (module boundary, SPI direction, bootstrap selection), §3.1 (resolver
  registration), F1 (core references no downstream type).
- spec §2/§2b (detection precedes Paper detection; fail-closed on uncertified versions).
- In-tree: `worldedit-bukkit/.../folia/FoliaSupport.java` (detection + fail-closed),
  `WorldEditPlugin.java` onLoad block (existing detection call), the six folia-module service
  classes, `worldedit-core/.../util/task/ContextResolver.java`.
- Dev record `tasks/09-build-plumbing.md` (current module layout + packaging proof, local
  build-environment note for full-jar builds).

## Files to touch
- `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/WorldEditPlugin.java` — extend the
  existing Folia branch with backend bootstrap (reflective/ServiceLoader only).
- `worldedit-bukkit/folia/.../` — CREATE the entry-point implementation (+
  `META-INF/services/...` descriptor if ServiceLoader is chosen).
- `worldedit-core/.../util/task/` — only if a core-owned bootstrap SPI interface is required
  (flag its exact signature in the dev record; it becomes part of the frozen SPI surface).
- `worldedit-bukkit/.../folia/FoliaSupport.java` — certified-set edit per A2.1 (see gate above).

## Executor constraints
- `worldedit-bukkit` main stays Java 21; NO import of folia-module or folia-api types there.
- Gradle always `--no-configure-on-demand`. No git commits. No FQN inline. English.

## Escape hatch
If the `Fawe`/`FaweBukkit` init order makes pre-registration impossible without touching
upstream files beyond `WorldEditPlugin` (e.g. TaskManager is instantiated before onLoad
completes), STOP with NEEDS_CONTEXT and the exact init-order trace — re-sequencing upstream
bootstrap is an architecture-level decision, not an implementation detail.

## Acceptance criteria
- Graph compile proof green (same command as tasks 10–16).
- Packaging re-proof after changes: Paper jar carries the folia entry point + descriptor;
  Spigot reobf jar carries neither (commands in `tasks/09-build-plumbing.md`; full local build
  needs the archived `harness/adapter-jdk21.init.gradle.kts` init script).
- Harness runtime gate, BOTH directions:
  - `JAVA_HOME_RUNTIME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
    harness/scenario.sh smoke-set --version 26.1.2 --with-plugin` boots green: detection fires,
    backend registers, no ownership violations in scan.
  - Fail-closed leg: Spigot-artifact-on-Folia aborts with the operator message (deterministic
    test acceptable if the harness leg is not yet scripted — record which).
- Paper regression: FAWE loads on a Paper 26.1.2 runtime with zero Folia code paths touched
  (harness Paper leg or deterministic classloading assertion).

## Test / verification strategy
Orchestrator runs compile + packaging inspection + harness legs. This task closes wave 1:
its verification is the wave-1 exit evidence bundle (record every command + log path in the
dev record for the certification review).

---
## Dev record (worker fills this in on completion)

- **Status:** DONE_WITH_CONCERNS — wiring and focused tests are complete; Gradle, packaging,
  and runtime harness confirmation remain orchestrator-owned.
- **File List:**
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/FawePlatformBackend.java:12`
    — new core-owned bootstrap SPI. Frozen signature:
    `registerThreadContext()`, `createTaskManager(Object)`, `createQueueHandler()`, `shutdown()`.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/queue/implementation/QueueHandler.java:116`
    — ticket-authority injection and executor-shutdown hook.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/IFawe.java:67`
    and `worldedit-core/src/main/java/com/fastasyncworldedit/core/Fawe.java:269` — backend disable hook.
  - `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/BackendSelector.java:14`
    — lazy ServiceLoader selection, exact-one registration, and absent-backend operator failure.
  - `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/BukkitPlatformBackend.java:16`
    — Paper/Spigot context, task manager, queue, authority, and shutdown graph.
  - `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/adapter/BukkitQueueHandler.java:13`
    — Bukkit ticket-authority injection.
  - `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/FaweBukkit.java:66`
    — selected-backend service factories and fail-closed initialization cleanup.
  - `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/WorldEditPlugin.java:129`
    — detect, certify, select, and register in `onLoad`; consume in `onEnable`; stop on disable.
  - `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/BukkitEntity.java:64`
    — internal opaque live-entity bridge for the separately compiled Folia module.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaThreadContext.java:12`
    — live Folia tick/global/ownership predicates and the extent-carrying worker predicate.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/LiveFoliaTargetAdapter.java:11`
    — fail-closed reflective unwrapping of the supplied Bukkit world/entity wrappers.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaWorldHandle.java:27`
    and `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaEntityHandle.java:27`
    — validated live ownership-query handles.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/LiveRegionObserver.java:14`
    — backend observer; region ID is recorded only as lane metadata, never ownership proof.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaPlatformBackend.java:17`
    — Folia entry point and complete shared dispatcher/backpressure/broker/completion/cache graph.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java:266`
    — package-local ordered shutdown entry point.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaTickThreadGuard.java:17`
    — production guard now uses only the registered context.
  - `worldedit-bukkit/folia/src/main/resources/META-INF/services/`
    `com.fastasyncworldedit.core.util.task.FawePlatformBackend:1` — ServiceLoader provider.
  - `build-logic/src/main/kotlin/buildlogic.common.gradle.kts:109` — the real Folia context fills
    the reserved backend slot; the temporary `FoliaTickThreadGuard` C5 allowance is deleted from
    `c5Allowances` at line 117. No temporary W1 allowance remains.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/BootstrapOrderingTest.java:17`
    — real `QueueHandler.run()` fails before registration and reaches the predicate afterward.
  - `worldedit-bukkit/src/test/java/com/fastasyncworldedit/bukkit/BackendSelectorTest.java:18`
    — lazy non-Folia discovery, exact absent-backend message, and service-factory ordering.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/FoliaThreadContextTest.java:18`
    — live ownership beats opposing region hints; tick/global and worker semantics are executable.
- **Deviations:**
  - Chose the preferred core-owned ServiceLoader SPI. The Folia provider reflectively unwraps
    Bukkit-main wrappers because the Java-25 Folia module has no compile dependency on Bukkit main.
  - Audited init order: `WorldEditPlugin.onLoad` detects Folia (`:134`), certifies it (`:135`),
    selects and registers the context (`:142`), then performs ordinary plugin setup (`:145+`).
    `onEnable` creates `FaweBukkit` (`:263`); its constructor creates the selected TaskManager
    (`FaweBukkit.java:69`) before `Fawe.set` (`:71`). The Folia manager's first guard/context read
    is therefore after registration. `Fawe` installs that manager (`Fawe.java:110`) before it
    starts scheduled work. The first queue request delegates to the backend (`FaweBukkit.java:111`),
    constructs the shared graph, and only then registers `QueueHandler.run()`.
  - The one Folia `OperationCompletionService` is shared by dispatcher, backpressure, and broker.
    Disable is `stopAccepting -> drain -> completion flush -> executor shutdown`.
  - Per task constraints, no Gradle, packaging, or harness command was run. Focused verification
    used JDK 25 `javac` with `--release 21` for core/Bukkit and `--release 25` for Folia, then the
    JUnit Platform launcher: 7 tests found, 7 successful. `git diff --check` also passed. These
    terminal-only checks produced no persisted log. Standards and Spec re-reviews found no
    remaining actionable gap.
  - `FoliaSupport.CERTIFIED_MINECRAFT_VERSIONS` remains exactly `Set.of("26.1.2")`.
- **Attack points:**
  - Harness must run `smoke-set --version 26.1.2 --with-plugin` and prove live detection,
    provider discovery, correct region/entity/global ownership, no ownership-violation scan hits,
    and the ordered bounded shutdown on a real Folia server.
  - The fail-closed harness leg must run the Spigot artifact on Folia and reproduce the tested
    operator message without listener, scheduler, command, or FAWE singleton half-initialization.
  - The Paper 26.1.2 leg must prove the Folia provider and all Java-25 backend classes stay unloaded.
  - Packaging inspection must re-prove the provider class and descriptor exist only in the Paper
    artifact. The reflective `BukkitWorld.getWorld` / `BukkitEntity.getEntityHandle` bridges must
    also be exercised by the certified runtime.
  - Wave 2 must replace `CommitAction.unavailable()` before real chunk operations are admitted.
- **Escalation:** AUTHORIZED — no ownership predicate or init ordering was guessed; live Folia API
  predicates and the concrete bootstrap call chain were verified. Runtime and packaging gates are
  intentionally deferred to the orchestrator, so status remains `DONE_WITH_CONCERNS`.
