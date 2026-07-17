# Spike W0.2 — Regionized chunk write pipeline: results (Folia 26.1.2)

- **Wave:** 0 · **Task:** 02 · **Status:** measured, consolidated
- **Runs:** initial boot `harness/logs/folia-26.1.2-20260717-151811.log`; restart readback
  `harness/logs/folia-26.1.2-20260717-151846.log`. Both boots exited cleanly; both
  zero-tolerance harness scans were clean.
- **Probe:** `harness/probe/` (paperweight-userdev against Folia `26.1.2.build.8-stable`,
  Mojang-production mappings — the SS2 precedent route; no reflection fallback needed).
- **Evidence convention:** every claim below cites a verbatim `FAWE_HARNESS_*` marker from
  the logs. The markers are the ground truth; interpretation follows each one.

---

## 0. TL;DR

**GO** on the architecture §1 model (detached-prepare → commit-on-owning-region), with one
mandatory contract addition: **relight completion callbacks land on NON-owner threads**
(`Paper_Common_Worker_#0`, `owner=false`) — the commit contract MUST mandate a re-dispatch
to the owning region before any callback touches live state.

Strategy ranking: **(1) region-sweep, (2) batched-neighbor, (3) per-chunk.** Visibility was
scheduler-delay dominated (~51-55 ms ≈ 1 region tick) in all three; region-sweep wins on
task count (4 vs 16) and max commit-task cost (364 µs vs 551/705 µs) at equal visibility.

---

## 1. Experiment matrix — measured results

### Leg 1 — Safety: detached prepare → owner commit → save → restart readback

Evidence (initial run):

```
FAWE_HARNESS_PIPELINE_SAFETY_OK prepare_thread=fawe-pipeline-worker-1 commit_thread=Folia_Region_Scheduler_Thread_#1 owner=true visible_blocks=4096 expected=4096 ticks_during_prepare=3 max_tick_interval_us=51678 elapsed_us=202946 live_refs_on_worker=false save_marker=emerald_section_0_5_0
```

Evidence (restart run):

```
FAWE_HARNESS_PIPELINE_READBACK_OK owner=true blocks=4096 expected=4096 material=EMERALD_BLOCK thread=Folia_Region_Scheduler_Thread_#1
```

**Result: PASS.** A `LevelChunkSection` was built entirely on a probe worker thread
(`fawe-pipeline-worker-1`) from palette data, with `live_refs_on_worker=false` (C2 detached
discipline held); the swap ran on the owning region thread (`owner=true`). All 4096 blocks
were immediately visible, and all 4096 survived a full save + server restart. The target
region ticked **3 times during the detached prepare** (`ticks_during_prepare=3`,
`max_tick_interval_us=51678` ≈ normal 50 ms cadence) — the region was live and unblocked
while the worker prepared. Zero TickThread/ownership violations in either log (scan clean).

### Leg 2 — Commit-strategy tournament (16 chunks, 4 target groups, 4 observed regions)

Evidence (one marker per strategy; region_ticks payload elided here, quoted in full in §3):

```
FAWE_HARNESS_TOURNAMENT_OK strategy=per-chunk chunks=16 target_groups=4 observed_regions=4 commit_tasks=16 probe_queue_highwater=16 max_schedule_delay_us=52194 max_commit_task_us=551 visibility_us=54964 visible_blocks=65536 expected_blocks=65536 owner_mismatches=0 ...
FAWE_HARNESS_TOURNAMENT_OK strategy=batched-neighbor chunks=16 target_groups=4 observed_regions=4 commit_tasks=8 probe_queue_highwater=8 max_schedule_delay_us=50793 max_commit_task_us=705 visibility_us=51322 visible_blocks=65536 expected_blocks=65536 owner_mismatches=0 ...
FAWE_HARNESS_TOURNAMENT_OK strategy=region-sweep chunks=16 target_groups=4 observed_regions=4 commit_tasks=4 probe_queue_highwater=4 max_schedule_delay_us=51089 max_commit_task_us=364 visibility_us=51321 visible_blocks=65536 expected_blocks=65536 owner_mismatches=0 ...
```

**Result: PASS, all three strategies exact.** 65536/65536 blocks visible per strategy,
`owner_mismatches=0` across four observed region IDs (4, 5, 6, 7) in every run. Ranking and
analysis in §3.

### Leg 3 — Cross-region hazards (packet send + neighbor updates)

Packet evidence:

```
FAWE_HARNESS_PACKET_OK player=pipeline_01 packet_chunk=96,96 cross_owner=true tracked_viewer=false packet_ready=true ack_scheduled=true send_thread=Folia_Region_Scheduler_Thread_#0 client_ack=pending_bot_chat
```

**Result: PARTIAL PASS.** `ClientboundLevelChunkWithLightPacket` was built on the chunk's
region thread without assertion (`packet_ready=true` — A1/A3 supported), and
`connection.send` to a player with a **different owner** (`cross_owner=true`) did not throw
(A2 supported at the server side). Two explicit limits: `tracked_viewer=false` — the player
was not a tracked viewer of the packet chunk, so this is NOT a tracked-viewer guarantee —
and `client_ack=pending_bot_chat` — client-visible payload confirmation is still OPEN
(post-send bot chat succeeded, block payload unconfirmed).

Neighbor evidence:

```
FAWE_HARNESS_NEIGHBOR_OK mode=suppressed chunk_boundary=true neighbor_owned_by_caller=true events=0 source=REDSTONE_BLOCK neighbor=REDSTONE_WIRE thread=Folia_Region_Scheduler_Thread_#1
FAWE_HARNESS_NEIGHBOR_OK mode=normal chunk_boundary=true neighbor_owned_by_caller=true events=94 source=REDSTONE_BLOCK neighbor=AIR thread=Folia_Region_Scheduler_Thread_#1
```

**Result: PARTIAL PASS.** Neighbor updates across a **chunk** boundary work in both modes:
suppression flags produce `events=0`, normal mode produced `events=94` physics events. But
`neighbor_owned_by_caller=true` in both — the boundary crossed was a chunk edge inside one
region, not a proven Folia **region** boundary. The cross-region neighbor question stays
OPEN (§2, W05-Q2).

Event-dispatch evidence (A4), 15 `BlockPhysicsEvent` + 1 `ItemSpawnEvent` markers, all of
the form:

```
FAWE_HARNESS_EVENT event=BlockPhysicsEvent owner=true chunk=81,82 thread=Folia_Region_Scheduler_Thread_#1
FAWE_HARNESS_EVENT event=ItemSpawnEvent owner=true chunk=81,82 thread=Folia_Region_Scheduler_Thread_#1
```

**Result: PASS (partial coverage).** `BlockPhysicsEvent` and `ItemSpawnEvent` dispatch on
the owning region thread (`owner=true`). `EntityChangeBlockEvent` did not occur in the
captured markers — A4 unconfirmed for that event type.

### Leg 4 — Lighting (`queueSectionData` + `starlight$serverRelightChunks`)

Evidence:

```
FAWE_HARNESS_QUEUE_SECTION_OK group=0 owner=true thread=Folia_Region_Scheduler_Thread_#0
FAWE_HARNESS_RELIGHT_SUBMIT group=0 accepted=1 requested=1 owner=true thread=Folia_Region_Scheduler_Thread_#0
FAWE_HARNESS_RELIGHT_CHUNK_CALLBACK group=0 chunk=96,96 owner=false thread=Paper_Common_Worker_#0
FAWE_HARNESS_RELIGHT_COMPLETE_CALLBACK group=0 completed=1 thread=Paper_Common_Worker_#0
FAWE_HARNESS_RELIGHT_RESULT group=0 accepted=1 requested=1 completed=1 neighbor_block_light=14 cross_chunk_correct=true verify_thread=Folia_Region_Scheduler_Thread_#0
FAWE_HARNESS_QUEUE_SECTION_OK group=1 owner=true thread=Folia_Region_Scheduler_Thread_#1
FAWE_HARNESS_RELIGHT_SUBMIT group=1 accepted=2 requested=2 owner=true thread=Folia_Region_Scheduler_Thread_#1
FAWE_HARNESS_RELIGHT_CHUNK_CALLBACK group=1 chunk=224,224 owner=false thread=Paper_Common_Worker_#0
FAWE_HARNESS_RELIGHT_CHUNK_CALLBACK group=1 chunk=225,224 owner=false thread=Paper_Common_Worker_#0
FAWE_HARNESS_RELIGHT_COMPLETE_CALLBACK group=1 completed=2 thread=Paper_Common_Worker_#0
FAWE_HARNESS_RELIGHT_RESULT group=1 accepted=2 requested=2 completed=2 neighbor_block_light=14 cross_chunk_correct=true verify_thread=Folia_Region_Scheduler_Thread_#1
FAWE_HARNESS_LIGHTING_OK groups=2 cross_chunk_verified=2 concurrent_region_submits=2
```

**Result: PASS with a mandatory contract consequence.**
- `queueSectionData` is legal from an owner-region task (`QUEUE_SECTION_OK owner=true`,
  both groups) — W0.3's L1/L2 commit-path light injection path is confirmed viable.
- `starlight$serverRelightChunks` **accepts submission from a region thread**
  (`RELIGHT_SUBMIT owner=true`, all requests accepted) and does NOT throw a TickThread
  assertion — it internally reschedules.
- **Critical:** both the per-chunk callback and the completion callback ran on
  `Paper_Common_Worker_#0` with `owner=false` — a NON-owner thread. Any callback code that
  touches live state (tickets, packets, chunk fields) MUST re-dispatch to the owning region
  first. See §4.
- Light values were correct across chunk edges (`neighbor_block_light=14`,
  `cross_chunk_correct=true`, both groups) under two concurrent per-region submissions
  (`concurrent_region_submits=2`). Caveat: the adjacent pairs were not proven to have two
  different owners, and no Paper oracle comparison was run — the cross-REGION starlight
  correctness question (W0.3-Q3) stays OPEN.

### Leg 5 — Environment answers

Evidence:

```
FAWE_HARNESS_ENV_OK minecraft=26.1.2 bukkit=26.1.2.build.8-stable name=Folia paperlib_is_paper=true thread=Folia_Region_Scheduler_Thread_#0
```

**Result: ANSWERED.** `Bukkit.getMinecraftVersion()` returns exactly `26.1.2` (no suffix),
Bukkit version is `26.1.2.build.8-stable`, server name is `Folia`, and
`PaperLib.isPaper()` is `true` on Folia. The W0.9 guard's matrix comparison against
`"26.1.1"`/`"26.1.2"` is therefore correct as written.

**FAWE-jar fail-closed boot leg: DELIBERATELY SKIPPED (out of matrix).** Orchestrator
decision: the out-of-matrix abort is deterministic code with no runtime uncertainty —
wave-3 deterministic tests own it. Recorded as such; not a gap in this spike's evidence,
but the fail-closed path remains runtime-unexercised until then.

### Leg 6 — W0.4 Q1: startup-declared scratch world

Evidence:

```
FAWE_HARNESS_STARTUP_WORLDS phase=load worlds=
FAWE_HARNESS_STARTUP_WORLDS phase=enable worlds=
FAWE_HARNESS_SCRATCH_RESULT loaded=false worlds=fawe-harness,fawe-harness_nether,fawe-harness_the_end
```

**Result: NEGATIVE (answered).** The `bukkit.yml` generator declaration did NOT cause Folia
to instantiate `fawe-scratch`; only the three standard harness worlds loaded. W0.4's
alternative (d) — a pre-provisioned regionised scratch world — is unavailable via this
route. **//regen's DISABLE disposition stands.**

---

## 2. Question ledger

Every runtime question from the cited sources, with status.

### From `spikes/w03-lighting.md` §6

| # | Question | Status | Evidence & implication |
|---|---|---|---|
| Q1 | `PaperLib.isPaper()` true on Folia? | **ANSWERED — yes** | `ENV_OK ... paperlib_is_paper=true`. The default relighter factory on Folia is starlight today; the Folia backend must override the factory (per W0.3 L3 → NMSRelighter). |
| Q2 | Region-thread legality of `serverRelightChunks` | **ANSWERED** | `RELIGHT_SUBMIT ... owner=true` accepted without assertion → answer (c): it runs from a region thread and internally reschedules. Callback threads captured: `Paper_Common_Worker_#0`, `owner=false` — the L5 salvage precondition "legal on a region thread" holds for SUBMISSION only; callbacks are non-owner (see §4). |
| Q3 | Cross-region border light correctness vs Paper oracle | **OPEN** | `cross_chunk_correct=true` across chunk edges under 2 concurrent region submits, but no proven two-owner adjacent pair and no Paper oracle. Starlight salvage (L5) stays gated; W0.3's DEGRADE-to-NMSRelighter recommendation unchanged. |
| Q4 | `getChunkAtAsync` completion thread | **ANSWERED (this run, weak)** | `ASYNC_CHUNK_OK chunk=12,12 owner=true thread=Folia_Region_Scheduler_Thread_#1` — completion was owner-region in this single observation. Not a documented guarantee: ticket adds in continuations should still get an explicit region hop (cheap, removes the assumption). |
| Q5 | NMSRelighter vs starlight throughput (§8 budget) | **OPEN** | Not measurable: the standalone probe does not load FAWE, so NMSRelighter never ran. This is a W0.8/repeated-baseline input once FAWE boots on Folia; it does not gate the mechanism GO. |

### From `spikes/w05-physics-packets.md` (runtime questions + assumptions)

| # | Question / assumption | Status | Evidence & implication |
|---|---|---|---|
| Q1 / A2 | Cross-region `connection.send` legal? | **PARTIALLY ANSWERED / client_ack OPEN** | `PACKET_OK ... cross_owner=true ... packet_ready=true ack_scheduled=true send_thread=Folia_Region_Scheduler_Thread_#0 client_ack=pending_bot_chat`. The send call from the chunk region to a differently-owned player did not throw. But `tracked_viewer=false` (not a tracked-viewer scenario) and client-visible payload confirmation is pending the bot chat leg. Design can tentatively use direct sends (2d simple shape) but must keep the entity-scheduler fan-out as ready fallback until the ack lands. |
| Q2 | Cross-region neighbor notification | **OPEN** | `NEIGHBOR_OK ... chunk_boundary=true neighbor_owned_by_caller=true` (both modes) — the chunk edge crossed was caller-owned. Chunk-boundary updates + suppression (`events=0` vs `events=94`) are proven; a true region-boundary neighbor update is not. Boundary EVENTS side-effect contract still needs per-neighbour dispatch or documented restriction, pending a two-owner test. |
| Q3 / A1, A3 | Region-owned reads + packet build | **ANSWERED — yes** | `packet_ready=true` on the region thread: `getChunkAtIfLoadedImmediately`, viewer read, and `ClientboundLevelChunkWithLightPacket` construction all succeeded with no global-main assertion. |
| Q4 / A4 | Event dispatch on region threads | **ANSWERED (partial coverage)** | 15× `FAWE_HARNESS_EVENT event=BlockPhysicsEvent owner=true` + 1× `ItemSpawnEvent owner=true`, all on `Folia_Region_Scheduler_Thread_#1`. Confirms region-thread dispatch for those two; `EntityChangeBlockEvent` was never fired in the run — A4 unconfirmed for it. Tick-limiter disable decision stands (its static counters would race across region threads). |
| Q5 | `runUnsafe` public-API safety | **OPEN (design item, not runtime-answerable here)** | No probe scenario exercises `runUnsafe`; the `FoliaQueueHandler` no-op `startUnsafe/endUnsafe` disposition is a wave-1 implementation contract, verifiable deterministically. |
| A5 | `RegionScheduler.execute` targets the owning region | **ANSWERED — yes** | Every commit marker across all legs shows `owner=true` with `owner_mismatches=0` over 3×16 tournament commits + safety + lighting legs. A5 held without exception. |
| Hazard a/b (W0.5) | packet-to-other-region-viewer; border neighbor updates | See Q1/Q2 above | Same evidence rows. |

### From `spikes/w04-regen.md`

| # | Question | Status | Evidence & implication |
|---|---|---|---|
| Q1 | Does Folia load a plugin-declared extra world at startup? | **ANSWERED — no** | `SCRATCH_RESULT loaded=false worlds=fawe-harness,fawe-harness_nether,fawe-harness_the_end`. The scratch-world DEGRADE route (alternative d) is unavailable via `bukkit.yml` declaration; //regen DISABLE disposition confirmed. (W0.4 Q2 — throw-site sizing — was not in this matrix and remains open but non-gating.) |

### From task 09 dev record

| # | Question | Status | Evidence & implication |
|---|---|---|---|
| 1 | Exact `Bukkit.getMinecraftVersion()` string on Folia 26.1.2 | **ANSWERED** | `ENV_OK minecraft=26.1.2 bukkit=26.1.2.build.8-stable name=Folia`. Exactly `26.1.2`, no suffix — the W0.9 guard's string comparison is correct as coded. |
| 2 | Fail-closed path check (real FAWE jar aborts cleanly out of matrix) | **DEFERRED (deliberate)** | Not run: deterministic abort code → wave-3 deterministic tests own it (orchestrator decision). INV-LIFE boot items keep that coverage obligation. |

---

## 3. Commit-strategy ranking

All three strategies: 16 chunks, 4 target groups, 4 observed Folia regions (IDs 4-7),
65536/65536 blocks exact, `owner_mismatches=0`.

| Rank | Strategy | commit_tasks | probe_queue_highwater | max_commit_task_us | max_schedule_delay_us | visibility_us |
|---|---|---|---|---|---|---|
| 1 | **region-sweep** | 4 | 4 | **364** | 51089 | 51321 |
| 2 | **batched-neighbor** | 8 | 8 | 705 | 50793 | 51322 |
| 3 | **per-chunk** | 16 | 16 | 551 | 52194 | 54964 |

(`probe_queue_highwater` is the probe's own scheduled-not-started count — comparable across
strategies, NOT Folia's internal scheduler queue depth.)

**Analysis — visibility is scheduler-delay dominated.** In all three strategies,
`max_schedule_delay_us` (~50.8-52.2 ms) ≈ one region tick, and `visibility_us` (~51.3-55 ms)
is essentially that delay: actual commit work is sub-millisecond (364-705 µs max per task).
At N=16 chunks the strategies are indistinguishable on visibility because a single ~1-tick
scheduling latency floor dwarfs everything else.

**What this means at larger scales (honest extrapolation, not measurement):** the
differentiator is not visibility latency but per-region task pressure. Per-chunk scales the
task count linearly with chunk count (a 100k-chunk edit → 100k scheduled tasks and a
matching probe-side queue highwater); region-sweep scales with region count only. Since
each region drains its queue tick by tick, at large N the task-count difference should
translate into real visibility and tick-time divergence that N=16 cannot exhibit — but we
did not measure that, and per-task cost of a sweep grows with chunks-per-region (a sweep
committing hundreds of chunks in one task could exceed a tick and must be sliced). The
measured data supports the ranking direction; it does NOT give a budget or a slicing
threshold — that is W0.8/W0.10a work. Batched-neighbor's higher max task cost (705 µs for
2-chunk batches vs 364 µs for 4-chunk sweeps) is within noise at these magnitudes; do not
read a per-batch cost model out of a single run.

**Recommendation to the W0.10a tournament:** default to **region-sweep grouping** (fewest
tasks, lowest measured max task cost, equal visibility), with a mandatory per-task chunk cap
(sliced sweeps) to bound commit-task duration, and re-derive the region grouping at
execution time — the probe's 4 logical clusters happened to map 1:1 onto 4 region IDs, but
Folia regions split/merge dynamically; a precomputed grouping is invalid by design.

Rolling pre-commit tick reports (the `region_ticks=...` payloads in the tournament markers,
e.g. per-chunk g0 `tick_avg_before_ns=151991542, tick_avg_after_ns=10575520`) include chunk
generation/warm-up in the "before" windows and are NOT a clean commit-impact measure; the
direct task timings and visibility fields above are the load-bearing numbers.

---

## 4. GO / NO-GO on the architecture §1 model

**GO** — detached-prepare → commit-on-owning-region is proven at spike quality on live
Folia 26.1.2. Evidence chain:

1. **Detached prepare is real and safe (C2):** section built off-thread from palette data,
   `live_refs_on_worker=false`, while the target region ticked concurrently
   (`ticks_during_prepare=3`) — `PIPELINE_SAFETY_OK`.
2. **Owner commit is correct and complete (C1):** swap on `Folia_Region_Scheduler_Thread_#1`
   with `owner=true`; immediate visibility 4096/4096 — `PIPELINE_SAFETY_OK`.
3. **Persistence:** save + restart readback 4096/4096 `EMERALD_BLOCK` —
   `PIPELINE_READBACK_OK`.
4. **Scale-out across regions:** 3 strategies × 16 chunks × 4 regions, 65536/65536 exact,
   `owner_mismatches=0`, all on region threads — `TOURNAMENT_OK` ×3.
5. **Zero violations:** both boots clean under the zero-tolerance harness scan; no
   TickThread/ownership assertion anywhere in either log.
6. **Adjacent mechanisms viable on the model:** region-thread packet build (`packet_ready=true`),
   region-thread `queueSectionData` (`QUEUE_SECTION_OK owner=true`), region-thread starlight
   submission (`RELIGHT_SUBMIT owner=true`), owner-region event dispatch (`FAWE_HARNESS_EVENT
   ... owner=true`).

### ⚠ Mandatory contract addition — non-owner completion callbacks

The single most important integration constraint found by this spike:

```
FAWE_HARNESS_RELIGHT_CHUNK_CALLBACK group=0 chunk=96,96 owner=false thread=Paper_Common_Worker_#0
FAWE_HARNESS_RELIGHT_COMPLETE_CALLBACK group=0 completed=1 thread=Paper_Common_Worker_#0
```

Starlight/moonrise completion callbacks landed on `Paper_Common_Worker_#0` with
**`owner=false`** — a shared pool thread that owns nothing. Any FAWE callback continuation
(ticket removal, packet send, chunk-future completion, history finalization) that touches
live state directly from such a callback would violate the very model this spike just
proved. **The commit contract (C1/C4 wording at W0-FREEZE) must mandate: completion
callbacks re-dispatch to the owning region via `FoliaRegionDispatcher` before touching any
live state.** This is the same pattern as the SS2 port's chunk-future rule: treat every
future/callback continuation thread as hostile until re-dispatched. The GO is conditional
on this being written into the frozen contract, not left as a convention.

---

## 5. Review focus points (weakest claims first)

1. **Small N.** 16 chunks, 4 regions, one tournament run per strategy. The ranking is an
   architectural signal (task-count scaling + sub-ms task cost), not a perf budget. §3's
   large-scale reasoning is extrapolation and says so; W0.8 owns the numbers.
2. **Single hardware, single run.** All timings from one machine, one boot. TPS-before
   values as low as 6.58 in the per-chunk g0 window show warm-up contamination in the
   rolling tick data; only the direct task/visibility fields should be compared.
3. **Bot ack pending.** `client_ack=pending_bot_chat` and `tracked_viewer=false`: the packet
   leg proves server-side legality of a cross-owner send, NOT client-visible delivery to a
   tracked viewer. Do not promote it.
4. **Region-count=4 and caller-owned neighbors.** The 4 clusters ↔ 4 region IDs mapping was
   observed, not forced; and every neighbor/lighting adjacency was caller-owned. No claim in
   this document is a cross-REGION-boundary guarantee for neighbors or light propagation.
5. **`probe_queue_highwater` semantics.** Probe-side scheduled-not-started count, not
   Folia's private queue depth. Valid only for same-instrumentation comparisons.
6. **Skipped legs are obligations, not absences:** FAWE-jar fail-closed boot (wave-3
   deterministic tests), NMSRelighter-vs-starlight throughput (W0.8), `EntityChangeBlockEvent`
   dispatch thread (unobserved), W0.4 Q2 throw-site sizing.
