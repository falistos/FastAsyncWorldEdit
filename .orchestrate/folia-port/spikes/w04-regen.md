# Spike W0.4 — `//regen` feasibility on Folia 26.1.x

**Disposition: DISABLE** (fail-closed on the Folia backend). No §1b-compliant path in
26.1 preserves regen correctness (biomes, structures, NBT). One DEGRADE candidate exists
but depends on an unresolved runtime question and is heavily lossy; it is deferred, not
adopted here.

Evidence sources: FAWE source (this repo), Folia server patches cloned from
`github.com/PaperMC/Folia` @ HEAD (`folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch`,
`folia-server/paper-patches/features/0001-Region-Threading-Base.patch`) and folia-api
`26.1.2.build.8-stable` (gradle cache). Folia's regionization invariants below are
structural (present since Folia's inception); exact 26.1.2 line numbers may differ from
HEAD but the semantics are the architectural contract, not incidental. Claims tagged
**VERIFIED(Folia)** were read from the patch; **VERIFIED(FAWE)** from this repo.

---

## 1. Requirement inventory — what regen needs from the server

### 1.1 Core / command entry (backend-agnostic)
- `RegionCommands.regenerate(...)` — `worldedit-core/.../command/RegionCommands.java:708-758`.
  Marked `@SynchronousSettingExpected`. Calls `world.regenerate(region, editSession, options)`.
  Options carry: optional seed override, `-b` regen-biomes, single `biomeType` override.
- `World.regenerate(...)` default — `worldedit-core/.../world/World.java:268-299`.
- `BukkitWorld.regenerate(...)` — `worldedit-bukkit/.../bukkit/BukkitWorld.java:261-266`
  → `adapter.regenerate(getWorld(), region, extent, options)`.
- `PaperweightFaweAdapter.regenerate(...)` — adapter-26.1 `.../v26_1/PaperweightFaweAdapter.java:825-826`
  → `new PaperweightRegen(...).regenerate()`.
- Abstract driver `Regenerator.regenerate()` —
  `worldedit-bukkit/.../bukkit/adapter/Regenerator.java:64-88`: `prepare()` → `initNewWorld()`
  → `copyToWorld()` → `cleanup()`. `copyToWorld()` (L104-130) spins a
  `TaskManager.repeat(..., 1)` global-scheduler task that repeatedly calls `runTasks(...)`,
  while `target.setBlocks(region, PlacementPattern)` pulls generated blocks from a
  `SingleThreadQueueExtent` over the temp world.

### 1.2 The temp-`ServerLevel` trick — `PaperweightRegen` (adapter-26.1 `.../v26_1/regen/PaperweightRegen.java`)
Requirements, each with the server capability it assumes:

| # | Requirement | Code (VERIFIED FAWE) | Server capability assumed |
|---|---|---|---|
| R1 | Construct a detached `ServerLevel` off the normal world-load path | `initNewWorld()` L154-207 `Fawe...getQueueHandler().sync(() -> new ServerLevel(...))` | A world may be built ad-hoc and driven without being registered as a live world |
| R2 | Run the construction "on the main thread" via `QueueHandler.sync` | L154; `QueueHandler.sync` drains on the single global main thread (`QueueHandler.java:330-359`, gated by `Fawe.isMainThread()`) | A single global main thread exists |
| R3 | Hide the temp world from the Bukkit worlds map (reflection) | `removeWorldFromWorldsMap()` L256-263 mutates `CraftServer.worlds` | Global, single-owner world registry |
| R4 | Drive chunk generation by pumping the chunk source | `runTasks()` L100-106 `freshWorld.getChunkSource().pollTask()` in a loop | A poll-driven main-thread chunk executor owned by no region |
| R5 | Read generated blocks/biomes/tile-NBT back synchronously | via `initSourceQueueCache()` L250-252 → `ChunkCache` over `freshWorld.getWorld()`; `PlacementPattern` reads `source.getFullBlock(...)` | Synchronous chunk access from the calling thread |
| R6 | Tear down the temp chunk source + world | `cleanup()` L217-247: `chunkSource.close(false)`, worlds-map remove, dir delete, all via `QueueHandler.sync` | Global-main teardown |
| R7 | Optional single-biome override via anonymous `getUncachedNoiseBiome` + `BiomeProvider` | L176-187, `getBiomeProvider()` base L248-253 | Subclassing a live `ServerLevel` |

The vanilla-WE sibling `PaperweightAdapter.doRegen(...)` (adapter-26.1 `.../v26_1/PaperweightAdapter.java:730-851`)
takes the same shape but drives generation via `chunkProviderExecutor.managedBlock(...)`
(L800-814) and reads `serverWorld.captureBlockStates`/`capturedBlockStates` state
(L548-563 in the FAWE adapter; the shared ServerLevel capture fields). It is only reached
by the non-FAWE adapter, but shares every blocker below and adds one more (R-cap).

### 1.3 What breaks under Folia's regionized chunk system

**All of R1–R6 break. The design is structurally impossible on Folia 26.1.** Evidence
from the Folia patch (all **VERIFIED(Folia)**):

- **B0 — No world may be created at runtime, by any means.**
  `CraftServer.createWorld(...)` and `unloadWorld(...)` both begin with
  `if (true) throw new UnsupportedOperationException(); // Folia - not implemented properly yet`
  (paper-patch `0001...:856,864`). This kills the Bukkit-API route to a temp world
  outright, and independently signals that Folia's world lifecycle is not runtime-mutable.
- **B1 — A hand-built `ServerLevel` is never regionised (breaks R4/R5).** Real worlds are
  registered by `RegionizedServer.getInstance().addWorld(level)` inside the server's own
  world-load path (mc-patch `0001...:8213`), never by the `new ServerLevel(...)` constructor.
  FAWE's R1 bypasses that path, so the temp world has no region, no region tick thread, and
  no `RegionizedWorldData`.
- **B2 — `ServerChunkCache` chunk pump is region-owned (breaks R4).** The chunk executor's
  `pollTask()` now asserts ownership:
  `if (ServerChunkCache.this.level != TickRegionScheduler.getCurrentRegionizedWorldData().world) throw new IllegalStateException("Polling tasks from non-owned region");`
  (mc-patch `0001...` ServerChunkCache, ~L900-1050). Off a region tick thread
  `getCurrentRegionizedWorldData()` is `null` → NPE; on any real region thread its `.world`
  is some *other* world → `IllegalStateException`. The temp world can never satisfy the
  equality. `getChunkFuture(...)` and every `MainThreadExecutor.execute/submit/schedule`
  override additionally `throw new UnsupportedOperationException()`.
- **B3 — No global main thread (breaks R2/R6).** `MinecraftServer.pollTask/pollTaskInternal/doRunTask`
  all `throw new UnsupportedOperationException()` (mc-patch `0001...:8376+`). Folia has no
  single main thread to drain `QueueHandler.sync` onto — matching architecture.md §2's
  `FoliaQueueHandler` removing the global sync-drain.
- **B4 — `managedBlock` route is equally dead (breaks the vanilla-WE R4).** `managedBlock`
  runs `pollTask` under the hood → B2/B3. `ChunkTaskScheduler`'s blocking chunk-load helper
  is explicitly rewritten to require `TickRegionScheduler.getCurrentRegion()` and otherwise
  spin (mc-patch `0001...:788-802`); a non-region caller never makes progress.
- **B5 — `syncLoad`/chunk read is owner-only (breaks R5).** `ServerChunkCache.syncLoad`
  gains `TickThread.ensureTickThread(this.level, cx, cz, "Cannot asynchronously load chunks")`
  (mc-patch `0001...` ServerChunkCache ~L100). Reading generated chunks off the owning
  region thread throws.
- **B6 — `getCurrentWorldData()` is null off-region (breaks R5/R7 and R-cap).** Per-world
  live state (players, ticking chunks, capture buffers) moved into `RegionizedWorldData`
  fetched via `TickRegionScheduler.getCurrentRegionizedWorldData()`; off a region thread it
  is `null` (Level patch, ~L17567). The vanilla-WE **R-cap**: `Level.getBlockStateIfLoaded`
  now reads `this.getCurrentWorldData().captureTreeGeneration` /`.capturedBlockStates` →
  NPE off-region.
- **B7 — `close("...off-main")` and structure/registry paths** carry `TickThread.ensureTickThread`
  assertions too (`"Closing world off-main"`, mc-patch `0001...:472`), breaking R6.

Net: R1 builds an object Folia will never tick; R2/R6 have no thread to run on; R4 throws;
R5 throws; only R3 (reflection on a map) still "works" but is meaningless. Bypassing the
`TickThread`/`ensureTickThread` checks is explicitly forbidden by spec §1b and cannot be
done per-flag anyway (they are region-ownership assertions, not one global toggle — recon
`recon-adapters-nms.md` §5/§6).

---

## 2. Alternative paths

### (a) Folia-safe world-generation API without a live `ServerLevel`
**Not available.** 26.1's generation pipeline (`NoiseBasedChunkGenerator`, surface, carvers,
`FeaturePlacement`, `StructureManager`/`StructureCheck`) is bound to a `ServerLevel` + its
`ChunkTaskScheduler` + region ownership. No public API generates a chunk into a detached
buffer. Structures in particular need `StructureManager`, the world seed's structure
placement, and neighbour chunks — all region/level-bound. §1b: N/A (no path). Correctness:
would require reimplementing vanilla generation, guaranteed to drift on structures/biomes.
Complexity: prohibitive. **Rejected.**

### (b) Generate into a detached buffer, place via the W0.2 prepare/commit pipeline
The **placement** half (writing generated blocks into the target region) is exactly W0.2's
job and is compliant. The blocker is the **generation** half: producing the pristine blocks
requires (a), which doesn't exist. You cannot "prepare" a generated chunk off-thread because
generation is not detachable. §1b: the commit half complies; the generate half has no
compliant source. **Rejected as a whole** (reduces to (a)).

### (c) Restrict `//regen` to the global region / a dedicated context
The global region has no world data (`getCurrentRegionizedWorldData()` → null for it), so
B2/B5/B6 still fire; and B0 still forbids creating the temp world. Running generation "in
the live world via its own `RegionScheduler`" is **incorrect by construction**: regen must
produce *pristine* terrain without mutating existing player builds, and vanilla will not
re-run generation on an already-generated chunk in place. §1b: the live-world variant is
technically region-legal but violates §4b correctness (destroys accepted world state).
**Rejected.**

### (d) DEGRADE candidate — persistent server-loaded scratch world (DEFERRED, not adopted)
The only theoretically §1b-compliant shape: use a **properly regionised** scratch world
(one Folia loaded itself at startup, never `createWorld`'d at runtime), generate the
requested region inside it via `getRegionScheduler().execute(scratchWorld, cx, cz, ...)`,
copy chunks out **on that region's thread** as detached C2 data, then commit into the target
via the W0.2 pipeline. This respects region ownership end to end.

Why it is not a wave-0 GO:
- **World provisioning is the wall.** Runtime `createWorld` throws (B0). A scratch world can
  only exist if Folia's own startup world-loading brings it up. **OPEN RUNTIME QUESTION Q1**
  below.
- **Per-source parametrisation is lost.** Regen must match the *source* world's seed,
  generator, and dimension. A single static scratch world matches at most one source world;
  FAWE's seed-override (`//regen <seed>` / `-r`) and per-call biome override
  (`RegenOptions.hasBiomeType`) cannot be honoured without constructing a world with those
  params — which B0 forbids. So even (d) degrades to "regen only in worlds for which a
  matching scratch world was pre-provisioned, no seed override, no biome override."
- Complexity: high (operator provisioning, world-matching, teleport-free region scheduling,
  detached read-back), and still a documented DEGRADE, never identity. It belongs to a
  follow-up spike with runtime evidence, per the escape hatch — **not** this wave.

---

## 3. Disposition & operator-facing wording

**DISABLE on the Folia backend.** Fail-closed: `PaperweightFaweAdapter.regenerate(...)` on
Folia must not attempt the temp-world trick (it would throw deep inside NMS with an opaque
`IllegalStateException`/NPE and possibly leave a half-constructed world/ticket leak).
Instead reject early with a clear message. This is a §5 documented degradation →
compatibility-inventory entry + signed spec amendment required before dependent work
treats regen as final.

Proposed operator/actor message (Caption-style, English; final key naming per the
compatibility-inventory owner):

> `//regen` is not available on Folia. Folia's regionized chunk system forbids the
> temporary-world generation FastAsyncWorldEdit uses for regen (worlds cannot be created at
> runtime, and chunk generation is bound to region-owning threads). Use a Paper server for
> regen, or restore the area from a backup/schematic.

Console/log note (once, at first attempt or at detection): state that regen is disabled on
the Folia backend and point to FOLIA.md.

No DEGRADE wording is offered because no partial mode meets §5's "never silent
semi-functioning" at acceptable correctness: any in-place variant loses pristine-terrain
correctness, and the scratch-world variant (d) is unproven and lossy on seed/biome.

---

## 4. Open runtime questions (escape hatch — for a follow-up spike, not guesses)

- **Q1 (gates a future DEGRADE):** Does Folia 26.1.2 load extra, plugin-declared worlds at
  **startup** (e.g. via `bukkit.yml` `worlds:` or a config-declared level folder), given
  runtime `createWorld` throws? If yes, a pre-provisioned regionised scratch world is
  obtainable and alternative (d) becomes a real (lossy) DEGRADE. Experiment: on the W0.2
  harness (Folia 26.1.2), declare a secondary world at startup, confirm it boots regionised,
  then from a plugin call `getRegionScheduler().execute(scratchWorld, cx, cz, ...)`, force-load
  and generate a chunk, and read it back on that region thread. Pass = generation completes
  and read-back succeeds without `TickThread`/ownership exceptions.
- **Q2:** Confirm the exact throw site/observability when the current `PaperweightRegen`
  runs unmodified on Folia (which of B2/B3/B5 fires first, and whether it leaks a temp world
  dir / tickets). Needed only to size the fail-closed guard's placement; the DISABLE stands
  regardless. Experiment: run `//regen` once on Folia under the harness with the guard
  removed, capture the stack + any leaked `faweregentempworld` artifacts.

Both are runtime confirmations of a *possible future relaxation* / *guard placement*; they
do **not** change the wave-0 disposition, which is DISABLE.

---
## Dev record

- **Status:** done — disposition **DISABLE** (fail-closed on Folia backend). Contract-changing
  (§5): requires a signed spec amendment + compatibility-inventory entry before dependent
  work treats regen as final. Not a STOP (no frozen-contract conflict; the contracts
  anticipate DISABLE dispositions).
- **File List:** `.orchestrate/folia-port/spikes/w04-regen.md` (this report). No source
  changes (read-only spike).
- **Completion Notes:** Every FAWE regen requirement (R1-R7 + vanilla R-cap) inventoried
  with file:line. Folia impossibility proven from Folia server patches, not assumed:
  runtime `createWorld`/`unloadWorld` throw `UnsupportedOperationException` (B0);
  `ServerChunkCache` pollTask asserts region ownership and `MinecraftServer.pollTask` throws
  (B2/B3); chunk read (`syncLoad`) and per-world state (`getCurrentWorldData`) are
  region-thread-only (B5/B6). All three alternative families (Folia-safe gen API / detached
  buffer / restricted context) reduce to the same missing capability: off-region,
  world-less chunk generation, which 26.1 does not expose. One lossy DEGRADE (persistent
  startup-loaded scratch world) is deferred behind open runtime question Q1.
- **Deviations:** None from the task. Went beyond desk analysis by cloning the Folia source
  (per user's verify-at-source directive) to turn Folia-internal claims from ASSUMPTION into
  VERIFIED(Folia) — only Q1/Q2 remain genuinely runtime-open.
- **Attack points (for adversarial review):**
  1. Folia patch was read at repo HEAD, not pinned 26.1.2 — line numbers may drift. Rebut:
     the invariants (createWorld throws, pollTask region-owned, getCurrentWorldData null
     off-region) are Folia's founding architecture, stable across its history; verify against
     the certified 26.1.2 build during the harness gate if desired.
  2. Is the DISABLE too conservative — could the scratch-world DEGRADE (d) be a wave-0 GO?
     Rebut: blocked on Q1 (unverified) and lossy on seed/biome override; adopting it now
     would be guessing, which §5 and the escape hatch forbid.
  3. Did I miss a non-`createWorld`, non-`ServerLevel`-ctor generation entry (e.g. a
     Moonrise/paper async-gen API)? Searched adapter + core; both 26.1 regen impls build a
     full `ServerLevel`. Worth a second look by the adapter owner if any lower-level
     generator hook exists.
