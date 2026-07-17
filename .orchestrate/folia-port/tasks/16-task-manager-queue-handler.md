# Task 16 — FoliaTaskManager + FoliaQueueHandler (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1
- **Depends on:** task 10 (FaweThreadContext resolver — ownership predicates), task 12
  (FoliaRegionDispatcher — the routing target of the context-carrying surface). Task 13
  (backpressure) consumed indirectly via the dispatcher.
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: queue pipeline /
  scheduler SPI. Double-reviewer (concurrency & thread-ownership) per spec §6.

## Mandate
Make the two platform service implementations in the folia module real: `FoliaTaskManager`
(FAWE `TaskManager` on Folia's three schedulers) and `FoliaQueueHandler` (QueueHandler without
the global sync-drain tick, routing the §3.5 context-carrying internal surface to the
dispatcher). These are the seams through which ALL legacy FAWE scheduling flows on Folia.

## In scope
- `FoliaTaskManager` (folia module): implement the 7 skeleton methods against Folia's
  schedulers — `GlobalRegionScheduler` (task/repeat/later without a location context),
  `AsyncScheduler` (async/repeatAsync/laterAsync), `EntityScheduler`/`RegionScheduler` routing
  where the architecture's C4 table maps a call to an owned context. Tick-interval semantics:
  Folia scheduler delays are in ticks like Bukkit — preserve the `interval`/`delay` contract of
  the base class exactly (architecture §3.5 C4 mapping table is binding).
- Task-id/cancellation bridge: `TaskManager.cancel(int)` must remain functional — maintain the
  id→`ScheduledTask` mapping internally (Folia returns handles, not ints). Bounded map,
  cleaned on completion (H-LEAK).
- `FoliaQueueHandler` (folia module): implement `startUnsafe`/`endUnsafe` WITHOUT the global
  sync-drain tick (architecture §2 strikes it); route submission through the dispatcher's
  context-carrying surface (§3.5). MUST NOT touch AsyncCatcher/physicsFreeze (§1b; W0.5:
  physicsFreeze is orphaned dead code on the Folia path).
- Honor the QueueHandler binary-compat constraint from the §4c audit (w06: QueueHandler's 10
  public sync/async methods are PRESERVED) — the Folia subclass overrides behavior, never
  signature.
- Async-first (user conventions): IO/queue work lands on `AsyncScheduler` or FAWE pool, never
  on a region tick thread; sync joins only where the base contract demands one.

## Out of scope
- Bootstrap registration/selection of these implementations (task 17).
- The chunk GET/SET pipeline itself (wave 2) — `FoliaQueueHandler` routes, it does not commit.
- Any `Fawe.isMainThread()` call-site migration (task 15).

## Binding references
- architecture.md §2 (platform services in the folia module; sync-drain tick struck), §3.5
  (context-carrying internal surface + C4 scheduler mapping table), §1b (no global toggles).
- spec §4c via `spikes/w06-api-context-audit.md` (QueueHandler rows: 10 public methods
  PRESERVED) and `spikes/w06b-apic-gap-closure.md`.
- In-tree skeletons: `worldedit-bukkit/folia/.../FoliaTaskManager.java` (7 methods),
  `FoliaQueueHandler.java` (startUnsafe/endUnsafe). Base classes:
  `worldedit-core/.../util/TaskManager.java`,
  `worldedit-core/.../queue/implementation/QueueHandler.java` (NOTE: carries a small
  uncommitted wave-0 modification — read the current tree, not upstream).
- `spikes/recon-queue-threading.md` (legacy scheduling inventory).

## Files to touch
- `worldedit-bukkit/folia/.../FoliaTaskManager.java` — implement (folia-api allowed here).
- `worldedit-bukkit/folia/.../FoliaQueueHandler.java` — implement.
- New folia-module helpers if needed (id bridge, scheduler adapters) — same package.

## Executor constraints
- Java 25 toolchain in the folia module; folia-api types NEVER escape the module's public
  surface into core/bukkit (architecture F1/§2).
- Gradle always `--no-configure-on-demand`. No git commits. No FQN inline. English.

## Escape hatch
If a C4 mapping needs a region context that the call site cannot supply (no location/entity at
hand), do NOT guess a region: escalate NEEDS_CONTEXT with the call-site list — architecture
§3.5 decides between global-region routing and API-context rejection per row.

## Acceptance criteria
- Graph compile proof green:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava
  :worldedit-bukkit:compileJava --no-configure-on-demand`
- No global sync-drain: grep-able absence of the struck drain loop; no Bukkit
  `BukkitScheduler` usage anywhere in the folia module.
- `cancel(int)` round-trip works for every submit shape; the id map drains (H-LEAK).
- Runtime confirmation deferred to harness after task 17 wiring:
  `JAVA_HOME_RUNTIME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
  harness/scenario.sh smoke-set --version 26.1.2` (26.1.2 only; 26.1.1 does not exist).

## Test / verification strategy
Orchestrator runs the graph compile proof. Scheduler-routing deterministic tests (which
scheduler receives which call shape) are in-scope per spec §4 "targeted deterministic tests" —
implement them as plain JUnit against a scheduler-recording fake, no server required.

---
## Dev record (worker fills this in on completion)

- **Status:** <DONE | DONE_WITH_CONCERNS | BLOCKED>
- **File List:**
- **Deviations:**
- **Attack points:**
- **Escalation:** <AUTHORIZED | BLOCKED | NEEDS_CONTEXT> — <detail>
