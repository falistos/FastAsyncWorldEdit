I cannot co-sign v1 as written. The direction is sound, but several safety contracts are still implicit, so incompatible implementations could all claim compliance.

## Findings

[BLOCKING] — No hard ownership invariant for live NMS state

The spec merely says the async pipeline will be “made correct” ([spec.md:36](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:36)). A worker could retain FAWE’s off-thread CAS model: its lock protects only FAWE participants, not the region tick concurrently using the section ([NMSAdapter.java:122](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/adapter/NMSAdapter.java:122)). That permits palette/count, block-entity, entity, POI, and lighting corruption.

Amend with:

> Folia safety invariant: no FAWE worker, global-scheduler callback, or non-owning region thread may directly read or mutate live region-owned Minecraft state. Off-thread work may use detached data only; every live read, commit, and finalizer must execute through the owning world/chunk/entity scheduler with ownership valid at execution time. AsyncCatcher/TickThread checks must never be disabled or bypassed. Any exception requires a signed upstream-supported contract and a dedicated certification gate.

[BLOCKING] — The deadlock prohibition is a goal, not a liveness contract

Current paths contain a worker `join()` ([ParallelQueueExtent.java:161](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-core/src/main/java/com/fastasyncworldedit/core/queue/implementation/ParallelQueueExtent.java:161)), blocking chunk-load `get()` ([AbstractBukkitGetBlocks.java:82](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/adapter/AbstractBukkitGetBlocks.java:82)), and a finalizer routed back through location-free `sync` ([AbstractBukkitGetBlocks.java:174](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/adapter/AbstractBukkitGetBlocks.java:174)). A region thread waiting for FAWE while FAWE waits for that region is the obvious permanent cycle; “no watchdog stalls” cannot prove its absence.

Amend with:

> Folia liveness invariant: a Folia tick thread must never block on work that can transitively require any Folia scheduler. FAWE executors must not wait for region/entity/global callbacks while holding chunk, session, queue, or history locks. Backpressure must not use caller-runs execution for owner-bound work. Every allowed wait must have a documented acyclic wait-for path, bounded timeout, cancellation propagation, and thread-dump assertion.

[BLOCKING] — “Third-party plugins work unchanged” has no defined semantics

“Within those plugins’ own Folia constraints” ([spec.md:38](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:38)) could mean binary compatibility, source compatibility, same results, or merely “does not throw.” The public `TaskManager` promises a single “main thread” and exposes location-free methods ([TaskManager.java:49](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-core/src/main/java/com/fastasyncworldedit/core/util/TaskManager.java:49)); it cannot select an owner for an arbitrary runnable. Workers will otherwise invent different caller obligations and callback contexts.

Amend with:

> Before implementation decomposition, freeze and co-sign an API thread-context contract. For every supported FaweAPI, EditSession, Extent, and TaskManager entry point, record: binary/source compatibility; legal caller contexts (region, entity, global, async, FAWE worker); required target context; blocking/return behavior; completion point; callback thread; cancellation; and error propagation. “Caller constraints” excludes only Bukkit access performed directly by the caller; FAWE remains responsible for its internal routing. Every location-free legacy method must be explicitly PRESERVED with deterministic context derivation, DEGRADED with a deterministic failure, or excluded by a signed spec amendment—never routed arbitrarily or allowed to block a tick thread.

[BLOCKING] — Multi-region success, partial failure, and history consistency are undefined

The code can currently catch a chunk failure and return `null` ([AbstractBukkitGetBlocks.java:134](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/adapter/AbstractBukkitGetBlocks.java:134)); region fan-out magnifies that into silent partial edits. The spec never defines whether success means queued, committed, finalized, or recorded in history, nor what cancellation, ownership migration, unload, or shutdown does.

Amend with:

> An operation may report success only after every accepted world mutation and required server-owned finalizer has succeeded. Every asynchronous failure must reach the actor/API completion exactly once. History must describe exactly the mutations that committed and must be usable only after its configured persistence boundary. Cancellation, timeout, world/chunk unload, ownership migration, and plugin disable must either complete cleanly or return an explicit partial-failure result with a valid matching undo record; whole-operation atomicity must not be implied unless certified.

[BLOCKING] — The certification suite can be vacuously green

“Every inventory item” does not define the universe of items, so omitted surfaces disappear from the zero-UNKNOWN gate ([spec.md:40](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:40)). The harness verifies blocks, but the write path also mutates block entities, entities, lighting, POI, tickets, counters, and packets ([recon-adapters-nms.md:63](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/recon-adapters-nms.md:63)). In-memory block equality can pass while saved chunks are damaged.

Amend with:

> Wave 0 must freeze and co-sign the compatibility inventory before dependent task decomposition. Its coverage baseline must enumerate every public/player entry point, caller context, adapter-owned state class, persistence surface, lifecycle transition, and degradation. Certification must compare canonical block states, biomes, block-entity NBT, entities, lighting/heightmaps and relevant persisted metadata after save, unload, and restart; assert no ticket/listener/task leaks; and exercise overlapping edits, region boundaries, concurrent players, cancellation, unload, disable, and injected failures. The public-API gate must use a separately compiled consumer plugin. The harness and oracle must themselves receive independent review.

[MAJOR] — Upgrade, downgrade, and existing disk state have no contract

History and clipboards are explicitly in scope, yet no requirement covers an existing Paper data directory moving to Folia and back. These are versioned formats—history accepts versions 1/2 ([FaweStreamChangeSet.java:275](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-core/src/main/java/com/fastasyncworldedit/core/history/changeset/FaweStreamChangeSet.java:275)); disk clipboards also carry versions 1/2 ([DiskOptimizedClipboard.java:63](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-core/src/main/java/com/fastasyncworldedit/core/extent/clipboard/DiskOptimizedClipboard.java:63))—plus a summary database and asynchronous per-player IO.

Amend with:

> The port must preserve upstream config, history, clipboard, schematic, and summary-database paths and formats. Gates must start from fixtures produced by the unported base revision, verify read/undo/redo/save/restart on Folia, then verify the resulting state remains readable on Paper. Any format change requires a separately signed, versioned, idempotent, crash-safe migration with backup, rollback/downgrade policy, and failure recovery; “no compatibility shims” does not waive persistent-data compatibility.

[MAJOR] — Packaging, adapter ownership, and unsupported-Folia behavior are ambiguous

The spec says “a single jar,” but upstream produces separate Paper/Mojang and reobfuscated Bukkit artifacts ([worldedit-bukkit/build.gradle.kts:160](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-bukkit/build.gradle.kts:160), [worldedit-bukkit/build.gradle.kts:199](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-bukkit/build.gradle.kts:199)). It also contains eight adapter modules, including 26.1 and 26.2 ([settings.gradle.kts:58](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/settings.gradle.kts:58)). One worker could Folia-enable 26.2 accidentally; another could attempt a risky artifact consolidation. An unsupported future Folia could fall through as Paper and run unsafe NMS paths.

Recommended amendment:

> “Single jar” means no Folia-only artifact: the existing Paper/Mojang artifact contains both Paper and Folia backends; the existing reobfuscated Bukkit artifact remains Spigot-only. Only adapter-26.1 is Folia-certified, on 26.1.1 and 26.1.2; adapter-26.2 and adapter-1_21* remain Paper/Spigot-only. Folia detection precedes Paper detection. Any Folia version outside the certified matrix, failed detection, or missing Folia adapter must fail closed before listeners, executors, or world access start. Certification locks exact server builds, JDK, plugin artifacts, and checksums; dynamic `26.1.2.build.+` dependencies are not certification identities.

If one physical jar across all three platforms is genuinely intended, that packaging redesign must be stated explicitly and separately gated.

[MAJOR] — The Paper/Spigot regression promise contradicts its gates

“Byte-for-byte equivalent in behavior” is not meaningful and conflicts with the allowed shared SPI touch-points ([spec.md:26](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:26)). The target table hard-gates Paper and Spigot, but done condition 4 tests only Paper ([spec.md:86](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:86)). “Paper paths untouched by default” is likewise not an objective assertion.

Amend with:

> Replace “byte-for-byte equivalent” with “observable public behavior, configuration, events, exceptions, persistence formats, and results remain equivalent on non-Folia runtimes, within frozen quantitative budgets.” A signed regression matrix must enumerate both existing artifacts and every supported adapter/runtime, with boot/linkage/no-Folia-classloading checks and functional command/API/edit/undo/history scenarios; no listed Paper or Spigot target may remain untested.

[MAJOR] — Performance and memory done-conditions have no budgets

“Fast,” “no degraded performance,” and “within agreed budgets” ([spec.md:5](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:5), [spec.md:85](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:85)) are unverifiable and mutually inconsistent if an eventual budget permits regression. Region fan-out also introduces scheduler-queue, retained-chunk, future, and heap amplification that operation latency alone will miss. FAWE’s existing admission logic depends on global TPS ([recon-queue-threading.md:57](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/recon-queue-threading.md:57)), which Folia cannot supply meaningfully.

Amend with:

> Wave 0 must freeze numeric performance/resource budgets before core implementation: workloads and edit sizes; region counts and concurrency; hardware/JVM/config; warm-up and trials; Paper baseline; median/p95/p99 completion time and throughput; per-region tick-time impact; scheduler queue depth; outstanding futures/tickets/chunks; peak heap/RSS and GC; and shutdown drain time. Folia backpressure must use bounded resources and ownership-relevant signals, not a fabricated global TPS. Replace “no degraded performance” with “no regression beyond the frozen budgets.”

[MAJOR] — “MAX assurance” is internally under-scoped and then weakened

The adversarial perimeter omits the harness/oracle, public API contract, detection/bootstrap, lifecycle, global mutable state, and degradation inventory ([spec.md:67](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:67)). Done permits unresolved MAJOR findings because it blocks only BLOCKING ones ([spec.md:87](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:87)). The “no tests beyond the harness” restriction ([spec.md:94](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:94)) forbids deterministic scheduler, routing, failure, and persistence tests that the runtime harness cannot reliably replace. Finally, hard cases named in §5 are absent from the go/no-go spike list, and spike failure has no stopping rule.

Amend with:

> Independently review the harness/oracle, API contract, bootstrap/detection, lifecycle, global-state removal, and compatibility inventory. Release requires zero open BLOCKING or MAJOR findings; accepted MINOR findings require recorded owner disposition. Targeted unit/component/stress tests are required where they provide deterministic coverage beyond the harness. Every spike must define evidence and pass/fail criteria plus GO, DEGRADE, DISABLE, or STOP outcomes; STOP or contract-changing outcomes require spec amendment and both signatures before dependent work continues. Include cross-region operations, snapshots, and packet resend in the spike disposition table.

No currently armed assurance module should be removed; the defect is missing perimeter and exit criteria.

[MINOR] — “All state on disk in `.orchestrate`” is literally overbroad

It conflicts with runtime history, clipboard, database, and server state ([spec.md:97](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:97)).

Amend “All state” to “All orchestration and project-control artifacts.”

## Decisions required now

Freeze now: ownership and liveness invariants; API compatibility meaning; success/partial-failure/history semantics; packaging and adapter matrix; unsupported-version behavior; persistent-data compatibility; certification coverage; and budget methodology.

Correctly deferred to architecture: SPI names and package layout, region-task batching, detached snapshot representation, internal queue topology, and NMS implementation technique. Numeric budgets may be measured in Wave 0, but must be frozen before implementation. Feature outcomes such as regen may follow spikes, but any degradation changes the signed contract.

Final verdict: **CO-SIGN WITH AMENDMENTS** — findings 1–10 are mandatory before architecture/task decomposition proceeds. Finding 11 is editorial but should be corrected in the same revision.