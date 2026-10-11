# Payments Ledger Platform

A Java 21 / Spring Boot payment platform that keeps payment intent, provider evidence, double-entry accounting and bank confirmation separate. API and Worker processes share a PostgreSQL transaction domain. Durable operations, immutable journals and independent reconciliation make uncertain outcomes visible and recoverable.

This is an engineering demonstration using test funds. It covers hosted Checkout, refunds, internal partner share accrual, disputes, payout ingestion and **simulated bank evidence**. It does not establish production money handling, bank connectivity, Stripe Connect or partner disbursement.

## Read in ten minutes

| Document | What it explains |
|---|---|
| [Architecture and five traced paths](architecture.md) | A request, a timeout, financial ingestion, settlement and independent reconciliation |
| [Payment state](payment-state.md) | Business outcome, provider state, certainty and accounting confirmation |
| [Accounting and invariants](accounting.md) | Where money moves and what the database can enforce |
| [Ten-minute demonstration](10-minute-demo.md) | Selected A/B branches, UNKNOWN, trusted-order L3 and an independent balance difference |
| [Failure retrospective](failure-retrospective.md) | A real mixed-settlement defect, completeness correction and separate test-driver failures |
| [Recovery runbook](recovery-runbook.md) | Backup, restore, external rescan and the gate before writes resume |
| [Resume evidence map](resume-evidence-map.md) | Claims with code, environment, command and actual report provenance |
| [Acceptance evidence audit](acceptance-evidence-audit.md) | All 38 frozen acceptance rows, scoped local evidence and remaining gaps |

The central distinction is visible in a successful payment: Checkout creation provides a customer action; a verified capture establishes the business fact; Balance Transactions (BTs) supply accounting amounts and fees; a payout BT moves PSP funds into transit; only verified bank evidence establishes bank cash. Those events may arrive independently or out of order.

## Evidence boundary

| Source | What this repository can establish |
|---|---|
| `LOCAL_MODEL` | Model rules, PostgreSQL constraints and transaction behavior |
| `PROVIDER_SIMULATOR` | Controlled remote acceptance, timeout, expiry, persistent effects and recovery |
| `STRIPE_TEST_CONTRACT` | Selected real test-account contracts at the recorded API/SDK versions |
| `INDEPENDENT_PROVIDER_FIXTURE` | Comparison with an independently supplied closing balance at a fixed cutoff |
| `SIMULATED_BANK_EVIDENCE` | File completeness, matching, principal allocation and bank-fee accounting |

[PR18](../evidence/pr18/README.md) and [PR19](../evidence/pr19/README.md) preserve actual local property, database concurrency and fault experiments. [PR05's real lifecycle](../evidence/pr05/lifecycle/README.md) is an adapter contract experiment: its BTs were **not posted into this application's business database or ledger**. [PR06's real HTTP contract](../evidence/pr06/README.md) created, queried, replayed and canceled an unpaid session. Private, untracked later real-account material is not proof supplied by this document set.

PR20's [complete local verification](../evidence/pr20/verified-all-result.json) passed 748 Java tests with three real-account probes explicitly skipped. The isolated restore and six selected demo branches passed; restore RPO 885 ms / RTO 3012 ms apply only to that small fixture, and global writes remain held. The first million-row performance attempt **failed during initial warmup** because database instrumentation failed; no completed warmup or measurement is available; it does not establish the [frozen performance targets](../superpowers/specs/2026-10-10-pr20-experiments-design.md). A second diagnostic run confirmed a socket timeout in the original two-table observation query; [its raw evidence](../evidence/pr20/history/full-million-observation-timeout-diagnostic.tar.gz) is retained. A refined test-only observation uses one live aggregate and a verified immutable expiry cutoff; its third full-profile attempt still exceeded 10 seconds under load. The fourth run at `0dcd509` is [BLOCKED before load](../evidence/pr20/latest-performance-result.json): the first million-row association check passed and four restricted JVMs became ready, but the required post-readiness four-table association count exceeded the unchanged 10-second deadline (10,220.515 ms, SQLSTATE 08006). No requests, warmup or measurement ran. The new protocol for retaining ordinary in-load failures is verified by focused tests, not by this blocked run; mandatory preflight and disk/raw-evidence failures still stop execution. Complete full-budget performance evidence remains unavailable. Failed runs and nonrecoverable records remain in the archive.

## Run and inspect

Use the repository's [setup instructions](../../README.md), [business payment runbook](../runbook/business-payments.md) and [pages/observability runbook](../runbook/pages-observability.md). Exactly one role profile, `api` or `worker`, must be active in each application process. Local credentials and real-account opt-ins are outside these English materials; do not copy them into reports.

Existing evidence can be replayed with Java 21 and an available Docker engine:

```bash
python3 scripts/ci/run_pr18.py --profile pr
python3 scripts/ci/run_pr19.py --fault all
```

These are reproduction commands. PR20 also reran the default PR18 sequences and complete PR19 fault matrix in its archived clean verification. [PR18 run metadata](../evidence/pr18/run-summary.json) and [PR19 run metadata](../evidence/pr19/run-summary.json) retain their original timestamps, environment and input hashes. [Commit provenance](../evidence/pr18/commit-provenance.json) explains why the pre-commit PR18 run preserves its baseline HEAD while matching the later implementation commit.

The architecture notes state source-observed behavior; rationale from an ADR is identified as a documented decision. Any reconstruction is explicitly marked **Inferred**. The original [payment state specification](../state-machines/payment.md) still carries its PR00 “unimplemented design” heading, so the English state diagram is checked against current source rather than treating that heading as current execution evidence.
