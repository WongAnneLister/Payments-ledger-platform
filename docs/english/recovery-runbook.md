# Isolated backup and recovery runbook

[English index](README.md) · [Frozen recovery requirements](../product/payment-platform-acceptance.md#92-备份恢复演练) · [PR20 experiment design](../superpowers/specs/2026-10-10-pr20-experiments-design.md)

**Actual local replay: PASS**, run `20261011T154420Z-e7793514`, source `1313e36170ff36d52f0fc96cbbc1d12ab6ac000f`. The [result index](../evidence/pr20/verified-all-result.json) and [raw archive manifest](../evidence/pr20/history/full-million-instrumentation-failure-manifest.json) retain the dump/restore, external state, 41 assertions and all 12 release gates. Measured fixture RPO is **885 ms** and RTO **3012 ms**, using the definitions below. These are observations from a small local fixture, not production recovery objectives.

Only one independently qualified operation is released. **Global writes remain held**; an expired UNKNOWN operation and one post-backup local-only order remain manual outcomes. Nightly I02 remains **INCOMPLETE** because unresolved outcomes still exist. The release gate is test-only orchestration; production eligibility uses existing recovery and source-verification rules. [Gate tests](../../src/test/java/com/paymentsledger/payment/Pr20RecoveryGateTest.java), [isolated fixture](../../src/test/java/com/paymentsledger/payment/Pr20RecoveryFixture.java) and [report writer](../../src/test/java/com/paymentsledger/payment/Pr20RecoveryReports.java) implement this exercise.

## Scope and prerequisites

Run only against fresh disposable PostgreSQL containers/databases created by the exercise. Capture the source and restore container/database identities, PostgreSQL/client versions, source commit and input hashes before invoking backup commands. Production and unrelated project databases are outside this procedure. Retain the independent persistent Fake state outside the source database; record its effect identities and checksum before backup, after post-backup activity and after recovery.

Use Java 21, Docker and the fixture's `payments_app` restricted role. The intended focused replay is:

```bash
./mvnw -B -Dtest=Pr20RecoveryGateTest -Dit.test=Pr20RecoveryIT verify
```

Freeze the source revision before replaying it. The script must retain command exit codes, raw backup/restore output and per-case JSON under `target/pr20/recovery/<runId>/`. Archive them outside `target` before a clean build. A failed prerequisite is BLOCKED; an actual failed restore/assertion is FAIL. Neither can be turned into PASS by prose.

## Procedure and release evidence

| Step | Required action | Evidence before proceeding |
|---|---|---|
| 1. Establish isolation | Identify both new databases/containers and reject accidental source/target reuse | Names/IDs, ports, clean fixture origin, restricted role, source commit and versions |
| 2. Take a consistent backup | Run `pg_dump` with the exercise's isolated connection; keep the archive | Actual command and exit code, UTC start/end, backup checksum, cutoff definition |
| 3. Produce divergence | Create post-backup payment/refund facts and durable external effects | Local rows before loss, independent external requests/effects/BTs, exact timestamps; do not roll external state back |
| 4. Restore separately | Use `pg_restore` into the new isolated target; keep fund execution gated | New target identity, actual restore output/exit, migration version, application ACL checks |
| 5. Rebuild and compare | Independently scan immutable entries, trial balance and event↔journal coverage | Per-account rebuilt/projected residuals and journal balance/coverage checks |
| 6. Rescan external evidence | Re-enumerate fixed scoped windows and resolve BT→source dependencies | Full page/cursor coverage, pre/post-cutoff facts, stable scope/currency/domain, trusted source observations |
| 7. Recover only eligible work | Query trusted anchors; use the original safe key only when qualified; L3 links only trusted orders | Operations/tasks, key/fingerprint, lease fencing, provenance, expired UNKNOWN escalation and no invented order |
| 8. Replay local notifications | Reuse stable canonical event IDs through the real Outbox and Consumer Inbox | One Inbox/delivery obligation per event; delivery remains HELD without a configured merchant target |
| 9. Evaluate the gate | Compare independent closing/effects and retain unresolved records | All required independent gate results true, explained residuals, no duplicate external effect, explicit write-gate release |

[OperationRecoveryCoordinator.java:45](../../src/main/java/com/paymentsledger/payment/OperationRecoveryCoordinator.java#L45) supplies trusted-anchor priority and safe-key qualification; [ReconciliationSourceRecovery.java:95](../../src/main/java/com/paymentsledger/payment/ReconciliationSourceRecovery.java#L95) supplies trusted-order linking and fenced ownership; [BalanceRebuildService.java:49](../../src/main/java/com/paymentsledger/ledger/BalanceRebuildService.java#L49) supplies independent local rebuild. These are reusable production paths, not a special rule that treats a restore as evidence of payment success.

Notification replay in the recovery fixture validates local Outbox, Consumer Inbox and delivery obligations through a direct sender. Merchant delivery remains **HELD** for manual target configuration; no merchant transport or external merchant effect is exercised in this restore. Kafka transport is also outside this fixture. Actual Kafka interruption/replay is separately evidenced in [PR19 F-07](../evidence/pr19/README.md). These distinct experiments do not prove that the restore restarted a broker or completed merchant delivery.

## Measure loss and recovery precisely

RPO must name the backup cutoff and the loss/incident timestamp. Report both the temporal gap and the actual records unavailable from the restored database. Classify which post-cutoff facts can be recovered from external evidence and trusted orders; list local-only records that cannot be reconstructed automatically. External rescan does not imply zero data loss.

RTO must name its start (incident or restore initiation, as actually defined by the run) and end (all required gates passed and eligible writes released). Report restore duration separately from full recovery duration. Advancing a Fake clock for lease eligibility is not wall-clock time elapsed. A database accepting connections is not the RTO release point.

## Controlled manual outcomes

Expired UNKNOWN work without a trusted object anchor remains escalated. Missing trusted orders remain unallocated financial observations and cannot trigger automatic fulfillment. Evidence conflicts retain original facts and investigation records. Missing bank completeness or independent closing evidence leaves the affected check INCOMPLETE; a known residual remains FAIL even if another source is incomplete.

For every nonautomatic outcome, save the resource/task ID, reason, known external evidence, retained reservation or Suspense, and the evidence a reviewer would need to resolve it. Approved adjustments follow [the ops approval/audit flow](../runbook/ops.md); never edit sealed entries or add arbitrary balancing entries to make the gate green. Release only the workflows whose qualification is actually proven.

## What the final report must contain

One report must bind runId, source commit/hash manifest, UTC timeline, environment/client versions, isolation identities, backup archive/checksum/cutoff, raw command outputs, unchanged external-state evidence, reconstructed balances and coverage, external rescan differences, recovered/escalated/unrecoverable records, notification/effect counts, manual steps, actual RPO/RTO definitions and values, and every release gate. Missing fields mean incomplete evidence. The archived local PASS applies to this scoped exercise, including its unresolved manual outcomes; it does not certify unrestricted recovery or production availability.
