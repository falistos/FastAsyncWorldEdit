# Task 15 — C5 requalification of Fawe.isMainThread() call sites (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1
- **Depends on:** task 10 (FaweThreadContext resolver + Bukkit backend — the replacement API).
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: queue pipeline /
  thread-ownership. Each site's requalification is a semantic decision, not a mechanical swap —
  double-reviewer on thread-ownership.

## Mandate
Requalify every `Fawe.isMainThread()` call site in the **Folia-enabled source sets**
(worldedit-core, worldedit-bukkit main, adapter-26.1, folia module) onto the
`FaweThreadContext` predicate (architecture §3.1 / C5), and add the build-time check that
rejects new production references to `Fawe.isMainThread()` from those source sets. **Produce a
per-site requalification record artifact** — this record IS the C3/C5 review deliverable.

## In scope
- For each site (~20 in the Folia-enabled sets — inventory in
  `spikes/recon-queue-threading.md`), requalify to exactly one of:
  `isTickThread()`, `ownsChunk(world, cx, cz)`, `ownsEntity(entity)`, `isFaweWorker()`,
  `isGlobalContext()`, or **remove** (assertion-only sites subsumed by ticket-required
  signatures). Sites that carry a world/chunk/entity MUST use the ownership-specific predicate,
  never a location-free fallback (architecture §3.5).
- **Requalification record artifact** (CREATE — propose path, e.g.
  `.orchestrate/folia-port/c5-requalification-record.md`): one row per site — file:line, the
  old expression, the chosen predicate/removal, and the rationale (what ownership question the
  site actually asks). This is the C5 "review artifact" required by architecture §3 C5.
- **Build-time guard:** reject new production `Fawe.isMainThread()` references from
  Folia-enabled source sets (architecture C5). Prefer an existing FAWE/Gradle mechanism
  (checkstyle/forbidden-apis/custom task) — investigate `gradle/` and existing static-analysis
  config first; propose the diff (build files are orchestrator-owned per architecture §5).

## Out of scope
- The other adapter versions (adapter-26.2, adapter-1_21*) — they remain Paper/Spigot-only
  (spec §2b); their `isMainThread()` sites are NOT in the Folia-enabled sets and MUST NOT be
  touched. (The repo-wide grep shows ~59 raw occurrences across all adapters + the definition;
  only the ~20 in Folia-enabled sets are in scope — enumerate precisely, do not swap blindly.)
- Removing/renaming `Fawe.isMainThread()` itself (the Bukkit path still uses it internally via
  the backend from task 10). Keep the method; guard only NEW references from Folia sets.
- Any behavior change on Paper: the Bukkit backend collapses every predicate to the old
  main-thread identity (task 10), so requalified sites must be behavior-identical on Paper.

## Binding references
- architecture.md §3 C5 (the six requalification targets + build-time check + record =
  review artifact), §3.1 (`FaweThreadContext` predicates), §3.5 (location-carrying lambdas may
  not use the location-free fallback), C6 (global-signal replacement — related but separate).
- `spikes/recon-queue-threading.md` (the site inventory: `QueueHandler.run()` L109 +
  `sync(...)` variants; `SingleThreadQueueExtent.submitUnchecked` L258; `FaweCache` L159;
  `SlowExtent` L30; `LazyBaseEntity` L27; `AbstractChangeSet` L415; `AbstractPlayerActor`;
  `LocalSession`; `EditSessionBuilder`; `PlatformCommandManager`; `TaskManager`; adapter-26.1
  `PaperweightFaweWorldNativeAccess` / `PaperweightPlatformAdapter`; `BukkitWorld` L398;
  `NMSAdapter`; `FaweDelegateSchematicHandler`).
- spec §4b (correctness > performance), §9 (fail-fast internal).

## Signature / structural flags
- Some sites are inside `QueueHandler.sync(...)` private helpers that will be reworked by
  task 16 (FoliaQueueHandler routing). Coordinate: requalify the *predicate* here; leave the
  *routing* redesign to task 16. Flag any site where requalification cannot be done without the
  routing change (hand it to task 16 with a note).
- The Bukkit backend `isFaweWorker()` marker must exist (task 10 escape hatch). If a site needs
  a predicate task 10 did not deliver, escalate NEEDS_CONTEXT.

## Files to touch
- The ~20 site files across worldedit-core / worldedit-bukkit main / adapter-26.1 / folia
  module (enumerate exactly in your record).
- CREATE the requalification record artifact under `.orchestrate/folia-port/`.
- Propose (do not commit) the build-guard diff for orchestrator-owned build files.

## Context & decisions
- Correctness > performance (spec §4b): a mis-requalified site (e.g. `isTickThread` where
  `ownsChunk` is meant) is a latent ownership bug invisible on Paper and catastrophic on Folia.
  When in doubt about which ownership question a site asks, record the doubt and escalate rather
  than guess.
- No FQN inline; resolve `FaweThreadContext.current()` via the core static resolver.

## Executor constraints
- worldedit-core / worldedit-bukkit main / adapter-26.1 = Java 21; folia module = Java 25.
  No folia-api type leaks into core (F1). No git commits. English. `// Folia port:` marker at
  each replaced call (grep-able touch-point inventory, architecture §4).
- Gradle always `--no-configure-on-demand`.

## Escape hatch
If a site's correct requalification is genuinely ambiguous from the code (the ownership question
is not determinable locally), DO NOT guess — record it in the artifact as UNRESOLVED with your
analysis and escalate NEEDS_CONTEXT. A wrong ownership predicate is worse than an escalation.

## Acceptance criteria
- Graph compile proof green:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava
  :worldedit-bukkit:compileJava --no-configure-on-demand`
- Every in-scope site requalified or explicitly UNRESOLVED-with-rationale in the record.
- Zero behavior change on Paper (Bukkit backend identity — reviewer confirms).
- Build guard rejects a seeded new `Fawe.isMainThread()` reference from a Folia-enabled set
  (self-test), and passes on the requalified tree.
- adapter-26.2 / adapter-1_21* untouched (grep confirms).

## Test / verification strategy
Orchestrator runs the compile proof + build-guard self-test. Paper behavior-equivalence is a
regression-matrix concern (spec §7.4) — record as pending the harness/regression run. The
requalification record is reviewed site-by-site by the Opus cross-reviewer.

---
## Dev record (worker fills this in on completion)

- **Status:** <DONE | DONE_WITH_CONCERNS | BLOCKED>
- **File List:**
- **Deviations:**
- **Attack points:**
- **Escalation:** <AUTHORIZED | BLOCKED | NEEDS_CONTEXT> — <detail>
