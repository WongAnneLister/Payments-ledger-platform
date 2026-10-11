# PR20 Performance, Recovery and English Evidence Design

Baseline: d92bae3; branch codex/pr20-experiments. The user requested the frozen PR20 in docs/product/stripe-payment-ledger-codex-pr-prompts.md. Its existing scope and targets are authoritative; this design makes execution concrete without altering business rules.

## Performance

Use an isolated PostgreSQL16 and Kafka3.9.0 environment, two actual API JVMs and two actual Worker JVMs, and a shared durable test-only Provider simulator. Production HTTP authentication, controllers, services, repositories, ledger and notification paths remain in use. No real account credentials or .env are read. Seed exactly one million valid synthetic historical CREATED/UNKNOWN payments with complete scoped orders/operations and escalated recovery obligations ineligible for blind resend. Document their unresolved distribution and baseline separately from new traffic; this is not a million completed payments. Keep triggers and constraints enabled and verify the production scanner cannot re-execute that history. Run an arrival-rate generator at 100 write requests/s and 200 queries/s: 300s warmup, 1800s measurement. Save per-request outcomes/latencies, scheduled versus actual arrivals, P50/P95/P99 by class, acceptance and completed-business throughput separately, errors and dropped arrivals, JVM/DB resource samples, Kafka lag, queue age/size, provider requests/effects, event/Journal counts and independent correctness/reconciliation results. Preserve target breaches as FAIL; smoke is never full-budget PASS. A bounded post-load drain is separately timed and cannot erase queue growth during measurement.

## Backup and recovery

Use fresh test containers/databases exclusively, record names, versions, backup cutoff/checksum and isolation checks before pg_dump. Generate post-backup local and durable external payment/refund facts; restore the consistent archive into a separate new database while retaining external simulator state. Keep write execution gated, verify migration and restricted application permissions, independently rebuild/trial-balance and rescan scoped provider evidence, replay notifications, restore only trusted-order and safe-key eligible work, escalate expired unknown work. Record actual timestamps, RPO/RTO definitions and values, missing local-only records, nonrecoverable/controlled manual steps, task/BT/event/effect counts and release-gate checks. Never reset external facts to make a restore pass.

## Demonstration and English materials

Add replayable selected A/B lifecycle branches, UNKNOWN recovery, trusted-order L3 backfill and independent balance-difference detection from fresh isolated fixtures. Author an English README, architecture/state/accounting diagrams, ten-minute demo, failure retrospective, recovery runbook and resume evidence mapping. Every quantitative claim names its actual source commit/environment/data/command/report. Preserve distinctions between design, local model, Fake, Stripe test contract, simulated bank and NOT RUN/BLOCKED. Audit all38 acceptance rows without retroactively marking historical NOT RUN rows PASS merely because an implementation exists.

## Execution boundaries

Performance, recovery and documentation own separate files; independent implementation can run in parallel under dispatching-parallel-agents. Maven and Docker experiment execution are serialized by the root agent, and no heavy unrelated work runs during measured load. Source inputs are frozen and hashed for final experiments. Missing capabilities produce explicit BLOCKED artifacts; actual failed targets/assertions produce FAIL. No automatic push/merge is included in this request.

## Evidence-driven minimal production correction

The isolated restore reproduced `Verified source merge did not apply` at an equal provider-query timestamp. The source-recovery path had classified its verified charge/intent reads as webhook facts. The narrow correction invokes existing query ordering through `mergeReconciliationQuery`; immutable capture validation, scope/order locks, stale provider watermarks and webhook timestamp protection remain. The independent review found no actionable safety issue; archived RED sources match baseline d92bae3 and the GREEN regression observes unchanged journals and external effects. This correction is necessary for the frozen restore scope, not a change to fund rules or targets.

Full clean verification also exposed a pre-existing PR19 Kafka fixture date dependency: five scenarios stopped before injection because fixed October 9 provider time preceded current database price validity. Only that fixture clock is anchored ahead of setup wall time (+120 seconds), recorded in each dataset/child environment. Seed, fault windows, production guards and assertions are unchanged; original five errors and focused five-case GREEN are archived.


### Observation refinement after actual million-row failure

The second full-profile attempt `20261011T164255Z-f056d9b2` at `e04af4a` reached initial warmup but its historical payment/business-order observation timed out after 10396.234458 ms (SQLSTATE08006, SocketTimeoutException). Only the test harness is refined: immediately before HTTP generation, capture database clock_timestamp and verify all one million seeded UNKNOWN deadlines are before it. Their expires_at is protected by the payment identity guard; new production request deadlines are after this fixed cutoff. A single live scoped payment-table MVCC aggregate reads history/current UNKNOWN, success/posted, unfinished new work and UNKNOWN age. No cached counts, new production indexes, business behavior, relaxed deadlines or reduced pressure targets. Raw failure and per-query diagnostics remain archived. PostgreSQL regressions cover current traffic exclusion, immutable-deadline grouping despite created_at changes and live history state changes.


### Retaining failed full-load experiments

The frozen `71e422e` run confirmed that a single live aggregate can still exceed the same 10-second query/socket limit under the fixed load and resource caps. Two live samples completed before a later aggregate timed out; all raw results remain archived. Changing to a cursor without a whole-scan deadline could silently extend the observation budget, so that approach is not used.

The narrower test-only protocol change retains ordinary observation failures as FAIL while continuing the complete frozen HTTP arrival budget. Each failed boundary or correctness observation is marked incomplete with original exception evidence; unavailable completion/queue metrics are null, never fabricated zero or a reused sample. Any observation error, incomplete boundary/correctness, or undrained workload defeats PASS. SQL and all deadlines, resource caps, scopes, source business code and acceptance targets are unchanged. Disk capacity or inability to preserve raw evidence still abort immediately. This allows a completed request-budget failure to be distinguished from a complete and passing resource/correctness measurement.
