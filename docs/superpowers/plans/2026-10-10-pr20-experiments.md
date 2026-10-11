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
- [x] Author `docs/english/README.md`, diagrams, demo, retrospective, recovery manual and `resume-evidence-map.md` using actual reports only.
- [x] Build a 38-row evidence/status audit from current/history reports, preserving unavailable scope rather than blanket PASS.
- [x] Add automatic runner regressions and recovery/demo checks through existing verify, plus a manual full/smoke experiment entry (no new scheduled task); always upload actual reports and failures.
- [x] Independent whole spec then quality review; resolve findings with targeted regressions, no silent target changes.
- [x] Freeze implementation revisions and preserve actual source identity; archive complete clean verification retaining PR18 defaults/PR19 matrix, restore/demo PASS and every actual performance failure. Production code is unchanged after clean-verified `1313e361`; later test-only changes have separate focused evidence.
- [ ] Obtain the completed 300s warmup / 1800s measurement and complete observations at 1M history / 100 write + 200 query RPS. Fourth frozen run `0dcd509` is BLOCKED by mandatory post-readiness association verification; no full-budget PASS is claimed.
- [x] Run Node/Python/OpenAPI/docs/diff checks; provenance audit; record actual acceptance states and prepare local evidence commit. Leave main/push for subsequent authorization.

Actual complete clean verification at `1313e361` passed 232 unit + 516 executed integration tests (748 PASS; 3 real-account opt-ins skipped), including the PR18 default 100-seed/10000-step sequence and all 42 PR19 fault windows. Restore passed 41 assertions / 12 release gates, local RPO 885 ms / RTO 3012 ms; six selected fresh-state demo cases passed. Subsequent test-only observation refinements have separate source-bound focused results; the latest `0dcd509` passed 12 unit + 1 PostgreSQL integration tests. The fourth frozen full-profile attempt at `0dcd509` is BLOCKED before load: its first 1M association check and four JVM readiness passed, but the mandatory repeated four-table check exceeded 10 seconds. Earlier attempts remain partial-warmup FAIL records. All original results are archived without changing budgets or gates. English artifacts, diagrams and the 38-row audit distinguish these outcomes; complete full-budget performance evidence remains outstanding.

Final read-only evidence review found no material discrepancy: original XML and source bytes support 748 PASS / 3 skipped at `1313e361`, latest 12 unit + 1 PostgreSQL integration PASS, restore 41 assertions / 12 gates and six selected demo PASS. Final runner/browser/OpenAPI checks passed (93 / 35 / 29 paths, 70 schemas, 99 resolved refs). Full performance acceptance remains outstanding; no additional experiment-architecture change or target relaxation is part of this evidence commit.
