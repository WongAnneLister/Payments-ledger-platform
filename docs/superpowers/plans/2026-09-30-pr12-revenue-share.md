# PR12 Revenue Share Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development and test-driven-development. Separate core implementation from parent integration; spec review precedes quality review.

**Goal:** Implement internal partner accrual and atomic refund/evidence-driven adjustments under I-04.

**Architecture:** A scoped RevenueShareService owns cumulative allocation and immutable adjustment journals. Payment, refund and financial ingress call it inside their existing transactions; the financial path prepares business locks before posting. Existing LedgerPostingService remains the sole posting entry.

**Tech Stack:** Java 21, Spring, PostgreSQL 16, Flyway, JUnit/Testcontainers.

## Task 1 — Core service and migration

Files: revenueshare/SharePolicy.java, RevenueShareService.java, ShareEligibilityPolicy.java, RevenueShareConfiguration.java, RevenueShareWorker.java; V10__revenue_share.sql; revenueshare policy and PostgreSQL core/schema tests.

- [x] Write independent rounding/rate-limit tests and capture their RED before implementation. Example: `assertThat(cap(6000,2000)).isEqualTo(1200)`; independent oracle uses quotient/remainder arithmetic.
- [x] Implement scoped immutable policy parsing, exact cumulative caps, stable allocation generations and sealed adjustment Journals; reject direct history mutation and mismatched scoped references.
- [x] Expose `lock(UUID paymentId)` returning a locked handle and `reconcile(Locked, String cause)` with no business lock acquisition; ordinary transaction hooks use `reconcile(UUID,String)`. No network I/O and no REQUIRES_NEW.
- [x] Validate manual 2000 -> 1200 -> 2000 sequence, stable repeat, missing evidence gate and V9 upgrade. Use real runtime-role PostgreSQL and existing LedgerPostingService.

## Task 2 — Existing workflow integration

Files: payment/RefundRepository.java, PaymentRepository.java; financialingestion/FinancialReplayService.java, FinancialImportService.java, FinancialIngestionConfiguration.java; integration tests in payment/RevenueShareLifecycleIT.java.

- [x] Add integration coverage: posted capture accrues 2000, reserve4000 atomically reverses800, failure restores800, repeated failure unchanged; success H-to-R does not reverse again.
- [x] Hook refund prepare, canonical accept, explicit rejection, conflict freeze and audited unfreeze before terminal Outbox/transaction completion.
- [x] Prepare share locks before capture posting, reconcile afterward in the same transaction. Late conflicting BT import prelocks all affected parents before accepting facts and adjusts before commit.
- [x] Wire real Spring services under controlled enabled configuration; keep old manual constructors compatible. Validate immutable policy at new order creation.
- [x] Add concurrency barrier, UNKNOWN/recovery, external conflict, late financial conflict and rollback injection tests, including no PSP change from share adjustments.

## Task 3 — Delivery

- [x] Run focused RED/GREEN suites with `JAVA_HOME=/Users/anne_lister/.jdks/amazon-corretto-21.jdk/Contents/Home ./mvnw -B ... verify`; serialize Maven access across agents.
- [x] Run full clean verify after integration; fix only concrete failures and findings.
- [x] Independent spec then quality review, with regression-backed fixes.
- [x] Update README/runbooks and archive reports, review outcomes, precise counts and source hashes in docs/evidence/pr12. PR13 dispute end-to-end NOT_RUN; no external partner payout is provided.
- [x] Check OpenAPI, documentation links, diff whitespace and configured secrets; verify staged source matches tested source, then local commit on codex/pr12-revenue-share.

## Execution notes

Policy tests captured two assertion failures before implementation. Initial lifecycle setup attempts failed on fixture/migration setup and are not counted as behavior RED. Review added three meaningful regression REDs: malformed known-source BT left S=2000, concurrent source association left S=1200, and reversed BT lock order caused PostgreSQL NOWAIT 55P03. Each passed after its corresponding fix; focused final suite contains 25 tests. Final verification and review evidence is archived under docs/evidence/pr12.
