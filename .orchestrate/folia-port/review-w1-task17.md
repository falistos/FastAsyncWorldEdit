# Cross-review — Wave 1, Task 17 (bootstrap wiring + Folia `FaweThreadContext`)

Reviewer: fresh independent Opus thread. Read-only on source. Everything below is grounded in
files I opened and, for the load-bearing Folia predicates, in the actual `folia-api:26.1.2.build.8-stable`
jar (`javap` + bundled sources javadoc) and PaperMC/Folia region-threading docs. Dev-record claims
were treated as claims and re-derived.

## VERDICT: PASS-WITH-NOTES

The crux — `FoliaThreadContext` — is correct: every ownership predicate is a genuine live Folia
query, never a region-ID comparison, and the non-owner case genuinely returns false. Bootstrap
ordering is sound in production; F1 is clean; selection is fail-closed and cannot register both or
the wrong backend; there is exactly one completion service and the frozen shutdown order holds.
No BLOCKING or MAJOR defect found. The notes are (a) two unit tests whose headline properties are
not actually what they exercise, and (b) the plugin-level fail-closed abort resting on runtime
`onLoad`-throw semantics that no deterministic test pins. Nothing here is fail-open.

---

## `FoliaThreadContext` correctness verdict (per predicate)

File: `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaThreadContext.java`
Raw predicates live in the nested `LiveOwnershipPredicates` enum (:105-128).

- **`ownsChunk(world,cx,cz)` — CORRECT.** Backed by `Bukkit.isOwnedByCurrentRegion(world.world(), chunkX, chunkZ)`
  (:115). Verified against the certified jar: the `(World,int,int)` overload exists and its javadoc
  reads *"the region being ticked owns the chunk at the specified world and chunk position … chunkX/chunkZ"*
  — the two ints are **chunk** coordinates, and the code passes chunkX/chunkZ. This is a live scheduler
  ownership query, not a `RegionKey`/region-ID compare (no `RegionKey` is referenced anywhere in the
  predicate path).
  - **Non-owner scenario (constructed):** thread is ticking region R1 (owns chunks near origin) and asks
    `ownsChunk(world, 1000, 1000)` whose chunk belongs to region R2. `isOwnedByCurrentRegion` returns
    true only when *the currently-ticking region* owns that chunk; R1 does not own (1000,1000) →
    returns **false**. The predicate correctly rejects a thread that is on *a* region thread but does
    not own *this* chunk. This is the property the whole no-corruption guarantee rests on, and it holds.
- **`ownsEntity(entity)` — CORRECT.** `Bukkit.isOwnedByCurrentRegion(entity)` (:120). Folia javadoc:
  *"this function is the only appropriate method of checking for ownership of an entity."* Genuine.
- **`isTickThread()` — CORRECT.** `Bukkit.isPrimaryThread()` (:110). On Folia this returns true for
  **any** tick thread — region *and* global (PaperMC docs/source: `isPrimaryThread()` is deliberately
  *not* equivalent to the global-only check; entity work runs on the owning region tick thread, async
  scheduler threads are not tick threads). Matches §3.1 "any server tick thread (region/entity/global)".
- **`isGlobalContext()` — CORRECT.** `Bukkit.isGlobalTickThread()` (:125) — "current thread is ticking
  the global region". Matches §3.1. Consistency check on the global thread: isTickThread=true,
  isGlobalContext=true, ownsChunk/ownsEntity=false (global owns no chunks/entities) — internally coherent.
- **`isFaweWorker()` — CORRECT and correctly narrow.** `Thread.currentThread() instanceof FaweThread
  faweThread && faweThread.getCurrentExtent() != null` (:46). True ONLY on an extent-carrying
  `FaweThread`; a bare `FaweThread` with no extent, and a non-`FaweThread` carrying an extent via the
  `FaweThreadUtil` thread-local, both return false. Not widened to "any FAWE thread"; the TaskManager's
  plain `ForkJoinPool` threads are excluded because they are not `FaweThread`.

**Null / unloaded / cross-world inputs — predicates THROW (fail-closed), they do not return false.**
`FoliaThreadContext.ownsChunk/ownsEntity` `requireNonNull` the arguments, then `LiveFoliaTargetAdapter`
(:42-74) rejects a non-Bukkit world/entity with `IllegalArgumentException`, an unloaded world with an
`IllegalStateException` (via `BukkitWorld.getWorld()`'s `checkNotNull`, BukkitWorld.java:198), and a
GC'd/retired entity with `IllegalStateException` (`FoliaEntityHandle.from(null)`). This never fails
open (never falsely claims ownership), but see MINOR-3: a requalified caller expecting a boolean gets
an exception instead of a graceful `false → route to dispatcher`.

Bridge verification: `BukkitWorld.getWorld()` returns `org.bukkit.World`; `BukkitEntity.getEntityHandle()`
(BukkitEntity.java:71) returns the live `org.bukkit.entity.Entity` from its `WeakReference` (not the NMS
handle), so `FoliaEntityHandle.from`'s `instanceof org.bukkit.entity.Entity` check passes when alive.
Reflective, string-named lookup — no compile linkage folia→bukkit-main.

## Bootstrap-ordering verdict: SOUND in production; can nothing read a predicate before registration?

Traced the real code, not the dev record's prose:

1. `WorldEditPlugin.onLoad()` — detection `FoliaSupport.isFolia()` (:134) → `checkCertifiedOrFail()`
   (:137) → **`BackendSelector.selectAndRegister(folia, getClassLoader())` (:142)** which calls
   `backend.registerThreadContext()` → `ContextResolver.register(...)`. Lines 134–141 read no
   predicate. Registration is the effective first side-effecting FAWE step.
2. `WorldEditPlugin.onEnable()` — `new FaweBukkit(this, faweBackend)` (:263); its constructor calls
   `backend.createTaskManager` (FaweBukkit:69) then `Fawe.set` (:71). `QueueHandler` is built lazily
   only on the first `Fawe.getQueueHandler()` → `backend.createQueueHandler()`.

The first predicate reader, `QueueHandler.run()` (QueueHandler.java:138 reads `isGlobalContext()`), is
scheduled from the `QueueHandler` constructor's `TaskManager.taskManager().repeat(this,1)` (:119), which
runs inside `createQueueHandler` — strictly after registration. The escape-hatch condition (TaskManager
built before `onLoad` completes) does not occur. **No production path reaches a requalified predicate,
`QueueHandler.run()`, a cache, supplier, scheduler, or command before registration.**

Defense in depth is real and layered: `ContextResolver.resolve()` throws pre-registration (fail-closed,
no permissive default); both backends' state machines reject out-of-order calls (`BukkitPlatformBackend`/
`FoliaPlatformBackend` `requireState`: NEW→REGISTERED→TASK_MANAGER_CREATED→QUEUE_CREATED); and
`ContextResolver.register` is a CAS so a second registration throws.

One integration subtlety worth recording as verified-good: `FoliaQueueHandler.run()` throws
`UnsupportedOperationException`, and the base-class constructor would otherwise schedule it every tick —
but `FoliaTaskManager.repeat` (:172-176) explicitly strikes the `FoliaQueueHandler` drain registration
(`return -1`), so `run()` is never invoked by the scheduler. No per-tick exception storm.

Caveat (MINOR-4): the *test* that claims to guard this does not exercise the `onLoad`/`onEnable`
sequence; see test section.

## F1 module-boundary verdict: CLEAN

- `worldedit-core`: **zero** references to `com.fastasyncworldedit.bukkit.folia` (grep clean).
- `worldedit-bukkit` main: the only folia-package imports are `FoliaSupport` + `UnsupportedFoliaVersionException`
  (WorldEditPlugin:25-26). Both live in the **main** module's `com.fastasyncworldedit.bukkit.folia`
  package (`worldedit-bukkit/src/main/...`), carry no `folia-api` type in any signature/field, and detect
  purely by reflection (`Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`). No import
  of any folia-**submodule** (Java-25) type from core or bukkit-main.
- Discovery is reflective `ServiceLoader.load(FawePlatformBackend.class, classLoader)`, wrapped in a
  `Supplier` that is `.get()`-ed **only** in the `folia==true` branch (`BackendSelector.selectFoliaBackend`).
  On Paper (`folia==false`) the supplier is never evaluated, so `FoliaPlatformBackend` is never
  classloaded — a single eager classload of a Java-25 type on a Paper JVM cannot happen here. The
  `nonFoliaSelectionNeverLoadsTheFoliaProvider` test genuinely pins this.
- Only reflective `Class.forName` of a Folia name in main/core is the `RegionizedServer` detection probe.
- ServiceLoader descriptor `META-INF/services/…FawePlatformBackend` → `com.fastasyncworldedit.bukkit.folia.FoliaPlatformBackend`
  lives only in the folia submodule resources (Paper artifact only, per packaging).

## Fail-closed verdict: PASS at the selector; plugin-level abort is runtime-dependent and untested

- Uncertified Folia: `onLoad` (:135-140) throws before selection/registration. `CERTIFIED_MINECRAFT_VERSIONS`
  is exactly `Set.of("26.1.2")` (FoliaSupport:24) — matches A2.1; not regressed.
- Missing backend (Spigot artifact on Folia): `selectFoliaBackend` finds no provider → throws
  `IllegalStateException(MISSING_FOLIA_BACKEND_MESSAGE)` **before** `registerThreadContext`, so nothing
  is registered and no service is constructed. Clear operator message. `missingFoliaBackendFailsWithOperatorMessageBeforeAnyRegistration`
  asserts the message and that the Bukkit backend is never constructed as a fallback.
- No half-init before the throw: at `onLoad:142` no listener, executor, world access, `INSTANCE`,
  platform registration, or `Fawe.set` has happened yet (all are later). See MINOR-1 for the residual.

---

## Findings (most severe first)

**MINOR-1 — Plugin-level fail-closed abort relies on `onLoad`-throw semantics that no deterministic
test covers.** `BackendSelector` throwing is verified by unit test, but spec §2b's requirement ("plugin
does not half-start") is a *plugin-lifecycle* property. On standard CraftBukkit, `onEnable` is invoked
even when `onLoad` threw; then `new FaweBukkit(this, faweBackend==null)` NPEs at FaweBukkit:69 before any
listener/executor (WorldEditPlugin:271+) is registered — so it is still functionally fail-closed and
leaks no registered context, but the *clean operator message* is not what stops enablement; a secondary
NPE is. Whether the target Paper/Folia 26.1.2 build aborts on `onLoad`-throw I could not verify from here.
This mirrors FAWE's pre-existing wrong-jar `onLoad` throws, so it is consistent with established
behavior — but no test pins the plugin abort. The dev record acknowledges this and defers to the harness
fail-closed leg. Recommend the harness leg assert: no listeners/tasks/world access and no leaked
`ContextResolver` registration after the abort.

**MINOR-2 — The ownership test's headline assertion is vacuous; the high-stakes property is untested by
it.** `FoliaThreadContextTest.ownershipUsesTheLiveQueryAndNeverASameRegionHint` (:20-53) swaps in a fake
`OwnershipPredicates`, so the production `Bukkit.isOwnedByCurrentRegion` path is never exercised. The
`sameRegionHint`/`sameRegionHintReads` machinery is a decoy: `FakeOwnershipPredicates.sameRegionHint()`
is `private @SuppressWarnings("unused")` and is called by nothing, so `assertEquals(0, sameRegionHintReads)`
**cannot fail** regardless of implementation. What the test *does* prove is narrow but real —
`FoliaThreadContext` faithfully returns its `OwnershipQueries` result and does not consult a hint of its
own. It does **not** prove "live ownership beats opposing region hints"; that the production predicate
uses the live query rather than a region-ID compare is established only by source inspection (which I did
— it is correct, FoliaThreadContext.java:115/120) and must be covered by the runtime harness. Exactly the
"assertion on a counter nothing increments" anti-pattern this effort has been burned by. Not a code
defect; a false-confidence test.

**MINOR-3 — Ownership predicates throw instead of returning false on unloaded/retired/non-Bukkit inputs.**
Per r12 Q1 a false ownership result should route the callback through the target-bearing dispatcher; an
exception bypasses that routing. This is fail-closed (never falsely true) and the inputs indicate the
operation cannot proceed anyway, so risk is low — but the `ownsChunk`/`ownsEntity` boolean contract is
effectively "true / false / throws", which a requalified site's `if (owns) inline else route` does not
anticipate. Document the throw-vs-false contract, or have the seams treat these as a fail-before-side-effect.

**MINOR-4 — `BootstrapOrderingTest` proves the fail-closed *mechanism*, not the production *sequence*.**
It manually nulls `ContextResolver.CONTEXT`, asserts `QueueHandler.run()` and `FaweThreadContext.current()`
throw pre-registration, registers, then asserts the scheduled queue reads exactly one predicate. Genuine
and non-vacuous: it fails if `current()` gains a permissive default or if `run()` stops reading the
predicate (I verified the mechanics — `RecordingTaskManager.repeat` captures the `QueueHandler` via the
base-class `repeat(this,1)`, and `run()` reads `isGlobalContext()`). But it never runs
`WorldEditPlugin.onLoad/onEnable`; if a future edit moved `selectAndRegister` after `new FaweBukkit(...)`,
this test would still pass. The runtime fail-closed resolver + backend state machine would catch such a
regression at boot, but no test pins the onLoad-before-onEnable registration order itself.

**MINOR-5 (observational, task-10 not task-17) — cross-backend `isFaweWorker` divergence.**
`FoliaThreadContext.isFaweWorker` requires `FaweThread` AND `getCurrentExtent()!=null` (matches the core
interface javadoc "extent-carrying"); `BukkitThreadContext.isFaweWorker` (BukkitThreadContext.java:54)
returns true for *any* `FaweThread`. Same interface method, two meanings. Defensible as legacy-Paper
preservation (arch §3.1 says Bukkit = pool membership), but worth reconciling the interface contract vs.
the Bukkit impl. Out of this task's perimeter; flagged for the record.

**MINOR-6 (observational) — certified-version string match is unverified.** `checkCertifiedOrFail` compares
`Bukkit.getMinecraftVersion()` against `"26.1.2"` (FoliaSupport:59-60); the code comment admits the exact
runtime string is unverified without a live server. A format mismatch fails *closed* (rejects a certified
build), not open. Harness must confirm the literal string. Detection's concern, acknowledged in-code.

---

## Test-quality verdict, test by test

- `FoliaThreadContextTest.ownershipUsesTheLiveQueryAndNeverASameRegionHint` — **PARTLY VACUOUS.** The
  `assertFalse/assertTrue` on delegation CAN fail (if FoliaThreadContext stopped honoring its predicate);
  the `assertEquals(0, sameRegionHintReads)` CANNOT fail (dead decoy). Does NOT distinguish "owns this
  chunk" from "is on some region thread" at the production layer — production predicate is faked out. See
  MINOR-2.
- `FoliaThreadContextTest.tickAndGlobalRolesDelegateToThePlatformPredicates` — **WEAK-BUT-HONEST.** A pure
  delegation test; CAN fail if delegation breaks, but does not exercise `Bukkit.isPrimaryThread()/isGlobalTickThread()`
  (harness territory). Names delegation, tests delegation — no false advertising.
- `FoliaThreadContextTest.workerRoleRequiresTheExtentCarryingThreadMarker` — **STRONG.** Genuinely pins
  BOTH conditions: `idleResult` fails the suite if the extent check were dropped; `plainResult` fails it
  if the `instanceof FaweThread` guard were dropped (a non-FaweThread with a thread-local extent stays
  false). This is the model the others should follow.
- `BootstrapOrderingTest.queueRunAndRequalifiedPredicatesFailUntilRegistrationCompletes` — **GENUINE,
  scoped to the mechanism.** Fails red if `current()` gains a permissive default or `run()` stops reading
  the predicate. Does not cover the production bootstrap order (MINOR-4).
- `BackendSelectorTest.nonFoliaSelectionNeverLoadsTheFoliaProvider` — **GENUINE.** Fails if the folia
  supplier is ever evaluated on the Paper path (throws `AssertionError` inside the supplier).
- `BackendSelectorTest.missingFoliaBackendFailsWithOperatorMessageBeforeAnyRegistration` — **GENUINE.**
  Pins the exact operator message and that no Bukkit fallback is constructed. Tests the selector, not the
  plugin lifecycle (MINOR-1).
- `BackendSelectorTest.selectionRegistersBeforeEitherServiceCanReachAPredicate` — **GENUINE.** The fake
  backend's `createTaskManager/createQueueHandler` throw unless `registered`, so the `[context,
  task-manager, queue-predicate]` order is enforced; fails red on reorder. Fakes the backend, so it pins
  the selector's contract, not `FoliaPlatformBackend`'s internal state machine (which I read separately
  and which enforces the same order).

Net: the eager-Folia-loading, missing-backend, premature-`QueueHandler.run()`, and worker-misclassification
claims are all backed by tests that CAN fail on their named property. The **false-ownership** claim is the
exception — its test cannot fail on the property that matters and the real protection is source inspection
+ the C5 build guard + the (deferred) harness.

## Dev-record accuracy

Accurate where I could check: file list matches; the init-order trace (onLoad detect→certify→register,
onEnable FaweBukkit→createTaskManager before `Fawe.set`, lazy queue) matches the code; "one
`OperationCompletionService` shared by dispatcher/backpressure/broker" is true (single `new` at
FoliaPlatformBackend:64, passed to all three); shutdown order `stopAccepting → drain → completion flush →
executor shutdown` matches `shutdownGraph` (:117-130); `CERTIFIED_MINECRAFT_VERSIONS` is exactly `26.1.2`;
the `FoliaTickThreadGuard` C5 allowance is gone from `c5Allowances` (only the `Fawe.java` identity compare
and the `BukkitThreadContext` `Fawe.isMainThread()` allowances remain) and the guard now resolves solely
via the registered context; `FoliaThreadContext.java` fills the reserved `c5ApprovedBackends` slot.
Over-statements: the ordering test "proving … paths cannot execute before registration" and the ownership
test proving "live ownership beats opposing region hints" both overstate what the tests exercise (MINOR-2,
MINOR-4). Status `DONE_WITH_CONCERNS` with runtime/packaging deferred to the orchestrator is honest.

## What I verified and could NOT fault

- Every `FoliaThreadContext` predicate is a live Folia query (chunk-coord semantics, entity ownership,
  tick/global roles) verified against `folia-api:26.1.2.build.8-stable` and Folia docs; no region-ID
  comparison anywhere; the constructed non-owner case returns false.
- Production bootstrap registers the context before any requalified predicate, `QueueHandler.run()`,
  cache, supplier, scheduler, or command path can run; triple-guarded (fail-closed resolver, backend
  state machine, CAS single-registration).
- F1: core has zero folia references; bukkit-main links only the reflective detector pair; folia backend
  discovered lazily via ServiceLoader and never classloaded on Paper.
- Fail-closed selection: cannot register both backends, cannot pick the wrong one, emits a clear operator
  message when the backend is absent, and registers nothing on that path.
- Exactly one completion service, shared by all coordinators; frozen shutdown order honored and
  idempotent; disable routes `Fawe.onDisable → FaweBukkit.onDisable → backend.shutdown`.
- `FoliaTaskManager.repeat` correctly suppresses the base-class per-tick drain for `FoliaQueueHandler`.
- Commit path is intentionally inert (`CommitAction.unavailable()`), so bootstrap registers services
  without admitting chunk operations — correct wave-1 scope; wave 2 must replace it.
