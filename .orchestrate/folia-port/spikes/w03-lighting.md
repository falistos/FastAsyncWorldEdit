# Spike W0.3 — Lighting under regionization (Folia 26.1.x)

Status: **DONE_WITH_CONCERNS** — disposition settled by static analysis; one GO option
(salvage starlight on region threads) is gated behind a W0.2 runtime experiment that no
static reading can decide (moonrise internals are not on the classpath surface).

Scope reminder: lighting only. //regen (task 04) and general packet resend (task 05) are
out of scope; lighting's own packet need (`sendChunk`) is noted only where it gates a
disposition, and handed to task 05.

---

## 0. TL;DR dispositions

| Lighting mode / path | Disposition | One-line reason |
|---|---|---|
| **fillLightNibble direct injection** (commit-path light write) | **GO** | Per-chunk, single-section, runs inside `internalCall` → already owning-region under the W0.2 commit contract. No cross-region reach. |
| **skip-light** (`LIGHTING.MODE=0` / `SideEffect.LIGHTING` off) | **GO** | No relighter created; no off-thread live-light work beyond the GO fillLightNibble path. |
| **GET-side light reads** (`getSkyLight`/`getEmittedLight`) | **GO (with fix)** | Legal only as part of the region-thread GET snapshot; they hide a *write* (`queueSectionData`) during a read — must be region-owned, cannot run on a worker. |
| **Full relight — NMSRelighter** (FAWE's own Java engine) | **GO / DEGRADE target** | Fully detached (worldedit-core, zero NMS); light computed over GET snapshots, written into detached SET, committed via fillLightNibble. §1b-clean. Slower/lower-quality than starlight → §8 budget + §5 documentation. |
| **Full relight — PaperweightStarlightRelighter** (default on Folia today) | **STOP as-structured → DEGRADE recommended; conditional GO pending W0.2** | Built on global main-thread scheduling, multi-region 1024-chunk batches, and ticket/packet ops on arbitrary completion threads. Directly violates §1b. Salvage requires per-region batch splitting whose cross-border correctness only a runtime probe can confirm. |

Recommended contract: on the Folia backend, **route relighting to NMSRelighter
(DEGRADE, documented)** and keep the fillLightNibble commit path (GO). Treat a
region-scheduled starlight as a *future optimization* gated on the W0.2 experiment in §6,
never the wave-0 default.

---

## 1. How lighting is wired (evidence)

Two distinct mechanisms, often conflated:

**(a) Commit-path light injection** — writes light nibbles the SET already carries into the
live light engine, as part of the chunk commit:
- `PaperweightGetBlocks.setLightingToGet` / `setSkyLightingToGet` — adapter-26.1
  `PaperweightGetBlocks.java:130`, `:142`; invoked inside `internalCall` at `:581-590`.
- `fillLightNibble(...)` — `PaperweightGetBlocks.java:935-966`: per section, reads/creates
  `DataLayer` via `serverLevel.getChunkSource().getLightEngine().getLayerListener(...)
  .getDataLayerData(sectionPos)`, mutates the byte array under `synchronized(dataLayer)`,
  and `((LevelLightEngine) …).queueSectionData(layer, sectionPos, dataLayer)` when the layer
  was absent (`:947`).
- `removeSectionLighting(int, boolean)` — `PaperweightGetBlocks.java:171-197`: same engine,
  zeroes a section's block+sky `DataLayer`.
- All three key on `SectionPos.of(getChunk().getPos(), …)` — the **single chunk being
  committed**. No neighbor reach.

**(b) Post-edit relight pass** — recomputes lighting for a batch of edited chunks after the
edit finishes:
- Selected in `EditSessionBuilder.java:586-593`: if `SideEffect.LIGHTING` or a non-NONE
  `RelightMode`, a `Relighter` is built from `getRelighterFactory().createRelighter(...)` and
  wrapped in a `RelightProcessor`.
- `RelightProcessor.processSet` — `RelightProcessor.java:26-43`: `MODE==2` adds every chunk;
  `MODE==1` adds a chunk only if it has a non-air section, with per-section skip reasons.
- Flushed at edit end: `EditSession.java:1354-1366` → `relighter.removeAndRelight(true)`
  (if `LIGHTING.REMOVE_FIRST`) or `fixLightingSafe(true)`.
- Factory choice: `PaperweightFaweAdapter.getRelighterFactory()` (adapter-26.1 `:865-871`)
  returns **`PaperweightStarlightRelighterFactory` when `PaperLib.isPaper()`**, else
  `NMSRelighterFactory`. **Folia is a Paper fork → `PaperLib.isPaper()` is true → the default
  Folia relighter is starlight today.** (ASSUMPTION on isPaper()==true on Folia — near-certain
  but confirm at bring-up; the port's Folia branch can override this factory anyway.)

`RelightMode` (core `RelightMode.java`): `NONE(0)` / `OPTIMAL(1)` / `ALL(2)`.

---

## 2. Live-state light mutation points (the C1/§4c danger set)

Recon `recon-adapters-nms.md` §4c lists these; verified and expanded:

| Point | File:line (adapter-26.1) | Live state | Reach | Runs on (today) |
|---|---|---|---|---|
| `fillLightNibble` | `PaperweightGetBlocks.java:935` | LightEngine DataLayer of the committed section | 1 section, 1 chunk | inside `internalCall`, chained through `handleCallFinalizer` → `queueHandler.sync` |
| `removeSectionLighting` | `PaperweightGetBlocks.java:171` | DataLayer (block+sky) of committed section | 1 chunk | inside `internalCall` |
| `getSkyLight` / `getEmittedLight` | `PaperweightGetBlocks.java:221`, `:246` | DataLayer read **+ `queueSectionData` write** (`:234`, `:263`) | 1 chunk (+ implicit engine touch) | GET-side |
| `starlight$serverRelightChunks` | `PaperweightStarlightRelighter.java:59` (`invokeRelight` `:53`) | moonrise StarLightInterface, **batch of ≤1024 chunks + neighbor propagation** | **multi-chunk, multi-region** | CompletableFuture continuation after chunk loads |
| `addTicketAtLevel` / `removeTicketAtLevel` | `PaperweightStarlightRelighter.java:46`, `:77` | ticket system | per chunk | `getChunkAtAsync(...).thenAccept(...)` continuation |
| `sendChunk` (delayed light packet) | `PaperweightStarlightRelighter.java:75` → `PaperweightPlatformAdapter.java:345` | ChunkMap/player connections, `MinecraftServer.getServer().execute` (`:365`) | per chunk | global main executor |

The commit-path writes (rows 1-3) reach exactly the chunk under commit. The starlight rows
(4-6) are the ones that break regionization.

---

## 3. Per-mode disposition with evidence

### 3.1 fillLightNibble direct injection — **GO**

`setLightingToGet`/`setSkyLightingToGet` are called from within `internalCall`
(`:581-590`), whose sync tasks are chained onto the owning execution via
`AbstractBukkitGetBlocks.handleCallFinalizer` (`AbstractBukkitGetBlocks.java:144-183`,
`queueHandler.sync(chain)` `:174`). Under the W0.2 chunk-commit contract, `internalCall` and
its finalizer chain run on the region owning `(world, chunkX, chunkZ)`. Every light write
here targets `SectionPos.of(getChunk().getPos(), …)` — the committed chunk's own engine
sections. No neighbor chunk, no other region.

Evidence of no cross-region reach: `fillLightNibble` (`:935`), `removeSectionLighting`
(`:171`) both derive `SectionPos` solely from `getChunk().getPos()`. The `synchronized
(dataLayer)` guards are redundant once the write is region-serialized, but harmless.

GO condition: this path inherits W0.2's guarantee that `internalCall` executes on the owning
region. The seam is: replace the `queueHandler.sync(chain)` in `handleCallFinalizer` with a
`FoliaRegionDispatcher` region hop keyed on `(world, chunkX, chunkZ)` (C1). No lighting-
specific work beyond that.

### 3.2 skip-light (`MODE=0` / `SideEffect.LIGHTING` off) — **GO**

`EditSessionBuilder.java:586` only creates a relighter when `SideEffect.LIGHTING` is on or
`RelightMode != NONE`. With neither, no `RelightProcessor` is added, no post-edit pass runs.
Any light present in the result is whatever the SET carried, injected via the GO fillLightNibble
path (schematic paste with baked light, etc.). Nothing off-thread touches the live engine.
Trivially §1b-compliant.

### 3.3 GET-side light reads — **GO with a required fix**

`getSkyLight` (`:221`) / `getEmittedLight` (`:246`) are `IChunkGet` reads used by NMSRelighter
(neighbor light lookup) and history/copy. They are **not pure reads**: when a section's
`DataLayer` is absent they construct one and call `queueSectionData` (`:234`, `:263`) — a
mutation of the live `LevelLightEngine`. On Folia this is legal only on the owning region.
Requirement: these must execute as part of the region-thread GET snapshot (per architecture §1
GET model), never on a FAWE worker. Flag for W0.2: the GET snapshot must either capture light
on the region thread or route these lazy-creates through a region hop. This is the one place
where the GET side, not just the commit side, touches the light engine.

### 3.4 Full relight — NMSRelighter — **GO (as the DEGRADE target)**

`NMSRelighter` lives in `worldedit-core` (`extent/processor/lighting/NMSRelighter.java`) and
**cannot reference NMS**. It operates entirely over the FAWE queue:
- reads block state via `queue.getBlock(...)` (detached GET; e.g. `:354`, `:391`),
- reads neighbor light via the GET `getSkyLight`/`getEmittedLight` (§3.3),
- writes results into the **detached SET** through `ChunkHolder`:
  `iChunk.setBlockLight(...)` (`:257`, `:801`, `:838`), `iChunk.setSkyLight(...)`
  (`:1052`+), `iChunk.removeSectionLighting(...)` (`:203`).

Its output therefore flows back through the *standard commit path* and is materialized by the
GO fillLightNibble path (§3.1). NMSRelighter itself performs **zero live-state mutation** — it
is C2-clean by construction. Cross-border propagation is computed in FAWE's own model over GET
snapshots of neighbor chunks; it is not globally atomic (same as any multi-region FAWE edit,
governed by §4b/§4d), but it never races the live light engine.

Costs / caveats:
- NMSRelighter is FAWE's legacy engine (the pre-starlight Spigot path). Quality and speed are
  known to be below starlight; it is a real, shipping relighter, not a stub.
- Its `fixSkyLighting`/`fixBlockLighting`/`removeLighting` are `synchronized` on the relighter
  instance → single-threaded per EditSession relighter. Parallelism is across *independent
  edits/regions*, not within one relight. This is a §8 performance-budget item, not a
  correctness one.
- Selecting it on Folia is a documented degradation (§5): "on Folia, FAWE relights with its
  internal engine instead of the server's starlight engine; results are correct, throughput
  and edge-accuracy may differ from Paper."

### 3.5 Full relight — PaperweightStarlightRelighter — **STOP as-structured; DEGRADE recommended; conditional GO pending W0.2**

Three independent §1b violations, each fatal on its own:

1. **Global main-thread scheduling.** The whole flow is driven by `TaskManager.taskManager()
   .task(...)` (`StarlightRelighter.java:102` in `fixLighting`, `:81` in `postProcessCallback`)
   and `.async(...)` (`:83`). On Folia `BukkitTaskManager` wraps the legacy `BukkitScheduler`,
   which throws (recon-queue-threading §2/§3). The relighter cannot even start.

2. **Multi-region batch.** `StarlightRelighter` groups edited chunks into 32×32 = 1024-chunk
   batches (`CHUNKS_PER_BATCH` `StarlightRelighter.java:36`), with a 3×3 neighbor halo added
   per edited chunk (`addChunk` `:118-133`). `invokeRelight` (`PaperweightStarlightRelighter
   .java:53`) hands the *entire batch* to `starlight$serverRelightChunks(coords, …)` (`:59`)
   in one call. A 1024-chunk batch spans **many Folia regions**; no single region thread owns
   it. There is no region to schedule this call onto. (Note: this "region" is FAWE's own
   32×32 grouping, unrelated to Folia regions — the collision is exactly the problem.)

3. **Ticket/packet ops on arbitrary threads.** `chunkLoadFuture` (`:44`) does
   `getChunkAtAsync(x,z).thenAccept(c -> …addTicketAtLevel(...))` — the ticket mutation runs
   on the CompletableFuture completion thread, not a guaranteed region thread.
   `postProcessChunks` (`:69`) calls `removeTicketAtLevel` (`:77`) and `sendChunk` (`:75` →
   `PaperweightPlatformAdapter.java:365`, `MinecraftServer.getServer().execute`) — global main.

Salvage path (why it is only *conditional* GO, not DISABLE-forever): starlight can in
principle be invoked per-chunk or per-region-subset, region-scheduled through
`RegionScheduler.execute(plugin, world, cx, cz, …)` (verified present in the pinned
`folia-api-26.1.2.build.8-stable.jar`). The blocker is correctness at region borders: starlight
propagates light across chunk edges, so a call that relights chunks in region A while reading/
writing neighbor nibbles owned by region B races region B's ticking. Whether moonrise's
`serverRelightChunks` (a) may legally run on a Folia region thread at all (TickThread checks),
(b) internally schedules onto region-owned light threads, and (c) coordinates cross-region
neighbor access — **cannot be determined statically**: the method is a moonrise mixin accessor
(`ca.spottedleaf.moonrise.patches.starlight.*`, seen in `PaperweightChunkAccessProxy.java`) and
is not on the folia-api surface (confirmed: the pinned folia-api jar exposes no relight API).
This is the W0.2 experiment in §6.

Recommended wave-0 disposition: **do not attempt starlight on Folia**; DEGRADE to NMSRelighter
(§3.4). Revisit starlight as a perf optimization only if the W0.2 probe returns a clean GO.

---

## 4. Cross-region light propagation — the core question

Light crosses chunk and therefore region borders. Two ways FAWE handles it, two Folia outcomes:

- **NMSRelighter / fillLightNibble (recommended):** border light is computed in FAWE's detached
  model from GET snapshots and committed per chunk on that chunk's owning region. Adjacent
  chunks in different regions each get a correct, self-consistent commit; the multi-region
  result is eventually-consistent, not globally atomic — exactly what §4b/§4d permit for
  multi-region ops. No live-engine race. **Safe.**

- **Starlight batch (rejected for wave 0):** border light is computed by the live engine
  reading neighbor *live* nibbles. Splitting the batch across regions and running them
  concurrently means region A reads region B's nibbles mid-tick. **Unsafe unless moonrise
  itself serializes cross-region access — unverifiable statically.**

Per-chunk granularity is achievable for the recommended path (each chunk commit is independent
and region-local); it is *not* naturally achievable for starlight, which is batch-oriented by
design.

---

## 5. Proposed frozen contract wording (lighting portion of the chunk-commit pipeline)

> **L1 — Commit-path light injection is region-local.** On the Folia backend, all live
> `LevelLightEngine` writes performed during a chunk commit (`setLightingToGet`,
> `setSkyLightingToGet`, `fillLightNibble`, `removeSectionLighting`) execute on the region
> owning `(world, chunkX, chunkZ)`, as part of the same commit task that swaps that chunk's
> sections (C1/C2). They target only sections of the committed chunk; no light write may reach
> another chunk or region.
>
> **L2 — GET-side light reads are region-captured.** `getSkyLight`/`getEmittedLight`, including
> their lazy `queueSectionData` creation of absent `DataLayer`s, execute only on the owning
> region as part of the GET snapshot. Detached workers consume captured light nibbles, never
> the live engine.
>
> **L3 — Post-edit relight is detached (NMSRelighter) on Folia.** The Folia backend selects a
> relighter that computes light over detached GET/SET data (worldedit-core `NMSRelighter`) and
> materializes it through the L1 commit path. The server's batch starlight engine
> (`serverRelightChunks`) is NOT invoked on Folia in wave 0. This is a documented degradation
> (§5 compatibility inventory): correct results, potentially different throughput and border
> accuracy versus the Paper starlight path.
>
> **L4 — No global scheduling or global ticket/packet ops for lighting.** On Folia the lighting
> pipeline uses `FaweThreadContext`/`FoliaRegionDispatcher` region hops keyed on the target
> chunk; it never uses `TaskManager` global-scheduler entry points, `MinecraftServer.execute`,
> `MCUtil.MAIN_EXECUTOR`, or ticket/packet mutations on CompletableFuture completion threads.
> Lighting's own delayed packet send (`sendChunk`) is region-scheduled per chunk (coordinated
> with task 05).
>
> **L5 — Starlight-on-Folia is a gated future option, not a wave-0 deliverable.** Any attempt
> to invoke `serverRelightChunks` on Folia requires: per-region batch decomposition, a signed
> upstream-supported contract that the call is legal on a region thread with correct
> cross-region neighbor handling, and its own certification gate (§1b exception clause). Absent
> that, L3 stands.

---

## 6. Open runtime questions for W0.2 (the prototype must settle these)

The escape hatch applies: the starlight GO/STOP boundary depends on moonrise internals no
static read can settle. W0.2's prototype should run, on Folia 26.1.1 and 26.1.2:

1. **isPaper on Folia.** Confirm `PaperLib.isPaper()` is true on Folia (drives the default
   factory choice). Trivial, but load-bearing.
2. **Region-thread legality of starlight.** From a `RegionScheduler.execute(plugin, world, cx,
   cz, …)` task, call `getChunkSource().getLightEngine().starlight$serverRelightChunks(...)` on
   a **single chunk** owned by that region. Does it (a) throw a TickThread/ownership assertion,
   (b) run inline, or (c) internally reschedule? Capture the thread of `chunkCallback` /
   `processCallback`.
3. **Cross-region border correctness.** Relight two adjacent chunks owned by different regions,
   concurrently, via starlight; compare border block/sky light against the Paper oracle.
   Determines whether §5-L5 salvage is ever viable or starlight is DISABLE-forever on Folia.
4. **`getChunkAtAsync` completion thread.** Is the `thenAccept` continuation on the owning
   region thread or a pool thread? Decides whether ticket adds need an explicit region hop
   (they almost certainly do).
5. **NMSRelighter perf vs starlight (§8 budget).** Measure NMSRelighter throughput on the §8
   workloads (large `//set`, `//replace`, schematic paste) against ported-Paper starlight.
   Quantify the degradation the §5 doc will state and confirm it is within the frozen Folia
   budget — this is the number that decides whether the DEGRADE is acceptable or forces
   reopening the starlight salvage.

---

## 7. Touch-point inventory (grep-able, for implementers)

Live light-engine touches to reroute/replace on the Folia backend (all adapter-26.1 unless
noted):
- `PaperweightGetBlocks.java`: `:130` setLightingToGet, `:142` setSkyLightingToGet, `:171`
  removeSectionLighting, `:221` getSkyLight, `:246` getEmittedLight, `:935` fillLightNibble,
  `:581-590` commit-path light calls, `:744-766` finalizer/callback.
- `PaperweightStarlightRelighter.java`: `:44` chunkLoadFuture, `:46` addTicketAtLevel, `:59`
  serverRelightChunks, `:75` sendChunk, `:77` removeTicketAtLevel.
- `StarlightRelighter.java` (worldedit-bukkit base): `:81`/`:83`/`:102` TaskManager global
  scheduling, `:36` CHUNKS_PER_BATCH=1024, `:118` 3×3 halo.
- `AbstractBukkitGetBlocks.java:174` `queueHandler.sync(chain)` — the commit finalizer hop.
- `EditSession.java:1354-1366` relighter flush; `EditSessionBuilder.java:586-593` relighter
  selection; `PaperweightFaweAdapter.java:865-871` factory choice (Folia override point).
- Detached, safe, no change needed: `worldedit-core/.../lighting/NMSRelighter.java` (entire),
  `RelightProcessor.java`, `RelightMode.java`.
