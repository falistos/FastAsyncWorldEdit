# W0.6 — API thread-context audit

## Verdict

The named source sets contain **382 public declarations**. All are classified below, grouped only where overloads share the same thread-context contract.

| Disposition | Declarations |
|---|---:|
| PRESERVED | 374 |
| DEGRADED | 8 |
| EXCLUDE | 0 |
| **Total** | **382** |

This draft is suitable as the §4c contract input, with two material coverage concerns:

1. The stable `Extent` contracts (`InputExtent`, `OutputExtent`, `Extent`) account for 86 declarations. The package contains another **255 public declarations across 24 Extent-derived implementation types**. Interface overrides inherit the rows below, but implementation-specific methods still need a separate supported/internal classification.
2. Architecture C3 also names `QueueHandler.sync(...)`, although task 06’s objective does not. Its public location-free surface adds **10 methods** (`async` ×3, `sync` ×3, `syncWhenFree` ×4). These are not silently folded into the TaskManager rows.

## Contract conventions

- Contexts: `R` = region thread owning every target chunk; `E` = owning entity scheduler; `G` = global-region thread; `A` = non-FAWE async thread; `W` = FAWE worker.
- `ANY` means all five contexts, subject to ordinary object-level concurrency rules.
- No `R`, `E`, or `G` caller may wait for another owner. A tick-thread call that cannot execute locally fails before side effects.
- `A` and `W` may wait only through the bounded worker → owner path required by architecture §1.
- “Staged” means the return value describes detached preparation, not committed server state. Terminal success occurs at `EditSession.close()`, `flushQueue()`, or completion of the returned `Operation`.
- Pattern, mask, generator, processor, custom-extent, and event-bus code is caller-supplied callback code. It may run only on the callback thread stated below and may not make direct Bukkit/NMS calls outside that thread’s ownership.
- `Y/Y` means binary/source compatibility is retained. DEGRADED methods retain their signatures and fail deterministically on Folia.

## Disposition table

### FaweAPI — 18 declarations

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-001 | `getTaskManager()` | 1 | Y/Y | ANY | Detached singleton lookup | Nonblocking; returns manager | Return | — | — | Initialization failure thrown synchronously | PRESERVED | HIGH |
| APIC-002 | `createQueue(World, boolean)` | 1 | Y/Y | ANY | Logical world identity only; later chunk access derives `R(world,cx,cz)` | Nonblocking construction | Return creates queue; no edit is committed | — | Queue/session cancellation later | Construction errors synchronous; later errors at queue completion | PRESERVED | HIGH |
| APIC-003 | `getWorld(String)` | 1 | Y/Y | G, A, W; R/E only if platform registry is certified concurrent | G/platform registry | Synchronous lookup; tick caller must not owner-hop | Return | — | — | Synchronous lookup error | PRESERVED | MEDIUM, NR-01 |
| APIC-004 | `upload(Clipboard, ClipboardFormat)`; `load(File)` | 2 | Y/Y | A, W | Detached I/O | Blocking network/disk call | Return | I/O implementation thread = caller | External I/O cancellation only | Declared/unchecked I/O failure on return | PRESERVED | HIGH |
| APIC-005 | `getMaskManagers()`; `addMaskManager(FaweMaskManager)` | 2 | Y/Y | ANY | Internal registry; mutations serialized | Nonblocking | Return | — | — | Synchronous validation/registry error | PRESERVED | HIGH |
| APIC-006 | `isMemoryLimited()`; `addMemoryLimitedTask(Runnable)`; `addMemoryPlentifulTask(Runnable)` | 3 | Y/Y | ANY | Memory monitor / async service | Query or callback registration returns immediately | Query return or callback termination | A/W monitor thread; never an owner thread by implication | No public registration handle | Registration errors synchronous; callback failure reaches task handler once | PRESERVED | MEDIUM |
| APIC-007 | `getRegions(Player)`; `getRegions(Player, MaskType, boolean)` | 2 | Y/Y | E(player), matching R, A, W | E(player), plus player’s current region for protection callbacks | Direct on owner; bounded wait from A/W; wrong tick fails | Regions fully computed on return | E/R protection-manager callback | — | Callback/API failure propagated once to caller | PRESERVED | HIGH |
| APIC-008 | `cancelEdit(AbstractDelegateExtent, Component)` | 1 | Y/Y | ANY with exclusive ownership of the EditSession pipeline | Session-local extent chain | Nonblocking cancellation request | Return acknowledges request; accepted commits settle through operation completion | — | This method is cancellation | No signature-level error channel; failures must be logged once, not silently swallowed | PRESERVED | MEDIUM |
| APIC-009 | `getChangeSetFromFile(File)`; `getChangeSetFromDisk(World, UUID, int)` | 2 | Y/Y | A, W; other contexts only if construction is proven non-I/O | Detached history metadata | Synchronous construction | Return; no history read necessarily complete | — | — | Validation failure synchronous | PRESERVED | MEDIUM |
| APIC-010 | `getBDFiles(Location, UUID, int, long, boolean)` | 1 | Y/Y | A, W | Detached filesystem/history scan | Blocking disk scan | Complete list on return | Caller thread | Interruption/cooperative I/O only | I/O/format failure propagated synchronously; no silent partial list | PRESERVED | HIGH |
| APIC-011 | `fixLighting(World, Region, IQueueExtent, RelightMode)` | 1 | Y/Y | A, W; R/E only for a single currently owned target | W detached NMSRelighter preparation → per-chunk R commits | Synchronous count; may bounded-wait only from A/W | Relighter close plus light/packet finalizers, not merely chunk enumeration | W for detached relight; R per light commit | Queue cancellation; partial failure must match committed chunks | Failure must reach API once; current catch-and-log behavior is insufficient | PRESERVED with documented NMSRelighter degradation | MEDIUM, NR-02 |
| APIC-012 | `getTranslations(Locale)` | 1 | Y/Y | ANY | Immutable/config registry | Nonblocking | Return | — | — | Synchronous registry error | PRESERVED | HIGH |

### EditSessionBuilder — 44 declarations

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-013 | `getWorld`, `getMaxBlocks`, `getActor`, `getBlockBag`, `isTracing`, `getRelighter`, `isWNAMode`, `getAllowedRegions`, `isWrapped`, `getBypassHistory`, `getBypassAll`, `getLimit`, `getChangeTask`, `getSideEffectSet`, `getExtent` | 15 | Y/Y | ANY; builder must be single-owner | Builder-local state | Nonblocking | Return | — | — | Synchronous state error | PRESERVED | HIGH |
| APIC-014 | `world`, `maxBlocks`, `actor`, `blockBag`, `tracing`, `event`, `limit`, `limitUnlimited`, `changeSet(AbstractChangeSet)`, `changeSetNull`, `command`, `allowedRegions` ×3, `disallowedRegions` ×3, `allowedRegionsEverywhere`, `fastMode`, `relightMode`, `checkMemory`, `combineStages`, `setSideEffectSet`, `forceWNA` | 24 | Y/Y | ANY; builder must be single-owner | Builder-local/detached state | Nonblocking fluent return | Return | — | — | Validation failure synchronous | PRESERVED | HIGH |
| APIC-015 | `locatableActor(A)`; `limitUnprocessed(Actor)` | 2 | Y/Y | E(actor), matching R, A, W | E(actor) for extent/limit reads | Direct on owner; bounded wait from A/W; wrong tick fails | Return | Actor adapter on E | — | Actor/validation failure synchronously propagated | PRESERVED | MEDIUM |
| APIC-016 | `changeSet(boolean, UUID)` | 1 | Y/Y | A, W | Detached history construction | May perform filesystem-oriented setup; no tick blocking | Return | — | — | Construction failure synchronous | PRESERVED | MEDIUM |
| APIC-017 | `compile()`; `build()` | 2 | Y/Y | A, W; E(actor) when all actor-derived access is local | W for pipeline construction; E for player permissions/regions; queue target derived from world | Synchronous; A/W may bounded-wait for actor callback; wrong tick fails | Fully constructed pipeline on return, not committed edit | EditSessionEvent callbacks on the actor owner when present; otherwise G/W | No operation exists yet | Event, permission, memory, or construction failure propagated once | PRESERVED | HIGH |

### TaskManager — 24 declarations

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-018 | `taskManager()`; `getPublicForkJoinPool()` | 2 | Y/Y | ANY | Singleton / W pool | Nonblocking | Return | Tasks submitted to returned pool run W | Pool/Future APIs | Initialization failure synchronous | PRESERVED | HIGH |
| APIC-019 | `repeatAsync(Runnable,int)`; `async(Runnable)`; `laterAsync(Runnable,int)` | 3 | Y/Y | ANY | A scheduler | Submission returns immediately | Callback termination, not submission return | A | `repeatAsync` by returned ID; void submissions have no handle | Submission error synchronous; callback failure to task handler once | PRESERVED | HIGH |
| APIC-020 | `repeat(Runnable,int)`; `task(Runnable)`; `later(Runnable,int)` | 3 | Y/Y | ANY for submission; callback must be global-safe | G | Submission returns immediately | Callback termination | G | Repeating task by ID; void submissions have no public handle | Submission error synchronous; callback failure to task handler once | PRESERVED with deterministic G fallback | HIGH |
| APIC-021 | `taskNow(Runnable,boolean)`; `taskNowAsync(Runnable)` | 2 | Y/Y | ANY | Explicit A or current caller context | Inline path returns after callback; async path after acceptance | Callback return or scheduled callback termination | Current caller or A, exactly as selected | No handle | Callback error propagated inline or through task handler once | PRESERVED | HIGH |
| APIC-022 | `taskNowMain(Runnable)`; `taskSoonMain(Runnable,boolean)`; `taskWhenFree(Runnable)` | 3 | Y/Y | ANY; callback must be current-owner-safe when inline or global-safe otherwise | Current R/E/G if already on a tick thread; otherwise G; `taskSoonMain(async=true)` targets A | Never waits on a tick thread; submission otherwise nonblocking | Callback termination | Current tick, G, or A according to branch | No handle | Callback error propagated once | PRESERVED with current-context/G derivation | HIGH |
| APIC-023 | `cancel(int)` | 1 | Y/Y | ANY | Scheduler owning the ID | Nonblocking cancellation request | Scheduler confirms cancellation internally | — | Entry point itself | Unknown/already-finished ID is deterministic no-op or documented error | PRESERVED | MEDIUM, NR-03 |
| APIC-024 | `objectTask(Collection,RunnableVal,Runnable)` | 1 | Y/Y | ANY for submission; callbacks global-safe | G, fragmented by tick budget | Returns after first task is scheduled | `whenDone` callback termination | G | Cancel through underlying scheduled task only; current API exposes no handle | Item/terminal callback failure reaches task handler once | PRESERVED | MEDIUM |
| APIC-025 | `parallel(Collection)` ×2 | 2 | Y/Y | A, W only | W pool | Blocks until all submitted callbacks terminate | All callbacks complete | W | Future/pool interruption; no per-item handle | Aggregate callback failures and restore interrupt; do not swallow | PRESERVED | LOW |
| APIC-026 | `wait(AtomicBoolean,int)`; `notify(AtomicBoolean)` | 2 | Y/Y | `wait`: A/W only; `notify`: ANY | Caller-local synchronization | `wait` blocks; `notify` nonblocking | Flag cleared or explicit timeout/interruption | Caller thread | Flag/interrupt | Interruption restored and propagated; tick call rejected | PRESERVED | LOW |
| APIC-027 | `syncWhenFree(RunnableVal)`; `syncWhenFree(Supplier)`; `sync(RunnableVal)`; `sync(Supplier)` | 4 | Y/Y | ANY only for current-owner/global-safe callback code | Current R/E/G when invoked there; otherwise G | Inline on tick; bounded wait only from A/W | Callback return | Current tick or G | Future cancellation internally; no public handle | Callback exception propagated once; never wrap owner failure twice | PRESERVED with current-context/G derivation | HIGH |
| APIC-028 | `runUnsafe(Runnable)` | 1 | Y/Y | None on Folia | No legal target: advertised AsyncCatcher/physics suppression is forbidden | Immediate failure before callback | Failure return | Callback never invoked | — | Deterministic `UnsupportedOperationException`/caption | DEGRADED | HIGH |

### Extent contract hierarchy — 86 declarations

The rows apply to `InputExtent`, `OutputExtent`, and `Extent`, including overrides through FAWE-owned Extent implementations.

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-029 | `getBlock(BlockVector3|x,y,z)`; `getFullBlock(BlockVector3|x,y,z)`; `getBiome(BlockVector2|BlockVector3)`; `getBiomeType(x,y,z)` | 7 | Y/Y | R/E owning target, A, W; detached extents ANY | R(world, x>>4, z>>4), or detached extent | Direct on owner/detached; bounded snapshot wait from A/W; wrong tick fails | Immutable snapshot returned | R for live capture | Caller/Future cancellation from A/W | Read/load failure synchronously propagated | PRESERVED | HIGH |
| APIC-030 | `getEmittedLight` ×2; `getSkyLight` ×2; `getBrightness` ×2; `getOpacity` ×2 | 8 | Y/Y | Same as APIC-029 | R(world,chunk) for live light snapshot | Same as APIC-029 | Detached light value returned | R for live capture | Same as read future | Read/light-engine failure once | PRESERVED | HIGH |
| APIC-031 | `getHeightMap(HeightMapType)` | 1 | Y/Y | ANY for default/detached chunk object | Intrinsic chunk key when overridden; otherwise detached default | Nonblocking detached return | Return | — | — | Synchronous | PRESERVED | MEDIUM |
| APIC-032 | `setBlock` ×2; `setTile`/`tile`; `setBiome` ×3 | 7 | Y/Y | R/E owning target, A, W; detached extents ANY | R(world,chunk) for live implementation; W staging for queue | Point operation direct/staged; no cross-owner tick wait | Direct extent: return; queued extent: terminal at commit | Caller/W for preparation; R for commit | Queue/session cancellation | Preparation error immediate; commit error once at terminal completion | PRESERVED | HIGH |
| APIC-033 | `setBlockLight` ×2; `setSkyLight` ×2 | 4 | Y/Y | Same as APIC-032 | R(world,chunk) | Direct/staged | Return or queue commit | R for live mutation | Queue/session cancellation | Failure propagated once | PRESERVED with Folia lighting backend | MEDIUM |
| APIC-034 | `fullySupports3DBiomes()` | 1 | Y/Y | ANY | Detached capability query | Nonblocking | Return | — | — | Synchronous | PRESERVED | HIGH |
| APIC-035 | `setHeightMap(HeightMapType,int[])` | 1 | Y/Y | ANY for detached chunk object | Intrinsic chunk SET object; live application later on R | Nonblocking detached mutation | Commit of containing chunk | — during preparation; R during commit | Queue/session cancellation | Preparation/commit failure once | PRESERVED | MEDIUM |
| APIC-036 | `OutputExtent.commit()`; `Extent.commit()` override | 2 | Y/Y | A, W; R/E only if every accepted chunk is currently owned | Fan-out to all recorded R keys | Returns `Operation`; no tick waiting | Returned Operation terminates after commits/finalizers | R per chunk; completion continuation W | Operation/session cancellation | Every failure reaches operation once with partial-commit record | PRESERVED | HIGH |
| APIC-037 | `getMinimumPoint`, `getMaximumPoint`, `isWorld`, `contains(BlockVector3|x,y,z)`, `getMinY`, `getMaxY` | 7 | Y/Y | ANY | Detached bounds/metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED | HIGH |
| APIC-038 | `getEntities(Region)`; `getEntities()`; `createEntity` ×2; `removeEntity`; `removeEntities` | 6 | Y/Y | A, W; R/E when all entities/locations share current owner | Region fan-out or R(location)/E(entity) | Reads may bounded-wait only A/W; mutations staged or direct on owner | Snapshot return; queued mutation at commit | R/E for live entity access | Queue/session cancellation | Per-owner failure aggregated once; no partial silent list | PRESERVED | HIGH |
| APIC-039 | `isQueueEnabled`, `enableQueue`, `disableQueue` | 3 | Y/Y | ANY with exclusive extent ownership | Extent-local queue state | Nonblocking | Return | — | Queue controls | Unsupported queue state fails synchronously | PRESERVED | MEDIUM |
| APIC-040 | `regenerateChunk(int,int,BiomeType,Long)` | 1 | Y/Y | None for live Folia worlds; detached/default implementation may return false | No certified Folia target | Immediate fail/false before live work | Return | — | — | Clear unsupported-operation failure/caption | DEGRADED | HIGH |
| APIC-041 | `getHighestTerrainBlock` ×2; `getNearestSurfaceLayer`; `getNearestSurfaceTerrainBlock` ×5 | 8 | Y/Y | A, W; R/E only for a single current owner and bounded scan | W over detached per-chunk snapshots; R captures | CPU/blocking scan; never on G or foreign tick | Complete result on return | Mask callback on W/caller preparation thread | Read-future cancellation | Read/mask failure synchronously propagated | PRESERVED | MEDIUM |
| APIC-042 | `addCaves`; `generate`; `addSchems`; `spawnResource`; `addOre` ×2; `addOres` ×2 | 8 | Y/Y | A, W | W detached generation → R commits | CPU-heavy synchronous preparation; return is staged | Containing extent/session commit | Generator/mask/pattern callbacks on W | Extent/session cancellation | Preparation immediate; commit error once | PRESERVED | MEDIUM |
| APIC-043 | `getBlockDistribution`; `getBlockDistributionWithData`; `countBlocks` ×2; `lazyCopy` | 5 | Y/Y | A, W; detached extent ANY | W over detached snapshots; lazy proxy derives R per access | Synchronous scan or lazy-view construction | Scan return; lazy copy per-access snapshot completion | Mask on W | Read/session cancellation | Read/callback error once | PRESERVED | MEDIUM |
| APIC-044 | `setBlocks(Region,block|Pattern)`; `setBlocks(Set,Pattern)`; `replaceBlocks` ×3; `center` | 7 | Y/Y | A, W | W preparation → per-chunk R commit | CPU-heavy; returned count is staged | Extent/session commit | Pattern/mask on W | Extent/session cancellation | Preparation immediate; terminal failure once with history | PRESERVED | HIGH |
| APIC-045 | `cancel()` | 1 | Y/Y | ANY with exclusive extent ownership | Extent pipeline and pending commit set | Nonblocking request | Terminal state after accepted work settles | — | Entry point itself | Explicit clean cancellation or partial failure | PRESERVED | HIGH |
| APIC-046 | `relight`; `relightBlock`; `relightSky` | 3 | Y/Y | A, W; owner R for one target | W detached NMS relight → R light commit | Return indicates accepted/staged result | Light commit and packet finalizer | W then R | Queue/session cancellation | Failure once at terminal completion | PRESERVED with documented degradation | MEDIUM, NR-02 |
| APIC-047 | `addProcessor`; `addPostProcessor`; `enableHistory`; `disableHistory` | 4 | Y/Y | ANY with exclusive extent ownership | Extent-local pipeline | Nonblocking | Return | Processor later executes on W/read context declared by scope | Session cancellation | Construction/scope error synchronous; processor errors later once | PRESERVED | HIGH |
| APIC-048 | `apply(Region,Filter,boolean)`; `apply(Iterable,Filter)` | 2 | Y/Y | A, W | W over detached snapshots → R if filter produces SET | CPU-heavy synchronous preparation | Return for pure filter; commit for mutations | Filter on W | Extent/session cancellation | Filter/commit failures once | PRESERVED | HIGH |

### EditSession — 159 declarations

Inherited Extent methods use APIC-029–048. This section covers declarations made directly by `EditSession`.

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-049 | `getLimit`, `resetLimit`, `getLimitUsed`, `getLimitLeft`, `getRegionExtent`, `getBypassAll`, `getBypassHistory`, `setExtent`, `getActor` | 9 | Y/Y | ANY with exclusive session ownership | Session-local extent pipeline | Nonblocking | Return | Custom extent later follows relevant operation row | Session cancel | Synchronous state/validation error | PRESERVED | HIGH |
| APIC-050 | `enableStandardMode`; `setReorderMode`; `getReorderMode` | 3 | Y/Y | ANY with exclusive session ownership | Session-local queue mode | Nonblocking under current FAWE semantics | Return | — | Session cancel | Unsupported mode synchronous | PRESERVED | MEDIUM |
| APIC-051 | `getWorld`, `getChangeSet`, `setRawChangeSet`, `getBlockChangeLimit`, `setBlockChangeLimit`, `isQueueEnabled`, `enableQueue`, `disableQueue` | 8 | Y/Y | ANY with exclusive session ownership | Session-local metadata/pipeline | Nonblocking | Return | — | Session cancel | Synchronous | PRESERVED | HIGH |
| APIC-052 | `getMask`, `getSourceMask`, `getAllowedRegions`, `addTransform`, `getTransform`, `setSourceMask`, `addSourceMask`, `setMask` | 8 | Y/Y | ANY with exclusive session ownership | Session-local pipeline | Nonblocking | Return | Mask/transform executes on operation preparation thread | Session cancel | Configuration error synchronous; callback error at operation | PRESERVED | HIGH |
| APIC-053 | `getSurvivalExtent`, `setFastMode`, `setSideEffectApplier`, `getSideEffectApplier`, `disableHistory`, `hasFastMode`, `getBlockBag`, `setBlockBag`, `toString` | 9 | Y/Y | ANY with exclusive session ownership | Session-local state | Nonblocking | Return | — | Session cancel | Existing unsupported operations remain deterministic | PRESERVED | MEDIUM |
| APIC-054 | `popMissingBlocks()` | 1 | Y/Y | E(actor), matching R, A, W | E(actor) for inventory flush/message | Direct on owner; bounded wait A/W | Inventory/message complete on return | E | — | Inventory/actor error propagated once | PRESERVED | MEDIUM |
| APIC-055 | `isBatchingChunks`, `setBatchingChunks`, `isBufferingEnabled`, `disableBuffering`, `isTickingWatchdog`, `setTickingWatchdog`, `getBlockChangeCount` | 7 | Y/Y | ANY with exclusive session ownership | Session-local pipeline | Nonblocking | Return | — | Session cancel | Synchronous | PRESERVED | MEDIUM |
| APIC-056 | `fullySupports3DBiomes`, `isTrackingHistory`, `setTrackingHistory`, `size`, `getMinimumPoint`, `getMaximumPoint`, `setSize` | 7 | Y/Y | ANY with exclusive session ownership | Session/world metadata | Nonblocking | Return | — | Session cancel | Synchronous | PRESERVED | HIGH |
| APIC-057 | `close`; `flushSession`; `flushQueue` | 3 | Y/Y | A, W; R/E only when every accepted commit is current-owner | Fan-out to recorded R keys; E for actor/preloader finalization | Bounded wait only A/W; direct single-owner tick; cross-owner tick fails before commit | All accepted commits, history close, relight, packet/entity/tile finalizers | R/E finalizers; completion on W/caller | Session cancellation; unload/disable yields clean or explicit partial failure | Unchecked/API error exactly once; no `completeBlindly` loss | PRESERVED | HIGH, NR-04 |
| APIC-058 | `getBiome`; `getHighestTerrainBlock` ×2; `getBlockType`; `getBlockDistribution(Region,boolean)`; `getEntities()`; `getEntities(Region)`; `lazyCopy` | 8 | Y/Y | A, W; owner R/E for single-owner point read; detached lazy use ANY | R snapshots → W scan; entity fan-out as APIC-038 | Point reads direct/bounded; scans only A/W | Complete snapshot/scan return; lazy copy per-access | Mask on W; entity capture R/E | Read/session cancellation | Read/callback failure once | PRESERVED | HIGH |
| APIC-059 | `setBiome` ×2; `setBlock(position,block,Stage)`; `rawSetBlock`; `smartSetBlock`; `setBlock(position,block)`; `setBlock(x,y,z,block)`; `setBlock(x,y,z,Pattern)`; `setBlock(position,Pattern)`; `setBlocks` ×3 | 12 | Y/Y | Point calls: owner R/E, A, W; bulk calls: A/W | W/detached SET → R commit | Returned boolean/count is staged except direct owner extent | `close`/`flushQueue` | Pattern on caller/W; never arbitrary commit R | Session cancellation | Preparation immediate; commit/finalizer failure once | PRESERVED | HIGH |
| APIC-060 | `undo(EditSession)`; `setBlocks(ChangeSet,Type)`; `redo(EditSession)` | 3 | Y/Y | A, W; current-owner R only for single-region history | W history replay → R commits | Bounded wait only A/W | Method return after replay flush and matching history state | Change callbacks W; commits R | Session cancellation/partial undo record | Replay/commit failure once | PRESERVED | HIGH |
| APIC-061 | `fall`; `replaceBlocks` ×3; `fillDirection`; `fillXZ` ×2; `removeAbove`; `removeBelow`; `removeNear`; `center` | 11 | Y/Y | A, W | W over detached snapshots → R commits | CPU-heavy; returned count staged | Session commit | Pattern/mask on W | Session cancellation | Preparation and terminal errors once | PRESERVED | HIGH |
| APIC-062 | `makeCuboidFaces` ×2; `makeFaces`; `makeCuboidWalls` ×2; `makeWalls`; `overlayCuboidBlocks` ×2; `naturalizeCuboidBlocks` | 9 | Y/Y | A, W | W → R commits | CPU-heavy; staged count | Session commit | Pattern on W | Session cancellation | Same as APIC-061 | PRESERVED | HIGH |
| APIC-063 | `stackCuboidRegion` ×2; `stackRegionBlockUnits`; `moveRegion` ×3; `moveCuboidRegion` | 7 | Y/Y | A, W | Multi-region W preparation → keyed R commits | CPU-heavy; staged count | All commits/finalizers; not globally atomic | Mask/pattern/entity-copy callbacks W; entity finalizers E/R | Session cancellation with partial history | Per-region failure aggregated once with matching undo | PRESERVED | HIGH |
| APIC-064 | `drainArea` ×3; `fixLiquid` | 4 | Y/Y | A, W | W snapshots/preparation → R commits | CPU-heavy; staged count | Session commit | Mask/pattern on W | Session cancellation | Once at preparation/terminal completion | PRESERVED | MEDIUM |
| APIC-065 | `makeCylinder` ×3; `makeHollowCylinder`; `makeCone`; `makeCircle`; `makeSphere` ×2; `makePyramid` | 9 | Y/Y | A, W | W → R commits | CPU-heavy; staged count | Session commit | Pattern on W | Session cancellation | Once | PRESERVED | HIGH |
| APIC-066 | `thaw` ×2; `simulateSnow` ×3; `green` ×2; `makePumpkinPatches` ×2; `makeForest` ×4 | 13 | Y/Y | A, W | W snapshots/generation → R commits | CPU-heavy; staged count | Session commit | Tree/mask/pattern callbacks W | Session cancellation | Once | PRESERVED | LOW-CONF |
| APIC-067 | `makeShape` ×2; `deformRegion` ×3; `hollowOutRegion`; `makeBiomeShape` ×2 | 8 | Y/Y | A, W | W expression evaluation → R commits | CPU-heavy with configured timeout; staged count | Session commit | Expression/mask/pattern on W | Timeout/session cancellation | Timeout/expression error immediate; commit error once | PRESERVED | MEDIUM |
| APIC-068 | `drawLine` ×3; `drawSpline`; `getStretched`; `getOutline`; `getHollowed` | 7 | Y/Y | Detached set helpers ANY; edit-producing calls A/W | W/detached geometry → R commits | CPU-heavy; staged count or detached set return | Return for detached set; session commit for edits | Pattern on W | Session cancellation | Once | PRESERVED | LOW-CONF |
| APIC-069 | `morph(...)` | 1 | Y/Y | A, W | W snapshot calculation → R commits | CPU-heavy; staged count | Session commit | — | Session cancellation | Once | PRESERVED | LOW-CONF |
| APIC-070 | `regenerate(Region)`; `regenerate(Region,EditSession)`; `regenerate(Region,BiomeType,Long)` | 3 | Y/Y | None on Folia | No certified Folia regeneration context | Immediate failure before history/changeset mutation | Failure return | — | — | Clear unsupported caption/exception; no partial mutation | DEGRADED | HIGH |
| APIC-071 | `createEntity` ×2; `removeEntity` | 3 | Y/Y | A, W; owner R/E for local target | R(location), then E(entity) as needed | Staged/direct | Session commit | Entity finalizer E/R | Session cancellation | Entity failure once | PRESERVED | HIGH |
| APIC-072 | `generate(Region,GenBase)`; `addSchems`; `addOre` | 3 | Y/Y | A, W | W detached generation → R commits | CPU-heavy; staged | Session commit | Generator/mask/pattern W | Session cancellation | Once | PRESERVED | MEDIUM |
| APIC-073 | `makeBlob(...)` | 1 | Y/Y | A, W | W → R commits | CPU-heavy; staged count | Session commit | Pattern W | Session cancellation | Once | PRESERVED | LOW-CONF |
| APIC-074 | `generateFeature`; `generateStructure` | 2 | Y/Y | None until ownership footprint is certified | Origin alone does not prove ownership of full feature/structure footprint | Immediate deterministic failure on Folia | Failure return | — | — | Unsupported caption/exception before capture-state mutation | DEGRADED pending certification | MEDIUM, NR-05 |

### WorldEdit — 41 declarations

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-075 | `getInstance`, `getPlatformManager`, `getEventBus`, `getSupervisor`, `getExecutorService`, six factory/manager/translation getters | 12 | Y/Y | ANY | Singleton/config registries | Nonblocking | Return | Returned executor runs A/W | Executor/Future | Synchronous lookup error | PRESERVED | HIGH |
| APIC-076 | `getSafeSaveFile`; `getSafeOpenFile` | 2 | Y/Y | A, W; actor-dialog branch additionally derives E/G | W filesystem validation; E(actor) for `"#"` dialog | Blocking filesystem work only A/W | Resolved file on return | Dialog callback E/G | Dialog abort | Declared filename/selection exception | PRESERVED | HIGH |
| APIC-077 | `loadMappings()` | 1 | Y/Y | G during bootstrap, or W before consumers | Global registry initialization | Synchronous CPU work | Registries initialized on return | — | — | Initialization failure synchronous | PRESERVED | MEDIUM |
| APIC-078 | six `checkMaxRadius`/`checkMaxBrushRadius` overloads; `checkExtentHeightBounds` | 7 | Y/Y | ANY; actor methods require safe cached actor limit | Detached config/actor metadata | Nonblocking | Return | — | — | Declared/unchecked limit exception | PRESERVED | HIGH |
| APIC-079 | `getWorkingDirectoryFile`; `getWorkingDirectoryPath`; `getSchematicsFolderPath` | 3 | Y/Y | ANY for path construction | Detached configuration | Nonblocking | Return | — | — | Path/config error synchronous | PRESERVED | HIGH |
| APIC-080 | `getDirection`; `getDiagonalDirection` | 2 | Y/Y | Absolute names ANY; relative names E(player), matching R, A, W | E(player) for yaw/cardinal direction | Direct owner or bounded A/W wait | Direction returned | E for relative direction | — | Declared unknown-direction failure | PRESERVED | HIGH |
| APIC-081 | `flushBlockBag` | 1 | Y/Y | E(actor), matching R, A, W | E(player) inventory and messaging | Direct owner or bounded A/W wait | Inventory flush/message complete | E | — | Inventory/actor failure once | PRESERVED | MEDIUM |
| APIC-082 | `handleArmSwing`; `handleRightClick`; `handleBlockRightClick` ×2; `handleBlockLeftClick` ×2 | 6 | Y/Y | E(player) where clicked block is same-owned R; A/W may owner-dispatch | E(player)+R(clicked); mismatched dual ownership fails | Synchronous because cancellation result is returned | Event bus completes on return | E/R event context | Event cancellation | Event callback failure propagated once | PRESERVED | HIGH, NR-06 |
| APIC-083 | `runScript(Player,File,String[])` | 1 | Y/Y | None through the legacy synchronous Folia entry point | Script has no bounded target and may perform arbitrary I/O/world access | Immediate failure before reading/evaluating script | Failure return | Script not invoked | — | Clear unsupported caption/exception | DEGRADED | HIGH |
| APIC-084 | `getConfiguration`; `getEditSessionFactory`; `newEditSessionBuilder`; `newEditSession(World)`; `newEditSession(Locatable Actor)` | 5 | Y/Y | Accessors ANY; actor session construction E/matching R/A/W | Same as APIC-017 | Nonblocking accessor or synchronous builder construction | Builder/session constructed, no edit committed | EditSessionEvent per APIC-017 | — | Construction errors once | PRESERVED | HIGH |
| APIC-085 | `getVersion()` | 1 | Y/Y | ANY | Detached manifest metadata | Lazy synchronous lookup | Return | — | — | Manifest error synchronous | PRESERVED | HIGH |

### SessionManager — 10 declarations

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-086 | `SessionManager(WorldEdit)` | 1 | Y/Y | G during lifecycle bootstrap | G registry/event-bus setup; A timer service | Synchronous construction | Registration and timer setup complete | Timer callback A | `unload()` | Construction error synchronous | PRESERVED | HIGH |
| APIC-087 | `contains`; `findByName`; `getIfPresent` | 3 | Y/Y | ANY | Synchronized detached session map | May contend on monitor but performs no owner wait | Return | — | — | Validation error synchronous | PRESERVED | HIGH |
| APIC-088 | `get(SessionOwner)` | 1 | Y/Y | E(owner) only if session already loaded; A/W for first load | W session-store load; E(player) for permissions/game mode | First-load disk I/O forbidden on tick; A/W may load then owner-hop | Configured LocalSession returned | E for player-derived reads | Store/task cancellation | Existing load failure fallback logged once; no silent owner-access failure | PRESERVED with preloaded-session constraint | MEDIUM, NR-07 |
| APIC-089 | `remove(SessionOwner)` | 1 | Y/Y | ANY | Synchronized session map | Nonblocking aside from monitor contention | Removal on return | — | — | Validation error synchronous | PRESERVED | HIGH |
| APIC-090 | `unload`; `clear` | 2 | Y/Y | G lifecycle, A, W | Session map; A persistence executor | Returns after save work is accepted and map cleared; does not imply disk completion | Async save Future completion | A | Executor shutdown/unload | Persistence failures logged/future-visible once | PRESERVED | HIGH |
| APIC-091 | `onConfigurationLoad`; `onSessionIdle` | 2 | Y/Y | Event publisher’s certified G/E context | G config store update; session-local idle cleanup | Synchronous event handling | Return | Publisher context | Event/lifecycle cancellation | Event failure propagated once | PRESERVED | MEDIUM |

## Disposition counts by source set

| Source set | PRESERVED | DEGRADED | EXCLUDE | Total |
|---|---:|---:|---:|---:|
| FaweAPI | 18 | 0 | 0 | 18 |
| EditSessionBuilder | 44 | 0 | 0 | 44 |
| TaskManager | 23 | 1 | 0 | 24 |
| InputExtent + OutputExtent + Extent declarations | 85 | 1 | 0 | 86 |
| EditSession declarations | 154 | 5 | 0 | 159 |
| WorldEdit | 40 | 1 | 0 | 41 |
| SessionManager | 10 | 0 | 0 | 10 |
| **Total** | **374** | **8** | **0** | **382** |

The eight DEGRADED declarations are:

- `TaskManager.runUnsafe` — forbidden global safety-toggle semantics.
- `Extent.regenerateChunk` and three `EditSession.regenerate` overloads — fail-closed Folia regeneration.
- `EditSession.generateFeature` and `generateStructure` — origin alone does not bound ownership; shared ServerLevel capture state remains uncertified.
- `WorldEdit.runScript` — arbitrary synchronous script/I/O/world access has no deterministic bounded target.

## NEEDS-RUNTIME

1. **NR-01 — platform world registry:** Is `Platform#getWorlds()` safe from every Folia tick thread, or must `FaweAPI.getWorld` be restricted to G/A/W? Exact test: enumerate worlds concurrently from two region threads during world lifecycle activity.

2. **NR-02 — lighting:** Confirm the NMSRelighter fallback meets correctness and performance budgets, including cross-region borders and packet finalization. The API disposition remains PRESERVED; the backend is documented as degraded from Starlight.

3. **NR-03 — scheduler IDs:** Confirm integer IDs remain globally unambiguous when Folia global and async schedulers coexist, or define scheduler-tagged ID allocation behind the unchanged `int` API.

4. **NR-04 — edit terminal completion:** Inject commit, tile, entity, light, packet, unload, and cancellation failures and verify `close`/`flushQueue` returns only after every accepted finalizer or throws one explicit partial-failure with matching history.

5. **NR-05 — feature/structure footprint:** From a chunk near a region boundary, determine every chunk/entity/POI/light state touched by feature and structure generation, and whether capture state is region-local. Until both are proven, APIC-074 remains DEGRADED.

6. **NR-06 — input-event dual ownership:** Verify player input callbacks involving a clicked block always execute where the player entity and clicked block share a legal owner. Otherwise the synchronous boolean cancellation API has no safe cross-owner implementation.

7. **NR-07 — session first load:** Determine whether player sessions are always loaded before first entity-thread `SessionManager.get`. Test cold first access with a persistent store and measure whether any filesystem or permission/game-mode access occurs on the wrong owner.

8. **Regen future-relaxation only:** W0.4 already settles the current disposition as DEGRADED/DISABLED. Its startup-loaded scratch-world experiment may support a future signed relaxation but does not block this table.

## Evidence and priority basis

High-priority rows were selected from repository usage:

- `TaskManager.taskManager().{sync,task,async,later,repeat}`: approximately 140 Java occurrences.
- `WorldEdit.newEditSessionBuilder()`: 32 occurrences.
- `.setBlock(...)`: 273 occurrences; `.setBlocks(...)`: 43.
- `SessionManager.get(...)`: at least 21 direct `getSessionManager().get(...)` occurrences.
- `FaweAPI.fixLighting` is directly used by the PlotSquared integration.

Load-bearing source anchors:

- §4c fields and location-free rule: `.orchestrate/folia-port/spec.md:104`.
- C3 context-carrying sync contract: `.orchestrate/folia-port/architecture.md:63`.
- TaskManager location-free scheduling/sync: `TaskManager.java:47-374`.
- Builder actor/queue/event compilation: `EditSessionBuilder.java:436-692`.
- Edit terminal path: `EditSession.java:1267-1384`.
- Current unsafe regen sync: `EditSession.java:3989-4112`.
- Extent contract surface: `InputExtent.java:36-169`, `OutputExtent.java:42-199`, `Extent.java:96-1209`.
- CraftScript synchronous execution: `WorldEdit.java:811-892`.
- Session cold-load and player-derived reads: `SessionManager.java:142-208`.
- Lighting disposition: `spikes/w03-lighting.md`.
- Regen disable decision: `spikes/w04-regen.md`.
- Physics/packet and `runUnsafe` evidence: `spikes/w05-physics-packets.md`.

## Deviations

1. The table groups overloads with identical contracts into one APIC row, but the count column and entry-point cell enumerate every declaration. Counts are per source declaration, so `OutputExtent.commit` and its `Extent` override are both counted.

2. Per the task’s escape hatch, the audit does not silently expand into the 255 declarations on 24 concrete/derived Extent types. Overrides inherit the interface disposition; implementation-specific public methods remain an explicit coverage gap requiring supported/internal classification.

3. Architecture C3 requires `QueueHandler.sync(...)`, but task 06’s named source list omits `QueueHandler`. Its 10 public async/sync methods remain a separate contract gap.

4. The compatibility inventory points to four UUID-keyed clipboard/history executor methods on `Fawe` (`getClipboardExecutor`, `submitUUIDKeyQueuedTask` ×3), while task 06 and the inventory label call this “FaweAPI,” and the inventory’s `FaweBukkit.java:475-521` anchor is stale. These four declarations are not included in the 382 count.

5. Usage prioritization used this repository as the requested proxy. No external PlotSquared consumer source was included.

## Attack points

1. **Opaque TaskManager callbacks are the central misuse hazard.** Global routing is deterministic only for genuinely global-safe callbacks. Existing internal calls frequently hide a player, world, or chunk inside a lambda. Those calls must migrate to context-carrying variants; leaving them on APIC-020/022/027 would turn a deterministic contract into late Folia ownership exceptions.

2. **A no-op `runUnsafe` is not an acceptable disposition.** W0.5 suggests a Folia no-op, but the method promises unsafe/global suppression semantics that C1 forbids. Silently running the callback without those semantics is neither PRESERVED nor deterministic DEGRADED behavior. APIC-028 therefore fails before callback invocation.

3. **Staged counts are not success counts.** Most EditSession boolean/int results describe detached preparation. Consumer tests must prove plugins do not treat them as committed state before `close`/`flushQueue`.

4. **Current terminal error handling violates the proposed contract.** `Operations.completeBlindly`, `FaweAPI.fixLighting`’s catch-and-log, lighting’s catch-and-print, `cancelEdit`’s swallowed exception, and TaskManager’s interrupt/error swallowing can lose or duplicate failures. The Folia path must report exactly once.

5. **Raw Extent escape hatches can bypass the routing model.** `getBypassAll`, `getBypassHistory`, `getExtent`, `setExtent`, processors, patterns, masks, and EditSessionEvent replacement extents expose plugin-defined code. Interface routing protects only calls made through the contract; a custom extent retaining live Bukkit/NMS objects violates C2.

6. **`lazyCopy` is only safe as a routing proxy.** The current `WorldCopyClipboard` retains its source extent. It must not hand live collections or an unguarded live extent to workers; each lazy read must become a region-captured detached snapshot.

7. **Tick-thread lifecycle calls are a deadlock boundary.** Multi-region `close`, `flushQueue`, undo/redo, cold `SessionManager.get`, file selection, and scripts cannot wait from R/E/G. Tests must include wrong-owner and multi-owner calls and prove rejection occurs before partial work.

8. **Entity plus block ownership can require two schedulers.** Input events, entity removal, block-bag operations, packet sends, and neighbor effects are not always owned solely by the target chunk. The contract must validate same-owner cases or split finalizers without introducing region → worker waits.

9. **Feature/structure generation cannot route solely from its origin.** Vanilla generation can cross chunk/region boundaries, and current adapter code mutates shared `ServerLevel.captureBlockStates` state. APIC-074 must remain fail-closed until footprint and capture isolation are proven.

10. **SessionManager serializes unrelated regions.** Its synchronized map is correct at the Java level but cold loading and owner callbacks currently occur while holding the monitor. Under concurrent region access this is both a contention and lock-order attack surface.

Status: DONE_WITH_CONCERNS