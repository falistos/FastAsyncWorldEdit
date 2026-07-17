# Task 11 — Completion protocol: OperationCompletion + ChunkTerminalRecord (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1
- **Depends on:** none structurally (core coordinator). Consumed by tasks 12, 14.
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: history/persistence
  + concurrency. Double-reviewer (data persistence AND thread-ownership) per spec §6 — this is
  the exactly-once completion sink; corruption/loss here is the named risk.

## Mandate
Implement the exactly-once operation-completion protocol frozen in architecture §3.6
(F5 + r2 amendments 1–2): `OperationCompletion` with registration-before-scheduling, keyed
terminal records with applied receipts, explicit admission closure preserving the aggregate
rejection cause, and a same-chunk plan sequencer. Decision FD-1 stands: **CommitOutcome does
NOT exist** — the frozen §3.6 `ChunkTerminalRecord` protocol is the design. Do not reintroduce
any two-record Committed/CommitFailed fold.

## In scope
- `OperationCompletion` implementation (core): `register` (exactly once per chunk plan, BEFORE
  admission attempt/scheduling), `closeAdmission` (no further registration; completion becomes
  reachable; preserves aggregate `NOT_ACCEPTED` rejection cause so a no-committed-mutation
  operation resolves `FAILED`, never successful-empty), `terminal` (idempotent per
  (operationId, chunkKey, planSequence); duplicates counted diagnostically, never decrement),
  `future()` (completes exactly once). State machine OPEN → COMPLETING → SUCCEEDED | FAILED |
  PARTIAL. Completion only when: admission closed AND every registered chunk terminal AND every
  required finalizer settled AND every APPLIED record reached its persistence boundary.
  `SUCCEEDED` additionally requires every accepted mutation committed, all required finalizers
  succeeded, all packet sends enqueued (architecture §3.6).
- The **same-chunk plan sequencer**: allocates a per-chunk `planSequence` before registration
  and scheduling; invariant across dispatcher callbacks, ownership rebinds, recapture/reprepare
  attempts, and finalizers (r2 amendment 1). Same-chunk tickets execute in registration order.
- Fill `OperationResult` (currently an empty `// wave 1` shell) with a minimal coherent shape:
  terminal classification (succeeded/failed/partial) + per-chunk terminal records / receipts
  sufficient for undo and the actor-facing single result. `PacketPhaseResult` and
  `EntityAction` shapes: fill the minimum this task needs; note that wave 2 (chunk pipeline)
  may extend them.

## Out of scope
- Wiring the completion sink into the commit path / broker (task 14) or the dispatcher
  (task 12). This task delivers the coordinator + sequencer as standalone, unit-testable core
  types.
- Write-ahead history PREPARED/APPLIED persistence mechanics (wave 2). This task defines the
  "APPLIED reached its persistence boundary" gate as an injectable predicate/callback, not the
  persistence itself.

## Binding references
- architecture.md §3.6 (the entire exactly-once section — terminal identity r2 am.1,
  registration/rejection r2 am.2, the six-state `TerminalStatus`, `AppliedReceipt`,
  `ChunkTerminalRecord`, `OperationCompletion`, drain terminalization), §1b (liveness — the
  coordinator is reachable only from dispatcher-run contexts; `terminal`/`register` are the
  shared-state half of C1, asserted at entry), C4.
- spec §4d (operation semantics: success only after every accepted mutation + finalizer;
  every async failure reaches the actor exactly once; history describes exactly what committed).
- In-tree frozen types: `ChunkTerminalRecord.java`, `OperationCompletion.java`,
  `AppliedReceipt.java`, `TerminalStatus.java`, `OperationResult.java`, `PacketPhaseResult.java`,
  `EntityAction.java`. Implement AGAINST these.

## Skeleton state (RESOLVED 2026-07-17 — certification corrective C-F1F2)
The v3-stale skeleton this draft originally flagged (`ticketSequence` as terminal key) was
corrected by certification corrective C-F1F2 and re-verified verbatim against architecture
v3.1 §3.6: `ChunkTerminalRecord.planSequence` (invariant, same-chunk sequencer),
`OperationCompletion.register(long chunkKey, long planSequence)` documented pre-admission,
`RegionTicket.sequence()` re-documented diagnostics-only (MUST NOT key terminal completion).
Implement directly against the current tree — no rename authorization needed anymore.

## Files to touch
- `worldedit-core/.../util/task/OperationCompletion.java` — add a concrete implementation
  class (propose name); interface signatures are frozen as-is.
- `worldedit-core/.../util/task/ChunkTerminalRecord.java` — frozen as-is (no edits expected).
- `worldedit-core/.../util/task/OperationResult.java`, `PacketPhaseResult.java`,
  `EntityAction.java` — fill shapes.
- CREATE: same-chunk plan sequencer (core).

## Context & decisions
- Correctness > performance (spec §4b): exactly-once and no-silent-loss are non-negotiable.
- Async-first: `future()` is a `CompletionStage`; completion callbacks run on a FAWE executor,
  never a tick thread (architecture §3.3/§3.6).
- Partial commit is representable (`PARTIALLY_COMMITTED` + exact `AppliedReceipt`); undo
  operates only on the applied subset; no automatic whole-operation rollback (architecture §3.6).

## Executor constraints
- Java 21 output (core). No folia-api reference (F1). No git commits. No FQN inline. English.

## Escape hatch
If any frozen signature in the current tree contradicts architecture v3.1 §3.6 (should not
happen post-corrective — re-verified 2026-07-17), STOP with NEEDS_CONTEXT rather than editing
co-signed SPI signatures.

## Acceptance criteria
- Graph compile proof green:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava
  :worldedit-bukkit:compileJava --no-configure-on-demand`
- Idempotency: duplicate `terminal(...)` for the same (operationId, chunkKey, planSequence) does
  not double-count and does not flip the aggregate result.
- No-commit operation: admission closed with only `NOT_ACCEPTED` terminals resolves `FAILED`.
- `future()` completes exactly once for every terminal shape (deterministic unit tests here are
  in-scope per spec §6 / §4 "targeted deterministic tests" — failure injection).

## Test / verification strategy
Targeted deterministic tests (spec §6): exactly-once, duplicate suppression, no-commit → FAILED,
partial → PARTIAL, drain terminalization (no INCOMPLETE state). Orchestrator runs compile proof
+ tests. `[NR-H]` (terminal completion failure injection) is a certification input, noted.

---
## Dev record (worker fills this in on completion)

- **Status:** <DONE | DONE_WITH_CONCERNS | BLOCKED>
- **File List:**
- **Deviations:**
- **Attack points:**
- **Escalation:** <AUTHORIZED | BLOCKED | NEEDS_CONTEXT> — <detail>
