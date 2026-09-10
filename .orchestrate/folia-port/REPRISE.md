# REPRISE — FAWE Folia Port (updated 2026-07-20, session 4 — WAVE 0 CLOSED, WAVE 1 RUNNING)

> **USER GATE PENDING (raised 2026-07-20, blocking nothing else yet): `amendment-3-draft.md`.**
> One clause, A3.1. The architecture co-signer (round r10) ruled that §3.6 r2 amendment 2's
> "aggregate rejection cause" requirement is unimplementable against the current frozen SPI, and
> that fixing it changes a frozen signature: `OperationCompletion.closeAdmission()` →
> `closeAdmission(Optional<Throwable> aggregatePreRegistrationRejection)`, plus a three-way
> classification rule. Until signed, a verified false-success path stays open: an operation whose
> registrations were all rejected classifies `SUCCEEDED` to the actor (confirmed on disk, and a
> green test at `DefaultOperationCompletionTest:700` currently asserts exactly that). Task-11
> corrective 3 was dispatched EXCLUDING this clause; it only neutralizes that test assertion and
> leaves a `// pending Amendment 3` marker. Nothing about it has been applied or delegated.
> The other three r10 rulings (Q1 drain-expiry terminalizers, Q2 registration capability, Q4
> post-termination inline restriction) were ruled clarifications within co-signed intent, carry no
> frozen SPI change, and ARE applied to `architecture.md` §3.6b/§3.6c.
>
> **Session-4 state (2026-07-20, end of orchestrator turn).**
> **SEALED:** task 12 alignment VERIFIED on disk, graph compile GREEN (`/tmp/graph-compile-s4.log`).
> **IN FLIGHT (4 workers):** task-11 corrective 4 (r13 collaborator contract), task-13 corrective
> pending, task-15 corrective 1, task-16 corrective 1.
> **ALL FOUR REVIEWED TASKS CAME BACK RED OR AMBER** — every one of the 6 review lenses run this
> session found something real, several catastrophic. Task 11: 2 BLOCKING (silent permanent loss
> of the actor's result; un-isolated stage running consumer code inside the serialization lock) —
> corrective 3 DONE and verified, corrective 4 in flight. Task 13: 2 lenses REJECT (`deliverAll`
> abandoning settled outcomes; continuations bypassing byte/chunk accounting entirely; `tryAcquire`
> blocking on a global lock). Task 15: 2 lenses REJECT (**infinite recursion → StackOverflowError on
> the first world adaptation on ANY Paper 26.1 server**; three ownership guards provably INERT and
> strictly less safe than no change at all; no context ever registered in production). Task 16:
> routing PASS-WITH-NOTES, concurrency REJECT (a repeating task that throws once becomes
> permanently uncancellable, because Folia catches+logs+RESCHEDULES where the code assumed Bukkit
> ends the task — verified against PaperMC sources; the test certified the bug via a fake modelling
> the wrong platform).
> **ARCHITECTURE ROUNDS r10–r13 ALL RULED AND APPLIED** to §3.1/§3.5/§3.6b/§3.6c/§3.6d/C5. r13's
> §3.6d is the concrete three-type collaborator contract (`PlanProducerToken`/`AdmissionProducer`/
> `AdmissionDeliveryLease`) that unblocks tasks 11, 13 and 14 together. **`.codex/` is gitignored —
> every ruling has been transcribed into `architecture.md`; do not rely on `.codex/*.msg` surviving.**
> **THREE ORCHESTRATOR ERRORS OWNED AND CORRECTED THIS SESSION** (recorded so they are not
> repeated): (1) the task-16 file cited a "§3.5 C4 scheduler mapping table" FOUR times — no such
> table exists, same error class as FD-1; (2) that file also omitted C6, orphaning the struck
> drain's time budget so nobody owned it; (3) the task-13 salvage ruling ("accounting proven in
> correctives 1-2") was NOT safe — those reviews were scoped to the completion seam, so the
> salvaged machinery imported a BLOCKING and 4 further findings.
> **RECURRING PATTERN worth carrying forward:** on three separate tasks a review found tests that
> CANNOT FAIL on the property they name. Judge test suites adversarially, not by their green count.
> **Still to dispatch:** 14 (its file now carries the `RegionKey` HARD PRECONDITION), then 17 last
> (its file now states it must WRITE the Folia `FaweThreadContext`, which does not exist, not just
> register it). No commits — wave-1 tree intentionally uncommitted pending its own gate.

> PAUSE POINT (user-requested, 2026-07-17, after task-11 corrective 2): wave 1 in flight,
> NOTHING currently running — all workers/reviews returned. State: task 10 VERIFIED; task 11
> correctives 1+2 DONE (aligned on §3.6b-rewritten/§3.6c; re-review by its double-reviewer
> NOT yet run); task 12 delivered + cross-review PASS-WITH-NOTES (1 MAJOR API drift vs 11's
> new registerProducer + 3 shutdown-race minors — alignment recipe recorded verbatim in task
> 11's corrective-2 dev record, apply mechanically); task 13 = terminal-red corrective loop →
> RE-PLANNED via co-signed §3.6c (r7-r9), fresh worker NOT yet dispatched (its usage recipe
> also in 11's c2 record; salvage list in plan.md). NEXT ACTIONS ON RESUME, in order:
> (1) 12 alignment pass (resume its thread `.codex/w1-task12.jsonl` thread id, or fresh);
> (2) re-review 11 (c1+c2) by a fresh Opus double-reviewer; (3) FRESH 13 worker per §3.6c;
> (4) graph compile (expect green only after 1-3); (5) dispatch 14 (its file carries 3 wiring
> constraints), 15/16 parallelizable; 17 last. §3.6b/§3.6c are CO-SIGNED (arch thread rounds
> r4-r9); architecture header + plan.md wave-1 table are current. No commits since wave-0
> closure (8 commits on main); wave-1 tree intentionally uncommitted pending its own gate.
>
> SUPERSEDING UPDATE (earlier same session): everything below about "pending gate" is DONE —
> user signed Amendment 2 (A2.1 option "26.1.2 + prepare 26.2", A2.2, A2.3);
> re-certification r2 = CERTIFY-WITH-CONDITIONS (`codex-w0-certification-r2.md`), all
> conditions applied (record sync + count precision); Amendments 1+2 Codex CO-SIGNED
> clause-by-clause; inventory frozen. **Wave 0 committed as 8 conventional commits**
> (117569832..094702f8c on `main`; tree clean; `.git/info/exclude` no longer hides
> `.orchestrate/`; root `.gitignore` re-includes `harness/**/*.sh` — upstream ignores
> `*.sh`). **Wave 1 DISPATCHED**: tasks 10, 11, 13 running as three parallel Codex
> workers (xhigh, workspace-write, `.codex/w1-task{10,11,13}.{jsonl,msg}`). On each
> delivery: judge dev record vs disk → graph compile → Opus cross-review → then dispatch
> 12, then 14/15/16, then 17 last. Numeric budget freeze = W1-exit gate (A2.3). Check
> Folia 26.2 publication on Fill at every wave gate (A2.1 clause, operationalized in
> plan.md).

> **CODEX OUTAGE (2026-07-21): usage limit hit, resets ~2026-07-25 14:08.** All Codex-CLI
> implementation is BLOCKED until then. The task-17 corrective 1 (test-quality notes) was dispatched
> and failed immediately (`turn.failed`, usage limit) — NOT started, NOT on disk; re-dispatch it
> when Codex returns, or via an Opus agent if the user authorizes cross-family assurance relaxation.
> Opus subagents (reviews) are UNAFFECTED — the task-12 alignments 2+3 re-review was still running at
> outage time. The established regime is Codex-implements / Opus-reviews (families crossed); running
> implementation on Opus too would make implementer and reviewer the same family, weakening exactly
> the cross-family independence that caught the session's worst defects — a user decision, pending.

Cold-start bootstrap: read this FIRST, then `plan.md` (board = source of truth), then
`git -C /Users/falistos/Workspace/forks/FastAsyncWorldEdit status` to confirm recorded
state matches reality. If they disagree, reconcile before dispatching anything.

## Exact state

- Repo: `/Users/falistos/Workspace/forks/FastAsyncWorldEdit`, branch `main`, tracks
  `upstream/main` at base `f53400f00` (pinned perf base revision).
- **Working tree: intentionally UNCOMMITTED** — all wave-0 work + certification correctives.
  Commit plan: per-task conventional commits AFTER user approval (pending at the gate).
- **Spec v3 FROZEN** + **Amendment 1 USER-SIGNED** (A1.1 wording to be corrected by A2.2).
- **Amendment 2 DRAFT ready** (`amendment-2-draft.md`, awaiting USER signature): A2.1 targets
  → Folia 26.1.2-only (26.1.1 NEVER EXISTED — verified on Fill API + Maven + the
  `ver/26.1.x` branch history: gradle.properties jumps 1.21.11 → 26.1.2, commit 4729256c47;
  Paper shipped MC 26.1.1, Folia skipped it); A2.2 A1.1 evidence rewording (only the
  bukkit.yml route disproven; DISABLE stands); A2.3 numeric budget freeze moved to W1-exit
  gate (mechanical derivation from baseline distributions; no wave-2 dispatch before signed).
- **Architecture v3.1 CO-SIGNED unconditional** (header updated; F1 compile condition
  satisfied).
- **Wave-0 certification r1 = REJECT** (`codex-w0-certification.md`: 6 BLOCKING/1 MAJOR/
  1 MINOR; Amendment 1 REJECT-AS-WRITTEN, A1.2–A1.4 supported). ALL correctible findings
  corrected same day and orchestrator-verified:
  - F1: SPI → v3.1 verbatim (planSequence pre-admission key; NOT_ACCEPTED terminal path;
    DiagnosticSnapshot + producers on FoliaCommitBroker). Graph compile green.
  - F2: `foliaBackend` configuration (worldedit-bukkit/build.gradle.kts:81-88,:130,:211-212);
    DYNAMIC PROOF: Paper jar = 18 backend classes + 45 core SPI; Spigot reobf jar = detector
    pair only; single FoliaSupport (duplicate removed).
  - F3: inventory 92→93, 5 settled flow-backs, 88 UNKNOWN; freeze pending co-sign.
  - F6→A2.2; F4→A2.3; F5→A2.1 (amendment batch).
  - F7: dev records 01/06/09 backfilled evidence-cited; board/architecture headers coherent.
  - F8: stale root `spikes/` duplicate archived out of repo.
- **Harness fully green on 26.1.2**: corrective 3 closed (allow-flight determinism + kick
  wording fatal-pattern w/ self-test); pipeline-probe both phases green (readback 4096/4096);
  perf-probe green → probe reference numbers in `w08-perf-budgets.md` §6b (region-sweep
  ≈1.10 M blocks/s; commit task max 3.3 ms; GC p99 24 ms; queue highwater 4→0; shutdown
  drain ≤2.96 s). Certification reviewer independently reproduced these numbers.
- **Wave-1 task files DRAFTED**: `tasks/10-17` (see plan.md §Wave 1 for the map). Drafts
  11/14 re-synced to the corrected skeleton. 17 carries the A2.1 consequential edit
  (FoliaSupport certified set `{"26.1.1","26.1.2"}` → `{"26.1.2"}`) gated on signature.

## Next actions (in order)

1. [ ] USER GATE (single decision point, no piecemeal): (a) sign Amendment 2 batch;
   (b) approve per-task commits; (c) approve wave-1 plan (tasks/10-17) for dispatch.
2. [ ] Apply A2 consequential edits after signature: spec §2/§7.3 target wording;
   `harness/versions.env` drop 26.1.1 placeholder; `FoliaSupport` certified set;
   `w08-perf-budgets.md` header (numeric freeze = W1-exit).
3. [ ] RE-CERTIFICATION round: fresh Codex thread (xhigh, read-only, neutral vocabulary),
   mandate = verify all 8 corrective dispositions against disk + co-sign Amendment 1
   (corrected) + Amendment 2 + the frozen inventory. Output →
   `codex-w0-certification-r2.md`. Expect CERTIFY; wave 0 closes on it.
4. [ ] Close wave 0 on the board; commit (Conventional Commits, no attribution footers,
   no process context in messages — including the correctives).
5. [ ] Dispatch wave 1 per `tasks/10-17` deps (10 first; 11/13 parallel; 17 last).
   Routing: Codex majority + Opus cross-review armed; Sonnet trivial only.

## Established process & exact commands

- Codex one-shot: `cd /Users/falistos/Workspace/forks/FastAsyncWorldEdit && codex exec --json -s read-only -c model_reasoning_effort="xhigh" -o <out.md> - < <prompt.md> > <log.jsonl> 2>&1` (background; `-s workspace-write` for impl).
- Codex resume: `codex exec resume <thread-id> ...` — flags BEFORE the prompt dash, but NO
  `-s` (resume rejects it; the session keeps its original sandbox).
- Harness: `JAVA_HOME_RUNTIME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home harness/scenario.sh <smoke-set|pipeline-probe|perf-probe> --version 26.1.2`
  (perf-probe needs explicit `--without-plugin` until a FAWE jar is staged); probe build:
  `harness/probe/build.sh`; perf report: `perl harness/lib/perf-report.pl <log>`.
- Gradle: ALWAYS `--no-configure-on-demand`. Graph compile proof:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava :worldedit-bukkit:compileJava --no-configure-on-demand`.
- FULL bukkit build (shadowJar/reobfShadowJar) needs
  `-I harness/adapter-jdk21.init.gradle.kts` (old 1.21.x adapters' codebook 1.0.14 cannot
  parse Java-25 classes; init script pins them to JDK 21; pre-existing env issue, no repo edit).
- Agent workers: general-purpose subagents, model per routing; worker prompts point at task
  file + architecture.md; dev-record protocol; escalation AUTHORIZED/BLOCKED/NEEDS_CONTEXT.

## Known traps (accumulated scars)

- Background shells reset cwd → `cd <abs> && ...` in EVERY command (bit again this session).
- `... | tail; echo EXIT:$?` reports tail's exit — capture exit codes directly (bit again:
  a BUILD FAILED masked as exit 0 in a task notification).
- zsh: unquoted `===`/`==` in echo → "== not found" error; quote them.
- Judge artifacts, never summaries: Codex corrective thread died mid-turn but its edits were
  on disk and correct; conversely a certification claim ("duplicate FoliaSupport") went stale
  the moment the corrective landed — always re-verify on disk at read time.
- Codex content filter: impl thread `019f700b` trips on security-flavored vocabulary — use
  neutral measurement wording; fresh threads for reviews.
- ChunkPos.x/z are record accessors `x()`/`z()` in 26.1 mappings.
- folia-api needs JVM 25 metadata → only the folia module (Java-25 toolchain) depends on it.
- Folia console `fill` runs off-region → world mutation via the region-owned probe only.
- `Bukkit.getMinecraftVersion()` on Folia 26.1.2 == exactly `26.1.2`; `PaperLib.isPaper()`
  == true on Folia.
- PaperMC Fill v3: folia versions endpoint hides nothing — 26.1.1 truly absent (three-source
  proof recorded in amendment-2-draft.md A2.1).

## Owner decisions — do NOT re-litigate (full log in plan.md §Decision log)

- Dual-platform in-tree; Folia targets per A2.1 once signed (26.1.2-only); API guaranteed
  (§4c, 653 declarations); documented degradation; assurance MAX; correctness > performance
  (both hard, §4b); Codex-majority routing; tournament-synthesized architecture
  (region-lanes + RegionTicket + config-gated latency grafts); //regen DISABLE definitive
  (evidence wording per A2.2); relight DEGRADE to NMSRelighter (reopen only on §8 budget
  breach); tick-limiter DISABLE; callback re-dispatch rule (A1.4);
  dedicated `:worldedit-bukkit:folia` Java-25 module; CommitOutcome does not exist (FD-1 —
  ChunkTerminalRecord protocol is the frozen design); no piecemeal signatures — batch at gates.
