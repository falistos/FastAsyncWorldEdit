## Verdict

**REJECT.** The 26.1.2 harness evidence is green, but wave 0 does not have a coherent `[W0-FREEZE]`: the compiled SPI is stale relative to architecture v3.1, the compatibility inventory and numeric budgets remain drafts, and the signed specification still requires unpublished Folia 26.1.1.

**Amendment 1 co-signature: REJECT AS WRITTEN.** A1.2–A1.4 are supported. A1.1 overstates what the scratch-world probe established; the `//regen` DISABLE disposition itself remains supportable after correcting that evidence wording.

## Findings

1. **BLOCKING — The compile-proof skeleton implements the rejected v3 completion protocol, not co-signed v3.1.**

   Architecture v3.1 requires a pre-admission `planSequence`, invariant across callbacks, and explicitly forbids using `RegionTicket.sequence()` as the terminal key ([architecture.md:714](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:714), [architecture.md:745](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:745)). The source still uses `ticketSequence` from the SECTIONS callback ([ChunkTerminalRecord.java:7](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/ChunkTerminalRecord.java:7), [RegionTicket.java:37](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/RegionTicket.java:37)).

   Architecture also requires registration before admission so `NOT_ACCEPTED` has a terminal path ([architecture.md:721](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:721), [architecture.md:759](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:759)); the source still documents registration only for accepted chunks before scheduling ([OperationCompletion.java:8](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletion.java:8)).

   Finally, the frozen `DiagnosticSnapshot` producer is required on `FoliaCommitBroker` ([architecture.md:533](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:533)), but the broker has no such record or methods ([FoliaCommitBroker.java:8](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaCommitBroker.java:8)). A green Java compile does not satisfy this semantic proof.

2. **BLOCKING — The claimed module-graph proof does not exercise the declared packaging graph.**

   The architecture requires the Folia module to be bundled only into the Mojang/Paper artifact ([architecture.md:101](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:101)). On disk, the module is merely included in settings ([settings.gradle.kts:62](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/settings.gradle.kts:62)); its only project dependency is `worldedit-core` ([build.gradle.kts:25](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-bukkit/folia/build.gradle.kts:25)), and the Bukkit shadow configuration includes adapters but not the Folia project ([worldedit-bukkit/build.gradle.kts:199](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-bukkit/build.gradle.kts:199)).

   There are also two definitions of the same class, `com.fastasyncworldedit.bukkit.folia.FoliaSupport`, one in each module ([Bukkit FoliaSupport.java:17](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaSupport.java:17), [module FoliaSupport.java:8](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaSupport.java:8)). The recorded compile proves two disconnected source sets compile; it does not prove linkage, class uniqueness, or Paper-only packaging.

3. **BLOCKING — The compatibility inventory is neither accurate nor frozen/co-signed.**

   The specification requires it frozen and co-signed in wave 0 ([spec.md:76](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:76), [spec.md:169](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:169)). The artifact still calls itself a draft and leaves every item `UNKNOWN` ([w07-compat-inventory.md:3](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w07-compat-inventory.md:3)), including the settled regen, relight, packet, and physics entries ([w07-compat-inventory.md:235](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w07-compat-inventory.md:235)).

   Its count is also wrong: the tables contain 93 items, not 92. Axis 1 contains 23 command rows, 3 tool rows, 1 brush row, 2 CUI rows, and 2 registration rows — 31, while the summary claims 30 ([w07-compat-inventory.md:15](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w07-compat-inventory.md:15), [w07-compat-inventory.md:122](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w07-compat-inventory.md:122)). No inventory co-signature is present on disk.

4. **BLOCKING — Numeric performance/resource budgets remain empty.**

   The frozen specification requires numeric budgets during wave 0 and before core implementation ([spec.md:192](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:192)). W08 remains a draft ([w08-perf-budgets.md:3](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w08-perf-budgets.md:3)); every comparison and resource threshold remains an empty slot ([w08-perf-budgets.md:207](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w08-perf-budgets.md:207), [w08-perf-budgets.md:221](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w08-perf-budgets.md:221)).

   Section 6b is accurate and did **not** invent thresholds. I reproduced its report: 65,536 blocks in 59.503 ms, 3,335 µs maximum commit task, 55,798 µs scheduling delay, depth/in-flight high-water 4/2 ([170828.log:76](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/logs/folia-26.1.2-20260717-170828.log:76), [170828.log:79](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/logs/folia-26.1.2-20260717-170828.log:79)); GC maximum/p99 is 24.007 ms ([gc.log:156](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/logs/folia-26.1.2-20260717-170828.log.gc.log:156)); shutdown upper bound is 2,961 ms from the stop marker to process-exit observation ([marks.tsv:3](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/logs/folia-26.1.2-20260717-170828.log.marks.tsv:3), [perf.tsv:7](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/logs/folia-26.1.2-20260717-170828.log.perf.tsv:7)). These are one probe run’s observations, not frozen limits.

5. **BLOCKING — The signed target matrix is unsatisfiable and has not been amended.**

   The frozen spec still requires Folia 26.1.1 and 26.1.2 ([spec.md:31](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:31), [spec.md:174](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:174)). W08 records that 26.1.1 was never published and explicitly requires a signed target amendment ([w08-perf-budgets.md:269](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w08-perf-budgets.md:269)); the harness remains `UNSTAGED` for that version ([versions.env:8](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/versions.env:8)). Amendment 1 does not alter the target matrix.

   Therefore the available evidence is **26.1.2-only**. Wave 0 cannot close against the current signed scope; the user must sign either a 26.1.2-only target or a published replacement.

6. **BLOCKING — Amendment 1 A1.1 claims more than the runtime artifact proves.**

   A1.1 says the last scratch-world option is closed because Folia does not load plugin-declared worlds at boot ([amendment-1-draft.md:10](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/amendment-1-draft.md:10)). The probe established only that one `bukkit.yml` generator declaration did not instantiate `fawe-scratch` ([w02-pipeline-results.md:169](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w02-pipeline-results.md:169), [run.sh:200](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/run.sh:200)). W04’s own question also names a separately pre-provisioned/config-declared level folder and requires actual region-scheduled generation/readback before calling the alternative settled ([w04-regen.md:185](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w04-regen.md:185)).

   Required correction: state that the `bukkit.yml` route failed; do not claim all startup provisioning routes are disproven. The DISABLE disposition remains justified by the existing unproven, lossy nature of the alternative ([w04-regen.md:157](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w04-regen.md:157)).

   A1.2 is supported by the lighting disposition ([w03-lighting.md:17](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w03-lighting.md:17)) and non-owner callback observation ([170629.log:123](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/logs/folia-26.1.2-20260717-170629.log:123)). A1.3 is supported by the static/global tick-limiter design ([w05-physics-packets.md:99](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w05-physics-packets.md:99)). A1.4 is faithfully present in C1 ([architecture.md:164](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:164)).

7. **MAJOR — The task records and board are not closure-grade records of current disk state.**

   Task 06 has no dev record beyond its empty heading ([06-api-context-audit.md:50](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/tasks/06-api-context-audit.md:50)). Task 01 ends with corrective 2 still awaiting rerun ([01-harness-bringup.md:206](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/tasks/01-harness-bringup.md:206)), while corrective 3 appears only on the board ([plan.md:29](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/plan.md:29)). Task 09 claims the Folia API dependency lives in `worldedit-bukkit` ([09-build-plumbing.md:77](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/tasks/09-build-plumbing.md:77)), but the current correction moved it to the dedicated module.

   The board headline still says freeze is pending ([plan.md:10](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/plan.md:10)), while a later row declares the condition satisfied ([plan.md:31](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/plan.md:31)); architecture itself still says “pending conditional co-sign” ([architecture.md:1](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:1)).

8. **MINOR — A stale duplicate W02 result artifact exists outside the mandated control directory.**

   [spikes/w02-pipeline-results.md:1](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/spikes/w02-pipeline-results.md:1) is a different 246-line version from the canonical 323-line artifact under `.orchestrate`. This violates the rule that project-control artifacts live under `.orchestrate/folia-port/` ([spec.md:215](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:215)) and makes task 02’s file list ambiguous.

## Seam samples

| Seam | Result |
|---|---|
| Harness ↔ corrective-3 gate | **PASS.** `allow-flight=true` is generated ([run.sh:183](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/run.sh:183)); both actor-kick wordings are fatal patterns and self-tested ([scan-logs.sh:17](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/scan-logs.sh:17), [scan-logs.sh:56](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/scan-logs.sh:56)). Running the current scanner against the old 17:00 log rejects it at the actual kick lines ([170026.log:93](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/logs/folia-26.1.2-20260717-170026.log:93)). |
| Harness ↔ probe | **PASS.** Latest initial/readback runs contain owner-region commit, zero owner mismatches, clean actor completion, and 4096/4096 restart readback ([170629.log:68](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/logs/folia-26.1.2-20260717-170629.log:68), [170704.log:65](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/harness/logs/folia-26.1.2-20260717-170704.log:65)). |
| Probe ↔ architecture | **PASS for the measured model.** Detached preparation/owner commit and callback re-dispatch match architecture C1/C2. **FAIL for the transcribed SPI**, per Findings 1–2. |
| Architecture ↔ API audits | **PASS for census/contracts.** W06 records 382 declarations; W06b adds contiguous APIC-092–362, 271 declarations ([w06b-apic-gap-closure.md:413](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w06b-apic-gap-closure.md:413)); architecture records the 653 total ([architecture.md:924](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:924)). `runUnsafe` is consistently deterministic-failure. |
| API audits ↔ compatibility inventory | **FAIL.** The eight coarse API areas are present, but the inventory count is wrong and settled degradation dispositions were never flowed back from W03/W04/W05. |
| Inventory ↔ budgets | **PARTIAL.** H-STATE/H-CONCURRENT/H-FAILURE/H-LEAK/H-PERSIST mappings agree ([w07-compat-inventory.md:31](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w07-compat-inventory.md:31), [w08-perf-budgets.md:279](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/w08-perf-budgets.md:279)); neither side is frozen. |
| Spec ↔ amendment ↔ architecture | **PARTIAL/FAIL.** Relight, tick-limiter, and callback rules agree. Scratch-world closure is overstated, and the required 26.1.1 target amendment is absent. |

## Dev-record audit

| Task | Claim vs disk | Verdict |
|---|---|---|
| 01 | Harness files exist; latest correction and runs are green. File list still names stubs later deleted by task 02, and the record stops before corrective 3. | **PARTIAL — artifact passes, record stale** |
| 02 | Probe and canonical result support the mechanism GO. Compile-proof subsection claims the frozen protocol was transcribed, but source retains v3 terminal semantics and omits v3.1 diagnostics. Duplicate result file exists. | **FAIL** |
| 03 | Report exists; lighting modes, NMSRelighter degradation, and Starlight disposition agree with W02 evidence and architecture. | **PASS** |
| 04 | Report exists; DISABLE disposition and operator wording are backed. The later claim that every scratch-world route is closed is not backed by this task’s experiment. | **PASS for DISABLE; amendment qualification required** |
| 05 | Report exists; physics/packet mechanism GO, tick-limiter disable, and remaining boundary questions are accurately recorded. | **PASS** |
| 06 | W06 and W06b artifacts exist and their 382+271 census is coherent; task dev record is empty. | **FAIL record / PASS artifact** |
| 07 | Report exists, but dev record claims 92 items while disk contains 93; artifact remains draft/UNKNOWN and unsigned. | **FAIL** |
| 08 | All listed scenario specifications exist; base pin equals current HEAD/merge-base; §6b matches the runtime evidence and invents no threshold. The record accurately calls the artifact a draft, not a completed freeze. | **PASS as draft; BLOCKS closure** |
| 09 | Early fail-closed check and `folia-supported: true` exist. Current dependency/module layout supersedes the dev record and introduces the duplicate `FoliaSupport`; packaging is unproven. | **PARTIAL — record and module layout stale** |

## Deferred-by-design register

The following may remain for later waves, once the blocking freeze defects above are corrected:

- Wave 1 implementation bodies for `FaweThreadContext`, dispatcher, task/queue managers, backpressure, broker, snapshot cache, and bootstrap wiring. Their **frozen signatures and module graph cannot remain deferred**.
- Wave 2 adapter integration: GET capture, SET commit, tiles/entities/POI/light/packet finalizers, history integration, and live per-region admission.
- True adjacent-region neighbor testing and tracked-viewer packet delivery. Architecture currently uses destination-owner decomposition/entity-mailbox fallback, so no uncertified direct path is assumed.
- API wrong-owner, cancellation, unload, persistence and exactly-once failure-injection tests in wave 3.
- Deterministic unsupported-version/fail-closed runtime testing in wave 3, after the target amendment updates the guard.
- Wave 4 implementation and documentation of signed degradations, including `//regen`, relight, tick-limiter behavior, snapshots, and any later Starlight reconsideration.
- Wave 5 full Paper/Spigot regression matrix, consumer-plugin API gate, oracle comparisons, dual-baseline performance **verification**, FOLIA.md, and the port report. Numeric threshold selection/freeze itself is not deferred.