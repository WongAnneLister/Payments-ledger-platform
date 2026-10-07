# PR18 Property and Concurrency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Deliver AC-V1-34 with independent money oracles, reproducible combined production-service sequences, shrinking and PostgreSQL concurrency evidence.

**Architecture:** Test-only pure integer models compute expected effects independently. A real PostgreSQL/Fake Provider driver runs existing services and compares the database after each transaction; evidence-complete reconciliation is a separate gate. Concurrent actors have separately constructed services/connections and use barriers rather than a Java lock.

**Tech Stack:** Java21、JUnit Platform/Jupiter、PostgreSQL16/Testcontainers；独立确定性生成器/语义收缩器 PR18-1，不新增属性库依赖。

---

### Task 1: Independent models, generator and shrinking

Files: create `src/test/java/com/paymentsledger/pr18/` models, command/generator and model/generator tests. Do not import production calculation functions into expected-value code.

- [x] Add tests for C=1000, reserve600 then reserve600 rejection; terminal failure releases once; stale pending cannot regress success; capture10000, rate2000, H4000 yields1200; duplicate event no second ledger effect; payout5700 returned1200 leaves4500 transit; missing external evidence yields INCOMPLETE.
- [x] Run `./mvnw -B -Dtest='PropertyModelsTest,SequenceGeneratorTest' test`, retaining initial RED and subsequent GREEN. Initial missing implementations may compile-fail but do not claim that as a behavioral failure.
- [x] Implement six isolated models using exact integers and event identities. Generate explicit legal/illegal commands and deterministic case seeds, operation lists bounded by profile. Implement failure-preserving shrink and verify a deliberately faulty implementation is reduced; label it MUTATION_ONLY.

### Task 2: Production sequence and concurrency suites

Files: create a reusable fixture/production driver plus `Pr18SequenceIT`, `Pr18ConcurrencyIT`, `Pr18ScopeIsolationIT`, and `Pr18PayoutEvidenceIT` under `src/test/java/com/paymentsledger/payment/`. Existing services and migrations stay authoritative.

- [x] Drive payment preparation/executor/provider observations, refunds, capture/refund/dispute/payout balance transaction ingestion/replay, share reconciliation and bank fixture importer/matcher. Keep seeds and original commands outside model state; read assertions compare against the model, never populate it from database outcomes.
- [x] Check each transaction against independent C/R/H/share/ledger expected values, exact journal coverage and scope. Compare external PSP/bank totals only at a complete, same-cutoff evidence gate; deliberately missing evidence stays incomplete.
- [x] Retain generated original/minimized failures and database/provider/message snapshots in `target/pr18/`; ensure test reporting persists on failures. Expose explicit seed/count/maxSteps controls and fail fast on invalid ranges.
- [x] Use at least two distinct production component instances and PostgreSQL connections with bounded barriers/futures. Race competing refunds, same-key duplicate commands, refund/dispute/share interaction, and payout allocation. Verify exactly accepted effects, no over-allocation, no deadlock and independent balance rebuild. Record PID/trace evidence.
- [x] Compile through focused Failsafe suites with `-Dit.test='Pr18*IT' verify`. Save actual case/step counts and every failure, including driver failures separately from real defects.

### Task 3: Runner, CI and evidence

Files: `scripts/ci/run_pr18.py`, its standard-library tests; `.github/workflows/ci.yml`; PR18 evidence, design/plan, version matrix and roadmap.

- [x] Test command construction, strict numeric limits, nonzero exit propagation and report parsing without real credentials. Runner executes Maven with explicit profile budget, records command, environment, input hashes, actual operation counts, outcome and elapsed time. No `.env` reads.
- [x] Default CI runs100 seeds/100 max steps as part of verify; opt-in/manual or scheduled nightly runs1000/300. Upload `target/pr18/**` even on failure. Mark remote/nightly runs NOT RUN until actually executed; nightly must not silently reduce budget.
- [x] Run Java21 clean verify, Node22 existing browser tests, Python runner/docs/OpenAPI checks, migration isolation and `git diff --check`. Archive concise actual reports, not secrets or unbounded raw sensitive logs.
- [x] Request independent spec then quality review; fix findings and rerun affected tests. Finalize acceptance coverage and limitations from actual outputs. Keep branch reviewable; do not merge/push without the user's subsequent instruction.
