# Task 17 — Bootstrap wiring: detection → backend selection → registration (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1 (closure task — dispatch LAST, after 10–16 are verified)
- **Depends on:** tasks 10 (ContextResolver + Bukkit backend), 11 (completion protocol), 12
  (dispatcher), 13 (backpressure), 14 (broker), 16 (FoliaTaskManager/FoliaQueueHandler).
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: adapters/bootstrap.
  This task is the wave-1 integration seam; certification review samples it.

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

- **Status:** <DONE | DONE_WITH_CONCERNS | BLOCKED>
- **File List:**
- **Deviations:**
- **Attack points:**
- **Escalation:** <AUTHORIZED | BLOCKED | NEEDS_CONTEXT> — <detail>
