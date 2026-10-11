# Resume and interview evidence map

[English index](README.md) · [Acceptance audit](acceptance-evidence-audit.md)

Use the wording below only with its stated source and scope. The numbers are actual archived execution counts, not production volume, full-system coverage percentages or performance measurements. PR20 performance, restore and demo completion claims are NOT RUN at authoring.

## Provenance for quantitative claims

| Reference | Recorded run, source commit and environment | Data, command and raw report |
|---|---|---|
| R18 | Start `2026-10-07T14:43:17.352395+00:00`, end `2026-10-07T15:15:29.040261+00:00`; implementation `cac30bc39a123dca765632421f53bfcb1f40cd83`; original tested HEAD `8e1dc2ed52c27aab7d6c5c048aaa24e553cc3f26`. Darwin arm64, 8 logical CPUs, Corretto 21.0.12.1, Docker 27.4.0, PostgreSQL 16.14, restricted `payments_app`; persistent Fake and simulated bank. | PR18-1 generator, baseSeed 180018, 100 distinct completed case seeds and 10000 actual steps. `python3 scripts/ci/run_pr18.py --profile pr` invokes the archived `clean verify` arguments. [Run](../evidence/pr18/run-summary.json), [sequence](../evidence/pr18/sequence-summary.json), [case hashes](../evidence/pr18/case-manifest.json), [concurrency](../evidence/pr18/concurrency-summary.json), [commit/input match](../evidence/pr18/commit-provenance.json). |
| R19 | Start `2026-10-09T04:11:45.797416+00:00`, end `2026-10-09T04:46:01.472360+00:00`; implementation `97e74f3c370d912d405d4a7a171e477106b97d08`. Darwin arm64, 8 logical CPUs, Corretto 21.0.12.1, Docker 27.4.0, PostgreSQL 16.14; actual Kafka container, persistent Fake and independent simulated merchant effects. | Fixed fault fixtures and seeds, F-01–F-08. `python3 scripts/ci/run_pr19.py --fault all`; underlying command and hashes are preserved. [Run](../evidence/pr19/run-summary.json), [42-window index](../evidence/pr19/experiment-index.json), [test counts/skips](../evidence/pr19/test-results.json), [raw archive](../evidence/pr19/raw-artifacts.tar.gz), [byte manifest](../evidence/pr19/artifact-manifest.json), [commit/input match](../evidence/pr19/commit-provenance.json). |
| S05 | Recorded `2026-09-22T17:34:49.469134+00:00`; base `d29da5a`, per-source hashes in verification; stripe-java 33.3.0, API and temporary endpoint `2026-07-29.dahlia`; isolated hosted test Checkout. | One actual test lifecycle: USD 100 minor payment and full refund; JPY settlement BTs; six matched callbacks. Standalone opt-in browser/probe, not application business DB/ledger. [Lifecycle](../evidence/pr05/lifecycle/lifecycle-evidence.json), [webhook](../evidence/pr05/lifecycle/webhook-evidence.json), [browser](../evidence/pr05/lifecycle/browser-evidence.json), [versions/source snapshot](../evidence/pr05/lifecycle/verification.json). Its recorded Maven command is a separate local regression, not the network probe command. |

R18/R19's top-level archived run reports identify the run by UTC start and report location; they do not supply a top-level `runId`. Do not invent one. Their per-case/experiment IDs and artifact manifests disambiguate results. A future unified runId may index these records but must retain the original fields.

## Evidence-backed wording

| Claim suitable for a resume or interview | Code and evidence | Precise limitation |
|---|---|---|
| “Built a payment and accounting platform with durable provider operations, fenced recovery and immutable double-entry journals.” | [PaymentRepository](../../src/main/java/com/paymentsledger/payment/PaymentRepository.java), [recovery coordinator](../../src/main/java/com/paymentsledger/payment/OperationRecoveryCoordinator.java), [ledger posting](../../src/main/java/com/paymentsledger/ledger/LedgerPostingService.java), [V3 constraints](../../src/main/resources/db/migration/V3__immutable_ledger.sql), [PR03](../evidence/pr03.md), R18/R19 regressions | Engineering/test platform; no production funds or distributed exactly-once guarantee |
| “Validated production services against independent models with 100 deterministic seeds and 10000 executed steps in local PostgreSQL.” | R18 sequence, case manifest/archive and [Pr18SequenceIT](../../src/test/java/com/paymentsledger/payment/Pr18SequenceIT.java) | Bounded operation grammar; no failed seeds in this final run; extended 1000-seed/300-step budget NOT RUN |
| “Verified four PostgreSQL concurrency scenarios and a separate two-JVM fault matrix.” | R18 concurrency report; R19 experiment index and [Pr19MultiProcessIT](../../src/test/java/com/paymentsledger/payment/Pr19MultiProcessIT.java) | R18 used distinct database connections within one JVM; R19 used two independent JVMs. Neither is a production cluster benchmark |
| “Replayed 42 local fault windows across eight failure categories, including process termination, Kafka interruption and concurrent commands.” | R19 per-window artifacts and [fault runbook](../runbook/pr19-fault-matrix.md) | Fake provider, local containers and fixed fixtures; remote CI execution and real-account fault injection NOT RUN |
| “The archived PR19 clean verification passed 733 Java tests; three real-account probes were explicitly disabled and skipped.” | R19 test-results and raw Maven log: unit 225; integration 511 total with 3 skipped, yielding 733 passed | A total regression count, not 733 independently distinct financial requirements or real-account passes |
| “Fixed a mixed payout-settlement evidence defect and added completeness regressions without changing immutable money history.” | [BankMatcher:57](../../src/main/java/com/paymentsledger/bank/BankMatcher.java#L57), [actual RED/GREEN retrospective](failure-retrospective.md), R18 commit provenance | Synthetic principal/return/bank fixture; mutation evidence is not counted as a discovered product defect |
| “Implemented independent balance rebuilding and reconciliation that distinguish known residuals from missing evidence.” | [BalanceRebuildService](../../src/main/java/com/paymentsledger/ledger/BalanceRebuildService.java), [ReconciliationService](../../src/main/java/com/paymentsledger/reconciliation/ReconciliationService.java), [PR15 actual results](../evidence/pr15/README.md), R19 regression | Independent period fixtures are not real historical Stripe statements |
| “Verified selected Stripe test-account Checkout, payment/refund BT and raw-byte webhook contracts at pinned versions.” | S05 lifecycle and source hashes | Standalone adapter contract; no claim that these real BTs entered the application ledger, no multiple-attempt/failed-refund/dispute/payout completeness claim |

A concise combination supported by R18/R19 is: “Built durable payment recovery and immutable accounting; validated local production services with 100 seeded sequences / 10000 steps and replayed 42 controlled fault windows across eight categories.” Keep “local” and “controlled” in the wording.

## Claims that remain unavailable

| Claim | Current evidence state | Required proof |
|---|---|---|
| Full performance targets achieved on one million historical payments | NOT RUN | Fixed hardware/limits, dual API and dual Worker JVMs, full warmup/measurement budget, raw arrivals/outcomes/latencies, queue/resource samples and independent correctness report |
| Completed payment throughput of 100/s | NOT RUN | Completed business/effect counts and duration; HTTP acceptance rate alone is insufficient |
| Measured backup/restore RPO or RTO | NOT RUN | Actual isolated dump/restore timeline, retained external state, lost/recovered records and gate-release timestamp |
| Completed English end-to-end demonstration | PARTIAL: materials/source exist; replay NOT RUN | All selected branch reports from fresh fixtures with actual statuses, source identity and environment |
| Production availability, bank connectivity or partner disbursement | OUT OF SCOPE | The project does not implement or measure these claims |
| Real complete historical PSP balance reconciliation | NOT RUN / contract unavailable | Independent comparable historical Stripe evidence and complete scope/cutoff coverage |
| End-to-end exactly-once delivery or money effects | UNSUPPORTED | Local deduplication, fencing and scoped observed effect counts do not establish that distributed guarantee |

No numerical throughput, percentile, data-loss or recovery-time claim may be filled from a target or from a smoke budget. A future PR20 result should add a new evidence row with its original runId, code identity, data distribution and raw reports; retain the historical limitations above.
