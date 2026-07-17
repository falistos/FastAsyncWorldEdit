I cannot co-sign v2 as-is. The round-1 integration is otherwise faithful; two substantive ambiguities remain, both introduced or exposed by §4b.

## Fidelity audit

| Finding | Result |
|---|---|
| F1 ownership | Faithfully integrated in [§1b](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:15). |
| F2 liveness | Faithfully integrated, including caller-runs, wait graph, timeout, cancellation, and thread-dump requirements. |
| F3 API semantics | Faithfully integrated in [§4c](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:99). |
| F4 operation semantics | Text faithfully integrated in §4d, but contradicted by §4b; see R2-1. |
| F5 inventory/certification | Faithfully integrated in [§7.2–7.3](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:163). |
| F6 persistent data | Faithfully integrated in [§5](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:136). |
| F7 packaging/adapters | Faithfully integrated, including fail-closed behavior and pinned certification identities. |
| F8 non-Folia regression | Behavioral and matrix portions are faithful; performance-baseline identity remains ambiguous. |
| F9 budgets | Metrics and freeze timing are faithful; the same baseline ambiguity affects objective verification. |
| F10 assurance | Faithfully integrated: expanded perimeter, independent oracle review, deterministic tests, spike dispositions, zero open BLOCKING/MAJOR. |
| F11 wording | Faithfully corrected in [§9](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:202). |

## Residual findings

[BLOCKING] — §4b promises global atomicity that §4d explicitly refuses to imply — “atomically visible, never torn across regions” ([§4b](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:90)) reasonably means whole-operation atomic visibility, while [§4d](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:111) permits explicit partial failure and says whole-operation atomicity must not be implied. It also says every mutation must match Paper without excluding DEGRADED/DISABLED features. One worker could build an unsafe global barrier; another could correctly implement §4d and still fail §4b.

Replace §4b item 1 with:

> 1. **Correctness of accepted world operations** — for CERTIFIED-identical features, every committed block/entity/tile/light mutation matches the Paper oracle; no accepted mutation is silently lost. Each owning-region commit is internally consistent and never exposes torn chunk/section state. Multi-region operations are not globally atomic unless separately certified; their visibility, partial-failure, and history semantics are governed by §4d and the compatibility inventory. DEGRADED and DISABLED features follow §5. Correctness beats throughput whenever correctness and throughput conflict.

[MAJOR] — “Paper baseline” conflates two different performance gates — §1 and §3 require the port not to regress Paper itself, while §4b defines Folia performance against a Paper baseline and §8 names only one unspecified “Paper baseline” ([§8](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:185)). A team could benchmark Folia against the already-modified Paper backend and never compare modified Paper/Spigot against the unported base, leaving F8’s performance guarantee untested.

Add to §8:

> The budget artifact defines two distinct comparisons with separately named thresholds:  
> (1) **Non-Folia regression:** ported Paper/Spigot paths versus the unported base revision on the workloads and runtimes designated by the signed regression matrix.  
> (2) **Folia performance:** the ported Folia backend versus the ported Paper/Mojang backend on matched hardware, configuration, and scenarios.  
> “Paper baseline” in §4b refers to comparison (2) and does not satisfy comparison (1).

[MINOR] — The revision-history severity count is inaccurate — [line 212](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spec.md:212) says `5 BLOCKING, 4 MAJOR, 1 MINOR, 1 editorial`; round 1 contained five BLOCKING, five MAJOR, and one MINOR/editorial finding ([r1](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/codex-spec-review-r1.md:45)).

Replace it with:

> v2: all Codex r1 findings accepted (5 BLOCKING, 5 MAJOR, 1 MINOR/editorial).

The rest of §4b is sound: correctness is never traded for performance; a safe-but-over-budget implementation simply cannot release. Treating independent regions as a parallelism opportunity is subordinate to §1b and objectively judged by §8.

Final verdict: **residual mandatory amendments required** — R2-1 and R2-2 gate co-signature; correct R2-3 in the same revision. No other round-1 finding is reopened.