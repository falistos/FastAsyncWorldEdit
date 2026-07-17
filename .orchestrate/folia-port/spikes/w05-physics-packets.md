# Spike W05 — Physics suppression & chunk packet resend without global toggles

- **Wave:** 0 · **Task:** 05 · **Type:** read-only design evidence
- **Scope:** (1) physics/update suppression today via static `ChunkListener.physicsFreeze` +
  `AsyncCatcher.enabled`; (2) chunk packet resend via `MinecraftServer.execute` + chunkMap reads.
  Lighting (03) and regen (04) out of scope; referenced only where they touch the send path.
- **Binding:** spec §1b (AsyncCatcher/TickThread never disabled/bypassed — hard bar), §4d;
  architecture §1, §3 C1/C6. Line refs are adapter-26.1 unless noted.

## Outcome

**GO** for both mechanisms on the commit model, with no global-state port required.

- Physics suppression: the global `physicsFreeze`/`AsyncCatcher` toggle is **already orphaned**
  in the current pipeline (no in-tree caller) and is **unnecessary by construction** on the
  prepare/commit model. Nothing to scope; recommend not porting it to the Folia backend.
- Packet resend: the off-thread section-write vs. packet-build race that the `ChunkSendLock`
  protocol guards **cannot occur** once both the section swap and the packet build/send run on
  the chunk's owning region thread. The send lock is subsumed by region-thread serialization.
  One genuine open runtime question remains (cross-region `connection.send`), listed for W0.2.

No contract conflict found. Not BLOCKED.

## Assumptions (unverifiable Folia internals — confirm in W0.2)

- **A1** — On Folia, a chunk's `ChunkMap` / `ServerChunkCache` and the viewer set returned by
  `chunkMap.getPlayers(pos)` are owned by the region that owns that chunk and are safely
  readable from that region's thread.
- **A2** — `connection.send(Packet)` (`ServerCommonPacketListenerImpl.send`) may be called from
  a thread other than the target player's owning-region thread (Netty-backed thread-safe
  enqueue). If false, per-viewer sends must hop to each player's entity/region scheduler.
- **A3** — Constructing `ClientboundLevelChunkWithLightPacket` from a `LevelChunk` +
  `LevelLightEngine` performs no TickThread assertion beyond reading chunk/light state owned by
  the chunk region.
- **A4** — Folia dispatches Bukkit block events (`BlockPhysicsEvent`, `EntityChangeBlockEvent`,
  `ItemSpawnEvent`) on the owning region thread of the event's block, so a plain `Listener`
  receives them concurrently across regions.
- **A5** — `RegionScheduler.execute(plugin, world, cx, cz, task)` schedules a one-shot task on
  the region owning `(world, cx, cz)` (the Folia API `FoliaRegionDispatcher` wraps, per C1).

---

## Part 1 — Physics / update suppression

### 1a. What FAWE suppresses today (inventory)

| # | Mechanism | file:line | What it suppresses / does |
|---|---|---|---|
| P1 | `ChunkListener.physicsFreeze` (static bool) cancels `BlockPhysicsEvent` | `ChunkListener.java:249-251` | Hard-cancels ALL physics events while set |
| P2 | same flag cancels `EntityChangeBlockEvent` (falling blocks) | `ChunkListener.java:342-344` | Hard-cancels falling-block conversions |
| P3 | same flag cancels `ItemSpawnEvent` | `ChunkListener.java:376-378` | Hard-cancels item drops |
| P4 | flag set true | `BukkitQueueHandler.startUnsafe:33` | Turns the freeze on for an "unsafe" task |
| P5 | flag set false | `BukkitQueueHandler.endUnsafe:53`, `ChunkListener.java:72` | Turns it off (and the 1s tick-limiter reset) |
| P6 | `AsyncCatcher.enabled = false` (reflection) | `BukkitQueueHandler.startUnsafe:36` | Disables Spigot's async-access guard for parallel tasks |
| P7 | `Timings.setTimingsEnabled(false)` (reflection) | `BukkitQueueHandler.startUnsafe:37-44` | Cosmetic/perf only |
| P8 | Tick-limiter (stack-depth lag detection): `physCancel`, `badChunks`, per-chunk `counter[]`, falling/item caps, `getTimer().getTPS() < 18` gate | `ChunkListener.java:247-398` | **Independent of `physicsFreeze`** — always on when `TICK_LIMITER.ENABLED`; detects and cancels physics/falling/item lag caused by edits |

The per-block side-effect path in the adapter is **not** a suppression — it deliberately drives
physics only when requested:

- `PaperweightFaweWorldNativeAccess.setBlockState:104` — `levelChunk.setBlockState(pos, state,
  shouldApply(UPDATE) ? 0 : 512)`: physics behaviour is already governed by the **vanilla
  setBlockState flag**, not by any global freeze.
- `notifyNeighbors:182-195` / `updateNeighbors:197-222` — fire `BlockPhysicsEvent` and neighbour
  notifications **only** when `SideEffect.EVENTS` is set. Intended behaviour, kept as-is.

### 1b. Critical finding — the global freeze path is already dead code

`startUnsafe`/`endUnsafe` (which set `physicsFreeze=true` **and** disable `AsyncCatcher`) are
reachable only through `TaskManager.runUnsafe(Runnable)` (`TaskManager.java:162-171`) and the
deprecated `QueueHandler.startSet/endSet` (`QueueHandler.java:445-459`). **Verified by
whole-tree grep: none of `runUnsafe` / `startSet` / `endSet` has any in-tree caller.**
`itemFreeze` is never assigned `true` anywhere. So in the current section-swap pipeline
`physicsFreeze` is never turned on and `AsyncCatcher` is never disabled through this path.

Why the modern pipeline needs neither:

- The bulk write path swaps **whole `LevelChunkSection` objects** via a raw reflective CAS on the
  live section array (`setSectionAtomic` → `NMSAdapter.java:122-166`; called at
  `PaperweightGetBlocks.java:418,493,558`). It never calls `Level.setBlock`, so vanilla schedules
  **no** neighbour/physics/light updates for those blocks — there is nothing to suppress. (This is
  also why FAWE must separately relight and resend packets.)
- Because it bypasses server methods entirely, the bulk path trips no `AsyncCatcher.catchOp`
  callsite, so disabling `AsyncCatcher` is not required either. The entity/tile/beacon work that
  *does* call server methods is already deferred to `queueHandler.sync(...)` (main thread today),
  where the guard passes legitimately.

`runUnsafe` remains public API, so a third-party consumer could still invoke it — the Folia
backend must make it safe (see disposition), but no internal edit relies on it.

### 1c. Per-item disposition on the commit model

| Item | Disposition | Rationale / mechanism |
|---|---|---|
| P1–P5 `physicsFreeze` freeze of physics/falling/item events | **Unnecessary by construction** — do not port the global flag | Bulk commits fire no events (section CAS); deliberate side-effects (EVENTS) are opt-in and must keep firing. A global mutable flag is forbidden (§1b) and buys nothing. |
| Per-block physics on the native-access path | **Vanilla flag** (already) | `setBlockState(pos,state,flags)` at `NativeAccess:104` already governs physics via the vanilla flag; nothing to add. Just runs on the chunk's region thread under C1. |
| P6 `AsyncCatcher.enabled = false` | **Remove on Folia backend** (forbidden by §1b; unnecessary) | Folia's `TickThread.ensureTickThread` is not one global flag and must never be bypassed. All live-state touches move onto the owning region via `FoliaRegionDispatcher`, where the assertion passes legitimately. `FoliaQueueHandler.startUnsafe/endUnsafe` become no-ops (keep `physicsFreeze` untouched per architecture §2). |
| P7 Timings toggle | **Drop** | Cosmetic; Timings is gone in the certified 26.1 stack. |
| P8 Tick-limiter (stack-depth lag detection) | **Disable on Folia** (recommended) or region-scope | It is `@Deprecated(since=2.0.0)` ("no guarantee it will work"). It uses static mutable maps (`badChunks`, `counter`) and global `getTimer().getTPS()` — under A4 these are mutated concurrently across region threads (data race) and the TPS gate is a global signal (C6). Cleanest: leave `ChunkListener` a Paper-only listener, unregistered on the Folia backend, with an operator note. If ever kept, counters must be per-`(world,cx,cz)` region-local state, not statics. Not C1-critical. |

**If scoped physics suppression is ever genuinely wanted on Folia** (e.g. to stop third-party
listeners reacting to an in-flight FAWE edit — not a current requirement): it must be a
per-chunk key set (`Set<(world,cx,cz)>`), populated/cleared on the **owning region thread**
during commit and checked by a region-thread listener. Never a static boolean. Evidence says
this is not needed today; flagged only so it is not reintroduced as a global.

---

## Part 2 — Chunk packet resend

### 2a. Current path

`PaperweightGetBlocks.send()` (`:800-804`) → `synchronized(sendLock)` →
`PaperweightPlatformAdapter.sendChunk(pair, nmsWorld, cx, cz)` (`:345-391`):

1. `getPlayerChunk(...)` — reflective `ChunkMap.getVisibleChunkIfPresent` (reads ChunkMap).
2. `getChunkAtIfLoadedImmediately` (Paper) — reads `ServerChunkCache`.
3. `NMSAdapter.beginChunkPacketSend(world, pair, lockHolder)` — take a **read** lock on the
   per-`(world,chunk)` `ChunkSendLock`; bail if a write is waiting/ongoing or a send is already
   in flight (`NMSAdapter.java:182-196`).
4. `MinecraftServer.getServer().execute(() -> { build ClientboundLevelChunkWithLightPacket;
   viewers = chunkMap.getPlayers(pos); viewers.forEach(p -> p.connection.send(packet)); finally
   endChunkPacketSend(...) })` (`:365-390`).

Callers: the write finalizer callback (`PaperweightGetBlocks.java:754-764`, gated by the
`LIGHTING.DELAY_PACKET_SENDING` optimisation), the per-block flush
(`NativeAccess.java:260,276`), and the relighter (`PaperweightStarlightRelighter.java:75`).

### 2b. What the send-lock protocol protects

`setSectionAtomic` takes the **write** lock before CAS-swapping a live section
(`NMSAdapter.java:136-165`); `beginChunkPacketSend` takes a **read** lock and refuses while a
write is waiting/held (`:189`). The guarded hazard: a FAWE **worker thread** swaps sections on
the live array while, concurrently, the **main thread** reads that same live chunk to build the
`ClientboundLevelChunkWithLightPacket` — producing a torn/inconsistent packet. The lock exists
purely because the writer is off-thread and the packet reader is on another thread. It also caps
concurrent sends of the same chunk to one.

### 2c. Ownership on Folia and disposition

Under A1, `(world,cx,cz)`'s `ChunkMap`, `ServerChunkCache`, `LevelChunk`, `LevelLightEngine` and
viewer set are all owned by that chunk's region. The commit model already schedules the section
swap on that region thread (C1). If the packet build **and** viewer enumeration run on the **same
region thread, after the commit**, then steps 1–4's reads are all legal and the swap-vs-build
race is impossible — a single thread serialises them.

**Disposition:**

- **Send lock (`ChunkSendLock`, `beginChunkPacketSend`/`endChunkPacketSend`, `SENDING_CHUNKS`
  map, `getWorldSendingChunksMap`) — unnecessary on the Folia backend, subsumed by region-thread
  serialization.** Do not port it to the Folia path. (`NMSAdapter.setSectionAtomic`'s
  `Fawe.isMainThread()` fast-path at `:133` that already skips the lock is the same reasoning,
  generalised: on Folia "am I the owner?" replaces "am I main?".)
- **`MinecraftServer.getServer().execute(...)` → region dispatch.** Replace with
  `FoliaRegionDispatcher` on `(world,cx,cz)` (A5): `regionScheduler.execute(bukkitWorld, cx, cz,
  task)`. There is no global main thread on Folia; this is the C1-mandated hop.
- **Build the packet inside that region task** (needs the committed `LevelChunk` + light) and
  enumerate `chunkMap.getPlayers(pos)` there (A1). The packet is immutable once built and can be
  shared across viewers.

### 2d. Proposed Folia send design

```
FoliaSendChunk(world, cx, cz):                         # on chunk's region thread (A5)
  chunk = getChunkAtIfLoadedImmediately(cx, cz)        # region-owned read (A1)
  if chunk == null: return                             # dropped commit → no resend
  packet = new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, ...)   # A3
  viewers = chunkMap.getPlayers(pos)                   # region-owned read (A1)
  for p in viewers:
      p.connection.send(packet)                        # OPEN: cross-region? (A2)
```

The **one** residual hazard is the last line: viewers can be players owned by **other** regions.
Two shapes, decided by the A2 experiment:

- **A2 holds** → send directly from the chunk region thread (simplest; matches the immutable-
  packet assumption).
- **A2 fails** → per-viewer hop: `player.getScheduler().run(plugin, t -> p.connection.send(packet),
  null)` (entity scheduler), sending on each player's own region thread. Packet immutability makes
  the fan-out safe either way.

This also cleanly replaces the `LIGHTING.DELAY_PACKET_SENDING` batching (`:758`): batched
post-relight sends become one region-scheduled `FoliaSendChunk` per chunk after the relight
finalizer (coordinated with spike 03).

---

## Part 3 — Vanilla/Paper methods asserting TickThread during commit (pre-verify list)

Per C1, every live touch already routes to the chunk's region under `FoliaRegionDispatcher`;
the ones below perform TickThread/ownership assertions that make wrong routing fail loudly —
pre-verify each lands on the correct region, and note the cross-region cases.

| Method (during commit) | file:line | Owner | Note |
|---|---|---|---|
| `chunkMap.getPlayers(pos)` / `getVisibleChunkIfPresent` | `PlatformAdapter:394,335-342` | chunk region | send path (Part 2) |
| `getChunkAtIfLoadedImmediately` / chunk source reads | `PlatformAdapter:353` | chunk region | |
| `connection.send(packet)` | `PlatformAdapter:386` | player region | **cross-region** — A2 |
| `ServerChunkCache.blockChanged` (markBlockChanged) | `NativeAccess:171` | chunk region | asserts; must be chunk region |
| `Level.sendBlockUpdated` (notifyBlockUpdate) | `NativeAccess:159` | chunk region | |
| `updateNeighborsAt` / `handleNeighborChanged` / `updateIndirectNeighbourShapes` | `NativeAccess:183-207` | block region + neighbours | **cross-region at chunk/region boundary** — a neighbour may be owned by another region; Folia will assert. Needs per-neighbour region dispatch or documented restriction. Real hazard. |
| `BlockPhysicsEvent` callEvent (EVENTS side-effect) | `NativeAccess:211-215` | block region | |
| `getLightEngine().checkBlock` (updateLightingForBlock) | `NativeAccess:135` | chunk region | defer to spike 03 |
| `getBlockEntity` / `removeBlockEntity` / `loadWithComponents` (tiles syncTask) | `GetBlocks:726-737` | chunk region | under `synchronized(nmsWorld)` today |
| `addFreshEntity` / `moonrise$getEntityLookup().addNewEntity` (entities syncTask) | `GetBlocks:688-704` | chunk/entity region | entity add asserts region |
| `removeEntity` / `nmsWorld.getEntities().get(uuid)` (entity-remove syncTask) | `GetBlocks:623-632` | entity region | |
| `BeaconBlockEntity.playSound` + `BeaconDeactivatedEvent.callEvent` (beacon syncTask) | `GetBlocks:604-605` | chunk region | |
| `nmsChunk.setLightCorrect` / `mustNotSave` (syncTask) | `GetBlocks:751-752` | chunk region | plain field writes on live chunk |
| `removeBeacon` (blockEntities map + removeGameEventListener + removeBlockEntityTicker) | `GetBlocks:369` → `PlatformAdapter:593-606` | chunk region | **Runs on the FAWE worker thread inside `internalCall`, NOT a syncTask** — a live-chunk mutation off the owner today. Must move onto the chunk region on Folia. Flagged for the adapter prepare/commit split (C2). |

The two cross-region cases (**`connection.send`** and **neighbour notification across a region
boundary**) are the only places where "the chunk's region thread" is not automatically the right
owner; both are runtime questions below.

---

## Runtime questions for W0.2

1. **[A2] Cross-region packet send.** Can `p.connection.send(ClientboundLevelChunkWithLightPacket)`
   be called from the chunk's region thread when `p` is owned by a different region, without
   tripping a Folia/Paper TickThread assertion? Experiment: commit an edit in region R1 with a
   viewer whose player sits in region R2; send from R1's thread; assert no exception and client
   receives the update. If it fails → adopt the per-viewer entity-scheduler fan-out (2d).
2. **[Neighbour boundary] Cross-region neighbour notification.** With `SideEffect.EVENTS`/physics
   enabled, edit a block on a chunk/region boundary so `updateNeighborsAt` /
   `handleNeighborChanged` reaches a block owned by an adjacent region. Does Folia assert?
   Determines whether boundary neighbour updates need per-neighbour region dispatch or a
   documented degradation (spec §5). (Interacts with the EVENTS side-effect contract.)
3. **[A1/A3] Region-owned reads + packet build.** Verify `getChunkAtIfLoadedImmediately`,
   `chunkMap.getPlayers`, and `new ClientboundLevelChunkWithLightPacket(chunk, lightEngine,...)`
   all succeed on the chunk's region thread with no global-main assertion.
4. **[A4] Event dispatch thread for `ChunkListener`.** Confirm Folia fires `BlockPhysicsEvent` /
   `EntityChangeBlockEvent` / `ItemSpawnEvent` on region threads, validating the decision to
   disable the tick-limiter listener (or forcing region-local counters if it is kept).
5. **`runUnsafe` public-API safety.** Confirm no certified scenario exercises
   `TaskManager.runUnsafe` on Folia; if a third-party path does, the `FoliaQueueHandler` no-op
   `startUnsafe/endUnsafe` (no `physicsFreeze`, no `AsyncCatcher`) is the required behaviour — no
   assertion, no global mutation.
