# Cross-review W1 / Task 15 — C5 requalification **semantic correctness**

Reviewer: fresh Opus thread, no prior context. Lens: **is each requalified predicate
semantically correct?** (Paper/Spigot behaviour-equivalence and regression risk are the second
reviewer's perimeter and are only touched here where they bear on ownership semantics.)

Method: every one of the 32 rows was re-derived from the post-change source before reading the
record's justification. All file:line references below were read in this session.

---

## VERDICT: **REJECT**

Not because the predicate *choices* are wrong — 30 of 32 rows pick a defensible predicate, and
the four hardest adapter rows (PPA:308/325, WNA:110, NMSAdapter World overload) pick exactly
right. The rejection is for a narrower and sharper reason:

> **At three live-access sites the newly-introduced ownership predicate is provably inert.** Its
> false branch immediately re-enters a *location-free* `TaskManager.sync(...)` /
> `QueueHandler.sync(...)` path that task 15 itself just widened from `isMainThread()` to
> `isTickThread()` — so the "not the owner" branch runs the identical live-state work **inline on
> the very non-owning region thread the guard was added to exclude.**

The net effect at `LazyBaseEntity:37`, `PaperweightFaweWorldNativeAccess:289` and
`NMSAdapter` (World overload) is: on Paper, unchanged; **on Folia, strictly *less* safe than the
naive translation would have been**, because the legacy code would at least have gone to the
queue. The record presents these three as protections. They are not. That is a record-accuracy
failure on the exact axis the artifact exists to certify, and it is what tips this from
PASS-WITH-NOTES to REJECT.

The fix is small (see BLOCKING-1). Most of the pass is sound and should be kept.

---

## Per-site agreement table

Verdict is on the **predicate choice**. Where the choice is right but the site is still unsafe,
that is called out and cross-referenced to a finding.

| # | Site | Verdict | Reason (one line) |
|---:|---|---|---|
| 1 | `Fawe.java:210` (declaration, KEEP) | **AGREE** | Not a call site; the Bukkit backend still needs the legacy identity. See MINOR-6 (deprecate it). |
| 2 | `TaskManager.java:166` `startUnsafe` → `isGlobalContext()` | **AGREE** | AsyncCatcher/physicsFreeze/Timings are process-global; global context is the only matching role. Moot on Folia (`FoliaQueueHandler.startUnsafe` throws), identical on Paper. |
| 3 | `TaskManager.java:173` `endUnsafe` → `isGlobalContext()` | **AGREE** | Must classify identically to its paired start. |
| 4 | `TaskManager.java:199` `taskNowMain` → `isTickThread()` | **AGREE** | Location-free by contract; §3.5 sanctions inline execution in the caller's tick context. |
| 5 | `TaskManager.java:214` `taskNowAsync` → `isTickThread()` | **AGREE** | Asks "must this leave a tick context"; no target is consulted. Correct generalisation. |
| 6 | `TaskManager.java:317` `taskWhenFree` → `isTickThread()` | **AGREE (caveat)** | Contract-conformant; shares the inline-widening hazard of MAJOR-2. |
| 7 | `TaskManager.java:331` `syncWhenFree(RunnableVal)` → `isTickThread()` | **AGREE (caveat)** | Correctly prevents a tick thread waiting; inherits MAJOR-2. |
| 8 | `TaskManager.java:349` `syncWhenFree(Supplier)` → `isTickThread()` | **AGREE (caveat)** | As above. |
| 9 | `TaskManager.java:375` `sync(Supplier)` → `isTickThread()` | **DISAGREE** | **BLOCKING-1.** This is the single funnel through which two ownership-guarded live-access fallbacks run. `isTickThread()` here means "any region thread may execute an arbitrary caller's live-state payload inline". Would use `isGlobalContext()` as the interim (see BLOCKING-1 for why, and for the §3.5 conflict). |
| 10 | `QueueHandler.java:120` `run()` → `isGlobalContext()` | **AGREE** | Strictest correct choice for a global drain loop; accepting an arbitrary region tick would run unrelated callbacks under the wrong owner. Good call. |
| 11 | `QueueHandler.java:383` `sync(Runnable,T)` → `isTickThread()` | **AGREE (caveat)** | Matches the §3.5 preserved-descriptor rule; inherits MAJOR-2. |
| 12 | `QueueHandler.java:395` `sync(Runnable)` → `isTickThread()` | **AGREE (caveat)** | As above. |
| 13 | `QueueHandler.java:407` `sync(Callable)` → `isTickThread()` | **AGREE (caveat)** | As above. |
| 14 | `QueueHandler.java:418` `sync(Supplier)` → `isTickThread()` | **AGREE (caveat)** | As above. |
| 15 | `FaweCache.java:161` → `isTickThread()` | **AGREE (MINOR-1)** | Faithful generalisation, but the predicate is evaluated at *construction* of the `LongFunction`, not at use — pre-existing unsoundness preserved. `isFaweWorker()` is arguably the truer question here (see Record accuracy). |
| 16 | `LazyBaseEntity.java:37` → `ownsEntity(sourceEntity)` | **AGREE on predicate** | Exactly the right question for a live NMS entity read. **But BLOCKING-1**: the false branch runs the same live read inline on a non-owning region thread. |
| 17 | `AbstractChangeSet.java:417` → `isTickThread()` | **AGREE (MINOR-2)** | Selects inline detached history work; authorises no live access. Widens inline history *disk IO* from one main thread to every region thread — §1b pressure, pre-existing in kind. |
| 18 | `SingleThreadQueueExtent.java:260` → `ownsChunk(world, x, z)` | **AGREE** | `chunk.call()` is a target-bearing chunk operation; ownership is the right gate, and the false branch goes to a FAWE worker pool (not a tick thread), so it is *not* affected by BLOCKING-1. See MINOR-3 (`world` may be null). |
| 19 | `SlowExtent.java:31` → `isTickThread()` | **AGREE** | Deliberate sleeping is illegal on any tick context regardless of owner. Exactly right. |
| 20 | `PlatformCommandManager.java:681` → `isTickThread()` | **AGREE** | Offload decision only; the branch performs no target-owned access. |
| 21 | `EditSessionBuilder.java:506` → `!isTickThread()` | **AGREE** | Worker-selection question. `isFaweWorker()` would be too strong (breaks legitimate external async callers). |
| 22 | `AbstractPlayerActor.java:538` → `!isTickThread()` | **AGREE** | Semaphore acquisition is legal only away from all tick contexts. |
| 23 | `AbstractPlayerActor.java:578` → `isTickThread()` | **AGREE** | A tick caller must return rather than `fut.get()`. |
| 24 | `LocalSession.java:418` → `isTickThread()` | **AGREE** | Protects every tick context from blocking on the history write lock; the paired `tryLock`/`unlock` symmetry is preserved correctly. |
| 25 | `BukkitWorld.java:399` → `ownsChunk(this, X, Z)` | **AGREE** | Synchronous `getChunkAt` is live access to the exact chunk derived from `pt`. False branch is a genuine async request (`PaperLib.getChunkAtAsync`), not an inline path — safe from BLOCKING-1. |
| 26 | `FaweDelegateSchematicHandler.java:166` → `isTickThread()` | **AGREE** | Offloads long paste preparation from any tick context; the target rides in the edit pipeline. |
| 27 | `NMSAdapter.java:139` split (String→`isTickThread()`, World→`ownsChunk`) | **AGREE on the split, DISAGREE on sufficiency** | Split verified reachable-correct (see below). But **BLOCKING-2**: the World overload's false branch performs the same live-section CAS under a FAWE lock, so `ownsChunk` gates nothing today. |
| 28 | `BukkitThreadContext.java:35` (KEEP backend) | **AGREE** | §3.1 requires the Bukkit backend to collapse to the legacy identity. Correct to keep. See MINOR-4 (null-world contract). |
| 29 | `PaperweightPlatformAdapter.java:308` → `ownsChunk` | **AGREE** | `ServerLevel.getChunk` is a synchronous load of that exact chunk. False branch returns `null` — genuinely safe. |
| 30 | `PaperweightPlatformAdapter.java:325` → `ownsChunk` | **AGREE** | Same; false branch returns `null`. |
| 31 | `PaperweightFaweWorldNativeAccess.java:110` → `ownsChunk(levelChunk.locX, locZ)` | **AGREE on predicate (MAJOR-3)** | Direct `LevelChunk.setBlockState` demands exact-chunk ownership — right. The false branch caches and reaches the global drain, mutating arbitrary chunks from the global region thread. |
| 32 | `PaperweightFaweWorldNativeAccess.java:289` → `ownsCachedChunks()` | **AGREE on shape** | "Own *every* target, not one anchor" is the correct multi-chunk formulation. **But BLOCKING-1** nullifies it, plus MINOR-5 (coordinate derivation, empty-set). |

**Rows where I would change the code: 9 (predicate), 16 / 27 / 32 (false-branch routing, not the
predicate).** Everything else I independently re-derived to the same answer.

---

## Findings

### BLOCKING-1 — Three ownership guards are inert: the false branch runs the same live work inline on the non-owner

**Sites:** `LazyBaseEntity.java:37-41`, `PaperweightFaweWorldNativeAccess.java:289-293`, funnelled
through `TaskManager.java:373-383`.

The chain, verified end to end:

```
TaskManager.sync(RunnableVal f)  -> sync((Supplier) f)
TaskManager.java:375:  if (FaweThreadContext.current().isTickThread()) { return function.get(); }
RunnableVal.java:30:   public T get() { return runAndGet(); }   // runs the payload, inline
```

**Scenario A — `LazyBaseEntity` (live NMS entity read on a non-owning region thread).**
Region thread R_a is ticking. Something on R_a resolves a clipboard/history `BaseEntity` whose
source entity lives in region R_b. `getNbt()` runs:

- `ownsEntity(targetEntity)` → **false** (R_a ≠ R_b). Guard "works".
- else → `TaskManager.taskManager().sync(tmp)` → `TaskManager:375` → `isTickThread()` on R_a → **true**
- → `tmp.get()` executes inline on R_a → `mcEntity.save(output)` on an entity owned by R_b.

A live NMS entity is serialised from a foreign region thread. This is precisely the class of
silent corruption the project exists to prevent, and the `ownsEntity` guard added by this task
did not prevent it.

**Scenario B — `PaperweightFaweWorldNativeAccess.flush()` (live chunk mutation on a non-owner).**
`flush()` is reached on a region thread R_a with cached changes for chunks in R_b:

- `ownsCachedChunks()` → **false**. Guard "works".
- else → `TaskManager.taskManager().sync(runnableVal)` → `TaskManager:375` → true on R_a
- → `runnableVal.run()` inline → `cc.levelChunk.setBlockState(...)` for R_b's chunks, plus
  `PaperweightPlatformAdapter.sendChunk(...)` for R_b's viewers, from R_a.

Direct section mutation and packet send on chunks owned by another region thread.

**Why this is task 15's problem and not purely task 16's.** Before this change, `TaskManager:375`
short-circuited on `Fawe.isMainThread()` — one designated thread. Task 15 replaced it with
`isTickThread()`, i.e. *every* region thread. That widening is what converts the guards' false
branches from "enqueue" into "run here, wrong owner". Task 15 implemented the permissive half of
architecture §3.5 ("a location-free `sync` called from a tick context runs in that current
context") and deferred the protective half ("a tick-thread caller whose callback needs a
*different* owner fails fast before side effects — *wrong tick fails*") to task 16. Half of a
two-part safety contract is not a safe intermediate state.

**Remediation — pick one, before sign-off:**

1. **Interim narrowing (my recommendation).** Make the location-free inline short-circuit
   `isGlobalContext()` at `TaskManager:317/331/349/375` and `QueueHandler:383/395/407/418`. This
   reproduces the legacy one-designated-thread semantics exactly, matches the deterministic
   global derivation §3.5 already mandates for A/W callers, is identical on Paper, and is exactly
   the reasoning the worker already applied — correctly — to `QueueHandler.run()` (row 10). Cost:
   a scheduling hop for region-thread callers. Spec §4b (correctness > performance) settles that
   trade. **Note the conflict:** this deviates from §3.5's literal "runs in that current context",
   so it needs an orchestrator ruling, not a unilateral worker edit.
2. **Implement the fail-fast now.** Land the "wrong tick fails" half of §3.5 in this task rather
   than task 16.
3. **Minimum acceptable if neither:** amend the record to state plainly that rows 16, 27 and 32
   deliver **no ownership enforcement in the current tree**, and that the Folia backend must not
   be enabled until task 16 lands. Right now the record reads as if they do enforce.

### BLOCKING-2 — `NMSAdapter` World overload: the false branch performs the same off-owner live-section CAS

`worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/adapter/NMSAdapter.java:143-208`

```java
if (canMutateInline) {                                    // ownsChunk(world, pair.x, pair.z)
    return ReflectionUtils.compareAndSet(sections, expected, value, layer);
}
... acquire FAWE ChunkSendLock ...
return ReflectionUtils.compareAndSet(sections, expected, value, layer);   // line 195 — same CAS
```

Both branches replace a live `LevelChunkSection` in the live `sections[]` array. The FAWE
`ChunkSendLock` is a FAWE-internal packet-send lock; it excludes FAWE readers, not the owning
region thread. Architecture C1 is unambiguous: *"No … off-thread live-section CAS on the Folia
backend, ever."* The record does acknowledge this ("the `NMSAdapter` non-owner locked CAS is not
ownership authorization and must move behind the ticketed dispatcher"), which is why this is
listed second — but as written the new `ownsChunk` call selects *which* mutation path runs, not
*whether* mutation happens. It buys nothing until task 16.

**The split itself I verified and endorse.** `rg setSectionAtomic` across all adapters: every
excluded adapter (1_21, 1_21_4/5/6/9/11, 26.2) calls `NMSAdapter.setSectionAtomic(worldName, …)`;
**only** adapter-26.1's `PaperweightPlatformAdapter:230` calls the `World` overload. The
certified Folia path genuinely cannot reach the compatibility branch. `pair` is confirmed to be
chunk coordinates (`AbstractBukkitGetBlocks.java:53: this.chunkPos = new IntPair(chunkX, chunkZ)`),
and `FaweBukkitWorld.getName()` inherits `BukkitWorld.getName()`, so the `world.getName()`
round-trip preserves the existing `SENDING_CHUNKS` map key. That part is right.

### BLOCKING-3 — No `FaweThreadContext` is registered in production: every requalified site throws at runtime

`ContextResolver.register(...)` is called **only from tests**
(`TicketAuthorityTest.java:46`, `DefaultFoliaRegionDispatcherTest.java:80`). `BukkitThreadContext`
is never instantiated anywhere. `ContextResolver.resolve()` throws
`IllegalStateException("No FaweThreadContext has been registered")`.

Consequence: as of this tree, all 30 requalified sites throw on first call — **on Paper too**.
The acceptance criterion "zero behaviour change on Paper" is not merely unverified, it is
currently false. Task 10's dev record assigns the wiring to task 17
(`WorldEditPlugin.onLoad()`), so this is not task 15's code defect — but it is nowhere in the C5
record, and the record's "Verification" section (direct `javac` passed) reads as reassurance that
does not apply. Compilation is not the risk here.

**Additionally, a genuine semantic gap the requalification does not reproduce:**

```java
Fawe.java:210:  return instance == null || instance.thread == Thread.currentThread();
```

The legacy predicate is **null-safe and returns `true` before FAWE is initialised**. The new path
throws. Any site reached during bootstrap — `FaweCache.createMainThreadSafeCache` (row 15) is
invoked from `MaskingExtent`'s constructor, and static/early paths are plausible — changes from
"treated as main thread" to "hard exception". This must be either reproduced deliberately or
consciously rejected as part of C5 and recorded; right now it is silently different at all 30
sites.

### MAJOR-1 — Guard scope is name-based; a live-access legacy-identity site in an enabled source set is invisible to it

`worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/BukkitBlockCommandSender.java:194`

```java
if (Bukkit.isPrimaryThread()) {
    updateActive();                       // reads block.getType() -> live chunk access
} else {
    Bukkit.getScheduler().callSyncMethod(plugin, () -> { updateActive(); return null; });
}
```

This is a `Fawe.isMainThread()`-equivalent question guarding **live chunk access**, in
`worldedit-bukkit/src/main` (a Folia-enabled source set), and:

- the C5 inventory misses it (the inventory regex matches the *name*, not the *semantics*);
- `NoFaweIsMainThread` cannot catch it (the pattern is `Fawe\s*\.\s*isMainThread`);
- the else branch uses the legacy `BukkitScheduler`, which architecture C1 bans outright on Folia.

Literally outside task 15's mandate ("`Fawe.isMainThread()` call sites"). But the record and the
guard together claim to secure the Folia-enabled sets against the legacy main-thread identity,
and they do not. Either widen the guard and inventory to legacy-identity *aliases*, or state the
limitation explicitly and hand this site to a named task.

### MAJOR-2 — The location-free `sync` widening is a general hazard, not just the three sites in BLOCKING-1

Rows 6, 7, 8, 9, 11, 12, 13, 14 all changed "run inline if on THE main thread" to "run inline if
on ANY region thread". Every existing caller of `TaskManager.sync/syncWhenFree/taskWhenFree` and
`QueueHandler.sync/syncWhenFree` inherits this. BLOCKING-1 documents the two callers I could
prove carry live state; I did not enumerate the rest, and neither did the record. Any caller
whose payload touches world/chunk/entity state now executes it on an arbitrary region thread
unless C1's ticket typing catches it — and ticket typing is not yet applied to these paths
(`QueueHandler.java:378` still reads `throw new UnsupportedOperationException("wave 1")` for
`syncOnGlobal`). The record's forward-looking note names only the *false*-branch routing; the
**true** branch is the more dangerous half and is not mentioned at all.

### MAJOR-3 — `WNA.setBlockState` false branch reaches live mutation via the global drain

`PaperweightFaweWorldNativeAccess.java:110-126` → `flushAsync` (248-273) →
`TaskManager.async(() -> TaskManager.sync(runnableVal))` → not a tick thread → `QueueHandler.sync`
→ `syncTasks` → drained by `QueueHandler.run()` on the **global context** (row 10) →
`cc.levelChunk.setBlockState(...)` for chunks owned by arbitrary regions.

The `ownsChunk` predicate at :110 is correct; the routing behind it is not. Note also that
`flushAsync` contains no predicate at all, so it was never in the C5 inventory — the record's
generic "WNA cached multi-chunk flush" handoff should name it explicitly, because it is the
higher-volume path of the two (it fires every 1024 cached changes).

### MINOR-1 — `FaweCache.createMainThreadSafeCache`: right predicate, structurally wrong place

`FaweCache.java:158-169`. The predicate is evaluated once, in the anonymous class's field
initialiser, on the **constructing** thread; the resulting `LongFunction` is then used from
whatever thread runs the filter. Its sole caller is `MaskingExtent.java:68`, typically constructed
off-tick — so a cache is created, and mutable `CharFilterBlock` state is then served to whichever
thread calls `apply(...)`, including region threads. Pre-existing; the requalification preserves it
faithfully. Flagging per the brief's "original already unsound" category. A correct fix evaluates
the predicate at use time.

### MINOR-2 — `AbstractChangeSet:417` widens inline history IO to every region thread

`completeNow = isTickThread()` runs the history write (disk-backed for
`DiskStorageHistory`) inline. Previously that was one main thread; now it is N region threads.
§1b ("tick threads never run unbounded work") gets N× more exposure. Correct predicate for the
question asked; worth a §1b budget note.

### MINOR-3 — `SingleThreadQueueExtent:260` can pass a null world

`SingleThreadQueueExtent.java:59: private World world = null;`, set in `init()` (185-212) via
`WorldWrapper.unwrap(extent)`, which can yield `null` for a non-world extent, and reset to `null`
in `reset()` (177). `BukkitThreadContext.ownsChunk` ignores the argument, so Paper never sees it;
a Folia backend will. `FaweThreadContext.ownsChunk` / `requireOwns` specify no null contract.

### MINOR-4 — `ownsChunk(World, …)` world-identity contract is unspecified, and the sites pass four different `World` implementations

For one physical world the requalified sites pass: `BukkitWorld` (`this`, row 25),
`FaweBukkitWorld` (row 27, via `PaperweightGetBlocks`), `BukkitAdapter.adapt(serverLevel.getWorld())`
(rows 29/30), and a `WorldWrapper.unwrap(...)` result (row 18). A Folia backend keying on
reference identity would be wrong for at least three of them. The interface comment should pin
the mapping (by name / by underlying Bukkit handle) before task 12/17 implements it. Not task 15's
defect, but it is task 15 that created the multi-implementation call surface.

### MINOR-5 — `ownsCachedChunks()` validates a derived coordinate, not the object it mutates

`PaperweightFaweWorldNativeAccess.java:298-303` derives chunk coords from
`change.blockPos.getX() >> 4`, while the mutation at :280 targets `cc.levelChunk`. Prefer
`change.levelChunk().locX/locZ` — the object actually mutated, and consistent with what :117 puts
into `cachedChunksToSend`. Also: `allMatch` on an empty stream is `true`, so `flush()` with
nothing cached takes the "owner" branch. Harmless today, but it means the guard's true branch is
not proof of ownership of anything.

### MINOR-6 — `Fawe.isMainThread()` is not deprecated

Row 1 correctly keeps the declaration. Adding `@Deprecated` (with a javadoc pointer to
`FaweThreadContext`) would give IDE-level signal in the excluded adapter trees that the build
guard deliberately does not scan, at zero cost and zero behaviour change.

### MINOR-7 — pre-existing bug adjacent to row 10, now on the global region thread

`QueueHandler.operate` (165-189): `synchronized (syncTasks) { queue.wait(1); }` — when called as
`operate(syncWhenFree, …)` (line 134) it waits on a monitor it does not hold →
`IllegalMonitorStateException`. Also, `operate` *blocks a tick thread* (`wait(1)`), which row 10
now sanctions on the global region context — direct §1b tension. Out of C5 scope; noting it
because row 10's requalification is what makes it a Folia-relevant liveness question.

### MINOR-8 — build wiring nits

`build.gradle.kts`: the `Exec` task declares `inputs` but no `outputs`, so it can never be
up-to-date and re-runs on every `compileJava`. It resolves the JVM from
`System.getProperty("java.home")` (the Gradle daemon JVM, not the project toolchain) — fine for a
single-file source launch on 11+, but implicit. It calls `project(":…")` at configuration time,
which is incompatible with `--configure-on-demand`; the project convention is
`--no-configure-on-demand`, so this is consistent, but it should be stated as a constraint rather
than assumed.

---

## Guard scope and evadability

**Scope: correct.** `PRODUCTION_ROOTS` (core main, core legacy, bukkit main, adapter-26.1, folia)
matches the Folia-enabled source sets exactly. Excluding tests is right. Excluding only
`BukkitThreadContext.java` — rather than all of `Fawe.java` — is the right call: the declaration
survives because the regex requires a `Fawe .` qualifier, so the file stays scanned against future
*call* sites inside it. The self-test asserts both exemptions. The checker lives outside the
source tree, so it cannot match itself. I did not re-run it (per instruction).

**Evasions that slip past, in descending order of realism:**

1. **Semantic aliases — already exploited in-tree.** `Bukkit.isPrimaryThread()` at
   `BukkitBlockCommandSender.java:194` is the legacy identity guarding live chunk access, in an
   enabled source set, and the guard is blind to it (MAJOR-1). Others that would slip: a direct
   `Fawe.instance().thread == Thread.currentThread()` re-derivation (`Fawe.thread` is reachable
   via `setMainThread()` at `Fawe.java:461`), `Thread.currentThread().getName()` comparisons,
   `MinecraftServer.isSameThread()`. **This is the real hole** — the guard enforces a *spelling*,
   not a *semantics*.
2. **`import static com.fastasyncworldedit.core.Fawe.*;` + bare `isMainThread()`.** The pattern
   matches only the explicit single-member static import
   (`import\s+static\s+…\.Fawe\.isMainThread\s*;`). A wildcard static import followed by an
   unqualified call matches neither alternative. One-line fix: add a
   `import\s+static\s+…\.Fawe\.\*` alternative, or flag bare `\bisMainThread\s*\(` in files that
   statically import `Fawe`.
3. **New unqualified calls inside `Fawe.java` itself.** `Fawe.java` is scanned but only for the
   qualified form; code added inside the class calls `isMainThread()` bare and passes.
4. **A one-line wrapper** (`static boolean onMain() { return Fawe.isMainThread(); }`) — the
   wrapper's own body is caught, so this only works if the wrapper lives in an unscanned tree
   (any excluded adapter) and is called from a scanned one. Low realism, but note the excluded
   adapters are compiled into the same jar.
5. **Reflection / `MethodHandles`** — not caught. Acceptable; no static checker catches this and
   there is no plausible motive.

**Recommendation:** close (2) and (3) — both are single-line regex additions with a self-test
case each. Then either widen to an alias denylist or state explicitly in the record that the
guard enforces spelling only, and that semantic aliases are a separate, unclosed obligation with
at least one known open instance.

---

## Record accuracy

**Verified true:**

- **Inventory arithmetic.** Independently counted at `HEAD`: 23 in `worldedit-core/src/main`
  (TaskManager 8, QueueHandler 5, AbstractPlayerActor 2, and 8 files with 1 each), 3 in
  `worldedit-bukkit/src/main`, 4 in adapter-26.1 = 30 tracked; plus the uncommitted
  `BukkitThreadContext.java` = **31 qualified invocations**, +1 declaration = **32 rows**. Exactly
  as claimed. The record's correction of the task's stale "~20 / nine TaskManager calls" estimate
  is right, and it flagged the correction rather than quietly absorbing it — good practice.
- **All 32 line numbers.** Spot-checked every row against the post-change files; all resolve to
  the stated seam. No drift.
- **The `isFaweWorker()` claim.** Verified by grep: zero production call sites; only the interface
  declaration, the Bukkit backend implementation, and two test fakes. The claim holds *literally*.
  **One qualification:** row 15 (`FaweCache.createMainThreadSafeCache`) plausibly *does* ask
  "am I a thread with a stable FAWE-worker identity for which caching mutable filter state is
  safe" — `isFaweWorker()` is a defensible, arguably better answer there, and `!isTickThread()` is
  the strictly weaker superset. "No original site semantically asked" is therefore slightly
  overstated; "no site *required* it, one site could reasonably use it" would be accurate.
- **The `NMSAdapter` reachability claim.** Verified by exhaustive grep across all adapters (see
  BLOCKING-2). The certified Folia path cannot reach the compatibility overload. This was the row
  I expected to break and it holds up.
- **adapter-26.2 / adapter-1_21\* untouched.** Confirmed via `git diff --stat`; only adapter-26.1
  files changed.

**Forward-looking claims — right in direction, materially incomplete:**

- *"Task 16/C1 must replace remaining location-free **false-branch** routing."* Correct as far as
  it goes, and it names the right three areas. But it inverts the priority: the **true** branch of
  the location-free `sync` is the one that newly executes arbitrary payloads inline on arbitrary
  region threads (MAJOR-2), and it is what nullifies this task's own ownership guards
  (BLOCKING-1). Not mentioned anywhere.
- The record does **not** state that rows 16, 27 and 32 currently deliver zero enforcement. As
  written they read as protections in place. This is the accuracy failure behind the REJECT.
- `flushAsync` (the 1024-change path) is not named, only the `flush()` path.
- Registration (BLOCKING-3) is absent entirely — including the lost `instance == null → true`
  pre-boot semantics, which is a *semantic* difference at all 30 sites and therefore squarely a
  C5 concern, not just task 17 wiring.
- *"Direct `javac` passed"* is accurate but is presented in a "Verification" section next to
  behavioural claims. It verifies nothing about the semantics under review.

---

## What I verified and could not fault

- **The four adapter ownership rows are exactly right, and they were the hardest.** `PPA:308/325`
  correctly demand exact-chunk ownership before `ServerLevel.getChunk`, and — unlike the core
  sites — their false branches genuinely return `null` rather than falling through to a weaker
  path. `WNA:110` correctly gates direct `LevelChunk.setBlockState` on exact-chunk ownership.
  These are the sites where a lazy `isTickThread()` would have been easiest to justify and most
  catastrophic, and the worker did not take it.
- **`ownsCachedChunks()` is the right *shape* for a multi-chunk guard.** Requiring ownership of
  every change target *and* every send target — rather than one anchor chunk — is the correct and
  non-obvious formulation. The reasoning is sound even though BLOCKING-1 defeats it downstream.
- **`QueueHandler.run()` → `isGlobalContext()` (row 10) is the best judgement call in the set.**
  `isTickThread()` would have compiled, read fine, and let an arbitrary region tick drain
  unrelated callbacks under the wrong owner. Choosing the strictest predicate as deliberate
  defence, and saying so, is exactly right.
- **The `NMSAdapter` overload split.** I tried hard to break the "compatibility overload is
  unreachable from Folia" claim and could not. Verified against all nine adapter trees.
- **Paper behaviour-equivalence of the two non-obvious restructurings** (noted only because they
  bear on whether the *semantics* were preserved; the depth belongs to the other reviewer):
  `LazyBaseEntity`'s null `sourceEntity` on the 2-arg constructor still reaches
  `TaskManager.sync` → `isTickThread()` → inline on main, so legacy adapters are unchanged; and
  `LocalSession:418`'s `tryLock`/`unlock` pairing stays symmetric under the renamed flag.
- **No site was requalified to a predicate weaker than the ownership question it asks**, with the
  single exception of row 9 (`TaskManager:375`), which is location-free by contract and therefore
  a contract problem rather than a careless choice.
- **Every `// Folia port:` marker is present and its wording matches the chosen predicate.** The
  grep-able touch-point inventory required by architecture §4 is intact.
- **No `folia-api` type leaks into core** (F1): the requalified core sites reference only
  `FaweThreadContext` and core `World`/`Entity`.

---

## Summary for the orchestrator

The predicate table is good work and should mostly survive. Three things must change before
sign-off:

1. **Resolve BLOCKING-1** — decide between narrowing the location-free inline short-circuit to
   `isGlobalContext()` (deviates from §3.5, needs your ruling) or landing §3.5's "wrong tick
   fails" fail-fast now. Do not leave the permissive half shipped alone.
2. **Amend the record** to state that rows 16, 27 and 32 currently enforce nothing, that the
   *true* branch of location-free `sync` is the primary hazard, and that no `FaweThreadContext` is
   registered in production (BLOCKING-3, including the lost pre-boot `true`).
3. **Decide the guard's ambition** — spelling-only (then say so, and assign
   `BukkitBlockCommandSender:194` to a named task) or semantic (then widen it). Close the
   wildcard-static-import and in-`Fawe.java` holes either way; both are one-line fixes.
