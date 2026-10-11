# Failure retrospective: settlement evidence is not a single status

[English index](README.md) · [PR18 historical evidence](../evidence/pr18/README.md) · [PR19 historical evidence](../evidence/pr19/README.md)

PR18 found and fixed a real production-code defect in `BankMatcher`'s derived external-evidence status. A payout with synthetic principal 5700 was fully settled by a PSP return of 1200 and a bank receipt of 4500, yet the old implementation kept `WAITING_EVIDENCE` because neither the receipt nor the return alone equaled the entire payout. The existing principal allocation constraint held; the error was the interpretation of the complete settlement.

## Actual RED, correction and GREEN

| Evidence | What it demonstrates |
|---|---|
| [Mixed-settlement RED](../evidence/pr18/payout-evidence-red.txt) | Actual result WAITING_EVIDENCE; expected MATCHED for the mixed settlement |
| [Completeness RED](../evidence/pr18/bank-completeness-red.txt) | Mixed and full receipts must not be MATCHED when the participating statement is incomplete |
| [Focused GREEN](../evidence/pr18/payout-concurrency-green.txt) | The payout regressions and existing concurrency cases pass after correction |
| [Retained mixed-settlement snapshot](../evidence/pr18/regression-payout-mixed.json) | Actual scoped BTs, bank principal, journals and reconstructed balances for the synthetic case |
| [Full PR18 run](../evidence/pr18/run-summary.json) and [commit provenance](../evidence/pr18/commit-provenance.json) | The final verified inputs match implementation/fix commit `cac30bc39a123dca765632421f53bfcb1f40cd83`; the original tested baseline HEAD remains `8e1dc2e` |

The correction at [BankMatcher.java:57](../../src/main/java/com/paymentsledger/bank/BankMatcher.java#L57) recognizes a mixed exhausted principal only when transit and Suspense are zero and bank principal equals outgoing minus returned principal. It also uses [bankEvidenceComplete at line 71](../../src/main/java/com/paymentsledger/bank/BankMatcher.java#L71), requiring every participating bank file to be complete and to cover expected arrival. Unallocated principal belonging to another payout on a shared line does not automatically invalidate this payout's settlement.

The lesson is concrete: “allocated enough principal” and “evidence is complete” are distinct checks. A numeric equality can be true while a file is incomplete. The corrected code preserves WAITING_EVIDENCE for that case. It does not infer actual bank connectivity or real-account payout settlement.

## Mutation sensitivity is separate evidence

[PR18 mutation-sensitivity.json](../evidence/pr18/mutation-sensitivity.json) is marked `MUTATION_ONLY`. The experiment deliberately posted an extra balanced partner-share journal through the real posting service while leaving the independent model unchanged. The checker caught the difference and shrank the recorded sequence from 45 steps to 8. That demonstrates detection and shrink sensitivity, not a second real production defect.

The generator is bounded. The shrinker removes operations while preserving dependencies and failure identity; the result is minimal under that deletion procedure, not a global or numeric minimum. No “bugs discovered per seed” statistic is justified by the mutation run.

## Failures that were not fund defects

PR18 retains [driver diagnostics](../evidence/pr18/diagnostics/README.md) for short keys, missing advisory-lock tracing and bank reference format mistakes. Its [observability-clock failure](../evidence/pr18/observability-clock-failure/README.md) was a test credential fixture mixing a fixed clock with database `now()`, causing authentication failures. The targeted RED/GREEN and full rerun show the fixture correction; they do not establish a payment accounting defect.

PR19 retains a separate [initial Kafka driver failure](../evidence/pr19/diagnostics/kafka-initial-driver-failure.json): preparation selected an earlier notification, so the five initial artifacts remained FAIL until the fixture was corrected. Initial F-03 webhook cases also used invalid IDs; correction restored valid input without relaxing production checks. See the [history manifest](../evidence/pr19/history/manifest.json), [focused final manifest](../evidence/pr19/history/focused-final-manifest.json) and [review](../evidence/pr19/review.md). Compilation before implementation, sandbox-blocked Docker access, runner completeness defects and thread cleanup issues are recorded with their own classifications.

PR19's final [42-experiment index](../evidence/pr19/experiment-index.json) and [run report](../evidence/pr19/run-summary.json) passed locally at commit `97e74f3c370d912d405d4a7a171e477106b97d08`. That final result does not delete or recategorize earlier failed runs. Its persistent Fake effects, real PostgreSQL/Kafka containers, killed subprocesses and two-JVM races establish the stated experimental scope; they do not measure production availability or Stripe recovery latency.

## What remains unmeasured

PR20 full performance and restore experiments are NOT RUN at authoring. Their future reports must preserve target breaches, queue growth and unrecovered records. A post-load drain must not erase in-load backlog; restoring a local database must not reset independent provider effects. Historical local PASS evidence supplies a reason to run those experiments, not permission to predict their outcomes.
