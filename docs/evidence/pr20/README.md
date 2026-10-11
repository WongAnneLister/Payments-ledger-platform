# PR20 experiments and English evidence

Implementation is being frozen on `codex/pr20-experiments`, based on PR19 `d92bae3837dc4fc13eac734733ce36bf0ac0b3d0`. Main has not been changed. This document will record actual final runs after verification; no full-performance or recovery success is predicted here.

## Evidence so far

- Actual canonical smoke `20261011T142232Z-595b071f`: **FAIL**, with raw evidence validated, 1,000 historical payments, 5 seconds warmup and 20 seconds measurement at 5 write + 10 query RPS. Measurement: 300 attempts, 0 dropped, 11 errors (3.6667%); write P95 14,433.096 ms and P99 16,068.721 ms, query P95 13,908.999 ms and P99 15,255.516 ms. Positive individual-queue growth 196; completed accounting throughput 0/s, 122 provider captures and 0 posted payments after drain. Same-day BTs remain outside the production daily import cutoff. This is **not** the frozen full budget and supplies no full-target PASS.
- Full performance: **NOT RUN**. Frozen targets: 1,000,000 historical payments, 300 seconds warmup, 1,800 seconds measurement, 100 write + 200 query RPS; P95 ≤ 300 ms, P99 ≤ 1,000 ms, errors < 0.1%, bounded queues and independent correctness.
- Recovery and six demo scenarios: **focused PASS**, run `focused-final-20261011-08`: 7 unit + 8 integration tests, 41 restore assertions; raw backup, external effects and independent rebuild validated. Actual local RPO 894 ms and RTO 2,738 ms. Global writes remain held; one qualified operation is whitelisted, expired UNKNOWN and one lost local-only order require manual investigation. All earlier driver failures and the genuine query-classification regression are preserved under [history](history/).
- Runner validation: 66 Python cases passed after actual RED on empty resources, stale observations, undrained Kafka and mismatched source hashes. Focused Java query regression and recovery passed; full clean verification remains pending.

## Reproduce

Java 21 and Docker are required. The runner never reads `.env`, removes provider credentials and disables the three real-account opt-ins. It only creates owned disposable PostgreSQL/Kafka resources. Existing containers and user databases are outside scope.

```sh
JAVA_HOME=/path/to/java21 python3 scripts/ci/run_pr20.py --phase all --profile full
JAVA_HOME=/path/to/java21 python3 scripts/ci/run_pr20.py --phase performance --profile smoke
```

The [manual CI workflow](../../../.github/workflows/pr20-experiments.yml) uploads actual reports on success or failure. Remote CI is **NOT RUN**. The [design](../../superpowers/specs/2026-10-10-pr20-experiments-design.md), [plan](../../superpowers/plans/2026-10-10-pr20-experiments.md) and [English index](../../english/README.md) describe scope. Bank evidence and external recovery effects here are controlled persistent fixtures, not a real bank or production recovery certification.
