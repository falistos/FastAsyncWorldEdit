# Task 12 — TicketAuthority minting + FoliaRegionDispatcher on Folia schedulers (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1
- **Depends on:** task 10 (FaweThreadContext resolver + Bukkit backend — for the inline-when-
  owned decision). Consumed by tasks 14, 16, 17.
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: scheduler SPI /
  adapters-NMS / thread-ownership. **Double-reviewer (concurrency & thread-ownership)** —
  THIS is the single choke point (C1); a leak here defeats every downstream invariant.

## Mandate
Implement the capability-minting authority and the one class that talks to Folia's schedulers,
frozen in architecture §3.2 and §3.3: `TicketAuthority` (sole minting path for
`RegionTicket`/`EntityTicket`, single issuance at bootstrap) and a `FoliaRegionDispatcher`
implementation over `RegionScheduler` / `GlobalRegionScheduler` / `EntityScheduler` /
`AsyncScheduler`, with the explicit **inline-when-owned** execution guarantee (§3.3, F11) and
the §8 `TaskKind` instrumentation seam.

## In scope
- `TicketAuthority` (core, same-package final class): implement `mintRegion`/`mintEntity`/
  `retire(RegionTicket)`/`retire(EntityTicket)`. Constructor non-public; **single issuance** —
  a second issuance attempt throws (architecture §3.2). No public accessor exposes it.
- `RegionTicket` / `EntityTicket` bodies (core): implement `world()`/`owns()`/`assertOwns()`/
  `isLive()`/`sequence()`. F3 validity contract: valid only for the lexical dynamic extent of
  one dispatcher callback; `assertOwns` throws if not owning (cx,cz), used off its minting
  thread, or retired; `isLive()` true only until retirement. `EntityTicket.assertOwns` is an
  exact-entity assertion. `sequence()` is DIAGNOSTICS ONLY (see cross-ref to task 11 flag).
- `FoliaRegionDispatcher` **implementation** (folia module; the interface is already frozen
  in tree — create the impl class, propose a name, e.g. `FoliaRegionDispatcherImpl`):
  - `onRegion(world,cx,cz,kind,RegionTask/RegionCall)`: run on the owning region; mint a fresh
    `RegionTicket`, retire it in a `finally`. **Inline, synchronously, when the calling thread
    already owns the target** (ticket still minted/retired); otherwise schedule on
    `RegionScheduler.execute(plugin, world, cx, cz, task)` (A5, W0.2 `owner_mismatches=0`).
  - `onEntity(entity,kind,EntityTask)`: run on the entity's owning context with a fresh
    `EntityTicket`; entity retirement completes the stage exceptionally; inline when owned.
  - `onGlobal(kind,GlobalTask)`: global-region thread; **no ticket** (owns no chunk).
  - `stopAccepting(reason)` / `drain(deadline)` returning `DrainReport` — never awaited from a
    tick thread; the drain report's unresolved counts feed the G6 certification gate.
  - **Instrumentation (F10/G3):** record per `TaskKind` and per region: schedule delay,
    callback runtime, ticket mint/retire counts. Expose the mint/retire counters so task 14's
    `DiagnosticSnapshot` producer can read `liveTickets`/`outstandingFutures`.
  - Returned stages complete through the FAWE completion executor — **never run arbitrary
    continuations on a tick thread**; no caller-runs for owner-bound work (spec §1b).
- Registration of the Folia `FaweThreadContext` and the injected `TicketAuthority` instance is
  task 17's job; here, expose the injection points and report them.

## Out of scope
- `FoliaBackpressure` admission (task 13), lane/broker drain (task 14), snapshot cache (wave 2).
- Adding the `RegionTicket` parameter to the ~13 live-state adapter methods (that is the
  adapter GET/SET path — wave 2). This task delivers minting + dispatch only.
- Bootstrap selection/wiring (task 17).

## Binding references
- architecture.md §3.2 (capabilities, F1/F3), §3.3 (dispatcher interface + inline guarantee F11
  + no raw scheduler leaks, CI-grep-enforceable), C1 (ownership routing: no direct
  `MinecraftServer.execute` / `MCUtil.MAIN_EXECUTOR` / legacy `BukkitScheduler` / off-thread
  live-section CAS; AsyncCatcher/physicsFreeze/Timings never toggled), §1b, §2 (module layout).
- amendment-1-draft.md A1.4 (completion callbacks of any server-async facility are UNTRUSTED
  thread contexts — re-dispatch to the owner before touching live OR FAWE shared state).
- `spikes/w02-pipeline-results.md` (RegionScheduler proven: `owner_mismatches=0`; relight
  completion callbacks land on non-owner threads → re-hop required),
  `spikes/recon-adapters-nms.md` §6 (the danger set the ticket protects).
- In-tree frozen: `RegionTicket.java`, `EntityTicket.java`, `TicketAuthority.java`,
  `FoliaRegionDispatcher.java` (interface), core callback types `RegionTask/RegionCall/
  EntityTask/GlobalTask`.

## Signature / structural flags
- The `FoliaRegionDispatcher` interface exists but has **no implementation class** in tree —
  create it (name not frozen; propose in record).
- `RegionTicket.sequence()` javadoc currently says it keys terminal records — this is WRONG per
  v3.1 (diagnostics only). Coordinate with task 11's rename flag; do not rely on `sequence()`
  for completion keying.
- Confirm the Folia `plugin` handle / scheduler access available in the folia module (the module
  is `compileOnly` folia-api + `compileOnly` worldedit-core). If the plugin instance needed for
  `RegionScheduler.execute(plugin, ...)` is not reachable, flag the injection gap (task 17).

## Files to touch
- `worldedit-core/.../util/task/TicketAuthority.java`, `RegionTicket.java`, `EntityTicket.java`
  — implement bodies.
- `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/` — CREATE the
  dispatcher impl. References core types only (F1).

## Context & decisions
- Correctness > performance (spec §4b), but the dispatcher is the hottest scheduler seam:
  inline-when-owned must add no scheduling hop; the ticket must be allocation-lean.
- Async-first, virtual-threads-by-default for the FAWE completion executor where the executor
  is FAWE-owned (user Java conventions) — but tick threads never block (spec §1b).
- Fail-fast internally (retired/escaped ticket → exception); graceful operator message at the
  API boundary.

## Executor constraints
- Folia module compiles at **Java 25** (architecture §2); core stays Java 21. No folia-api type
  in any core signature (F1). No git commits. No FQN inline. English. `// Folia port:` marker
  only where a seam replaces an existing call.
- Gradle invocations always `--no-configure-on-demand`.

## Escape hatch
If the inline-when-owned decision cannot be made without a live ownership query that the
Bukkit/Folia context (task 10) does not yet expose, STOP and report the exact predicate needed
(NEEDS_CONTEXT) rather than approximating ownership by region-ID (region IDs are hints, never
proof — spec §1b, W0.2 §3).

## Acceptance criteria
- Graph compile proof green:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava
  :worldedit-bukkit:compileJava --no-configure-on-demand`
- CI-grep clean: `RegionScheduler`/`GlobalRegionScheduler`/`EntityScheduler`/`AsyncScheduler`
  referenced only inside the dispatcher impl (architecture §3.3). Danger-set review:
  `git grep 'RegionTicket'` (C1).
- Single-issuance: a second `TicketAuthority` issuance throws; a retired/escaped ticket fails
  `assertOwns`/`isLive`.
- Runtime confirmation deferred to harness (pending until task 17 wires bootstrap):
  `JAVA_HOME_RUNTIME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
  harness/scenario.sh pipeline-probe --version 26.1.2`.

## Test / verification strategy
Orchestrator runs the compile proof + grep gates. Targeted deterministic tests (spec §6):
scheduler routing (inline vs scheduled), ticket lifecycle (mint/retire/escape/off-thread),
single issuance. Runtime owner-mismatch=0 confirmed via harness pipeline-probe post-wiring.

---
## Dev record (worker fills this in on completion)

- **Status:** <DONE | DONE_WITH_CONCERNS | BLOCKED>
- **File List:**
- **Deviations:**
- **Attack points:**
- **Escalation:** <AUTHORIZED | BLOCKED | NEEDS_CONTEXT> — <detail>
