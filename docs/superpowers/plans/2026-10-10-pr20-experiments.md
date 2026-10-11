# PR20 Experiments Implementation Plan

> **For agentic workers:** Use superpowers:dispatching-parallel-agents for independent performance/recovery domains, and requesting-code-review for specification then quality review. Steps use checkbox syntax.

**Goal:** Execute and preserve AC-V1-36–38 experiments and English evidence without changing frozen targets or fund rules. A focused restore discovered the production source-recovery path misclassified a verified read as a webhook; a RED regression justifies using existing query ordering while retaining webhook stale guards.
**Architecture:** Test-classpath JVMs run production components in isolated containers; Python orchestrates budgets/provenance/report validation; historical evidence grounds English claims.
**Tech Stack:** Existing Java21/Spring Boot/PostgreSQL16/Kafka3.9.0/Testcontainers; Python standard library and Node22 checks.

## 1. Isolated baseline and report gates (root)
- [x] Verify main baseline and ignored worktree; preserve existing untracked files.
- [x] Run `JAVA_HOME=/Users/anne_lister/.jdks/amazon-corretto-21.jdk/Contents/Home ./mvnw -B test` once; archive its real output (225 passed).
- [x] Add `scripts/tests/test_pr20_runner.py` behavioral tests: smoke cannot meet full budget; missing processes/resource/correctness evidence fails; target breach remains FAIL; recovery release requires external-state continuity and all gates. Observe RED before adding `scripts/ci/run_pr20.py`.
- [x] Implement explicit `--phase all|performance|recovery|demo`, `--profile full|smoke` entry points. Capture source manifest/commit/versions/UTC/budget/exit codes; never read .env. Reuse run_pr18 input/environment helpers and persist logs outside target during clean.

## 2. Performance domain (independent implementer)
- [x] Own only `src/test/java/com/paymentsledger/pr20/performance/` and performance-specific Python under `scripts/pr20/`; add focused tests before helpers.
- [x] Implement actual dual API/dual Worker launcher, shared durable simulator, valid million-row history seed, bounded arrival generator and resource/queue/lag sampling.
- [x] Exercise production payment completion/BT/notification paths; compute independent effects and accounting checks from durable sources. Write raw per-request and sample files plus strict report. Use explicit hardware/limits and record overload rather than dropping it silently.
- [ ] Coordinate all Maven execution through root; run smoke then full 300s/1800s 1M/100/200 budget after code freeze and review. Archive both failures and successes.

## 3. Recovery and demo domain (independent implementer)
- [x] Own `src/test/java/com/paymentsledger/payment/Pr20Recovery*`, `Pr20Demo*` and recovery/demo-specific helpers; use existing fixtures without editing prior tests.
- [x] Add failing tests for restore gate and external-state divergence; implement real pg_dump/pg_restore to new isolated container/database with unchanged persistent external Fake.
- [x] Verify migration/permissions, independent rebuild, post-cutoff rescan, safe recovery, expired-key escalation, Outbox/event replay and effect uniqueness before release. Save actual RPO/RTO and unrecovered data.
- [x] Replay selected A/B, UNKNOWN, L3 and independent drift from empty test state; emit traceable demo reports.

## 4. English artifacts, acceptance and CI (root)
- [ ] Author `docs/english/README.md`, diagrams, demo, retrospective, recovery manual and `resume-evidence-map.md` using actual reports only.
- [ ] Build a 38-row evidence/status audit from current/history reports, preserving unavailable scope rather than blanket PASS.
- [x] Add automatic runner regressions and recovery/demo checks through existing verify, plus a manual full/smoke experiment entry (no new scheduled task); always upload actual reports and failures.
- [x] Independent whole spec then quality review; resolve findings with targeted regressions, no silent target changes.
- [ ] Freeze implementation commit; perform full performance and recovery/demo plus one complete clean verify retaining PR18 defaults and PR19 matrix; archive experiment evidence before clean.
- [ ] Run Node/Python/OpenAPI/docs/staged-diff checks; provenance audit; record actual acceptance states; commit locally. Leave main/push for subsequent authorization.

Focused verification: 7 unit + 8 integration tests PASS, 41 restore assertions, raw backup/effects/rebuild validated; 66 PR20 runner cases and 93 total runner cases PASS, 35 browser cases PASS. Full clean verification and frozen full performance remain pending.
