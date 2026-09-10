# Review W1 / Task 15 — Paper/Spigot behaviour-equivalence and regression risk

Reviewer lens: **does the C5 requalification preserve Paper/Spigot observable behaviour,
performance and failure modes?** Independent, read-only pass. Folia predicate semantics are
covered by a separate reviewer and are deliberately out of scope here.

---

## VERDICT

# REJECT

Two independent BLOCKING defects. Either one alone makes the plugin non-functional on
Paper/Spigot — the platform the spec requires to stay behaviour-equivalent.

1. **No `FaweThreadContext` backend is ever registered in production code.**
   `BukkitThreadContext` is written but never instantiated; `ContextResolver.register(...)` is
   called only from two test classes. `ContextResolver.resolve()` throws on a null registration,
   so **all 30 requalified call sites throw `IllegalStateException` on Paper**, starting with a
   per-tick throw from `QueueHandler.run()`.
2. **`PaperweightFaweAdapter.createWorldNativeAccess` (adapter-26.1) now recurses infinitely.**
   The new `BukkitAdapter.adapt(world)` argument resolves to `new BukkitWorld(world)`, whose
   constructor calls `createWorldNativeAccess(world)` again. Unbounded recursion →
   `StackOverflowError` on the first world adaptation on any Paper 26.1 server.

The requalification record (`c5-requalification-record.md`) states that direct `javac` passed and
that "the Paper regression matrix remains an orchestrator/harness gate". Both defects are
compile-clean, which is precisely why they survived. Neither is a Folia-only concern.

---

## Does every predicate collapse correctly on the Bukkit backend?

`worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/util/BukkitThreadContext.java`

| Predicate | Line | Body | Collapses to `Fawe.isMainThread()`? |
|---|---|---|---|
| `isTickThread()` | :34-36 | `return Fawe.isMainThread();` | **Yes** — identity. |
| `ownsChunk(World, int, int)` | :39-41 | `return isTickThread();` | **Yes** — arguments unread, so a null / cross-world / unloaded / wrapped `World` cannot change the answer or throw *inside this method*. |
| `ownsEntity(Entity)` | :44-46 | `return isTickThread();` | **Yes** — argument unread; a null `Entity` is safe here. |
| `isGlobalContext()` | :49-51 | `return isTickThread();` | **Yes** — identity. |
| `isFaweWorker()` | :54-56 | `Thread.currentThread() instanceof FaweThread` | **N/A and correct.** Not the old identity, but architecture §3.1 does not ask it to be, and no requalified site uses it. `FaweThread` is a sealed marker interface implemented by `FaweBasicThread`/`FaweForkJoinThread` — a marker check, not a name comparison, as §3.1 requires. |
| `requireOwns(...)` | `FaweThreadContext.java:31-35` (default) | delegates to `ownsChunk` | Yes. Unused at any Task 15 site. |

The class itself is correct, allocation-free per call, and matches architecture §3.1's
"Zero behavior change on Paper" clause. **The class is never used.**

Every argument-bearing predicate is argument-insensitive on Paper, so the "null / unloaded /
cross-world / wrapper-vs-native argument" risk class is closed *inside the backend*. It is **not**
closed at the call sites, because two call sites do real work to *construct* the argument before
handing it to a predicate that ignores it — see BLOCKING-2, MAJOR-1 and MAJOR-2.

---

## Bootstrap / resolution-order verdict: **FAIL**

`worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/ContextResolver.java:25-31`

```java
static FaweThreadContext resolve() {
    FaweThreadContext context = CONTEXT.get();
    if (context == null) {
        throw new IllegalStateException("No FaweThreadContext has been registered");
    }
    return context;
}
```

Compare the call it replaced — `Fawe.java:210-212`:

```java
public static boolean isMainThread() {
    return instance == null || instance.thread == Thread.currentThread();
}
```

`Fawe.isMainThread()` was **total**: answerable from any thread at any lifecycle point, including
before `Fawe` existed, where it deliberately returned `true` (treat pre-bootstrap as "main
thread"). `FaweThreadContext.current()` is **partial** and throws.

That would already be a hazard. It is fatal here because the registration never happens:

```
$ rg 'ContextResolver\.' --glob '*.java'
worldedit-core/src/main/.../FaweThreadContext.java:15      ContextResolver.resolve()
worldedit-core/src/test/.../TicketAuthorityTest.java:46    ContextResolver.register(CONTEXT)
worldedit-bukkit/folia/src/test/.../DefaultFoliaRegionDispatcherTest.java:80
                                                           ContextResolver.register(CONTEXT)
$ rg 'BukkitThreadContext' --glob '*.java' --glob '*.kts'
worldedit-bukkit/src/main/.../BukkitThreadContext.java:31  public final class BukkitThreadContext ...
```

One declaration, zero uses. No `ServiceLoader`, no `META-INF/services` entry, no registration in
`FaweBukkit`, `Fawe`, or `WorldEditPlugin`. Consequences on Paper:

- `QueueHandler`'s constructor registers `TaskManager.taskManager().repeat(this, 1)`. Its
  `run()` (`QueueHandler.java:118-121`) is the **first line of a per-tick task** and now begins
  `if (!FaweThreadContext.current().isGlobalContext())`. Every tick throws
  `IllegalStateException` out of the scheduler; the sync/`syncWhenFree` queues are never drained,
  so every `TaskManager.sync(...)` from a worker thread blocks forever.
- Any command dispatch reaches `PlatformCommandManager.java:681` and throws before the command runs.
- Any edit reaches `SingleThreadQueueExtent.java:260`, `EditSessionBuilder.java:506`,
  `AbstractChangeSet.java:417`.
- Class-load ordering is irrelevant: the failure is unconditional, not a race.

Even once a registration is added, the design remains fragile relative to what it replaced. Two
concrete pre-bootstrap reachability paths exist today:

- `FaweCache.createMainThreadSafeCache` (`FaweCache.java:159-161`) evaluates the predicate in an
  **anonymous-class field initialiser**. Its caller `MaskingExtent.java:68` runs at extent
  construction. Old behaviour pre-bootstrap: `true` → no cache. New: throw.
- `LocalSession.java:414`, `AbstractPlayerActor.java:534`/`:574` are reachable from player
  login/logout handling, which on Paper can fire before FAWE has fully enabled.

Recommendation: register `BukkitThreadContext` from the Bukkit platform bootstrap **before**
`QueueHandler` is constructed, and make `resolve()` fall back to a total default that reproduces
`Fawe.isMainThread()`'s pre-bootstrap `true` rather than throwing. A throwing resolver converts
"slightly wrong answer during a 200 ms window" into "plugin dead".

The build guard (`NoFaweIsMainThread.java`) enforces that the old call is gone but does not check
that the new mechanism is wired. It is a one-sided gate and passed a completely broken tree.

---

## Hot-path cost assessment

Baseline: `Fawe.isMainThread()` = one static field read + `Thread.currentThread()` + reference
compare. New floor for every site: `AtomicReference.get()` (volatile acquire load — free on x86,
a real barrier on ARM/Graviton) + one interface call + the same thread compare. On a Paper server
only `BukkitThreadContext` is ever loaded, so the call site stays monomorphic and JIT should
inline it to near-parity. **The per-call predicate cost is not the problem.** The problem is the
argument construction that some sites added *around* it.

Site by site:

| Site | Frequency | New cost | Assessment |
|---|---|---|---|
| `SingleThreadQueueExtent.java:260` | per chunk submit | volatile read + 2 inlined virtual calls; `chunk.getX()/getZ()` are trivial getters | Negligible. |
| `QueueHandler.java:383/395/407/418` | per `sync()` | same | Negligible. |
| `QueueHandler.java:120` | per tick | same | Negligible. |
| `TaskManager.java:166`+`:173` (`runUnsafe`) | rare | **two** resolutions where the old code did two `isMainThread()` calls | Parity. |
| `FaweCache.java:161` | once per `MaskingExtent` construction, **not** per `apply()` | one resolution | Negligible. The record calls this a hot path; it is not — the predicate is a field initialiser, and `apply()` (`:167`) only reads the resulting field. |
| `SlowExtent.java:31` | inside `if (increment >= THRESHOLD)`, i.e. once per simulated tick, not per block | one resolution | Negligible. |
| `NMSAdapter.java:139` (String overload) | per section write, excluded adapters | one resolution | Parity. |
| `NMSAdapter.java:159` (World overload) | per section write, adapter-26.1 | one resolution **plus an unconditional `world.getName()`** | See MAJOR-2. Real. |
| `PaperweightFaweWorldNativeAccess.java:110` | per block on the side-effect path | one resolution | Negligible. |
| `PaperweightFaweWorldNativeAccess.java:289` (`ownsCachedChunks()`) | per `flush()` | 2 stream pipelines + 2 capturing lambdas | **Smaller than the task brief assumes** — see below. |
| `PaperweightPlatformAdapter.java:308/:325` | sync chunk-load fallback | one resolution **plus `BukkitAdapter.adapt(serverLevel.getWorld())` = a whole `new BukkitWorld(...)`** | See MAJOR-1. |
| `PaperweightGetBlocks.java:119/123` (ctor) | **per chunk, every edit** | `FaweBukkitWorld.of(...)` = global `synchronized` map + `Bukkit.getWorld(name)` | See MAJOR-3. Real. |

### `ownsCachedChunks()` at `:289` — quantified honestly

```java
private boolean ownsCachedChunks() {
    return cachedChanges.stream().allMatch(change -> ownsChunk(
            change.blockPos.getX() >> 4, change.blockPos.getZ() >> 4
    )) && cachedChunksToSend.stream().allMatch(chunk -> ownsChunk(chunk.x(), chunk.z()));
}
```

The brief asks me to quantify "streams over two collections on every flush where the old code did
one thread compare". Measured against actual Paper control flow, it is **less bad than it looks**:

- On the main thread, `setBlockState` (`:110`) takes the inline branch and never populates
  `cachedChanges`/`cachedChunksToSend`. `allMatch` over two empty streams is `true`.
- Off the main thread, `ownsChunk` returns `false` for the first element and `allMatch`
  **short-circuits**. One predicate evaluation, not 1024.
- The full 1024-element walk only occurs when a flush runs on the main thread over changes cached
  from a worker thread — real, but not the common case.

Residual cost per `flush()`: 2 `ReferencePipeline$Head` + 2 spliterators + 2 **capturing** lambda
instances (both capture `this`, so neither is cached as a singleton) ≈ 6 short-lived allocations
where the old code allocated zero. On a young-gen collector this is cheap, but `flush()` is called
per side-effect application, so it is measurable allocation churn on the `//fast`-off path. It
should be rewritten as a plain loop with a single hoisted `FaweThreadContext.current()`.

**Bottom line on cost:** the predicate indirection itself is fine and I could not fault it. The
regressions are the two `World`-materialising helpers (`PaperweightPlatformAdapter.ownsChunk`,
`PaperweightGetBlocks` ctor) that were added purely to *feed* a predicate which, on Paper,
discards the argument.

---

## Findings

### BLOCKING-1 — No backend registered; every requalified site throws on Paper

`worldedit-core/.../util/task/ContextResolver.java:25-31`
`worldedit-bukkit/.../util/BukkitThreadContext.java:31` (never referenced)

Detailed above. Paper scenario: server starts, FAWE enables, `QueueHandler`'s per-tick task
throws `IllegalStateException("No FaweThreadContext has been registered")` on tick 1 and every
tick after; the first `//set` throws out of `PlatformCommandManager.handleCommand`. Total
breakage, 100% of Paper/Spigot users, every version.

Note the *silent* variant this creates for the future: `Fawe.isMainThread()` answered `true`
pre-bootstrap. Any fallback added later must reproduce that value, not `false`, or
`FaweCache.createMainThreadSafeCache` silently flips from "no cache" to "thread cache" for
mutable `CharFilterBlock` state — the exact bug that method exists to prevent.

### BLOCKING-2 — Infinite recursion in adapter-26.1 `createWorldNativeAccess` → `StackOverflowError`

`worldedit-bukkit/adapters/adapter-26.1/.../PaperweightFaweAdapter.java:348-355`

```java
public WorldNativeAccess<?, ?, ?> createWorldNativeAccess(World world) {
    return new PaperweightFaweWorldNativeAccess(
            this, new WeakReference<>(getServerLevel(world)),
            BukkitAdapter.adapt(world)          // <-- added by task 15
    );
}
```

Resolution chain, all verified in-tree:

1. `BukkitAdapter.adapt(org.bukkit.World)` — `BukkitAdapter.java:123-127` → `getAdapter().adapt(world)`.
2. `IBukkitAdapter.adapt(org.bukkit.World)` — `IBukkitAdapter.java:322-325` → `return new BukkitWorld(world);`.
   Neither `CachedBukkitAdapter` nor `SimpleBukkitAdapter` overrides this — checked both files.
3. `BukkitWorld(World)` — `BukkitWorld.java:135-146`:
   ```java
   BukkitImplAdapter adapter = WorldEditPlugin.getInstance().getBukkitImplAdapter();
   if (adapter != null) {
       this.worldNativeAccess = adapter.createWorldNativeAccess(world);
   }
   ```
4. Back to step 1. No base case. `getBukkitImplAdapter()` (`WorldEditPlugin.java:715-717`) is
   non-null for the whole post-enable lifetime, so the `null` guard never terminates it.

Paper scenario: on any Paper server running the 26.1 adapter (MC 26.1.2 per
`build.gradle.kts` `supportedVersions`), the first `BukkitAdapter.adapt(someWorld)` — world load,
`//paste`, `//copy`, any `FaweBukkitWorld.of(...)`, any `PaperweightGetBlocks` construction —
recurses until `StackOverflowError`. This is a pure Paper crash; Folia is incidental.

The intent was to give `PaperweightFaweWorldNativeAccess` a `World` for `ownsChunk`. On Paper the
argument is discarded (`BukkitThreadContext.java:39-41`), so the entire recursion is incurred to
produce a value nothing reads. Use `FaweBukkitWorld.of(world)` (which caches) or defer the
adaptation to first use behind a `Supplier`.

### MAJOR-1 — `PaperweightPlatformAdapter.ownsChunk` allocates a `BukkitWorld` per call

`worldedit-bukkit/adapters/adapter-26.1/.../PaperweightPlatformAdapter.java:330-337`

```java
private static boolean ownsChunk(ServerLevel serverLevel, int chunkX, int chunkZ) {
    return FaweThreadContext.current().ownsChunk(
            BukkitAdapter.adapt(serverLevel.getWorld()), chunkX, chunkZ);
}
```

`BukkitAdapter.adapt` → `new BukkitWorld(world)` (see BLOCKING-2), so each call at `:308` and
`:325` constructs a fresh `BukkitWorld` — and, once BLOCKING-2 is fixed, still runs a full
`createWorldNativeAccess`. Old code: one thread compare. This is on the synchronous chunk-load
fallback in `ensureLoaded`, which is not the hottest path but is taken repeatedly during
main-thread edits. Paper regression: allocation + native-access construction per chunk-load
probe, including on the negative (`return null`) path where the old code did nothing.

### MAJOR-2 — New `WorldUnloadedException` surface on the adapter-26.1 section-write path

`worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/adapter/NMSAdapter.java:143-161`

```java
protected static <LevelChunkSection> boolean setSectionAtomic(World world, IntPair pair, ...) {
    return setSectionAtomic(world.getName(), pair, ..., 
            FaweThreadContext.current().ownsChunk(world, pair.x(), pair.z()));
}
```

`world` here is a `FaweBukkitWorld`, so `world.getName()` resolves to `BukkitWorld.getName()`
(`BukkitWorld.java:224-226`):

```java
public String getName() {
    return getWorldChecked().getName();   // throws WorldUnloadedException if unloaded
}
```

The old call site passed `nmsWorld.getWorld().getName()` (`PaperweightGetBlocks.java:423`, `:499`,
`:564` pre-change) — a direct `CraftWorld.getName()` off a `ServerLevel` held strongly by
`PaperweightGetBlocks`, which **cannot** throw. The new path dereferences a `WeakReference` and
falls back to `Bukkit.getWorld(name)`; if the world was unloaded mid-edit it throws
`WorldUnloadedException` where the old code returned a valid name and completed the write.

Two aggravating details:
- `world.getName()` is evaluated **unconditionally**, including on the lock-free inline branch
  that never touches `worldName` at all. Old code: the name was already a `String`, and the map
  lookup only happened on the locked branch.
- `getNameUnsafe()` (`BukkitWorld.java:232-234`) exists precisely for "read this world's name even
  if unloaded" and returns `worldNameRef` for free. That is the correct call here.

Whether the new abort is "safer" is arguable, but it is an observable behaviour change on the
frozen-budget Paper write path and was not in the requalification record's decision table.

### MAJOR-3 — `FaweBukkitWorld.of(...)` moved into the per-chunk `PaperweightGetBlocks` constructor

`worldedit-bukkit/adapters/adapter-26.1/.../PaperweightGetBlocks.java:119-134`

```java
public PaperweightGetBlocks(World world, int chunkX, int chunkZ) {
    this(FaweBukkitWorld.of(world), ((CraftWorld) world).getHandle(), chunkX, chunkZ);
}
public PaperweightGetBlocks(ServerLevel serverLevel, int chunkX, int chunkZ) {
    this(FaweBukkitWorld.of(serverLevel.getWorld()), serverLevel, chunkX, chunkZ);
}
```

`PaperweightGetBlocks` is constructed once per chunk by `PaperweightFaweAdapter.get(World, int,
int)` (`:834-836`) — thousands of times in a large edit, from every FAWE worker thread in parallel.
`FaweBukkitWorld.of` (`FaweBukkitWorld.java:31-39`) is:

```java
private static final Map<World, FaweBukkitWorld> CACHE =
        Collections.synchronizedMap(new WeakHashMap<>());

public static FaweBukkitWorld of(World world) {
    return CACHE.compute(world, (__, val) -> {
        if (val == null) { return new FaweBukkitWorld(world); }
        val.updateReference();
        return val;
    });
}
```

Every construction now takes a **single global monitor** shared across all worlds and all worker
threads, and executes `updateReference()` → `Bukkit.getWorld(worldNameRef)` inside that lock. Old
constructor: no such call. This introduces a serialisation point on the parallel chunk-get path
that did not exist before, exactly where `PARALLEL_THREADS > 1` is supposed to scale.

It also adds a throw: `updateReference()` (`FaweBukkitWorld.java:57-64`) raises
`WorldUnloadedException` when `Bukkit.getWorld(...)` returns null. A constructor that previously
could not fail for that reason now can.

Since the resulting `FaweBukkitWorld` is only used to feed `setSectionAtomic(World, ...)` — which
immediately converts it back to a `String` via `world.getName()` and re-resolves it through
`FaweBukkitWorld.getWorldSendingChunksMap(String)` → `of(worldName)` → `Bukkit.getWorld(...)` —
the whole round trip is redundant. `getWorldSendingChunksMap(FaweBukkitWorld)` already exists
(`FaweBukkitWorld.java:49-51`) and is free.

### MAJOR-4 — `LazyBaseEntity` adds a null conjunct that changes the Paper branch for 7 of 8 adapters

`worldedit-core/src/main/java/com/fastasyncworldedit/core/entity/LazyBaseEntity.java:30-44`

```java
if (targetEntity != null && FaweThreadContext.current().ownsEntity(targetEntity)) {
    setNbt(tmp.get());
} else {
    setNbt(TaskManager.taskManager().sync(tmp));
}
```

Only `adapter-26.1` passes an entity (`PaperweightFaweAdapter.java:374`). The other seven
adapters — `1_21`, `1_21_4`, `1_21_5`, `1_21_6`, `1_21_9`, `1_21_11`, `26.2`, i.e. **every version
Paper users actually run today** — still call the 2-arg constructor, so `targetEntity == null` and
the old `if (Fawe.isMainThread())` true-branch becomes permanently unreachable for them.

I traced the consequence and it happens to be benign *today*:
`TaskManager.sync(Supplier)` (`TaskManager.java:373-376`) itself starts with
`if (FaweThreadContext.current().isTickThread()) return function.get();`, so a Paper main-thread
caller still executes inline. Net result identical; cost is one extra frame and one extra
resolution.

I am classifying it MAJOR rather than MINOR because the equivalence is **accidental and
undocumented**: it depends entirely on `TaskManager.sync` keeping its own inline fast path. The
requalification record (row 16) justifies this site on the premise that "adapter-26.1 now carries
that entity target into the lazy snapshot" without noting that the other seven adapters do not,
and that this silently rewrites their control flow. Task 16 is explicitly slated to change
`TaskManager` sync routing (record, "remaining routing attack points"); the moment that inline
branch moves, this becomes a main-thread self-deadlock on every non-26.1 adapter.

Secondary: `sourceEntity` is a mutable non-`final`, non-`volatile` field written inside `getNbt()`
(`:35`). `BaseEntity`/`LazyBaseEntity` instances are handed between the prepare pool and the main
thread; the existing `saveTag` field has the same shape, so this is consistent with prior
practice, but it widens an already-unsynchronised publication.

### MINOR-1 — `NMSAdapter` overload split: every caller verified, one asymmetry

I checked every caller of both overloads across all adapter trees:

- `String` overload (`NMSAdapter.java:123`): called by
  `adapter-1_21`, `1_21_4`, `1_21_5`, `1_21_6`, `1_21_9`, `1_21_11`, `26.2` via their respective
  `PaperweightPlatformAdapter.setSectionAtomic(String worldName, ...)`. Behaviour: `isTickThread()`
  → `Fawe.isMainThread()` on Paper. **Exact parity with the old code.** Confirmed.
- `World` overload (`NMSAdapter.java:143`): called only by
  `adapter-26.1/PaperweightPlatformAdapter.java:220-229`, fed from
  `PaperweightGetBlocks.java:423/499/564`. Behaviour on Paper: `ownsChunk(...)` →
  `isTickThread()` → `Fawe.isMainThread()`. **Parity for the branch decision.** Confirmed.
- Overload ambiguity: `String` and `com.sk89q.worldedit.world.World` are unrelated types, and the
  private 7-arg `(String, ..., boolean)` variant differs in arity. No silent re-resolution is
  possible. Confirmed by inspection of all 8 adapter call sites.

The excluded adapters are therefore safe *with respect to `NMSAdapter`*. Their only exposure to
Task 15 is through core (`LazyBaseEntity`, `TaskManager`, `QueueHandler`, `FaweCache`,
`AbstractChangeSet`, `SlowExtent`, `SingleThreadQueueExtent`) — where BLOCKING-1 breaks them and
MAJOR-4 rewrites their entity path.

The asymmetry: the `World` overload discards the ownership answer's precision by immediately
degrading `World` → `String` for the lock map (`:153`), so the two overloads end up in the same
private impl keyed by name. That is fine, but it means the `World` parameter exists purely for the
predicate — reinforcing that MAJOR-2/MAJOR-3's plumbing cost buys nothing on Paper.

### MINOR-2 — `QueueHandler.run()` exception message changed

`worldedit-core/.../QueueHandler.java:118-121`: `"Not main thread"` → `"Not global context"`.
Observable in Paper crash reports and in any downstream code matching on the message. Harmless,
but it is an observable string change on the legacy platform.

### MINOR-3 — `LocalSession.clearHistory` local rename only

`worldedit-core/src/main/java/com/sk89q/worldedit/LocalSession.java:414-430`. `mainThread` →
`tickThread`, same value on Paper, tryLock/unlock pairing preserved. Verified correct; listed only
for completeness.

---

## Build-guard integration risk

`build.gradle.kts:102-133`, checker at `.orchestrate/folia-port/checks/NoFaweIsMainThread.java`.
Gradle **9.6.1**, with `org.gradle.configureondemand=true`, `org.gradle.parallel=true`,
`org.gradle.caching=true` (`gradle.properties`).

1. **The checker file is untracked.** `git status` shows `?? .orchestrate/folia-port/checks/`
   while `build.gradle.kts` is modified. If the build file is committed without the checks
   directory, **every build in the repo fails immediately** — the `Exec` task points at a
   non-existent file. `.orchestrate/` is not in `.gitignore` (verified with `git check-ignore`),
   so this is fixable, but the two must land in the same commit. Highest-probability breakage.
2. **No `outputs` declaration → the task is never up-to-date.** Gradle 9 will report
   "Task has not declared any outputs despite executing actions" and re-run it on every
   invocation, defeating the build cache (`org.gradle.caching=true`) for four `compileJava` task
   graphs. Each run forks a JVM in single-file source mode, which **recompiles the 160-line
   checker from source every time** (~1-2 s). Added to every `assemble`, `test`, `runServer`, and
   every incremental IDE-triggered build. Fix: `outputs.file(...)` a stamp file, or
   `outputs.upToDateWhen { false }` if always-run is intended (which at least silences the warning).
3. **Cross-project configuration from the root build script.** `project(":worldedit-core")` etc.
   at `build.gradle.kts:128` forces configuration of four subprojects from the root, which
   directly contradicts `org.gradle.configureondemand=true` and is the pattern Gradle's Isolated
   Projects work prohibits. It works on 9.6.1 today; it is on the deprecation path and will break
   if the project enables the configuration cache or Isolated Projects. The idiomatic fix is to
   declare the `dependsOn` inside each subproject's own build script (or a convention plugin in
   `build-logic/`).
4. **`System.getProperty("java.home")` is the *Gradle daemon* JVM, not the project toolchain.**
   Resolved at configuration time and baked into `commandLine`. The checker uses records, `var`
   and single-file source launch, so it needs a Java 16+ daemon; Gradle 9 requires 17+, so this
   holds in practice. But it silently couples a verification task to daemon JVM layout rather than
   to the declared toolchains the rest of the build uses. Under a future configuration cache this
   value is cached and can go stale after a JDK switch.
5. **Windows path.** `resolve("bin/java").absolutePath` yields an extension-less command. Windows
   `CreateProcess` appends `.exe` when no extension is present, so I expect this to work — but I
   could not verify it on this platform and it is worth a CI check on the Windows runner, if one
   exists.
6. **Scope correctness (verified good).** `worldedit-core/src/legacy/java` exists;
   `worldedit-bukkit:folia` is unconditionally included in `settings.gradle.kts:62`, so
   `project(":worldedit-bukkit:folia")` will not throw `UnknownProjectException`. The exclusion
   set correctly spares only `BukkitThreadContext.java` and the regex naturally skips the
   `Fawe.java:210` declaration. The negative proof described in the record is sound.
7. **The guard checks the wrong invariant.** It proves the old call is gone; it does not prove the
   new mechanism is wired. It exited 0 on a tree where the plugin cannot start. A companion
   assertion — "`ContextResolver.register` is called from exactly one production site" — would
   have caught BLOCKING-1 mechanically.

Net: no *silent* correctness risk to a contributor build, but item 1 is a build-stopper if the
commit is split, and items 2-3 impose a permanent per-build tax and a forward-compatibility
liability on a repo that has caching and configure-on-demand switched on.

---

## What I verified and could NOT fault

- **`BukkitThreadContext` itself.** All five predicates collapse exactly as architecture §3.1
  requires. No allocation, no `ThreadLocal`, no map lookup, no blocking, nothing that can throw.
  `isFaweWorker()` correctly uses the `FaweThread` sealed marker rather than a thread-name
  comparison, matching §3.1's explicit wording.
- **Argument-insensitivity.** Confirmed that `ownsChunk`/`ownsEntity` never dereference their
  arguments on the Bukkit backend, so null, cross-world, unloaded, and `WorldWrapper`-vs-native
  arguments cannot change the answer or throw inside the predicate. The `BukkitWorld.java:399`
  site passing `this` and `SingleThreadQueueExtent.java:260` passing the extent's `world` are both
  safe on Paper for this reason.
- **The `NMSAdapter` overload split.** Every caller of both overloads across all 8 adapter trees
  resolves to the intended overload with the intended Paper behaviour. No ambiguity is possible
  between `String`, `World`, and the private 7-arg form. This is the item the brief flagged as the
  classic silent-drift risk, and the worker got it right.
- **Excluded adapters' `NMSAdapter` dependency.** `adapter-26.2` and every `adapter-1_21*` tree
  still call the `String` overload, which preserves their exact no-lookup `isMainThread()` branch.
  Nothing shifted underneath them there.
- **Boolean polarity at all 30 sites.** I checked each negation individually
  (`EditSessionBuilder.java:506`, `AbstractPlayerActor.java:534`, `SlowExtent.java:31`,
  `QueueHandler.java:120`). No inverted condition.
- **`LocalSession.java:414-430`** tryLock/unlock pairing preserved through the rename.
- **`runUnsafe` start/end pairing** (`TaskManager.java:166`/`:173`) uses the same predicate on both
  ends, matching the old double `isMainThread()` call. No leak of the unsafe scope.
- **No dangling `Fawe.` references** in any file whose `import com.fastasyncworldedit.core.Fawe`
  was removed — checked all seven such files. No compile error from the import cleanup.
- **`java.util.Objects` is imported** in `PaperweightFaweWorldNativeAccess.java:34`, so the added
  `requireNonNull` compiles.
- **`ownsCachedChunks()` short-circuits**, so the feared 1024-element-per-flush walk does not occur
  on the normal Paper async path. The brief's worst-case framing overstates this one; the residual
  cost is allocation churn, not iteration.
- **`FaweCache.java:161` and `SlowExtent.java:31` are not hot paths** despite being listed as such
  — the first is a per-`MaskingExtent` field initialiser, the second is inside a threshold guard.

---

## Recommended gate

Do not proceed to the Paper regression matrix. It cannot pass: the plugin does not start
(BLOCKING-1) and adapter-26.1 crashes on world adaptation (BLOCKING-2). Fix both, then re-run this
review — MAJOR-1 through MAJOR-4 all need resolution before the "behaviour-equivalent within
frozen budgets" claim can be made, and MAJOR-4 in particular must be reconciled with Task 16's
planned changes to `TaskManager` sync routing.
