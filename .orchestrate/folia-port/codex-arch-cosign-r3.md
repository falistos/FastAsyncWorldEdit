## Round-3 verification

| Integration | Verdict |
|---|---|
| `planSequence` terminal identity | PASS — allocated before registration/scheduling, invariant across retries and rebinds; `RegionTicket.sequence()` is diagnostic only ([architecture.md:710](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:710)). |
| `NOT_ACCEPTED` path | PASS — registration precedes admission, rejection terminalizes exactly once with its cause, and wholly rejected operations fail ([architecture.md:721](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:721)). |
| Drain terminalization | PASS — no `INCOMPLETE` state; pre-mutation and mutated plans receive valid terminal outcomes, while unresolved counts remain certification failures ([architecture.md:786](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:786)). |
| G4 diagnostic producer | PASS — per-region/global snapshots contain all required fields and have an exact `FAWE_QUEUE` mapping ([architecture.md:533](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:533)). |
| F9 freeze record | PASS — correctly closed at 271 additional rows and 653 combined declarations ([architecture.md:924](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/architecture.md:924)). |
| Judgment R6 | PASS — matches the corrected plan identity, admission, rejection, deduplication, and completion protocol ([judgment.md:146](/Users/falistos/Workspace/forks/FastAsyncWorldEdit/.orchestrate/folia-port/spikes/tournament/judgment.md:146)). |

No residual architecture amendment gates remain.

**Final verdict: CO-SIGN v3.1, conditional only on the F1 declared-module-graph compile proof being attached to the freeze record.**

Harness implementation and populated numeric budgets remain wave-1 dispatch gates, not conditions on this architecture co-signature.