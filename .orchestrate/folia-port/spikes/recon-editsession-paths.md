# Recon — EditSession/commands/history execution paths (orchestrator inline, 2026-07-17)

Replaces the cancelled third Explore agent. Conclusions from targeted reads; narrower than
the sibling reports — workers verify details in-task (escape hatch applies).

## Command dispatch — already async-first (good news for Folia)

- `PlatformCommandManager.handleCommand` (`worldedit-core/.../extension/platform/PlatformCommandManager.java:663-688`):
  non-queued commands run via `TaskManager.taskNow(..., Fawe.isMainThread())`; queued (edit)
  commands go through `actor.runAction(..., async=true)`.
- `AbstractPlayerActor.runAction` (`worldedit-core/.../extension/platform/AbstractPlayerActor.java:708-728`):
  `async=true` → `asyncNotifyQueue.run(wrapped)` — a per-actor async queue OFF the server
  thread. So FAWE edit commands already execute on FAWE-owned threads, not tick threads.
  Consequence for Folia: the tick-thread-blocking risk from `ParallelQueueExtent.join()` is
  mostly an API-caller concern (§4c), not the command path; but `Request` thread-locals and
  `Fawe.isMainThread()` branches in dispatch (L677) still need requalification.

## EditSession lifecycle

- `EditSession.flushQueue()` (`worldedit-core/.../EditSession.java:1335`) called from
  `close()` (L1275-1276) and several operation endpoints (L1174/1183/1206/1327/4112) —
  drains the queue on the calling thread (FAWE worker or API caller thread).

## History threading

- `AbstractChangeSet.addWriteTask(Runnable)` (`worldedit-core/.../history/changeset/AbstractChangeSet.java:414-418`)
  uses `Fawe.isMainThread()` as the `completeNow` flag: main thread → runs the history write
  INLINE on the server thread; otherwise queued to executor. On Folia every region thread
  reads as "not main" → the queued branch (safe direction), but the semantics (why inline on
  main?) must be requalified per call site; `processSet` routed through it (L230/235).
- Per-player IO ordering via `Fawe.uuidKeyQueuedExecutorService` (see recon-queue-threading.md §4).

## Carry-over from sibling recon (already mapped there)

- //regen: `regen/PaperweightRegen.java` — new ServerLevel + Bukkit worlds-map reflection +
  `pollTask()` loop; deeply single-owner bound → wave-0 spike decides GO/DEGRADE/DISABLE.
- Relight + packet resend + finalizers: recon-adapters-nms.md §3-4c.
