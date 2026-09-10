# Amendment 3 (DRAFT — awaiting USER signature)

Raised: 2026-07-20, session 4, wave 1.
Origin: task-11 corrective re-review (two independent fresh reviewers, persistence lens and
concurrency lens, both REJECT) → architecture ruling round r10, question Q3.
Artifacts: `review-w1-task11-c1c2-persistence.md` (F2), `.codex/arch-cosign-r10.msg` (Q3).

This amendment carries **one clause**. It is isolated here because r10 marked it — explicitly and
loudly, as instructed — a **frozen SPI signature change**, which this effort routes to the user
signature gate and never to a worker. The other three r10 rulings (Q1 drain-expiry terminalizers,
Q2 registration capability, Q4 post-termination inline restriction) were ruled clarifications
within already co-signed intent, carry no frozen SPI change, and have been applied directly to
`architecture.md` §3.6b/§3.6c.

---

## A3.1 — `OperationCompletion.closeAdmission` must carry the aggregate pre-registration
## rejection cause

### The defect being remedied

Architecture §3.6 r2 amendment 2 already requires (co-signed, binding wording) that admission
closure **preserve the aggregate rejection cause so an operation with no committed mutation
resolves `FAILED`, never successful-empty completion**.

The delivered implementation cannot satisfy that requirement, and the gap is structural rather
than an oversight in the code:

- `closeAdmission()` takes no argument and the coordinator holds no field for a cause.
- The *only* channel by which a rejection cause can reach the coordinator today is a
  `NOT_ACCEPTED` `ChunkTerminalRecord` — which requires a **successful** `register()` first.
- Therefore, when registration itself fails, or is never reached, the cause is unrepresentable by
  construction.
- `buildResult` consequently classifies an empty registration map as `SUCCEEDED`
  (`DefaultOperationCompletion.java:304-319`, orchestrator-verified on disk).

Verified consequence, on disk: `DefaultOperationCompletionTest.java:700` asserts
`Classification.SUCCEEDED` for an operation whose sole plan registration was **rejected**. A green
test currently locks in the successful-empty completion the architecture forbids.

This is reachable in ordinary operation, not only through the test's guard path: r10 Q2 confirmed
that a plan can fail registration after a successful lease preflight when the completion service
transitions to `FLUSHING`. Composed with this clause's defect, **an operation rejected wholesale
at shutdown reports `SUCCEEDED` to the actor** — a false success on the component whose entire
purpose is exactly-once, no-silent-loss completion. The named project risk is silent corruption;
this is its user-facing twin.

### The r10 ruling (verbatim)

Genuine empty work and wholly rejected work are distinct:

- Zero candidates and no admission/preflight failure → `SUCCEEDED` no-op.
- Any admission/preflight rejection with no applied mutation → `FAILED`.
- Rejection plus any applied mutation → `PARTIAL`.

### The signature change requiring signature

In the frozen core SPI type `OperationCompletion`, replace:

```java
void closeAdmission();
```

with:

```java
/**
 * Closes admission. Empty is legal only when no pre-registration admission failure occurred.
 * Multiple failures are aggregated into one cause, retaining individual causes as suppressed.
 */
void closeAdmission(Optional<Throwable> aggregatePreRegistrationRejection);
```

Registered `NOT_ACCEPTED` records continue carrying their own causes. The closure cause covers
only failures that occurred **before** registration and is combined with registered terminal
failures when classifying `OperationResult`.

### Consequential edits if signed

1. `OperationCompletion.java` — the signature above.
2. `DefaultOperationCompletion` — store the cause; `buildResult` classifies per the three-way
   rule; the cause joins `failures`.
3. `DefaultOperationCompletionTest:700` — the assertion currently locking in the wrong
   classification is corrected to `FAILED`.
4. Callers: task 14 (wiring) and task 13 (admission) must pass the aggregate cause. Task 12 does
   not call `closeAdmission`.

### Scope discipline

Nothing in this clause is being applied ahead of signature. Task-11 corrective 3 has been
dispatched **excluding** this clause; it fixes only the r10 Q1/Q2/Q4 items and the two BLOCKING
defects. The corrective is instructed to neutralize the incorrect test assertion (so it stops
asserting a forbidden outcome) **without** implementing the classification change, leaving a
recorded gap rather than a silent one.

**Consequence of NOT signing:** the false-success path above stays open, and §3.6 r2 amendment 2
remains unimplementable. There is no in-contract workaround — that is why this is at the gate.

---

## Signature

- [ ] **USER** — A3.1
- [ ] Codex co-signature (clause-by-clause, after user signature, per the established regime)
