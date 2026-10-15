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

The correction at [BankMatchRules.java:159](../../src/main/java/com/paymentsledger/bank/domain/BankMatchRules.java#L159) recognizes a mixed exhausted principal only when transit and Suspense are zero and bank principal equals outgoing minus returned principal. It also uses [everyReceiptCovered in BankMatchRepository.java:197](../../src/main/java/com/paymentsledger/bank/repository/BankMatchRepository.java#L197), requiring every participating bank file to be complete and to cover expected arrival. Unallocated principal belonging to another payout on a shared line does not automatically invalidate this payout's settlement.

The lesson is concrete: “allocated enough principal” and “evidence is complete” are distinct checks. A numeric equality can be true while a file is incomplete. The corrected code preserves WAITING_EVIDENCE for that case. It does not infer actual bank connectivity or real-account payout settlement.

## Mutation sensitivity is separate evidence

[PR18 mutation-sensitivity.json](../evidence/pr18/mutation-sensitivity.json) is marked `MUTATION_ONLY`. The experiment deliberately posted an extra balanced partner-share journal through the real posting service while leaving the independent model unchanged. The checker caught the difference and shrank the recorded sequence from 45 steps to 8. That demonstrates detection and shrink sensitivity, not a second real production defect.

The generator is bounded. The shrinker removes operations while preserving dependencies and failure identity; the result is minimal under that deletion procedure, not a global or numeric minimum. No “bugs discovered per seed” statistic is justified by the mutation run.

## Failures that were not fund defects

PR18 retains [driver diagnostics](../evidence/pr18/diagnostics/README.md) for short keys, missing advisory-lock tracing and bank reference format mistakes. Its [observability-clock failure](../evidence/pr18/observability-clock-failure/README.md) was a test credential fixture mixing a fixed clock with database `now()`, causing authentication failures. The targeted RED/GREEN and full rerun show the fixture correction; they do not establish a payment accounting defect.

PR19 retains a separate [initial Kafka driver failure](../evidence/pr19/diagnostics/kafka-initial-driver-failure.json): preparation selected an earlier notification, so the five initial artifacts remained FAIL until the fixture was corrected. Initial F-03 webhook cases also used invalid IDs; correction restored valid input without relaxing production checks. See the [history manifest](../evidence/pr19/history/manifest.json), [focused final manifest](../evidence/pr19/history/focused-final-manifest.json) and [review](../evidence/pr19/review.md). Compilation before implementation, sandbox-blocked Docker access, runner completeness defects and thread cleanup issues are recorded with their own classifications.

PR19's final [42-experiment index](../evidence/pr19/experiment-index.json) and [run report](../evidence/pr19/run-summary.json) passed locally at commit `97e74f3c370d912d405d4a7a171e477106b97d08`. That final result does not delete or recategorize earlier failed runs. Its persistent Fake effects, real PostgreSQL/Kafka containers, killed subprocesses and two-JVM races establish the stated experimental scope; they do not measure production availability or Stripe recovery latency.

## PR20's verified-query regression

The isolated restore exposed a production defect: reconciliation source recovery sent a verified provider read into `mergeWebhook`. When the read timestamp equaled the already confirmed query timestamp, the webhook stale guard rejected it and the task did not complete. The [actual baseline RED](../evidence/pr20/history/) was reproduced against the original PR19 source, with source hashes retained; the corrected query regression passed in focused replay and the [complete clean verification](../evidence/pr20/verified-all-result.json).

[PaymentRepository.mergeReconciliationQuery](../../src/main/java/com/paymentsledger/payment/PaymentRepository.java#L969) now applies the existing query merge ordering, and [ReconciliationSourceRecovery](../../src/main/java/com/paymentsledger/payment/ReconciliationSourceRecovery.java#L311) calls it for the verified read. Webhook ordering remains unchanged. The regression checks task DONE, identical payment/journal/effect counts, unchanged query watermark and no invented webhook timestamp.

## PR20 experiment-driver failures and remaining measurements

The [retained history](../evidence/pr20/history/) separates malformed fixture inputs, missing predeclared fee models, report serialization and wall-clock notification eligibility from production defects. The first complete verification also exposed a dated PR19 Kafka fixture: database-created price validity used wall time while its test clock was fixed to an earlier day. Aligning that fixture once to wall time plus 120 seconds preserved its fault windows, TTL, lease intervals and assertions; focused Kafka replay and the final complete verification passed. It is a test-driver correction.

The million-row performance attempt verified all historical associations and four restricted JVMs, then failed on a database observation during initial warmup. Its generic error originally omitted the failing SQL; a test-only diagnostic wrapper now records SQL, elapsed time and the complete exception chain without relaxing the query deadline or acceptance targets. This run supplies no full-budget performance result. The smoke already shows actual latency/error/queue and business-completion failures.

The [archived restore](../evidence/pr20/verified-all-result.json) passed its scoped exercise, with RPO 885 ms / RTO 3012 ms, retained external effects and manual local-only loss. Nightly I02 stays INCOMPLETE and global writes remain held. A post-load drain cannot erase in-load backlog; a local restore cannot reset provider effects. Production performance and recovery objectives remain unmeasured.

The [second frozen diagnostic run](../evidence/pr20/history/full-million-observation-timeout-diagnostic-manifest.json) identifies its own failure precisely: the history observation joined payment and business-order tables, took 10,396 ms and failed with SQLSTATE 08006 caused by a socket read timeout. This does not retroactively prove the first run's missing cause. The test-only refinement counts immutable expiry cohorts and current payment states in one live snapshot, checks one million historical UNKNOWN records before load, and retains independent queue/effect/accounting checks. Separate PostgreSQL tests prove the historical count changes when a row resolves and excludes a new UNKNOWN; no cached count, new production index, increased deadline or relaxed load target is used. The full rerun result remains required.


The [third full-profile attempt](../evidence/pr20/history/full-million-live-aggregate-timeout-manifest.json) verified one million historical UNKNOWN records before load. Its aggregate took 443 ms before load, 464 ms and 4087 ms in two live samples, then exceeded 10 seconds in a later sample. Simplifying SQL did not establish full measurement capability under these limits.

The subsequent test-only protocol keeps the same queries and deadlines, preserves ordinary observation failures as FAIL and continues the original HTTP arrival budget. A failed boundary or correctness scan publishes no invented counts; unavailable throughput or queue growth is null. Dedicated callbacks stop further load when request/SQL evidence cannot be saved, and any disk-protection failure aborts. Separate markers distinguish the full arrival budget from complete observation evidence. The source-bound [12 unit + 1 PostgreSQL focused verification](../evidence/pr20/history/retained-observation-focused-green-manifest.json) includes actual RED tests for unavailable disk protection and raw evidence. The [fourth full-profile run](../evidence/pr20/latest-performance-result.json) never reached load: after one million rows passed the first association check and all four JVMs became ready, the mandatory repeated four-table association count took 10,220.515 ms and failed with SQLSTATE 08006 / socket read timeout. It is BLOCKED before load, with strict runner FAIL and no requests or phase measurements. The [original raw archive](../evidence/pr20/history/full-million-preflight-association-timeout.tar.gz) retains the SQL, full exception chain, actor readiness and safe cleanup. This preflight failure is distinct from the ordinary in-load observation policy. Completing the fixed 300s/1800s arrival budget and full independent observations remains outstanding; bypassing history validation, enlarging timeouts or reusing cached counts would not establish the frozen claim.
