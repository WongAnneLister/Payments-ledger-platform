# PR15 Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development with one domain implementer and sequential spec/quality reviewers. Parent owns L3 integration and runs all Maven commands serially. Checkbox steps track actual completion.

**Goal:** Implement safe L3 source recovery, independent three-level reconciliation, fee models and seven nightly checks.
**Architecture:** Wrap PR10 importer and existing recovery/ledger services. Reconciliation stores scoped versioned evidence; L3 extends the one existing task store and reuses payment/replay/notification ownership.
**Tech Stack:** Java21, Spring/JdbcTemplate, PostgreSQL16/Flyway, JUnit/Testcontainers.

## Task 1 — reconciliation domain (one implementer)
Files: src/main/java/com/paymentsledger/reconciliation/{ReconciliationService,ReconciliationChecks,IndependentBalanceEvidence,FeeModelService,ReconciliationWindows}.java; V14__reconciliation_evidence.sql; src/test/java/com/paymentsledger/reconciliation/{ReconciliationCoreIT,ReconciliationWindowsTest,FeeArithmeticTest,ReconciliationSchemaIT}.java.
- [x] Write compilable contract/schema/behavior tests first; parent runs RED and records actual assertions/errors separately. No production implementation until the matching RED is confirmed.
- [x] `createDay(scope,currency,date,zone)` and explicit backfill wrap import windows; `advance(run,pages)` advances bounded existing importer; `finish(run,evidence)` persists immutable seven checks and versioned residuals; scoped query rejects mismatches.
- [x] Persist run/cursor/evidence/exception/fee model/check results, composite scoped FKs and immutable version rules/minimal grants. Run hooks document exact APIs for Task2.
- [x] Verify cursor chain rather than only window COMPLETE; missing pages/version/window mismatch cannot PASS. Keep source reads, unresolved observations and fees visible.
- [x] Compute three-way PSP comparison with exact opening/cutoff/source independence, no same-BT oracle. Implement exactly seven consistent snapshot checks and existing BalanceRebuildService integration.
- [x] Model expected fee using integer arithmetic and version pins; absence UNMODELED_FEE; no accounting writes. Test fee/evidence/version/currency/partial-bank outcomes.
- [x] Parent runs `JAVA_HOME=<Corretto21> ./mvnw -B -Dtest=ReconciliationWindowsTest,FeeArithmeticTest -Dit.test=ReconciliationCoreIT,ReconciliationSchemaIT verify`; assert RED then GREEN and retain reports.

## Task 2 — L3 recovery and scheduling (parent)
Files: payment/ReconciliationSourceRecovery.java, reconciliation/ReconciliationWorker.java, notification/FulfillmentEligibility.java, V15__reconciliation_source_recovery.sql; tests/payment/ReconciliationRecoveryIT.java.
- [x] Write PostgreSQL tests for trusted server binding vs metadata-only/no-order/mismatch; lease expiry/stale token; rollback/duplicate/history origin/known Webhook and notification deny/allow.
- [x] Extend recovery_task kind and scoped source identity without another task store; isolate existing operation queries from L3 task rows.
- [x] Query Charge/PI outside transactions; validate all frozen trust conditions, persist read evidence and reason, atomically link qualified order/payment/attempt/provenance under ascending parent locks, then replay actual BT through FinancialReplayService.
- [x] Add scoped immutable server order binding and eligibility checks; metadata never creates a binding. Preserve unallocated records/exceptions when proof absent.
- [x] Worker profile/opt-in schedule generates business T+1/DST windows, bounded overlap/backfill/resume and nightly seven-check runs. Report/Balance capabilities only accepted with comparable evidence; unavailable remains INCOMPLETE.
- [x] Focused RED/GREEN command: `JAVA_HOME=<Corretto21> ./mvnw -B -Dtest=ReconciliationWindowsTest,FeeArithmeticTest -Dit.test=ReconciliationRecoveryIT,ReconciliationCoreIT,ReconciliationSchemaIT verify`.

## Task 3 — independent review and delivery
- [x] Spec review against frozen PR15, then quality review. Reproduce each concrete finding with failing test before fixing.
- [x] Update historical migration count assertions only where latest migration counts changed; verify V13 upgrade/reapply/runtime privileges and existing payment/refund/payout behavior.
- [x] Add reconciliation runbook/state machine/README and independent fixtures; annotate local vs external proof and INCOMPLETE/NOT_RUN limits.
- [x] Capture input hashes before final Java21 `./mvnw -B clean verify`; assert unchanged afterward and verify staged index matches tested source.
- [x] OpenAPI/doc links/configured secret scan/diff checks; archive actual tests, review/REDGREEN/evidence/residuals in docs/evidence/pr15.
- [x] Local commit on codex/pr15-reconciliation; leave main/push for later authorization.
