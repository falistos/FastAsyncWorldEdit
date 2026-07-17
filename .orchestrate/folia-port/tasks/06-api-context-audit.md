# Task 06 — API thread-context audit → §4c disposition table draft (W0.6)

- **Wave:** 0
- **Depends on:** none
- **Status:** todo
- **Executor:** codex (FRESH thread — independent audit, read-only sandbox)

## Objective
The §4c contract input: for every supported public entry point of FaweAPI, EditSession
(public surface), Extent (public API surface), and TaskManager, record the fields required
by spec §4c and propose a disposition. Output file: `.orchestrate/folia-port/spikes/w06-api-context-audit.md`
(delivered via final message; read-only sandbox cannot write).

## Out of scope
No implementation proposals beyond disposition; no source changes; internal-only classes
(the internal migration is C3/C5's job, not this audit).

## Binding references
- spec §4c verbatim — the fields per entry point: binary/source compat; legal caller
  contexts (region, entity, global, async, FAWE worker); required target context;
  blocking/return behavior; completion point; callback thread; cancellation; error
  propagation; disposition = PRESERVED (with deterministic context derivation) | DEGRADED
  (deterministic failure) | EXCLUDE (needs signed amendment).
- architecture.md §3 C3.
- Key classes: `com.fastasyncworldedit.core.FaweAPI`, `com.sk89q.worldedit.EditSession`
  (+ `EditSessionBuilder`), `com.fastasyncworldedit.core.util.TaskManager`,
  `com.sk89q.worldedit.extent.Extent` hierarchy public surface,
  `com.sk89q.worldedit.WorldEdit` + `SessionManager` public methods used by plugins.

## Context & decisions
- Judge "legal caller contexts" from what the method actually touches (follow the call
  chain far enough to know if it hits live server state, FAWE queues, or pure data).
- Location-free scheduling methods are the crux (spec finding F3): propose deterministic
  derivations (e.g. "derive from the EditSession's world extent"; "global region when no
  location exists") — flag every case where no deterministic derivation exists.
- Prioritize by real-world usage: methods used by PlotSquared and typical FAWE consumers
  first (grep this repo's own usage as a proxy); mark rarely-used tail entries LOW-CONF
  rather than skipping.
- Table format: `ID (APIC-001...) | entry point | fields... | disposition | confidence`.

## Escape hatch
If a method's behavior can't be classified statically, mark NEEDS-RUNTIME with the exact
question, don't guess. If the public surface is materially larger than the classes listed
above, report the gap (with counts) instead of silently expanding scope.

## Acceptance criteria
Complete table for the listed classes' public surface, prioritized, with per-entry
disposition + confidence; summary of counts by disposition; list of NEEDS-RUNTIME items.

---
## Dev record (orchestrator fills from Codex final message)

> This section was left empty at delivery (certification finding 7). It is backfilled post-hoc
> from the on-disk artifacts (`spikes/w06-api-context-audit.md`, `spikes/w06b-apic-gap-closure.md`)
> during the certification correctives — the artifacts, not a live worker message, are the source.

**Status:** DONE_WITH_CONCERNS (both artifacts self-report DONE_WITH_CONCERNS).

**File List:**
- `.orchestrate/folia-port/spikes/w06-api-context-audit.md` — CREATE (via final message): the
  §4c disposition table for the named source sets (FaweAPI, EditSessionBuilder, TaskManager,
  Extent contract hierarchy, EditSession, WorldEdit, SessionManager). 382 public declarations:
  374 PRESERVED / 8 DEGRADED / 0 EXCLUDE.
- `.orchestrate/folia-port/spikes/w06b-apic-gap-closure.md` — CREATE (via final message):
  gap-closure pass, APIC-092–362 — QueueHandler location-free surface (10 declarations), the
  257 Extent-derived source declarations across 24 types, and the 4 `Fawe` UUID-keyed executor
  methods = 271 declarations, all PRESERVED (0 DEGRADED / 0 EXCLUDE).
- No source changes (read-only audit; sandbox could not write — both artifacts delivered by
  final message).

**Counts:**
- w06: 382 total — PRESERVED 374, DEGRADED 8, EXCLUDE 0. The 8 DEGRADED are `TaskManager.runUnsafe`;
  `Extent.regenerateChunk` + three `EditSession.regenerate` overloads; `EditSession.generateFeature`
  + `generateStructure` (DEGRADED pending certification, NR-05); `WorldEdit.runScript`.
- w06b: 271 total — PRESERVED 271, DEGRADED 0, EXCLUDE 0 (APIC-092–362).
- Combined §4c census: 382 + 271 = **653** declarations (also recorded architecture.md §7 F9).
- NEEDS-RUNTIME: NR-01…NR-07 (w06 §NEEDS-RUNTIME), plus w06b's QueueHandler
  current-context/global-safe callback constraint.

**Deviations:**
- The dev record was left empty at delivery (certification finding 7); this section is a
  post-hoc backfill from the two artifacts during the certification correctives, not a
  worker-authored completion message.
- Per the task escape hatch, w06 did not silently expand into the concrete Extent
  implementations (est. ~255); w06b closed that gap explicitly at 257 source declarations
  across 24 types (259 compiled members, minus one compiler-generated ctor and one synthetic bridge).
- Architecture C3 names `QueueHandler.sync(...)` although task 06's source list omitted it;
  w06b added the 10 QueueHandler location-free methods rather than folding them silently.
- The 4 UUID-keyed clipboard/history executor methods were labelled "FaweAPI" by task 07 with a
  stale `FaweBukkit.java:475-521` anchor; w06b classifies them on `Fawe` and adds them to the census.
- Overload rows are grouped by identical §4c contract; the Count column enumerates every
  declaration, so counts are per source declaration.

**Attack points (copied from `spikes/w06-api-context-audit.md` §Attack points — 10):**
1. Opaque TaskManager callbacks are the central misuse hazard — hidden player/world/chunk in
   lambdas must migrate to context-carrying variants, else deterministic contract becomes late
   Folia ownership exceptions.
2. A no-op `runUnsafe` is not an acceptable disposition; APIC-028 must fail before callback
   invocation (its advertised suppression semantics are C1-forbidden).
3. Staged counts are not success counts — most EditSession boolean/int results describe detached
   preparation; terminal state is `close`/`flushQueue`.
4. Current terminal error handling (`completeBlindly`, catch-and-log, swallowed interrupts)
   violates the report-exactly-once contract.
5. Raw Extent escape hatches (`getBypassAll`/`getExtent`/`setExtent`, processors, patterns,
   masks, replacement extents) can bypass the routing model / retain live Bukkit-NMS objects (C2).
6. `lazyCopy` is only safe as a routing proxy; `WorldCopyClipboard` must not hand live
   collections/extents to workers — each lazy read must become a region-captured detached snapshot.
7. Tick-thread lifecycle calls (multi-region `close`, `flushQueue`, undo/redo, cold
   `SessionManager.get`, file selection, scripts) are a deadlock boundary — must reject
   wrong-owner before partial work.
8. Entity + block ownership can require two schedulers (input events, entity removal, block-bag,
   packet sends, neighbor effects) — validate same-owner or split finalizers without region→worker waits.
9. Feature/structure generation cannot route solely from its origin — fail-closed until footprint
   and capture isolation are proven (APIC-074, NR-05).
10. SessionManager serializes unrelated regions — cold load and owner callbacks under the
    synchronized monitor are a contention + lock-order surface.
