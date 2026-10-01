# PR14 Payout and Bank Evidence Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development with one domain implementer and sequential independent spec/quality reviews. Parent owns ingress and bank services. All Maven runs are serialized by parent. Use test-driven-development.

**Goal:** Complete payout ingestion and simulated bank matching with immutable accounting and local I-06 allocation limits.
**Architecture:** PayoutService owns actual BT posting; financial replay acquires payout ownership first. Internal bank import and match services reuse LedgerPostingService. Query workers persist evidence/cursors outside network transactions.
**Tech Stack:** Java21, PostgreSQL16/Flyway, Spring, JUnit/Testcontainers.

## Task 1 — Core and V12 schema

Files: payout/PayoutService.java, payout/PayoutConfiguration.java, V12__payout_bank_evidence.sql, tests/payout/PayoutCoreIT.java and PayoutSchemaIT.java.
- [x] Capture schema/classification RED and PostgreSQL lifecycle regressions before implementing the corresponding fixes; distinguish absent-API errors from behavior failures.
- [x] Add scoped payout canonical observations, BT links, immutable financial facts/cases; payout columns control outgoing/transit/net-bank/confirmed-return/suspense/fees.
- [x] Add bank statement/line/match schema with scope, immutable source identity, line capacity and deferred counter/journal consistency checks.
- [x] Post signed actual fees and exact transit/return/suspense amounts under caller locks; no provider paid bank cash; scoped internal queries.
- [x] V11 upgrade/reapply and runtime role allowlist tests.

## Task 2 — Bank and ingress

Files: bank/BankStatementImporter.java, bank/BankMatcher.java; payout/PayoutSourceRecovery.java, PayoutIngestionService.java, PayoutRecoveryWorker.java; financial replay/normalizer/worker/configuration; webhook/PayoutWebhookHandler.java, normalizer/processor. Tests PayoutLifecycleIT and existing contracts (bank coverage is consolidated in PayoutLifecycleIT).
- [x] Cover paid-no-bank and failing payout classification; retain existing normalizer/privacy contract checks and integrate webhook facts with atomic Inbox acknowledgement.
- [x] Discover payout and lock rank60 before BT; recheck owner and restart transaction on association races.
- [x] Query missing sources outside transactions and bounded durable known/list recovery; automatic payout membership pages, manual no FIFO.
- [x] Strict simulated fixture importer, canonical digest/reimport and atomic duplicate source rejection.
- [x] Match checks scope/currency/reference/date/capacity, exact fee allocation and stable event keys; clawback only with explicit evidence and suspense capacity.
- [x] PostgreSQL barrier concurrency, rollback/immutability, partial/split/fees/short/missing/unknown and conflict branch coverage.

## Task 3 — Delivery

- [x] Run focused tests with Java21, then independent spec and quality review; reproduce/fix findings.
- [x] Run final clean verify, migration regressions, OpenAPI/doc/secret/diff checks; archive counts, scope, source hashes and NOT_RUN evidence.
- [x] Update README/runbooks/state machine and fixture schema/example. Commit locally on codex/pr14-payout-bank after staged hash verification. Main merge and push require a later user instruction.

## Execution evidence

Final Java21 clean verification: 196 unit and 336 integration tests passed, 3 opt-in real tests skipped, 0 failures/errors. Source/build/contract/fixture hashes were captured before verification and checked afterward (257 files). Focused verification after all review fixes: 32 unit and 35 integration tests passed. Independent spec and quality reviews passed after regression-backed fixes.

The separate read-only Stripe payout probe passed (HTTP 200, zero items). Only list reading is VERIFIED; real payout object, return, membership and continuation paths remain NOT_RUN because the test-account list was empty. Optional active payout creation was not called. Bank coverage uses SIMULATED_BANK_EVIDENCE only.

See [PR14 evidence](../../evidence/pr14/README.md) for actual counts, RED/GREEN logs, reviews and source hashes. Delivery is a local commit on codex/pr14-payout-bank; main merge and remote push remain outside this request.
