# C5 thread-predicate requalification record

## Closure status

Task 16 Corrective 3 closes all five task-16-owned rows. Task 15 is now
**DONE-PENDING-17** with **34 disposition rows**: 31 requalified or target-routed, two retained
definition/backend rows, and one task-17-owned raw predicate row. No routing mismatch or
`NEEDS_CONTEXT` question was found.

The sole temporary C5 allowance is row 34, `FoliaTickThreadGuard`; Task 17 owns both its removal
and the reserved `FoliaThreadContext.java` implementation slot. The `Fawe.java` definition and
`BukkitThreadContext` implementation remain explicit special allowances, not authorization call
sites awaiting requalification.

## Inventory and widened perimeter

The original production census remains **31 qualified `Fawe.isMainThread()` invocations**:
23 in core main, four in Bukkit main including the implementing backend, four in adapter-26.1,
and zero in the Folia module. Adding the unqualified declaration in `Fawe.java` produced the
original 32 review rows. `TaskManager` has eight calls, not the stale estimate of nine.

r12 widens C5 beyond that spelling. A second census searched the same production roots for:

```text
Fawe.isMainThread / Bukkit.isPrimaryThread / *.isSameThread
TickThread and MCUtil tick predicates / Bukkit ownership predicates
direct comparisons between Thread.currentThread() and a main, server, or tick thread
```

It found two additional authorization sites: `BukkitBlockCommandSender.java:200` and the
concurrently added `FoliaTickThreadGuard.java:27` method reference. The definition comparison in
`Fawe.java` and the Bukkit backend are already explicit special rows; capability-minting and
completion-thread comparisons are object-affinity checks, not server-thread authorization. No
other raw platform predicate was found in the enabled production roots. The resulting widened
inventory is **34 rows**, and every row has a final or explicitly task-17-pending disposition.

The closure census finds zero legacy authorization calls and zero `Bukkit.isPrimaryThread()`
calls outside the task-17-owned guard. The only live perimeter spellings are the
`Fawe.isMainThread()` definition/implementation surface (rows 1 and 28) and
`Bukkit::isPrimaryThread` in row 34. Direct `Thread.currentThread()` comparisons in tickets,
completion serialization, and queue-local affinity checks are capability/object-affinity checks,
not server-thread authorization.

`adapter-26.2` and every `adapter-1_21*` tree remain excluded by signed spec amendment A2.1/§2b.
Their 28 qualified legacy calls were not edited and need the same pass only after a signed
amendment certifies another Folia adapter.

## Per-site decisions

Line numbers identify the closure tree. `PENDING — TASK 17` means the raw platform predicate is
still present only because its approved backend implementation has not landed.

| # | File:line | Old expression | Current disposition | Semantic justification / required route |
|---:|---|---|---|---|
| 1 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/Fawe.java:215` | declaration `public static boolean isMainThread()` | **KEEP definition; deprecated** | The Bukkit backend still needs the legacy identity. The declaration and its implementation are definition sites, not authorization call sites. |
| 2 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/TaskManager.java:166` | `queue.startUnsafe(Fawe.isMainThread())` | `isGlobalContext()` | Process-global unsafe toggles match only the global context; Folia degrades before the callback. |
| 3 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/TaskManager.java:173` | `queue.endUnsafe(Fawe.isMainThread())` | `isGlobalContext()` | The end of the unsafe scope uses the same role as its start. |
| 4 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/TaskManager.java:199` | `if (Fawe.isMainThread())` | `isTickThread()` | The location-free task is explicitly current-context-safe under §3.5. |
| 5 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/TaskManager.java:214` | `taskNow(runnable, Fawe.isMainThread())` | `isTickThread()` | This is an offload decision: work must leave any tick context. |
| 6 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/TaskManager.java:317` | `if (Fawe.isMainThread())` | `isTickThread()` | r12 preserves inline execution for location-free, current-context-safe work. Target-bearing callers may not use this route. |
| 7 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/TaskManager.java:331` | `if (Fawe.isMainThread())` | `isTickThread()` | Prevents a tick thread from enqueueing and waiting; foreign-target payloads are forbidden. |
| 8 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/TaskManager.java:349` | `if (Fawe.isMainThread())` | `isTickThread()` | Same location-free contract and restriction as row 7. |
| 9 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/TaskManager.java:375` | `if (Fawe.isMainThread())` | `isTickThread()` | r12 rules out narrowing this to global context. Target-bearing callers now use `QueueHandlerRouting` instead of this location-free surface. |
| 10 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/queue/implementation/QueueHandler.java:130` | `if (!Fawe.isMainThread())` | `isGlobalContext()` | A legacy location-free drain is a global responsibility, never an arbitrary region-tick responsibility. The Paper exception text remains unchanged. |
| 11 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/queue/implementation/QueueHandler.java:471` | `if (Fawe.isMainThread())` | `isTickThread()` | Preserved location-free descriptor; only current-context-safe callbacks may use it. |
| 12 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/queue/implementation/QueueHandler.java:483` | `if (Fawe.isMainThread())` | `isTickThread()` | Same as row 11. |
| 13 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/queue/implementation/QueueHandler.java:495` | `if (Fawe.isMainThread())` | `isTickThread()` | Same as row 11. |
| 14 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/queue/implementation/QueueHandler.java:506` | `if (Fawe.isMainThread())` | `isTickThread()` | Same as row 11. |
| 15 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/FaweCache.java:161` | construction-time `Fawe.isMainThread()` | `isTickThread()`; pre-existing hazard preserved | The translation is faithful, but the predicate is still evaluated when the function is constructed rather than when it is used. This task did not silently redesign that cache. |
| 16 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/entity/LazyBaseEntity.java:74` | `if (Fawe.isMainThread())` | `EntityTarget` + `QueueHandlerRouting.syncOn(EntityTarget, EntityTask)` | adapter-26.1 supplies the exact entity target. Non-owning tick callers fail before dispatch; the owner callback asserts its fresh entity ticket and returns detached NBT. |
| 17 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/history/changeset/AbstractChangeSet.java:417` | `addWriteTask(..., Fawe.isMainThread())` | `isTickThread()`; liveness hazard preserved | This selects immediate detached history processing, not live-state ownership. It also preserves and widens the existing possibility of inline history disk I/O on tick threads; no unrelated I/O redesign was made. |
| 18 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/queue/implementation/SingleThreadQueueExtent.java:261` | `if (Fawe.isMainThread())` | world present: `ownsChunk`; world absent: `isTickThread` | A real world target requires exact ownership. A non-world extent has no foreign world target, so the current-context predicate preserves Paper behavior without passing null to `ownsChunk`. The false branch uses the FAWE worker pool. |
| 19 | `worldedit-core/src/main/java/com/fastasyncworldedit/core/extent/SlowExtent.java:31` | `if (!Fawe.isMainThread())` | `isTickThread()` | No server tick context may be deliberately slept. |
| 20 | `worldedit-core/src/main/java/com/sk89q/worldedit/extension/platform/PlatformCommandManager.java:681` | `taskNow(..., Fawe.isMainThread())` | `isTickThread()` | Long preparation leaves any tick context; this branch authorizes no live access. |
| 21 | `worldedit-core/src/main/java/com/sk89q/worldedit/EditSessionBuilder.java:506` | `!Fawe.isMainThread()` | `!isTickThread()` | Selects a prepare worker only away from tick contexts. |
| 22 | `worldedit-core/src/main/java/com/sk89q/worldedit/extension/platform/AbstractPlayerActor.java:538` | `if (!Fawe.isMainThread())` | `!isTickThread()` | Blocking semaphore acquisition is legal only away from tick contexts. |
| 23 | `worldedit-core/src/main/java/com/sk89q/worldedit/extension/platform/AbstractPlayerActor.java:578` | `if (Fawe.isMainThread())` | `isTickThread()` | A tick caller must return rather than wait on the clipboard future. |
| 24 | `worldedit-core/src/main/java/com/sk89q/worldedit/LocalSession.java:418` | `boolean mainThread = Fawe.isMainThread()` | `isTickThread()` | Protects every tick context from blocking on the history write lock. |
| 25 | `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/BukkitWorld.java:399` | `if (Fawe.isMainThread())` | `ownsChunk(this, X, Z)` | Synchronous `getChunkAt` needs the exact chunk owner; the false branch is a real target-aware async chunk request. |
| 26 | `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/regions/plotsquared/FaweDelegateSchematicHandler.java:166` | `if (Fawe.isMainThread())` | `isTickThread()` | Long paste preparation leaves every tick context. |
| 27 | `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/adapter/NMSAdapter.java:203` | `if (Fawe.isMainThread())` | `QueueHandlerRouting.syncOn(ChunkTarget, RegionCall<Boolean>)` | The adapter-26.1 World overload creates the exact chunk target and performs exactly one CAS inside a fresh ticket callback. The compatibility String overload is policy-gated away from Folia. |
| 28 | `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/util/BukkitThreadContext.java:35` | `return Fawe.isMainThread()` | **KEEP backend implementation** | §3.1 requires Bukkit tick/chunk/entity/global predicates to collapse to the old Paper identity. |
| 29 | `worldedit-bukkit/adapters/adapter-26.1/src/main/java/com/sk89q/worldedit/bukkit/adapter/impl/fawe/v26_1/PaperweightPlatformAdapter.java:316` | `if (Fawe.isMainThread())` | `ownsChunk(world, chunkX, chunkZ)` | Synchronous chunk loading needs the exact owner; the false branch returns null. A weak-key cache adapts one WorldEdit wrapper per `ServerLevel`, removing per-probe construction, synchronization, and reference refresh. |
| 30 | `worldedit-bukkit/adapters/adapter-26.1/src/main/java/com/sk89q/worldedit/bukkit/adapter/impl/fawe/v26_1/PaperweightPlatformAdapter.java:333` | `if (Fawe.isMainThread())` | `ownsChunk(world, chunkX, chunkZ)` | Same authorization and safe false branch as row 29. |
| 31 | `worldedit-bukkit/adapters/adapter-26.1/src/main/java/com/sk89q/worldedit/bukkit/adapter/impl/fawe/v26_1/PaperweightFaweWorldNativeAccess.java:108` | `if (Fawe.isMainThread())` | `ownsChunk`; non-owner work partitions to `QueueHandlerRouting.syncOn(ChunkTarget, RegionTask)` | Exact-owner calls may mutate inline. Other changes are partitioned by chunk and applied only inside a fresh ticket callback that asserts the partition target. |
| 32 | `worldedit-bukkit/adapters/adapter-26.1/src/main/java/com/sk89q/worldedit/bukkit/adapter/impl/fawe/v26_1/PaperweightFaweWorldNativeAccess.java:269` | `if (Fawe.isMainThread())` | unconditional per-`ChunkTarget` partition dispatch; `isTickThread()` only selects waiting | `ChunkTargetPartitions.dispatchEach` creates one target and one ticket-asserted callback per partition. No ownership boolean authorizes a multi-owner callback or location-free fallback. |
| 33 | `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/BukkitBlockCommandSender.java:200` | `if (Bukkit.isPrimaryThread())` | `QueueHandlerRouting.syncOn(ChunkTarget, RegionTask)` | The command block's exact world/chunk target is captured before the session key is created; live chunk/type access runs only in a ticket-asserted owner callback. The Bukkit scheduler fallback is gone. |
| 34 | `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaTickThreadGuard.java:27` | `Bukkit::isPrimaryThread` | **PENDING — TASK 17** | r12 permits the raw platform predicate only inside the approved `FoliaThreadContext`. Task 17 must fill that reserved slot and remove this last temporary allowance. |

No completed original site requires `isFaweWorker()`. Row 15 could be redesigned around worker
identity, but the current construction-time cache problem is older than C5 and was preserved
explicitly rather than guessed around.

## Ownership-funnel audit

Every ownership-bearing row was rechecked after task 16 Corrective 3:

- Rows 16, 27, 31, and 32 now enter `QueueHandlerRouting` with exact entity/chunk targets and
  assert fresh tickets before live access. No false branch reaches location-free `sync`.
- Row 18 falls back to the FAWE worker pool, row 25 to a target-aware async chunk request, and
  rows 29/30 return null. Those false branches do not execute the rejected live operation inline.
- Row 33 routes through its exact command-block `ChunkTarget`; no Bukkit scheduler remains.
- Row 34 remains pending because its raw Bukkit predicate is outside the reserved, still-unfilled
  task-17 backend implementation.
- The location-free `TaskManager` and `QueueHandler` predicates stay `isTickThread()` because
  r12 explicitly requires current-tick inline execution for current-context-safe callbacks. The
  foreign-target callers now bypass that surface through `QueueHandlerRouting`.

## Partial resolver and bootstrap-order audit

No production `ContextResolver.register(...)` exists yet. This record therefore makes no runtime
qualification claim: every active `FaweThreadContext.current()` call is gated on Task 17.

No active Task-15 predicate is evaluated by a JVM static initializer. The earliest or
bootstrap-sensitive reachability is:

- `QueueHandler` schedules `run()` from its constructor; the predicate is the first statement of
  that per-tick callback. Registration must precede QueueHandler construction and scheduling.
- `FoliaTickThreadGuard.production()` eagerly resolves `FaweThreadContext.current()` at line 26.
  Registration must precede construction of `FoliaTaskManager` or `FoliaQueueHandler`, both of
  which call that factory before their scheduler service is usable.
- `FaweCache.createMainThreadSafeCache` evaluates the predicate in an anonymous-object field
  initializer when `MaskingExtent` is constructed. Registration must precede any edit/extent or
  supplier construction capable of reaching it.
- TaskManager entry points can be exposed while `Fawe`/`FaweBukkit` is initializing. Registration
  must precede construction of those platform objects and any scheduled bootstrap task.
- Adapter rows 29/30 can run as soon as adapter-26.1 is published. Commands, sessions, actors,
  edits, history, and Bukkit-world access cover the remaining active rows and must remain
  unreachable until registration completes.

The resolver/dispatcher tests register explicit test contexts, but the concurrent Task-11 suite
does not yet satisfy that rule. `CompletionProtocolTestSupport.java:21` constructs the default
`OperationCompletionService`; at least
`OperationCompletionServiceTest.java:188-212` flushes that service to `TERMINATED` and submits
again. `OperationCompletionService.java:235` then evaluates the default tick-thread supplier,
whose current implementation reaches `FaweThreadContext.current()` at line 756 and catches a
missing registration as `false`. This is a discovered Task-11/17 test blocker, not a permissive
default endorsed by C5. The concurrent Task-11 corrective owns those files; Task 17 must ensure
every test reaching a predicate installs an explicit context.

Task 17 also owns production registration and an ordering test covering QueueHandler,
`FoliaTickThreadGuard.production()`, cache/supplier construction, TaskManager, adapter publication,
and command/edit entry points. The context must remain registered through drain and disable
completion.

## Paper/Spigot final dispositions

- `PaperweightFaweAdapter.createWorldNativeAccess` still terminates at the original two-argument
  WNA constructor; task 16 did not reintroduce the recursive adaptation edge.
- The task-16 NMS World overload carries an already-adapted target World and does not use
  `FaweBukkitWorld.of(...)` or introduce its reference-refresh exception surface.
- adapter-26.1 supplies the new targeted `LazyBaseEntity` constructor. Excluded Paper adapters
  retain the compatibility constructor, which is guarded by backend policy.
- Rows 29/30 use a weak-key `ServerLevel` cache whose loader calls `BukkitAdapter.adapt(...)` once.
  The hot probe performs no `FaweBukkitWorld.of(...)` synchronized lookup or reference refresh,
  and the loader cannot introduce that method's `WorldUnloadedException` surface.
- The old `QueueHandler.run()` exception text is preserved. The removed WNA multi-stream guard
  also removes its Paper allocation churn.

## Build-time guard

The guard now lives entirely in the already-versioned
`build-logic/src/main/kotlin/buildlogic.common.gradle.kts`. Each enabled project registers its own
`checkC5ThreadContextReferences` task and wires its own `compileJava`; there is no root
cross-project configuration and no edit under `worldedit-bukkit/folia/`.

The task scans production Java only, declares path-sensitive inputs plus a cacheable output stamp,
and runs in-process without forking a source-launcher JVM. Code and wiring are in the same tracked
file, so a split commit cannot strand the build with a missing checker source.

It rejects qualified calls and method references, explicit and wildcard static imports for legacy
and raw platform predicates, unqualified calls inside `Fawe.java`, `isPrimaryThread`,
`isSameThread`, raw TickThread/MCUtil predicates, raw Bukkit owner predicates, and direct
main/server/tick-thread identity comparisons.
The approved Bukkit backend and Task-17 Folia backend are exempt from every raw-platform pattern,
but not from the two legacy `Fawe` patterns. The allowance map now contains only the legacy
definition comparison, the Bukkit backend implementation, and row 34. The five task-16 entries
are gone; an added reference in any routed file fails. The guard also rejects untargeted
two-argument `LazyBaseEntity` construction in Folia-enabled source sets through JDK AST parsing.

## Minor-finding dispositions

1. `FaweCache` construction-time evaluation: **preserved and recorded**, not silently fixed.
2. `AbstractChangeSet` inline history I/O: **preserved and recorded**, including the widened
   tick-thread liveness exposure.
3. `SingleThreadQueueExtent` null world: **fixed** with a conditional current-context fallback
   only when there is no world target.
4. World-wrapper identity: **handed to Task 17**; its Folia context must normalize
   `BukkitWorld`, `FaweBukkitWorld`, and wrapped worlds to the real Bukkit handle/name.
5. WNA derived coordinates/empty `allMatch`: **eliminated** by exact per-target partitioning.
6. Legacy definition signal: **fixed** with `@Deprecated` and a `FaweThreadContext` Javadoc link.
7. `QueueHandler.operate` monitor wait/global-drain liveness: **preserved** as adjacent behavior;
   task 16 moved ownership-sensitive work to the target-bearing facade.
8. Guard wiring/caching: **fixed** by the per-project in-process build-logic task and output stamp.

Paper-review minors were also discharged: the NMS World overload carries the target without a
world-name lookup, the exception message was restored, and the `LocalSession` rename remains
behavior-neutral.

## Verification boundary

- Rows 16, 27, 31, 32, and 33 were traced from their current call sites through
  `QueueHandlerRouting` to exact target/ticket assertions. No route mismatch was found.
- The widened closure census found no unrecorded authorization predicate. All 34 historical rows
  have a disposition; only row 34 remains temporary and task-17-owned.
- The allowance map contains none of the five task-16 entries. The command-sender Bukkit
  scheduler and all five legacy predicates are absent from the routed sites.
- The orchestrator reports the graph compile green, including adapter-26.1. This closure pass did
  not run Gradle or edit production source.

Task 17 still owes the `FoliaThreadContext` implementation, replacement of row 34's raw predicate,
production registration/bootstrap ordering, and the associated runtime gates. Its landing removes
the last temporary C5 allowance and changes Task 15 from `DONE-PENDING-17` to `DONE`.
