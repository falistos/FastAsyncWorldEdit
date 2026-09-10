# Task 10 — FaweThreadContext resolver + Bukkit backend (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1
- **Depends on:** none (foundation — tasks 12, 15, 16, 17 depend on this)
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: scheduler SPI /
  thread-ownership. Double-reviewer (concurrency & thread-ownership) per spec §6.

## Mandate
Implement the core thread-role seam frozen in architecture §3.1: make
`ContextResolver.register/resolve` live, and provide the **Bukkit (Paper/Spigot) backend**
implementation of `FaweThreadContext` that collapses tick/owner/global to the single main
thread — zero behavior change on Paper. This is the seam that `Fawe.isMainThread()` semantics
migrate onto (C5, task 15) and that every ownership guard resolves through.

## In scope
- `ContextResolver` (core): single-registration resolver. `register(FaweThreadContext)` accepts
  exactly one platform registration at bootstrap; a second call fails fast. `resolve()` returns
  the registered context, or fails deterministically if none is registered (fail-fast internal,
  spec §9). No silent default.
- Bukkit backend `FaweThreadContext` implementation (in `worldedit-bukkit` main module, the
  Paper/Spigot path): `isTickThread() == ownsChunk(..) == isGlobalContext() == old
  Fawe.isMainThread()`; `ownsEntity(..)` collapses to the same main-thread identity;
  `isFaweWorker()` = membership in the FAWE pool via an **explicit thread-factory marker**
  (architecture §3.1: "not a name comparison"). Confirm the FAWE pool threads already carry a
  marker type (`FaweThread` / `FaweBasicThread` / `FaweForkJoinThread` exist in
  `com.fastasyncworldedit.core.util.task`); use `instanceof` on the marker, not thread names.
- Registration wiring point identification only: report WHERE the Bukkit backend must be
  registered at bootstrap (so task 17 wires it). Do NOT edit bootstrap files here beyond what
  is strictly required to make the Paper path resolve; flag the wiring as task-17-owned.
- `WrongOwnerException` is already in tree (do not redefine); ensure `requireOwns` default
  method behaves correctly against the Bukkit backend.

## Out of scope
- The Folia backend `FaweThreadContext` (lives in the `:worldedit-bukkit:folia` module —
  covered implicitly by tasks 12/17 which register it; if a dedicated Folia context impl is
  needed, flag it, do not build it here).
- Migrating any `Fawe.isMainThread()` call site — that is task 15 (C5). This task only makes
  the *replacement API* real.
- Bootstrap selection of which backend to register (task 17).

## Binding references
- architecture.md §3.1 (the frozen `FaweThreadContext` interface + resolver semantics),
  §2 (core-owned SPI, F1: core references no downstream type), §3 C5.
- spec §1b (ownership invariant — the predicate never blocks, never hops, never touches live
  state), §9 (fail-fast internal).
- In-tree frozen signatures: `worldedit-core/.../util/task/FaweThreadContext.java`,
  `ContextResolver.java`, `WrongOwnerException.java`. Implement AGAINST these; if a needed
  signature is absent or contradicts the architecture, FLAG it — do not invent one.
- `spikes/recon-queue-threading.md` (L24: `Fawe.isMainThread()` = `instance.thread ==
  currentThread()`, captured once in ctor, mutable via `setMainThread()` — the identity the
  Bukkit backend must reproduce).

## Files to touch
- `worldedit-core/.../util/task/ContextResolver.java` — UPDATE: implement register/resolve
  (currently `throw new UnsupportedOperationException("wave 1")`).
- `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/...` — CREATE: Bukkit backend
  `FaweThreadContext` impl (choose a package/name consistent with existing Bukkit conventions;
  propose it in your record). No FQN inline — imports only.

## Context & decisions
- Correctness of world operations > performance, both hard requirements (spec §4b). The
  predicate is on the hot path (called from every ownership guard); keep `isTickThread()` /
  `ownsChunk()` allocation-free.
- Async-first (user Java conventions): the resolver itself is a pure lookup; no executors here.

## Executor constraints
- Java 21 output for core + `worldedit-bukkit` main (architecture §2). No folia-api reference
  anywhere in this task (F1).
- No git commits. Orchestrator owns builds and the graph compile proof.
- No FQN inline (imports). English only. Match FAWE upstream style (4-space indent).

## Escape hatch
If the FAWE pool threads do NOT already expose a reliable marker type for `isFaweWorker()`
(verify against `FaweThread`/`FaweBasicThreadFactory`/`FaweForkJoinWorkerThreadFactory`),
STOP and report the gap (escalation NEEDS_CONTEXT) rather than falling back to a thread-name
comparison — the architecture explicitly forbids the name comparison.

## Acceptance criteria
- Graph compile proof green:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava
  :worldedit-bukkit:compileJava --no-configure-on-demand`
- On Paper/Spigot: the Bukkit backend answers identically to the pre-port
  `Fawe.isMainThread()` for `isTickThread`/`ownsChunk`/`isGlobalContext`; zero behavior change
  (grep-able: no new blocking, no live-state access in the predicate).
- `ContextResolver.register` rejects a second registration (fail-fast).
- Runtime confirmation deferred to harness (record as pending):
  `JAVA_HOME_RUNTIME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
  harness/scenario.sh smoke-set --version 26.1.2` (26.1.1 does not exist upstream — not a
  target).

## Test / verification strategy
Orchestrator runs the graph compile proof. Runtime behavior (Bukkit identity, worker marker)
is confirmed by the harness once task 17 registers a backend; record as pending until then.

---
## Dev record (worker fills this in on completion)

- **Status:** DONE_WITH_CONCERNS — implementation and static review complete; orchestrator-owned
  graph compilation and task-17-dependent runtime confirmation remain pending.
- **File List:**
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/ContextResolver.java:12-30`
    — implemented null rejection, atomic single registration, duplicate rejection, and
    deterministic unresolved failure.
  - `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/util/BukkitThreadContext.java:20-56`
    — created the Paper/Spigot backend. Ownership roles delegate to the old main-thread identity;
    worker classification uses the explicit `FaweThread` marker.
  - `.orchestrate/folia-port/tasks/10-thread-context-resolver.md:100` — completed this record.
- **Deviations:** None. The backend name/package is
  `com.fastasyncworldedit.bukkit.util.BukkitThreadContext`, beside the existing Bukkit utility
  backend classes. Bootstrap files were intentionally not edited because registration is owned
  by task 17.
- **Attack points:**
  - Task 17 must extend the initial `WorldEditPlugin.onLoad()` branch at
    `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/WorldEditPlugin.java:131-138`:
    register the Folia context after certification, or register `BukkitThreadContext` in the
    non-Folia branch, before execution continues at line 140 and before `FaweBukkit` is created
    at line 258.
  - `FaweThreadContext.requireOwns` delegates directly to `ownsChunk` and throws on false at
    `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/FaweThreadContext.java:28-33`;
    the Bukkit implementation therefore preserves the old main-thread guard exactly.
  - The graph compile proof was not run by instruction. Runtime identity/marker confirmation is
    pending task 17 registration and the required `smoke-set` harness run.
- **Escalation:** AUTHORIZED — no frozen-signature or marker gap. `FaweBasicThreadFactory` and
  `FaweForkJoinWorkerThreadFactory` construct marker-bearing threads at
  `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/FaweBasicThreadFactory.java:24-26`
  and `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/FaweForkJoinWorkerThreadFactory.java:20-23`.

---
## Cross-review outcome (Opus, fresh thread, 2026-07-17)

**PASS-WITH-NOTES.** Registration concurrency, Paper behavior identity (per-call delegation
correctly reproduces `setMainThread()` mutability), §1b purity, fail-fast, and F1 all pass.

One MINOR, semantics-defining: `isFaweWorker()` == `instanceof FaweThread` covers exactly the
extent-carrying prepare pools (QueueHandler ForkJoin primary/secondary + blocking executor).
The UUID-key-queued executor (`UUIDKeyQueuedThreadFactory`) and `TaskManager`'s own
pool/threads are FAWE-owned but NOT marked — by architecture §3.1 scoping ("unchanged pools" =
prepare pools), consistent with the pre-existing `FaweThreadUtil` test. **Binding note for
tasks 12/15/16/17: read `isFaweWorker()` as "extent-carrying prepare worker", never "any
FAWE-owned thread".** Interface comment updated accordingly (comment-only, no signature
change).
