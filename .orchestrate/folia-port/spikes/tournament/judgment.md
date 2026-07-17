# Design Tournament — Judgment (rev 2 — F11 provenance corrections applied)

FAWE Folia port, wave-0 pipeline design tournament. Three entries were submitted against the
same evidence base (`w02-pipeline-results.md`) and the same frozen spec (§1b/§4b/§4c/§4d/§8):

- **Proposal A — latency-first** (`proposal-A-latency.md`)
- **Proposal B — throughput-first** (`proposal-B-throughput.md`)
- **Proposal C — safety & simplicity-first** (`proposal-C-safety.md`)

The ruling is a **synthesis**, not a single winner: C provides the skeleton, B the engine, A the
config-gated grafts. This document records the scoring rationale, what each proposal contributed,
what was rejected, and the reconciliation list. The synthesized design is `../../architecture.md`
(**v3.1** — co-sign r1 findings applied in v3, r2 residuals in v3.1; see
`../../codex-arch-cosign-r1.md` / `../../codex-arch-cosign-r2.md`).

**Rev 2 note (F11):** rev 1 described several merges as "verbatim". The adversarial co-signer
showed that was overstated: every adopted piece carries semantic diffs — from its source
proposal, from v2, or both — and rev 1's phrasing risked implementation workers treating
adaptations as inherited guarantees. This revision replaces every "verbatim" claim with an
explicit semantic diff and re-runs the reconciliation check against the v3 signatures.

## Scoring rationale

Spec §4b fixes the priority order: **(1) correctness of accepted operations, (2) performance.**
"Correctness beats throughput whenever they conflict." The named risk (spec §6) is *silent
chunk/world corruption and server deadlock — invisible to code review*. That priority and that
risk decide the shape of the merge.

- **Skeleton → Proposal C.** The correctness-first priority makes C's core structurally
  mandatory. The `RegionTicket` capability turns "touch live state off-owner" into a
  **compile error** and makes the W0.2 non-owner-callback re-dispatch rule enforced by the type
  system rather than by review discipline — directly answering the named risk. A single
  `FoliaRegionDispatcher` choke point with ticket-typed callbacks (no opaque `Runnable`) makes
  the C1 danger set a `git grep`. One exactly-once completion sink makes §4d fall out of a
  protocol instead of discipline. These are the cheapest-to-review, hardest-to-violate
  primitives on offer. (r1 corrected their *placement and scope* — F1/F3/F5 — not their
  selection.)
- **Engine → Proposal B.** Once the skeleton guarantees correctness, §4b(2) performance is won
  by B's machinery. W0.2 ranked region-sweep first; B builds the production form of it —
  dynamic per-region commit lanes, an adaptive sliced-sweep controller keyed on real per-lane
  signals (C6/§8), a detached GET snapshot model with per-chunk versioning and single-flight,
  and weighted deficit-round-robin so many concurrent operations interleave fairly. B is the
  only entry that engineered behavior at large N and under concurrency rather than
  extrapolating from N=16. (r1 F4 additionally showed B's admission *data model* is
  load-bearing, not optional — see R4.)
- **Latency grafts → Proposal A.** A correctly reframed W0.2: visibility is ~1 region tick of
  scheduler delay on top of sub-millisecond commit work. A's three levers (owner-inline,
  viewer-near ordering, packet-at-commit) are retained as **config-gated** grafts, each
  defaulting conservatively and carrying its `[NEEDS-RUNTIME]` tag — and, per r1 F7/F8, each
  now bounded by explicit eligibility conditions and the terminal phase order.

Net: C wins the priority-#1 axis and the two non-quantified judging axes (merge cost,
reviewability); B wins priority-#2 without touching an ownership invariant; A adds
perceived-latency upside as opt-in tuning that fails safe.

## What each proposal contributed

**Proposal C (skeleton — the spine of the design):**
- The `RegionTicket` capability concept: caller-unconstructable, dispatcher-minted, required by
  every live-state adapter method. *As frozen in v3 it differs from C's text:* core-owned final
  class (not a sealed interface with platform permits — F1), scoped to one dispatcher callback
  and retired in `finally` (not the whole finalizer chain — F3), plus an `EntityTicket`
  analogue C did not have.
- `FoliaRegionDispatcher` as the sole scheduler choke point with ticket-typed callbacks and a
  ticket-free `onGlobal`. *v3 diffs:* core-owned callback types, a `TaskKind` label on every
  submission, `CompletionStage` returns, lifecycle `stopAccepting`/`drain` (F10), and an
  **explicit** inline-when-owner execution guarantee (see G-A1 below).
- The one-sink exactly-once completion idea. *v3 replaces C's `Committed/CommitFailed` fold
  entirely* (F5) — see R6.
- The fixed deterministic per-chunk phase order concept (reordered by F8 — see R8).
- `FaweThreadContext` predicate seam (unsealed in v3 — F1) and the ticket-typed internal sync
  migration target (retargeted in v3 — F2, R5).
- Per-region bounded backpressure with **caller-runs for owner-bound work forbidden** — the
  rule survives verbatim; the SPI carrying it is B's (R4).
- The structural framing of mandatory non-owner-callback re-dispatch (amendment A1.4): a
  ticketless callback cannot compile a live-state call. *r1 F8 extension:* the same rule covers
  FAWE shared operation state, which ticket typing alone does not protect — that half is a
  coordinator-API assertion, not a compile-time property.

**Proposal B (engine — how it performs and scales):**
- Dynamic per-region commit lanes / mailboxes with drain-time rebind on region split/merge
  (v3 adds F4 permit *transfer* on rebind, which B did not specify).
- The adaptive sliced-sweep controller (750 µs / 1.5 ms / cap 8→[1,32]) — adopted with its
  numbers as `[NEEDS-RUNTIME]` initial values.
- Detached GET snapshot profiles, per-chunk `ChunkVersion`, single-flight cache with concrete
  bounded limits and lease-based invalidation. *r1 F6 diff:* B's `ChunkVersion` alone is
  insufficient against external mutation; v3 adds world/load epoch + base-component
  fingerprints, which no proposal contained.
- Weighted deficit-round-robin interleaving; same-chunk registration ordering.
- The full admission model — `Demand`/`Priority`/`Stage`/byte accounting/cancellation signal —
  **restored in v3** after v2 wrongly reduced it (R4), plus B's saturation sequence, terminal
  taxonomy, and write-ahead `PREPARED`/`APPLIED` history (now the backbone of the F5 sink).
- `TaskKind` instrumentation labels (now actually accepted by dispatcher methods — F10).

**Proposal A (grafts — perceived-latency upside, config-gated):**
- The W0.2 reframing (visibility = ~1 tick scheduler delay over sub-ms commit) motivating all
  three levers.
- G-A1 Tier-1 owner-inline fast path — *v3 diff:* bound by F7's four eligibility conditions
  and the callback-exclusion rule, which A's text did not contain.
- G-A2 viewer-near commit ordering — demoted from A's priority-semaphore to intra-drain
  ordering under B's DRR fairness.
- G-A3 packet-at-commit — expressed as the optional phase-7 early packet under F8's terminal
  order; `DELAY_PACKET_SENDING` remains the default.
- The `[NEEDS-RUNTIME]` register discipline, carried into the architecture.

## What was rejected (one line each)

- **A's bespoke `dispatchCommit` inline/coalesce SPI** — superseded by the dispatcher's
  (now explicit) inline-when-owner guarantee; the fast path is a graft, not a second entry point.
- **A's standing per-region "commit pump"** — replaced by B's dynamic lane broker (same
  amortize-the-hop goal, plus drain-time ownership rebind the static pump lacked).
- **A's `ContextSync` interface shape** — replaced by the core-target `syncOn` (R5).
- **A's viewer-near *priority semaphore*** — reduced to intra-drain ordering under DRR.
- **A/B always-on immediate-packet + eager per-region GET prefetch** — config-gated only.
- **B's `FaweTaskTarget` sealed dispatch type + `submit(target, kind, task)` dispatcher** —
  dropped as the dispatch shape; its *descendants* survive as the core `ChunkTarget`/
  `EntityTarget` records (F2) and the `TaskKind` parameter (F10).
- **B's `SyncPriority` on the frozen sync signature** — not frozen; the preserved
  `syncWhenFree` public descriptors carry the WHEN_FREE semantics.
- **C's sealed `permits` clauses naming platform classes** — rejected by r1 F1 (cannot compile
  across modules); replaced by core-owned final capabilities + injected authority.
- **C's whole-chain single-ticket scope** — rejected by r1 F3 (cannot span async phases);
  replaced by per-callback tickets + yield/resume receipts (B's §4.3 behavior, generalized).
- **C's two-record `CommitOutcome` sink** — rejected by r1 F5 (cannot represent partial
  execution or dedupe); replaced by the keyed terminal-record protocol.
- **C's `acquire(RegionKey, Duration)` backpressure SPI** — rejected by r1 F4 (cannot carry
  demand/cancellation/stages); B's SPI restored.
- **C's static slice cap `C ≈ 16`** — superseded by B's adaptive controller.
- **C's `ChunkSnapshot` record shape** — the GET model froze to B's profile-based snapshots.
- **Starlight-on-Folia salvage** — stays DEGRADED to `NMSRelighter` (w03 Q3 OPEN, A1.2).

## Reconciliation list (rule 6 — with explicit semantic diffs, F11)

Where proposals defined the same piece differently, C's signature won for safety-relevant
parameters and B's for throughput-relevant knobs — **then r1 corrected several outcomes**.
Each entry states what was adopted AND what changed relative to its source. Checked against
the v3 signatures (re-run per F11).

| # | Piece | Resolution + explicit semantic diff |
|---|---|---|
| **R1** | Dispatch parameter type | C's chunk-anchored `onRegion(World,cx,cz,…)` over B's `submit(FaweTaskTarget,…)`. **Diffs from C:** callback types moved to core (F1); `TaskKind` parameter added (F10); returns `CompletionStage` not `CompletableFuture`; `onEntity` takes `EntityTask`/`EntityTicket` (F3). B's target concept survives as core `ChunkTarget`/`EntityTarget` for `syncOn` only (F2). |
| **R2** | `RegionKey` | B's record adopted — **but demoted** (F2): banned from all core/public scheduling signatures; internal lane metadata in `FoliaCommitBroker`/`FoliaBackpressure` only. v2's dispatcher-level `currentRegionKey`/`lastRegionHint` grafts are withdrawn from the frozen dispatcher surface; hint bookkeeping is broker-internal. |
| **R3** | Region-hint discovery | Same as R2: the *mechanism* (owner-side hint capture, drain-time rebind) is B's §3.2 behavior, kept; the *API exposure* on the dispatcher is dropped (F2). |
| **R4** | Backpressure SPI | **Reversed from rev 1.** v2 froze C's `acquire(RegionKey,Duration)`/`CommitPermit` and demoted B's model to prose — losing, concretely: `Demand` (operationId, chunks, preparedBytes, finalizerChains, priority, previouslyAccepted, deadlineNanos), the `cancellationSignal` parameter, the four-stage `Permit` lifecycle with `enter(Stage)`, byte accounting, and `Pressure` snapshots. r1 F4 showed that loss permits admission cycles. v3 restores B's full SPI and **adds three members no proposal had:** `tryAcquire` (owner-thread nonblocking), `acquireContinuation` (reserved continuation capacity), `transfer` (permit re-accounting on region merge). C's contribution that survives: the no-caller-runs rule and the worker-only wait discipline. |
| **R5** | Context-carrying sync | **Preserved public surface, now enumerated** (F2/F11): the exact ten descriptors `async(Runnable,T)`, `async(Runnable)`, `async(Callable)`, `sync(Runnable)`, `sync(Callable) throws Exception`, `sync(Supplier)`, `syncWhenFree(Runnable,T)`, `syncWhenFree(Runnable)`, `syncWhenFree(Callable) throws Exception`, `syncWhenFree(Supplier)` — all returning `Future`, unchanged (v2's frozen example altered `sync` returns and omitted eight; void). **Internal target:** C's ticket-typed callback style, retargeted from `RegionKey` to the new core `ChunkTarget`/`EntityTarget` records (neither proposal had these — F2), returning `CompletionStage` in every overload including `Runnable`. |
| **R6** | Chunk outcome / completion sink | **C's sink replaced, B's model promoted** (F5; terminal key corrected by r2 amendment 1). How every terminal enters the sink, precisely: each chunk plan receives a **`planSequence` allocated by the same-chunk sequencer before registration and scheduling** — invariant across dispatcher callbacks, ownership rebinds, recapture/reprepare attempts, and finalizers (`RegionTicket.sequence()` is diagnostics only and MUST NOT key terminal completion); every chunk ticket entering admission calls `register(chunkKey, planSequence)` **before its admission attempt**, and rejection before acceptance terminalizes it exactly once as `NOT_ACCEPTED` with its cause; exactly one `ChunkTerminalRecord(operationId, chunkKey, planSequence, TerminalStatus, AppliedReceipt, failure)` per key reaches `terminal(...)`; duplicates are ignored without decrement; `closeAdmission()` gates completion and preserves the aggregate rejection cause (an all-rejected operation resolves `FAILED`); the future resolves only after admission-closed + all-terminal + finalizers-settled + APPLIED-persisted. B's six `TerminalStatus` values and APPLIED receipt fields are the record's vocabulary; C's contribution that survives is the single-sink/exactly-once *shape*, not its types. |
| **R7** | Slice cap | B's adaptive controller (750 µs target / 1.5 ms threshold / cap 8, floor 1, ceiling 32, grow/halve rules) framed by C's liveness rationale (a task stays under a region tick, §1b). C's static `C ≈ 16` superseded. Numbers `[NEEDS-RUNTIME]`. Unchanged by r1. |
| **R8** | Phase order (new in rev 2) | v2 froze C's 10-step order with relight *submission* as step 9 and packet before relight. r1 F8 rejected that: v3's order runs live phases → neighbor settlement → optional G-A3 early packet → detached relight compute → fresh-owner light materialization → required packet send → APPLIED persistence → terminal record. Relight submission is never a terminal point. B's SECTIONS→…→CHUNK_COMPLETE machine is the closer ancestor of the v3 order than C's list. |

## Graft adaptations to the skeleton (rule 3)

- **G-A1 (Tier-1 inline).** Rev 1 claimed C's `onRegion` "already runs inline" — **Proposal C's
  text contains no such guarantee** (F11). v3 makes the inline-when-owner execution an explicit
  contract of `FoliaRegionDispatcher` (§3.3), and G-A1 builds on that plus the F7 eligibility
  conditions: no same-chunk predecessor (sequencer-proven), nonblocking owner `tryAcquire`,
  PREPARED boundary pre-satisfied without tick-thread I/O, framework-owned callback-free
  bounded preparation; pattern/mask/transform/custom-extent/persistence/plugin callbacks are
  excluded. Any failure → normal detached lane before any side effect.
- **G-A2 (viewer-near ordering).** Reduced from A's priority-semaphore reordering to
  intra-drain ordering *inside* B's DRR fairness; may cause an earlier yield but may not bypass
  same-chunk ordering or fairness.
- **G-A3 (packet-at-commit).** Expressed as the optional phase-7 early packet under the F8
  terminal order, with `DELAY_PACKET_SENDING` as the default and the required light packet at
  phase 10 regardless.

All three remain config-gated, default conservative, fail toward the safe/throughput path, and
carry their `[NEEDS-RUNTIME]` tags (`[NR-D]`, `[NR-E]`, `[NR-F]`, `[NR-G]`).
