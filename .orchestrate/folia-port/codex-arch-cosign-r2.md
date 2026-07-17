Verdict: withhold co-signature for v3. Most round-1 findings were integrated faithfully, including the conservative `RegionKey` interpretation, but four load-bearing contract issues and one freeze-record correction remain.

### Fidelity table

| Finding | Verdict | Review |
|---|---|---|
| F1 | PASS, compile-proof conditional | Core owns the unsealed context interface, final capabilities, callbacks, and targets; the Folia backend receives the authority rather than implementing tickets ([architecture.md:94](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:94), [architecture.md:194](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:194), [architecture.md:235](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:235)). The repository uses a JDK 25 toolchain with Java 21 core output, so these core types are language-legal ([buildlogic.common.gradle.kts:14](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/build-logic/src/main/kotlin/buildlogic.common.gradle.kts:14), [buildlogic.common-java.gradle.kts:10](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/build-logic/src/main/kotlin/buildlogic.common-java.gradle.kts:10)). Authority bootstrap constructability remains appropriately subject to the compile proof. |
| F2 | PASS | All ten legacy descriptors remain unchanged; internal scheduling uses core `ChunkTarget`/`EntityTarget` and `CompletionStage` ([architecture.md:526](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:526)). Withdrawing `RegionKey` hint methods from the public dispatcher is a correct conservative reading of F2: the dispatcher is a public scheduling SPI, while hint capture and rebind remain broker-internal ([architecture.md:347](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:347), [judgment.md:140](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/tournament/judgment.md:140)). No correction required. |
| F3 | PASS | Ticket lifetime is one dispatcher callback, retirement is in `finally`, ticket storage is prohibited, entity work uses `EntityTicket`, and every resumed phase receives a fresh capability ([architecture.md:244](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:244), [architecture.md:608](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:608)). |
| F4 | PASS | The full demand/stage/cancellation model is restored, with nonblocking `tryAcquire`, reserved continuation admission, and permit transfer before mutation ([architecture.md:359](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:359), [architecture.md:451](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:451), [architecture.md:470](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:470)). |
| F5 | PARTIAL — blocking residuals | The six statuses, applied receipt, deduplication, admission closure, and persistence gate are present. The terminal identity, rejected-admission path, and shutdown deadline remain internally inconsistent; amendments below. |
| F6 | PASS | Load epoch, FAWE mutation sequence, component fingerprints, pre-mutation validation, and bounded recapture are explicit ([architecture.md:590](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:590)). |
| F7 | PASS | All four eligibility requirements precede side effects, admission is immediate, and consumer callbacks are excluded ([architecture.md:772](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:772)). |
| F8 | PASS | The finalizer order now includes neighbor settlement, detached relight, fresh-owner materialization, required packet enqueue, APPLIED persistence, then terminal record ([architecture.md:608](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:608)). A1.4 is also extended correctly to FAWE shared state ([architecture.md:153](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:153), [amendment-1-draft.md:36](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/amendment-1-draft.md:36)). The neighbor-order weakening remains explicitly unavailable until certified or signed, which is consistent. |
| F9 | PASS artifact; stale architecture text | W06b has the original thirteen fields, 271 contiguous rows `APIC-092`–`APIC-362`, no missing/duplicate IDs, and valid column counts. Its 24 type totals sum to 257, with 10 QueueHandler and 4 Fawe rows; all are PRESERVED ([w06b-apic-gap-closure.md:3](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w06b-apic-gap-closure.md:3), [w06b-apic-gap-closure.md:413](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w06b-apic-gap-closure.md:413)). Architecture §7 still calls F9 pending and says 255. |
| F10 | PARTIAL — blocking architecture residual | `TaskKind`, lifecycle drain, timing seams, and G1–G8 ownership are named. Numeric values and harness implementation do gate wave 1 rather than the architecture signature. However, the frozen G4 producer is still insufficient; details below. |
| F11 | PASS, subject to F5 correction | Rev 2 now records explicit semantic differences for R1–R8 and correctly identifies the inline guarantee as a v3 addition ([judgment.md:131](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/tournament/judgment.md:131), [judgment.md:149](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/tournament/judgment.md:149)). R6 faithfully mirrors v3, but therefore also inherits its terminal-sequence defect. |
| Module layout | PASS, compile-proof conditional | The dedicated `:worldedit-bukkit:folia` Java-25 module, isolated compile-only dependency, reflective loading, Mojang/Paper-only bundling, Spigot exclusion, and Java-21 core are stated exactly as recommended ([architecture.md:94](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:94)). |

### Residual mandatory amendments

1. **[BLOCKING] — Terminal identity does not exist when registration is required**

Every phase receives a fresh ticket, yet `register` must run before scheduling and the terminal key is defined as the later SECTIONS callback’s `RegionTicket.sequence()` ([architecture.md:274](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:274), [architecture.md:608](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:608), [architecture.md:680](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:680), [architecture.md:690](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:690)). That sequence cannot be supplied before its callback ticket is minted.

Required wording:

> `ChunkTerminalRecord` and `OperationCompletion` use a per-chunk plan sequence allocated by the same-chunk sequencer before registration and scheduling. The plan sequence remains invariant across dispatcher callbacks, ownership rebinds, recapture/reprepare attempts, and finalizers. `RegionTicket.sequence()` identifies an individual callback mint for diagnostics only and MUST NOT key terminal completion.

Rename `ticketSequence` to `planSequence` in §3.6 and judgment R6.

2. **[BLOCKING] — `NOT_ACCEPTED` has no reachable completion path**

Registration is restricted to accepted chunks, while `NOT_ACCEPTED` is a terminal status and saturation must fail an operation when no plan committed ([architecture.md:518](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:518), [architecture.md:663](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:663), [architecture.md:690](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:690)). The coordinator has no operation-level rejection method, so a wholly rejected operation can close admission without recording its failure.

Required wording:

> Every chunk ticket entering admission is registered before its admission attempt. Rejection before acceptance terminates that registration exactly once with `NOT_ACCEPTED` and the rejection cause. Every accepted chunk remains registered before scheduling. Admission closure preserves the aggregate rejection cause so an operation with no committed mutation resolves `FAILED`, not successful empty completion.

3. **[BLOCKING] — Bounded drain permits non-terminal registered work**

“Recorded incomplete” is not one of the frozen terminal statuses and does not satisfy spec §4d’s clean completion or explicit partial failure requirement ([architecture.md:718](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:718), [spec.md:116](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:116)).

Required wording:

> Expiry of the shutdown drain deadline does not create an `INCOMPLETE` terminal state. Every registered plan that has not mutated terminates as `CANCELLED_BEFORE_MUTATION` or `FAILED_BEFORE_MUTATION`. Every plan that has mutated reaches APPLIED persistence with its exact receipt and terminates `PARTIALLY_COMMITTED` unless it completes cleanly. Non-zero unresolved tasks or live tickets in `DrainReport` constitute a failed certification gate; the report does not substitute for operation terminalization.

4. **[BLOCKING] — F10’s G4 producer omits accepted fields**

The accepted F10 amendment required per-region and global snapshots including packet bytes, tickets, futures, rebinds, and outstanding chunks. `Pressure` currently exposes ready counts/bytes, finalizers, waiters, drains, age, and timers only ([architecture.md:419](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:419)). §7 mentions dispatcher ticket counts, but names no producer for outstanding futures/chunks, packet bytes, rebind counts, or a global aggregate ([architecture.md:864](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:864)). Consequently the required `FAWE_QUEUE depth/inflight/outstanding/region` signal is not fully realizable ([w08-perf-budgets.md:171](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w08-perf-budgets.md:171)).

Required amendment:

> Freeze a per-region and global diagnostic snapshot producer containing ready chunks/bytes, waiters, finalizer chains, packet bytes, scheduled drains, live tickets, outstanding futures, outstanding chunks, rebind count, and oldest-ready age. Define the exact mapping from that snapshot to `FAWE_QUEUE depth= inflight= outstanding= region=`.

The harness implementation and numeric budget values may remain wave-1 gates after this architecture-side seam is frozen.

5. **[MINOR, mandatory freeze-record correction] — F9 remains described as pending**

Replace architecture §7’s F9 block with:

> **F9 — CLOSED.** `spikes/w06b-apic-gap-closure.md` adds 271 APIC rows: 10 QueueHandler declarations, 257 source declarations across 24 Extent-derived types, and 4 Fawe UUID-executor declarations. All are PRESERVED with deterministic context derivation. Combined §4c census: 653 declarations.

Also remove the obsolete statement that the read-only workspace prevented creation of the file ([w06b-apic-gap-closure.md:422](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w06b-apic-gap-closure.md:422)).

### Final verdict

**WITHHOLD CO-SIGN v3 pending amendments 1–4 and the F9 record correction.**

After those textual changes, I can co-sign conditionally on the `[W0-FREEZE]` compile proof. The in-progress harness implementation and numeric budget population then remain wave-1 dispatch gates, not architecture co-signature gates.