# W0.6b — APIC gap closure

## Contract conventions

- Contexts retain W0.6 meanings: `R`, `E`, `G`, `A`, `W`, and `ANY`.
- `SUPPORTED`, `INTERNAL`, and `OVERRIDE` classify implementation-specific declarations. `INTERNAL` declarations retain ABI but are not consumer-facing scheduling seams.
- An `OVERRIDE → APIC-nnn` row inherits all §4c semantics from that exact interface row.
- QueueHandler’s location-free synchronous methods derive the current `R`, `E`, or `G` context when called on a tick thread; otherwise they derive `G`. They accept only current-context-safe or global-safe callbacks. Internal location-bearing calls must migrate to context-carrying scheduling.
- The earlier “~255” Extent estimate resolves to **257 source declarations**. Compiled inspection exposes 259 members because `NullExtent()` is compiler-generated and `ForgetfulExtentBuffer.fork():Filter` is a synthetic bridge; neither is a source declaration.

## QueueHandler — 10 declarations

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-092 | `async(Runnable,Object):Future`; JVM `(Runnable,Object)Future` | 1 | Y/Y | ANY; callback must not touch live state | W secondary pool | Nonblocking submission; returns `Future<T>` | Callback termination | W | Returned Future | Submission failure synchronous; callback failure through Future once | PRESERVED with deterministic context derivation | HIGH |
| APIC-093 | `async(Runnable):Future`; JVM `(Runnable)Future` | 1 | Y/Y | ANY; callback must not touch live state | W secondary pool | Nonblocking submission | Callback termination | W | Returned Future | Submission failure synchronous; callback failure through Future once | PRESERVED with deterministic context derivation | HIGH |
| APIC-094 | `async(Callable):Future`; JVM `(Callable)Future` | 1 | Y/Y | ANY; callback must not touch live state | W secondary pool | Nonblocking submission | Callable termination | W | Returned Future | Submission failure synchronous; callable failure through Future once | PRESERVED with deterministic context derivation | HIGH |
| APIC-095 | `sync(Runnable):Future`; JVM `(Runnable)Future` | 1 | Y/Y | ANY for current-context/global-safe callback | Current R/E/G; otherwise G | Inline on derived tick context; otherwise nonblocking submission | Callback termination | Derived R/E/G | Returned Future when queued | Inline failure synchronous; queued failure through Future once | PRESERVED with deterministic context derivation | HIGH |
| APIC-096 | `sync(Callable):Future throws Exception`; JVM `(Callable)Future` | 1 | Y/Y | Same as APIC-095 | Current R/E/G; otherwise G | Inline or nonblocking submission | Callable termination | Derived R/E/G | Returned Future when queued | Inline failure synchronous; queued failure through Future once | PRESERVED with deterministic context derivation | HIGH |
| APIC-097 | `sync(Supplier):Future`; JVM `(Supplier)Future` | 1 | Y/Y | Same as APIC-095 | Current R/E/G; otherwise G | Inline or nonblocking submission | Supplier termination | Derived R/E/G | Returned Future when queued | Inline failure synchronous; queued failure through Future once | PRESERVED with deterministic context derivation | HIGH |
| APIC-098 | `syncWhenFree(Runnable,Object):Future`; JVM `(Runnable,Object)Future` | 1 | Y/Y | ANY for current-context/global-safe callback | Same derived target as APIC-095; lower priority within that target | Inline when eligible; otherwise nonblocking submission | Callback termination | Derived R/E/G | Returned Future | Failure reported once through inline call or Future | PRESERVED with deterministic context derivation | HIGH |
| APIC-099 | `syncWhenFree(Runnable):Future`; JVM `(Runnable)Future` | 1 | Y/Y | Same as APIC-098 | Same as APIC-098 | Inline when eligible; otherwise nonblocking submission | Callback termination | Derived R/E/G | Returned Future | Same as APIC-098 | PRESERVED with deterministic context derivation | HIGH |
| APIC-100 | `syncWhenFree(Callable):Future throws Exception`; JVM `(Callable)Future` | 1 | Y/Y | Same as APIC-098 | Same as APIC-098 | Inline when eligible; otherwise nonblocking submission | Callable termination | Derived R/E/G | Returned Future | Same as APIC-098 | PRESERVED with deterministic context derivation | HIGH |
| APIC-101 | `syncWhenFree(Supplier):Future`; JVM `(Supplier)Future` | 1 | Y/Y | Same as APIC-098 | Same as APIC-098 | Inline when eligible; otherwise nonblocking submission | Supplier termination | Derived R/E/G | Returned Future | Same as APIC-098 | PRESERVED with deterministic context derivation | HIGH |

## Extent-derived declarations — 257 declarations across 24 types

### AbstractBufferingExtent — 3

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-102 | `setBlock(BlockVector3,T)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-103 | `getBlock(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-104 | `getFullBlock(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |

### AbstractDelegateExtent — 54

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-105 | `extent` field — INTERNAL | 1 | Y/Y | ANY with exclusive wrapper ownership | Delegate reference | Direct field access | Read/write | — | — | Java field-access semantics; invalid replacement fails on later use | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-106 | `AbstractDelegateExtent(Extent)` — SUPPORTED | 1 | Y/Y | ANY | Detached wrapper construction | Nonblocking | Constructor return | — | — | Validation/construction failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-107 | `getExtent()` — SUPPORTED | 1 | Y/Y | ANY subject to safe publication | Delegate reference | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-108 | `getBlock(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-109 | `getBlock(int,int,int)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-110 | `getFullBlock(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-111 | `getFullBlock(int,int,int)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-112 | `getMinimumPoint()` — OVERRIDE → APIC-037 | 1 | Y/Y | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | PRESERVED with deterministic context derivation | HIGH |
| APIC-113 | `getMaximumPoint()` — OVERRIDE → APIC-037 | 1 | Y/Y | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | PRESERVED with deterministic context derivation | HIGH |
| APIC-114 | `getEntities(Region)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-115 | `getEntities()` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-116 | `createEntity(Location,BaseEntity)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-117 | `createEntity(Location,BaseEntity,UUID)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-118 | `commit()` — OVERRIDE → APIC-036 | 1 | Y/Y | APIC-036 | APIC-036 | APIC-036 | APIC-036 | APIC-036 | APIC-036 | APIC-036 | PRESERVED with deterministic context derivation | HIGH |
| APIC-119 | `cancel()` — OVERRIDE → APIC-045 | 1 | Y/Y | APIC-045 | APIC-045 | APIC-045 | APIC-045 | APIC-045 | APIC-045 | APIC-045 | PRESERVED with deterministic context derivation | HIGH |
| APIC-120 | `removeEntity(int,int,int,UUID)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-121 | `isQueueEnabled()` — OVERRIDE → APIC-039 | 1 | Y/Y | APIC-039 | APIC-039 | APIC-039 | APIC-039 | APIC-039 | APIC-039 | APIC-039 | PRESERVED with deterministic context derivation | HIGH |
| APIC-122 | `enableQueue()` — OVERRIDE → APIC-039 | 1 | Y/Y | APIC-039 | APIC-039 | APIC-039 | APIC-039 | APIC-039 | APIC-039 | APIC-039 | PRESERVED with deterministic context derivation | HIGH |
| APIC-123 | `disableQueue()` — OVERRIDE → APIC-039 | 1 | Y/Y | APIC-039 | APIC-039 | APIC-039 | APIC-039 | APIC-039 | APIC-039 | APIC-039 | PRESERVED with deterministic context derivation | HIGH |
| APIC-124 | `isWorld()` — OVERRIDE → APIC-037 | 1 | Y/Y | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | PRESERVED with deterministic context derivation | HIGH |
| APIC-125 | `getBlockDistribution(Region)` — OVERRIDE → APIC-043 | 1 | Y/Y | APIC-043 | APIC-043 | APIC-043 | APIC-043 | APIC-043 | APIC-043 | APIC-043 | PRESERVED with deterministic context derivation | HIGH |
| APIC-126 | `getBlockDistributionWithData(Region)` — OVERRIDE → APIC-043 | 1 | Y/Y | APIC-043 | APIC-043 | APIC-043 | APIC-043 | APIC-043 | APIC-043 | APIC-043 | PRESERVED with deterministic context derivation | HIGH |
| APIC-127 | `getMaxY()` — OVERRIDE → APIC-037 | 1 | Y/Y | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | PRESERVED with deterministic context derivation | HIGH |
| APIC-128 | `countBlocks(Region,Set)` — OVERRIDE → APIC-043 | 1 | Y/Y | APIC-043 | APIC-043 | APIC-043 | APIC-043 | APIC-043 | APIC-043 | APIC-043 | PRESERVED with deterministic context derivation | HIGH |
| APIC-129 | `countBlocks(Region,Mask)` — OVERRIDE → APIC-043 | 1 | Y/Y | APIC-043 | APIC-043 | APIC-043 | APIC-043 | APIC-043 | APIC-043 | APIC-043 | PRESERVED with deterministic context derivation | HIGH |
| APIC-130 | `setBlocks(Region,BlockStateHolder)` — OVERRIDE → APIC-044 | 1 | Y/Y | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | PRESERVED with deterministic context derivation | HIGH |
| APIC-131 | `setBlocks(Region,Pattern)` — OVERRIDE → APIC-044 | 1 | Y/Y | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | PRESERVED with deterministic context derivation | HIGH |
| APIC-132 | `replaceBlocks(Region,Set,BlockStateHolder)` — OVERRIDE → APIC-044 | 1 | Y/Y | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | PRESERVED with deterministic context derivation | HIGH |
| APIC-133 | `replaceBlocks(Region,Set,Pattern)` — OVERRIDE → APIC-044 | 1 | Y/Y | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | PRESERVED with deterministic context derivation | HIGH |
| APIC-134 | `replaceBlocks(Region,Mask,Pattern)` — OVERRIDE → APIC-044 | 1 | Y/Y | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | PRESERVED with deterministic context derivation | HIGH |
| APIC-135 | `setBlocks(Set,Pattern)` — OVERRIDE → APIC-044 | 1 | Y/Y | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | APIC-044 | PRESERVED with deterministic context derivation | HIGH |
| APIC-136 | `getMinY()` — OVERRIDE → APIC-037 | 1 | Y/Y | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | PRESERVED with deterministic context derivation | HIGH |
| APIC-137 | `relight(int,int,int)` — OVERRIDE → APIC-046 | 1 | Y/Y | APIC-046 | APIC-046 | APIC-046 | APIC-046 | APIC-046 | APIC-046 | APIC-046 | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-138 | `relightBlock(int,int,int)` — OVERRIDE → APIC-046 | 1 | Y/Y | APIC-046 | APIC-046 | APIC-046 | APIC-046 | APIC-046 | APIC-046 | APIC-046 | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-139 | `relightSky(int,int,int)` — OVERRIDE → APIC-046 | 1 | Y/Y | APIC-046 | APIC-046 | APIC-046 | APIC-046 | APIC-046 | APIC-046 | APIC-046 | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-140 | `addProcessor(IBatchProcessor)` — OVERRIDE → APIC-047 | 1 | Y/Y | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | PRESERVED with deterministic context derivation | HIGH |
| APIC-141 | `addPostProcessor(IBatchProcessor)` — OVERRIDE → APIC-047 | 1 | Y/Y | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | PRESERVED with deterministic context derivation | HIGH |
| APIC-142 | `disableHistory()` — OVERRIDE → APIC-047 | 1 | Y/Y | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | PRESERVED with deterministic context derivation | HIGH |
| APIC-143 | `apply(Region,Filter,boolean)` — OVERRIDE → APIC-048 | 1 | Y/Y | APIC-048 | APIC-048 | APIC-048 | APIC-048 | APIC-048 | APIC-048 | APIC-048 | PRESERVED with deterministic context derivation | HIGH |
| APIC-144 | `getBiome(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-145 | `getBiomeType(int,int,int)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-146 | `getEmittedLight(int,int,int)` — OVERRIDE → APIC-030 | 1 | Y/Y | APIC-030 | APIC-030 | APIC-030 | APIC-030 | APIC-030 | APIC-030 | APIC-030 | PRESERVED with deterministic context derivation | HIGH |
| APIC-147 | `getSkyLight(int,int,int)` — OVERRIDE → APIC-030 | 1 | Y/Y | APIC-030 | APIC-030 | APIC-030 | APIC-030 | APIC-030 | APIC-030 | APIC-030 | PRESERVED with deterministic context derivation | HIGH |
| APIC-148 | `getBrightness(int,int,int)` — OVERRIDE → APIC-030 | 1 | Y/Y | APIC-030 | APIC-030 | APIC-030 | APIC-030 | APIC-030 | APIC-030 | APIC-030 | PRESERVED with deterministic context derivation | HIGH |
| APIC-149 | `setChangeSet(AbstractChangeSet)` — INTERNAL | 1 | Y/Y | ANY with exclusive pipeline ownership | Extent/history chain | Nonblocking chain update | Return; later history completion follows session | Later history callbacks on W | Session cancellation | Rewire failure synchronous; later history failure once at terminal completion | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-150 | `setBlock(BlockVector3,T)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-151 | `setBlock(int,int,int,T)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-152 | `tile(int,int,int,FaweCompoundTag)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-153 | `fullySupports3DBiomes()` — OVERRIDE → APIC-034 | 1 | Y/Y | APIC-034 | APIC-034 | APIC-034 | APIC-034 | APIC-034 | APIC-034 | APIC-034 | PRESERVED with deterministic context derivation | HIGH |
| APIC-154 | `setBiome(BlockVector3,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-155 | `setBiome(int,int,int,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-156 | `setBlockLight(int,int,int,int)` — OVERRIDE → APIC-033 | 1 | Y/Y | APIC-033 | APIC-033 | APIC-033 | APIC-033 | APIC-033 | APIC-033 | APIC-033 | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-157 | `setSkyLight(int,int,int,int)` — OVERRIDE → APIC-033 | 1 | Y/Y | APIC-033 | APIC-033 | APIC-033 | APIC-033 | APIC-033 | APIC-033 | APIC-033 | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-158 | `toString()` — SUPPORTED | 1 | Y/Y | ANY subject to delegate concurrency | Delegate metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |

### ChangeSetExtent — 10

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-159 | `ChangeSetExtent(Extent,ChangeSet)` — SUPPORTED | 1 | Y/Y | ANY | Detached pipeline wrapper | Nonblocking | Constructor return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-160 | `ChangeSetExtent(Extent,ChangeSet,boolean)` — SUPPORTED | 1 | Y/Y | ANY | Detached pipeline wrapper | Nonblocking | Constructor return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-161 | `isEnabled()` — SUPPORTED | 1 | Y/Y | ANY with safe publication | Wrapper-local flag | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-162 | `setEnabled(boolean)` — SUPPORTED | 1 | Y/Y | ANY with exclusive pipeline ownership | Wrapper-local flag | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-163 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-164 | `setBiome(BlockVector3,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-165 | `createEntity(Location,BaseEntity)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-166 | `createEntity(Location,BaseEntity,UUID)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-167 | `getEntities()` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-168 | `getEntities(Region)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |

### MaskingExtent — 11

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-169 | `MaskingExtent(Extent,Mask)` — SUPPORTED | 1 | Y/Y | ANY | Detached wrapper | Nonblocking | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-170 | `getMask()` — SUPPORTED | 1 | Y/Y | ANY with safe publication | Wrapper-local mask | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-171 | `setMask(Mask)` — SUPPORTED | 1 | Y/Y | ANY with exclusive pipeline ownership | Wrapper-local mask | Nonblocking | Return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-172 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-173 | `setBiome(BlockVector3,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-174 | `setBiome(int,int,int,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-175 | `processSet(IChunk,IChunkGet,IChunkSet)` — INTERNAL | 1 | Y/Y | W | Detached chunk pipeline | Synchronous processing | Returned SET | W | Operation/session cancellation | Processor/mask failure propagated once | PRESERVED with deterministic context derivation | HIGH |
| APIC-176 | `applyBlock(FilterBlock)` — INTERNAL | 1 | Y/Y | W | Detached filter block | Nonblocking per block | Return | W | Parent operation cancellation | Mask failure propagated once | PRESERVED with deterministic context derivation | HIGH |
| APIC-177 | `construct(Extent)` — INTERNAL | 1 | Y/Y | W or pipeline-construction caller | Detached extent chain | Nonblocking | Return | — | — | Construction failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-178 | `fork():Filter` — INTERNAL | 1 | Y/Y | W | Detached processor copy | Nonblocking | Return | — | — | Copy failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-179 | `getScope()` — INTERNAL | 1 | Y/Y | ANY | Immutable processor metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |

### NullExtent — 19

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-180 | `INSTANCE` field — SUPPORTED | 1 | Y/Y | ANY | Detached singleton | Direct read | Read | — | — | Initialization failure at class initialization | PRESERVED with deterministic context derivation | HIGH |
| APIC-181 | `getMinimumPoint()` — OVERRIDE → APIC-037 | 1 | Y/Y | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | PRESERVED with deterministic context derivation | HIGH |
| APIC-182 | `getMaximumPoint()` — OVERRIDE → APIC-037 | 1 | Y/Y | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | PRESERVED with deterministic context derivation | HIGH |
| APIC-183 | `getEntities(Region)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-184 | `getEntities()` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-185 | `createEntity(Location,BaseEntity)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-186 | `createEntity(Location,BaseEntity,UUID)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-187 | `getBlock(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-188 | `getFullBlock(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-189 | `getBiome(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-190 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-191 | `fullySupports3DBiomes()` — OVERRIDE → APIC-034 | 1 | Y/Y | APIC-034 | APIC-034 | APIC-034 | APIC-034 | APIC-034 | APIC-034 | APIC-034 | PRESERVED with deterministic context derivation | HIGH |
| APIC-192 | `tile(int,int,int,FaweCompoundTag)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-193 | `setBiome(BlockVector3,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-194 | `setBiome(int,int,int,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-195 | `setBlock(int,int,int,T)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-196 | `addProcessor(IBatchProcessor)` — OVERRIDE → APIC-047 | 1 | Y/Y | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | PRESERVED with deterministic context derivation | HIGH |
| APIC-197 | `addPostProcessor(IBatchProcessor)` — OVERRIDE → APIC-047 | 1 | Y/Y | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | APIC-047 | PRESERVED with deterministic context derivation | HIGH |
| APIC-198 | `commit()` — OVERRIDE → APIC-036 | 1 | Y/Y | APIC-036 | APIC-036 | APIC-036 | APIC-036 | APIC-036 | APIC-036 | APIC-036 | PRESERVED with deterministic context derivation | HIGH |

### TracingExtent — 9

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-199 | `TracingExtent(Extent)` — SUPPORTED | 1 | Y/Y | ANY | Detached wrapper | Nonblocking | Constructor return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-200 | `isActive()` — SUPPORTED | 1 | Y/Y | ANY with safe publication | Trace-local state | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-201 | `getTouchedLocations()` — SUPPORTED | 1 | Y/Y | Pipeline owner or after completion | Trace-local collection | Nonblocking snapshot/view return | Return | — | Session cancellation fixes final contents | Synchronous access error | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-202 | `getFailedActions()` — SUPPORTED | 1 | Y/Y | Pipeline owner or after completion | Trace-local collection | Nonblocking snapshot/view return | Return | — | Session cancellation fixes final contents | Synchronous access error | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-203 | `setBlock(BlockVector3,T)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-204 | `setBiome(BlockVector3,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-205 | `createEntity(Location,BaseEntity)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-206 | `createEntity(Location,BaseEntity,UUID)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-207 | `toString()` — SUPPORTED | 1 | Y/Y | ANY subject to trace-state concurrency | Trace metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |

### ExtentBuffer — 3

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-208 | `ExtentBuffer(Extent)` — SUPPORTED | 1 | Y/Y | ANY | Detached buffer wrapper | Nonblocking | Constructor return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-209 | `ExtentBuffer(Extent,Mask)` — SUPPORTED | 1 | Y/Y | ANY | Detached buffer wrapper | Nonblocking | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-210 | `setBlock(BlockVector3,T)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |

### ForgetfulExtentBuffer — 10

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-211 | `ForgetfulExtentBuffer(Extent)` — SUPPORTED | 1 | Y/Y | ANY | Detached buffer wrapper | Nonblocking | Constructor return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-212 | `isQueueEnabled()` — OVERRIDE → APIC-039 | 1 | Y/Y | APIC-039 | APIC-039 | APIC-039 | APIC-039 | APIC-039 | APIC-039 | APIC-039 | PRESERVED with deterministic context derivation | HIGH |
| APIC-213 | `ForgetfulExtentBuffer(Extent,Mask)` — SUPPORTED | 1 | Y/Y | ANY | Detached buffer wrapper | Nonblocking | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-214 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-215 | `setBiome(BlockVector3,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-216 | `setBiome(int,int,int,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-217 | `applyBlock(BlockVector3)` — SUPPORTED Pattern method | 1 | Y/Y | ANY with buffer ownership | Detached buffer | Nonblocking lookup | Return | Caller | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-218 | `applyBiome(BlockVector3)` — SUPPORTED Pattern method | 1 | Y/Y | ANY with buffer ownership | Detached buffer | Nonblocking lookup | Return | Caller | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-219 | `asRegion()` — SUPPORTED | 1 | Y/Y | ANY with buffer ownership | Detached buffer keys | Nonblocking view construction | Return | Iterator later runs on consumer thread | — | Iteration/access error synchronous | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-220 | `fork():Pattern` — SUPPORTED | 1 | Y/Y | ANY | Detached pattern copy | Nonblocking | Return | — | — | Copy failure synchronous | PRESERVED with deterministic context derivation | HIGH |

### LastAccessExtentCache — 4

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-221 | `LastAccessExtentCache(Extent)` — SUPPORTED | 1 | Y/Y | ANY | Detached wrapper construction | Nonblocking | Constructor return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-222 | `getBlock(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-223 | `getFullBlock(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-224 | `setBlock(BlockVector3,T)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |

### BlockArrayClipboard — 35

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-225 | `BlockArrayClipboard(Region)` — SUPPORTED | 1 | Y/Y | A, W if backing may be disk; otherwise ANY | Detached clipboard storage | Synchronous construction | Constructor return | Caller | — | Allocation/I/O failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-226 | `BlockArrayClipboard(SimpleClipboard,BlockVector3)` — INTERNAL | 1 | Y/Y | A, W if disk-backed; otherwise ANY | Supplied detached storage | Synchronous construction | Constructor return | Caller | — | Validation/I/O failure synchronous | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-227 | `BlockArrayClipboard(Region,UUID)` — SUPPORTED | 1 | Y/Y | A, W if disk-backed; otherwise ANY | UUID-selected detached storage | Synchronous construction | Constructor return | Caller | — | Allocation/I/O failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-228 | `BlockArrayClipboard(Region,SimpleClipboard)` — INTERNAL | 1 | Y/Y | A, W if disk-backed; otherwise ANY | Supplied detached storage | Synchronous construction | Constructor return | Caller | — | Validation/I/O failure synchronous | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-229 | `getRegion()` — OVERRIDE → APIC-262 | 1 | Y/Y | APIC-262 | APIC-262 | APIC-262 | APIC-262 | APIC-262 | APIC-262 | APIC-262 | PRESERVED with deterministic context derivation | HIGH |
| APIC-230 | `getOrigin()` — OVERRIDE → APIC-264 | 1 | Y/Y | APIC-264 | APIC-264 | APIC-264 | APIC-264 | APIC-264 | APIC-264 | APIC-264 | PRESERVED with deterministic context derivation | HIGH |
| APIC-231 | `setOrigin(BlockVector3)` — OVERRIDE → APIC-265 | 1 | Y/Y | APIC-265 | APIC-265 | APIC-265 | APIC-265 | APIC-265 | APIC-265 | APIC-265 | PRESERVED with deterministic context derivation | HIGH |
| APIC-232 | `getMinimumPoint()` — OVERRIDE → APIC-037 | 1 | Y/Y | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | PRESERVED with deterministic context derivation | HIGH |
| APIC-233 | `getMaximumPoint()` — OVERRIDE → APIC-037 | 1 | Y/Y | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | APIC-037 | PRESERVED with deterministic context derivation | HIGH |
| APIC-234 | `getBlock(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-235 | `getFullBlock(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-236 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-237 | `tile(int,int,int,FaweCompoundTag)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-238 | `setTile(BlockVector3,CompoundTag)` — SUPPORTED legacy shim | 1 | Y/Y | ANY with clipboard ownership | Detached clipboard coordinate | Nonblocking/staged storage write | Return or clipboard flush for disk durability | Caller | Clipboard lifecycle | Validation/storage failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-239 | `setBlock(int,int,int,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-240 | `hasBiomes()` — OVERRIDE → APIC-266 | 1 | Y/Y | APIC-266 | APIC-266 | APIC-266 | APIC-266 | APIC-266 | APIC-266 | APIC-266 | PRESERVED with deterministic context derivation | HIGH |
| APIC-241 | `getBiome(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-242 | `setBiome(BlockVector3,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-243 | `setBiome(int,int,int,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-244 | `getEntities(Region)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-245 | `getEntities()` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-246 | `createEntity(Location,BaseEntity)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-247 | `createEntity(Location,BaseEntity,UUID)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-248 | `removeEntity(int,int,int,UUID)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 | PRESERVED with deterministic context derivation | HIGH |
| APIC-249 | `getBlock(int,int,int)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-250 | `getFullBlock(int,int,int)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-251 | `getBiomeType(int,int,int)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | APIC-029 | PRESERVED with deterministic context derivation | HIGH |
| APIC-252 | `iterator()` — OVERRIDE → APIC-275 | 1 | Y/Y | APIC-275 | APIC-275 | APIC-275 | APIC-275 | APIC-275 | APIC-275 | APIC-275 | PRESERVED with deterministic context derivation | HIGH |
| APIC-253 | `iterator2d()` — OVERRIDE → APIC-276 | 1 | Y/Y | APIC-276 | APIC-276 | APIC-276 | APIC-276 | APIC-276 | APIC-276 | APIC-276 | PRESERVED with deterministic context derivation | HIGH |
| APIC-254 | `iterator(Order)` — OVERRIDE → APIC-274 | 1 | Y/Y | APIC-274 | APIC-274 | APIC-274 | APIC-274 | APIC-274 | APIC-274 | APIC-274 | PRESERVED with deterministic context derivation | HIGH |
| APIC-255 | `getDimensions()` — OVERRIDE → APIC-263 | 1 | Y/Y | APIC-263 | APIC-263 | APIC-263 | APIC-263 | APIC-263 | APIC-263 | APIC-263 | PRESERVED with deterministic context derivation | HIGH |
| APIC-256 | `removeEntity(Entity)` — OVERRIDE → APIC-268 | 1 | Y/Y | APIC-268 | APIC-268 | APIC-268 | APIC-268 | APIC-268 | APIC-268 | APIC-268 | PRESERVED with deterministic context derivation | HIGH |
| APIC-257 | `getParent()` — INTERNAL | 1 | Y/Y | ANY subject to clipboard ownership | Backing clipboard reference | Nonblocking | Return | — | Clipboard lifecycle | Synchronous | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-258 | `close()` — OVERRIDE → APIC-279 | 1 | Y/Y | APIC-279 | APIC-279 | APIC-279 | APIC-279 | APIC-279 | APIC-279 | APIC-279 | PRESERVED with deterministic context derivation | HIGH |
| APIC-259 | `flush()` — OVERRIDE → APIC-280 | 1 | Y/Y | APIC-280 | APIC-280 | APIC-280 | APIC-280 | APIC-280 | APIC-280 | APIC-280 | PRESERVED with deterministic context derivation | HIGH |

### Clipboard — 29

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-260 | `create(Region)` — INTERNAL, deprecated | 1 | Y/Y | A, W; owner context only for a wholly local live region | Builder/session derived from region world | Synchronous construction | Clipboard/session constructed | W or current owner | Session cancellation | Construction/read failure synchronous | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-261 | `create(Region,UUID)` — INTERNAL, deprecated | 1 | Y/Y | A, W if disk-backed; otherwise ANY | UUID-selected detached clipboard storage | Synchronous construction | Return | Caller | Clipboard lifecycle | Allocation/I/O failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-262 | `getRegion()` — SUPPORTED | 1 | Y/Y | ANY with clipboard ownership | Detached clipboard metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-263 | `getDimensions()` — SUPPORTED | 1 | Y/Y | ANY with clipboard ownership | Detached clipboard metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-264 | `getOrigin()` — SUPPORTED | 1 | Y/Y | ANY with clipboard ownership | Detached clipboard metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-265 | `setOrigin(BlockVector3)` — SUPPORTED | 1 | Y/Y | ANY with exclusive clipboard ownership | Detached clipboard metadata | Nonblocking | Return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-266 | `hasBiomes()` — SUPPORTED | 1 | Y/Y | ANY | Detached capability metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-267 | `transform(Transform)` — SUPPORTED | 1 | Y/Y | A, W; detached small clipboards may use ANY non-tick caller | Detached clipboard transformation | CPU/blocking copy | Transformed clipboard returned | Caller/W; custom transform on same thread | Caller interruption/operation cancellation | Transform/copy failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-268 | `removeEntity(Entity)` — SUPPORTED | 1 | Y/Y | ANY with exclusive clipboard ownership | Detached clipboard entity list | Nonblocking | Return | — | Clipboard lifecycle | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-269 | `getWidth()` — SUPPORTED | 1 | Y/Y | ANY | Detached metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-270 | `getHeight()` — SUPPORTED | 1 | Y/Y | ANY | Detached metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-271 | `getLength()` — SUPPORTED | 1 | Y/Y | ANY | Detached metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-272 | `getArea()` — SUPPORTED | 1 | Y/Y | ANY | Detached metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-273 | `getVolume()` — SUPPORTED | 1 | Y/Y | ANY | Detached metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-274 | `iterator(Order)` — SUPPORTED | 1 | Y/Y | ANY with stable clipboard ownership | Detached clipboard region | Nonblocking iterator creation | Iteration completion | Consumer thread | Consumer stops iteration | Access failure on consumer thread | PRESERVED with deterministic context derivation | HIGH |
| APIC-275 | `iterator()` — SUPPORTED | 1 | Y/Y | Same as APIC-274 | Detached clipboard region | Nonblocking iterator creation | Iteration completion | Consumer thread | Consumer stops iteration | Access failure on consumer thread | PRESERVED with deterministic context derivation | HIGH |
| APIC-276 | `iterator2d()` — SUPPORTED | 1 | Y/Y | Same as APIC-274 | Detached clipboard region | Nonblocking iterator creation | Iteration completion | Consumer thread | Consumer stops iteration | Access failure on consumer thread | PRESERVED with deterministic context derivation | HIGH |
| APIC-277 | `getURI()` — SUPPORTED | 1 | Y/Y | ANY | Detached backing metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-278 | `apply(Region,Filter,boolean)` — OVERRIDE → APIC-048 | 1 | Y/Y | APIC-048 | APIC-048 | APIC-048 | APIC-048 | APIC-048 | APIC-048 | APIC-048 | PRESERVED with deterministic context derivation | HIGH |
| APIC-279 | `close()` — SUPPORTED | 1 | Y/Y | A, W if disk-backed; otherwise ANY | Clipboard resource | May block on resource close | Resource closed | Caller | Interruption/resource lifecycle | Close failure propagated once | PRESERVED with deterministic context derivation | HIGH |
| APIC-280 | `flush()` — SUPPORTED | 1 | Y/Y | A, W | Detached/disk clipboard storage | Blocking persistence | Durable flush completed | Caller/W | Interruption/clipboard close | I/O failure propagated synchronously | PRESERVED with deterministic context derivation | HIGH |
| APIC-281 | `paste(World,BlockVector3)` — SUPPORTED | 1 | Y/Y | A, W | W preparation → keyed R commits | Synchronous legacy wrapper; no tick-thread wait | Returned session has flushed/closed paste operation | Mask/transform callbacks W; commits R | EditSession cancellation | Preparation/commit/finalizer failure once | PRESERVED with deterministic context derivation | HIGH |
| APIC-282 | `save(File,ClipboardFormat)` — SUPPORTED | 1 | Y/Y | A, W | Detached filesystem I/O | Blocking | File fully written | Caller/W | Interruption/I/O cancellation | `IOException` propagated synchronously | PRESERVED with deterministic context derivation | HIGH |
| APIC-283 | `save(OutputStream,ClipboardFormat)` — SUPPORTED | 1 | Y/Y | A, W | Detached stream I/O | Blocking | Writer closes after full write | Caller/W | Interruption/stream close | `IOException` propagated synchronously | PRESERVED with deterministic context derivation | HIGH |
| APIC-284 | `paste(World,BlockVector3,boolean,boolean,Transform)` — SUPPORTED | 1 | Y/Y | A, W | W preparation → keyed R commits | Synchronous legacy wrapper | Returned EditSession after required flush/close | Transform/mask callbacks W; commits R | EditSession cancellation | Failure delivered once with matching history | PRESERVED with deterministic context derivation | HIGH |
| APIC-285 | `paste(World,BlockVector3,boolean,boolean,boolean,Transform)` — SUPPORTED | 1 | Y/Y | A, W | W preparation → keyed R/E commits | Synchronous legacy wrapper | Returned EditSession after block/entity/finalizer completion | Transform/mask W; entity finalizers E/R | EditSession cancellation | Failure delivered once with matching history | PRESERVED with deterministic context derivation | HIGH |
| APIC-286 | `paste(Extent,BlockVector3,boolean,Transform)` — SUPPORTED | 1 | Y/Y | A, W; detached target ANY | Supplied extent; derive R per staged target | Synchronous operation execution | Return plus supplied extent/session terminal commit if staged | Transform/mask W | Target operation/session cancellation | No `completeBlindly` loss; failure propagated once | PRESERVED with deterministic context derivation | HIGH |
| APIC-287 | `paste(Extent,BlockVector3,boolean)` — SUPPORTED | 1 | Y/Y | Same as APIC-286 | Same as APIC-286 | Same as APIC-286 | Same as APIC-286 | W/target contract | Same as APIC-286 | Same as APIC-286 | PRESERVED with deterministic context derivation | HIGH |
| APIC-288 | `paste(Extent,BlockVector3,boolean,boolean,boolean)` — SUPPORTED | 1 | Y/Y | A, W; detached target ANY | Supplied extent; R/E derived per mutation | Synchronous iteration/staging | Return plus target terminal commit if staged | Caller/W; commits R/E | Target operation/session cancellation | Block/entity/finalizer failure propagated once | PRESERVED with deterministic context derivation | HIGH |

### BlockBagExtent — 7

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-289 | `BlockBagExtent(Extent,BlockBag)` — SUPPORTED | 1 | Y/Y | E(owner), matching R, A, W | Extent plus owning inventory/entity | Synchronous construction | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-290 | `BlockBagExtent(Extent,BlockBag,boolean)` — SUPPORTED | 1 | Y/Y | Same as APIC-289 | Same as APIC-289 | Synchronous construction | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-291 | `getBlockBag()` — SUPPORTED | 1 | Y/Y | ANY with wrapper ownership | BlockBag reference | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-292 | `setBlockBag(BlockBag)` — SUPPORTED | 1 | Y/Y | ANY with exclusive wrapper ownership | Wrapper-local reference | Nonblocking | Return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-293 | `popMissing()` — SUPPORTED | 1 | Y/Y | Pipeline owner after edits | Wrapper-local missing-block map | Nonblocking state extraction | Return | — | Session cancellation fixes final contents | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-294 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 plus E(owner) | APIC-032 plus inventory owner | APIC-032 | APIC-032 | BlockBag callback E; commit R | APIC-032 | Inventory/block failure once | PRESERVED with deterministic context derivation | HIGH |
| APIC-295 | `setBlock(int,int,int,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | Same as APIC-294 | Same as APIC-294 | APIC-032 | APIC-032 | E then R | APIC-032 | Same as APIC-294 | PRESERVED with deterministic context derivation | HIGH |

### ChunkBatchingExtent — 6

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-296 | `ChunkBatchingExtent(Extent)` — SUPPORTED | 1 | Y/Y | ANY | Detached pipeline wrapper | Nonblocking | Constructor return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-297 | `ChunkBatchingExtent(Extent,boolean)` — SUPPORTED | 1 | Y/Y | ANY | Detached pipeline wrapper | Nonblocking | Constructor return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-298 | `isEnabled()` — SUPPORTED | 1 | Y/Y | ANY with safe publication | Wrapper-local flag | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-299 | `setEnabled(boolean)` — SUPPORTED | 1 | Y/Y | ANY with exclusive pipeline ownership | Wrapper-local flag | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-300 | `commitRequired()` — INTERNAL | 1 | Y/Y | Pipeline coordinator | Wrapper-local buffered state | Nonblocking query | Return | — | Session cancellation | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-301 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |

### MultiStageReorder — 7

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-302 | `MultiStageReorder(Extent)` — SUPPORTED | 1 | Y/Y | ANY | Detached pipeline wrapper | Nonblocking | Constructor return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-303 | `MultiStageReorder(Extent,boolean)` — SUPPORTED | 1 | Y/Y | ANY | Detached pipeline wrapper | Nonblocking | Constructor return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-304 | `isEnabled()` — SUPPORTED | 1 | Y/Y | ANY with safe publication | Wrapper-local flag | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-305 | `setEnabled(boolean)` — SUPPORTED | 1 | Y/Y | ANY with exclusive pipeline ownership | Wrapper-local flag | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-306 | `commitRequired()` — INTERNAL | 1 | Y/Y | Pipeline coordinator | Buffered stage state | Nonblocking query | Return | — | Session cancellation | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-307 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | PRESERVED with deterministic context derivation | HIGH |
| APIC-308 | `commitBefore()` — INTERNAL | 1 | Y/Y | A, W; local R only for wholly owned target | Buffered stages → keyed R commits | Returns Operation; no foreign-owner tick wait | Returned Operation terminates | W preparation; R commits | Operation/session cancellation | Per-stage failure propagated once | PRESERVED with deterministic context derivation | HIGH |

### ReorderingExtent — 0 declarations

No implementation-specific source declarations.

### BlockTransformExtent — 13

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-309 | `BlockTransformExtent(Extent)` — SUPPORTED | 1 | Y/Y | ANY | Detached wrapper | Nonblocking | Constructor return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-310 | `BlockTransformExtent(Extent,Transform)` — SUPPORTED | 1 | Y/Y | ANY | Detached wrapper | Nonblocking | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-311 | `getTransform()` — SUPPORTED | 1 | Y/Y | ANY with safe publication | Wrapper-local transform | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-312 | `getBlock(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | Caller/W transform | APIC-029 | Transform/read failure once | PRESERVED with deterministic context derivation | HIGH |
| APIC-313 | `getBlock(int,int,int)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | Caller/W transform | APIC-029 | Transform/read failure once | PRESERVED with deterministic context derivation | HIGH |
| APIC-314 | `getFullBlock(BlockVector3)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | Caller/W transform | APIC-029 | Transform/read failure once | PRESERVED with deterministic context derivation | HIGH |
| APIC-315 | `getFullBlock(int,int,int)` — OVERRIDE → APIC-029 | 1 | Y/Y | APIC-029 | APIC-029 | APIC-029 | APIC-029 | Caller/W transform | APIC-029 | Transform/read failure once | PRESERVED with deterministic context derivation | HIGH |
| APIC-316 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | Caller/W transform | APIC-032 | Transform/write failure once | PRESERVED with deterministic context derivation | HIGH |
| APIC-317 | `setBlock(int,int,int,T)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | Caller/W transform | APIC-032 | Transform/write failure once | PRESERVED with deterministic context derivation | HIGH |
| APIC-318 | `setTransform(Transform)` — SUPPORTED | 1 | Y/Y | ANY with exclusive wrapper ownership | Wrapper-local transform | Nonblocking | Return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-319 | `static transform(B,Transform)` — SUPPORTED | 1 | Y/Y | ANY | Pure/detached block-state transform | Synchronous CPU | Return | Caller | — | Transform failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-320 | `transform(BlockStateHolder<BaseBlock>)` — SUPPORTED | 1 | Y/Y | ANY | Pure/detached block-state transform | Synchronous CPU | Return | Caller | — | Transform failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-321 | `transform(BlockState)` — SUPPORTED | 1 | Y/Y | ANY | Pure/detached block-state transform | Synchronous CPU | Return | Caller | — | Transform failure synchronous | PRESERVED with deterministic context derivation | HIGH |

### BlockChangeLimiter — 5

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-322 | `BlockChangeLimiter(Extent,int)` — SUPPORTED | 1 | Y/Y | ANY | Detached wrapper | Nonblocking | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-323 | `getLimit()` — SUPPORTED | 1 | Y/Y | ANY with safe publication | Wrapper-local counter metadata | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-324 | `setLimit(int)` — SUPPORTED | 1 | Y/Y | ANY with exclusive pipeline ownership | Wrapper-local limit | Nonblocking | Return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-325 | `getCount()` — SUPPORTED | 1 | Y/Y | Pipeline owner or after completion | Wrapper-local counter | Nonblocking | Return | — | Session cancellation fixes final count | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-326 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | Limit/write failure propagated once | PRESERVED with deterministic context derivation | HIGH |

### DataValidatorExtent — 4

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-327 | `DataValidatorExtent(Extent,World)` — SUPPORTED | 1 | Y/Y | ANY if world bounds are cached; otherwise G/A/W | Detached bounds metadata | Synchronous construction | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-328 | `DataValidatorExtent(Extent,int,int)` — SUPPORTED | 1 | Y/Y | ANY | Detached bounds metadata | Nonblocking | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-329 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | Validation/write failure once | PRESERVED with deterministic context derivation | HIGH |
| APIC-330 | `setBiome(BlockVector3,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | Validation/write failure once | PRESERVED with deterministic context derivation | HIGH |

### BiomeQuirkExtent — 2

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-331 | `BiomeQuirkExtent(Extent)` — SUPPORTED | 1 | Y/Y | ANY | Detached wrapper | Nonblocking | Constructor return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-332 | `setBiome(BlockVector3,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | Quirk/write failure once | PRESERVED with deterministic context derivation | HIGH |

### BlockQuirkExtent — 2

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-333 | `BlockQuirkExtent(Extent,World)` — SUPPORTED | 1 | Y/Y | ANY if world metadata is detached; otherwise G/A/W | Wrapper plus logical world identity | Synchronous construction | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-334 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 | Quirk/write failure once | PRESERVED with deterministic context derivation | HIGH |

### ChunkLoadingExtent — 4

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-335 | `ChunkLoadingExtent(Extent,World,boolean)` — SUPPORTED | 1 | Y/Y | ANY for construction | Logical world identity | Nonblocking construction | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-336 | `ChunkLoadingExtent(Extent,World)` — SUPPORTED | 1 | Y/Y | ANY for construction | Logical world identity | Nonblocking construction | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-337 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | R(world,chunk) capture/load then APIC-032 | No foreign-owner tick wait | APIC-032 terminal point | R for load/commit; W preparation | APIC-032 | Load/write failure once | PRESERVED with deterministic context derivation | HIGH |
| APIC-338 | `setBiome(BlockVector3,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | R(world,chunk) capture/load then APIC-032 | No foreign-owner tick wait | APIC-032 terminal point | R for load/commit; W preparation | APIC-032 | Load/write failure once | PRESERVED with deterministic context derivation | HIGH |

### SideEffectExtent — 7

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-339 | `SideEffectExtent(World)` — SUPPORTED | 1 | Y/Y | ANY for construction | Logical world identity | Nonblocking construction | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-340 | `isPostEditSimulationEnabled()` — SUPPORTED | 1 | Y/Y | ANY with safe publication | Wrapper-local flag | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-341 | `setPostEditSimulationEnabled(boolean)` — SUPPORTED | 1 | Y/Y | ANY with exclusive pipeline ownership | Wrapper-local flag | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-342 | `getSideEffectSet()` — SUPPORTED | 1 | Y/Y | ANY with safe publication | Wrapper-local policy | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-343 | `setSideEffectSet(SideEffectSet)` — SUPPORTED | 1 | Y/Y | ANY with exclusive pipeline ownership | Wrapper-local policy | Nonblocking | Return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-344 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | R target plus required finalizers | Staged/direct; no foreign-owner tick wait | Side-effect finalizers complete, not block write alone | W preparation; R finalizers | Session cancellation | Block/finalizer failure once | PRESERVED with deterministic context derivation | HIGH |
| APIC-345 | `commitRequired()` — INTERNAL | 1 | Y/Y | Pipeline coordinator | Wrapper-local pending finalizer state | Nonblocking query | Return | — | Session cancellation | Synchronous | PRESERVED with deterministic context derivation | HIGH |

### SurvivalModeExtent — 6

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-346 | `SurvivalModeExtent(Extent,World)` — SUPPORTED | 1 | Y/Y | ANY for construction | Logical world identity | Nonblocking construction | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-347 | `hasToolUse()` — SUPPORTED | 1 | Y/Y | ANY with safe publication | Wrapper-local policy | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-348 | `setToolUse(boolean)` — SUPPORTED | 1 | Y/Y | ANY with exclusive pipeline ownership | Wrapper-local policy | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-349 | `hasStripNbt()` — SUPPORTED | 1 | Y/Y | ANY with safe publication | Wrapper-local policy | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-350 | `setStripNbt(boolean)` — SUPPORTED | 1 | Y/Y | ANY with exclusive pipeline ownership | Wrapper-local policy | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-351 | `setBlock(BlockVector3,B)` — OVERRIDE → APIC-032 | 1 | Y/Y | A, W; owner R/E only when block and inventory owner coincide | R(block), E(block-bag actor) | Staged/direct; no cross-owner tick wait | Block, drops, inventory, and history finalizers complete | R for drops/block; E for inventory | Session cancellation with partial history | Cross-owner/inventory/write failure once | PRESERVED with deterministic context derivation | HIGH |

### WatchdogTickingExtent — 7

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-352 | `WatchdogTickingExtent(Extent,Watchdog)` — SUPPORTED | 1 | Y/Y | ANY | Detached wrapper/watchdog reference | Nonblocking construction | Constructor return | — | — | Validation failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-353 | `isEnabled()` — SUPPORTED | 1 | Y/Y | ANY with safe publication | Wrapper-local flag | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-354 | `setEnabled(boolean)` — SUPPORTED | 1 | Y/Y | ANY with exclusive wrapper ownership | Wrapper-local flag | Nonblocking | Return | — | — | Synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-355 | `setBlock(BlockVector3,T)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032; watchdog signal is platform-safe metadata only | APIC-032 | APIC-032 | APIC-032 context; no global-thread assumption | APIC-032 | Watchdog/write failure once | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-356 | `createEntity(Location,BaseEntity)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 context | APIC-038 | Watchdog/entity failure once | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-357 | `createEntity(Location,BaseEntity,UUID)` — OVERRIDE → APIC-038 | 1 | Y/Y | APIC-038 | APIC-038 | APIC-038 | APIC-038 | APIC-038 context | APIC-038 | Watchdog/entity failure once | PRESERVED with deterministic context derivation | MEDIUM |
| APIC-358 | `setBiome(BlockVector3,BiomeType)` — OVERRIDE → APIC-032 | 1 | Y/Y | APIC-032 | APIC-032 | APIC-032 | APIC-032 | APIC-032 context | APIC-032 | Watchdog/write failure once | PRESERVED with deterministic context derivation | MEDIUM |

## Fawe UUID-keyed executor — 4 declarations

| ID | Entry point(s) | Count | Compat | Legal callers | Required target | Blocking / return | Completion point | Callback thread | Cancellation | Error propagation | Disposition | Confidence |
|---|---|---:|---|---|---|---|---|---|---|---|---|---|
| APIC-359 | `getClipboardExecutor():KeyQueuedExecutorService`; JVM `()KeyQueuedExecutorService` — INTERNAL, deprecated | 1 | Y/Y | ANY | UUID-keyed W executor lookup | Nonblocking | Return | Submitted tasks run on UUID-keyed W lane | Executor/Future API | Initialization failure synchronous | PRESERVED with deterministic context derivation | HIGH |
| APIC-360 | `submitUUIDKeyQueuedTask(UUID,Runnable):Future`; JVM `(UUID,Runnable)Future` | 1 | Y/Y | ANY; callback must not touch live state | Existing UUID worker inline; otherwise UUID-keyed W lane | Inline on UUID worker, otherwise nonblocking submission | Runnable termination | Current UUID worker or keyed W | Returned Future when queued; inline work cannot be cancelled after entry | Inline Runnable failure synchronous; queued failure through Future once | PRESERVED with deterministic context derivation | HIGH |
| APIC-361 | `submitUUIDKeyQueuedTask(UUID,Runnable,Object):Future`; JVM `(UUID,Runnable,Object)Future` | 1 | Y/Y | Same as APIC-360 | Same UUID-keyed derivation | Inline or nonblocking submission | Runnable termination and result availability | Current UUID worker or keyed W | Same as APIC-360 | Inline Runnable failure synchronous; queued failure through Future once | PRESERVED with deterministic context derivation | HIGH |
| APIC-362 | `submitUUIDKeyQueuedTask(UUID,Callable):Future`; JVM `(UUID,Callable)Future` | 1 | Y/Y | Same as APIC-360 | Same UUID-keyed derivation | Inline call returns completed/failed Future; otherwise nonblocking submission | Callable termination | Current UUID worker or keyed W | Returned Future when queued | Callable failure is represented by failed Future in both branches | PRESERVED with deterministic context derivation | HIGH |

## Counts

| Source set | PRESERVED with deterministic context derivation | DEGRADED deterministic failure | EXCLUDE | Total |
|---|---:|---:|---:|---:|
| QueueHandler | 10 | 0 | 0 | 10 |
| 24 Extent-derived types | 257 | 0 | 0 | 257 |
| Fawe UUID-keyed executor | 4 | 0 | 0 | 4 |
| **Total** | **271** | **0** | **0** | **271** |

Status: DONE_WITH_CONCERNS — the source census is 257 rather than the earlier approximate 255; QueueHandler’s opaque location-free callbacks remain preservable only under the frozen current-context/global-safe callback constraint.