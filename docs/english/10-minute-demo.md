# Ten-minute demonstration script

[English index](README.md) · [Accounting diagram](accounting.md) · [Payment state diagram](payment-state.md)

**Actual selected replay: PASS**, run `20261011T154420Z-e7793514`, source `1313e36170ff36d52f0fc96cbbc1d12ab6ac000f`. The six [case results](../evidence/pr20/verified-all-result.json) and [raw archive](../evidence/pr20/history/full-million-instrumentation-failure.tar.gz) cover both A branches, B's failed-refund/late-success race, UNKNOWN, trusted-order L3 and the deliberately nonzero independent difference. [Pr20DemoIT](../../src/test/java/com/paymentsledger/payment/Pr20DemoIT.java) starts each case with a fresh isolated database and persistent Fake provider state. Values below are synthetic fixture checkpoints. Bank statements are simulated; this suite does not exercise Kafka delivery.

## Prepare and retain the evidence

Use Java 21 and Docker. From this PR20 checkout, the focused Maven replay is:

```bash
./mvnw -B -Dtest=Pr20RecoveryGateTest -Dit.test=Pr20DemoIT verify
```

The [report writer](../../src/test/java/com/paymentsledger/payment/Pr20RecoveryReports.java) saves per-case JSON and a summary under `target/pr20/demo/<runId>/`, including failures. Retain that directory, command output, suite report, source commit/hash manifest, environment and simulator snapshots before any clean build. Read all six case statuses; a summary without the complete case set is insufficient. Record the tested source commit, because `WORKING_TREE_UNCOMMITTED` is not an immutable release identifier. Never relabel these simulated branches as real Stripe executions.

## Presenter timing and words

| Time | Show / action | Say |
|---|---|---|
| 0:00–1:00 | Architecture, source legend and current run metadata | “This demonstration uses test funds and a persistent provider simulator. Bank evidence is simulated. I separate payment outcome, provider certainty and accounting confirmation.” |
| 1:00–2:00 | Payment state dimensions and the create trace | “Creating a Checkout session is a customer action, not a confirmed charge. Intent and recovery obligation are committed before the provider call. A timeout preserves the existing payment.” |
| 2:00–4:00 | `DEMO-A-SUCCESS-BANK`, then its independent return branch | “The actual capture BT supplies the amount and fee. A refund hold changes capacity and partner entitlement without moving PSP cash. Refund and payout BTs move cash. The bank fixture confirms bank cash only after matching.” |
| 4:00–5:30 | `DEMO-B-REFUND-FAIL-LATE-WIN` | “An unknown refund retains its hold. This selected branch fails before any refund debit BT, so I do not invent a debit and reversal. Dispute loss and late win preserve separate financial facts.” |
| 5:30–6:30 | `DEMO-UNKNOWN`: original operation key and external effects before/after | “Recovery queries a trusted object or reuses the original safe key. The independent effect list must stay unchanged; payment row count alone cannot prove no repeat capture.” |
| 6:30–7:30 | `DEMO-L3`: trusted order, provenance and canonical journal | “An external Charge and PI can recover a missing local payment only when a valid server-proven order establishes scope and amount. Metadata alone cannot create an order or authorize fulfillment.” |
| 7:30–8:30 | `DEMO-DIFFERENCE`: independent closing versus rebuilt ledger | “Local trial balance can pass while external evidence differs. This deliberately supplied one-unit closing difference should produce a reconciliation FAIL. Detecting that FAIL is the test's expected success.” |
| 8:30–9:30 | Seven invariants and their commit/evidence boundaries | “Local constraints reject unbalanced journals, duplicate postings and oversubscribed commands. External completeness requires independent comparable evidence; absent evidence stays INCOMPLETE.” |
| 9:30–10:00 | Resume map, 38-row audit and PR20 report status | “These claims point to actual historical reports. Full performance and restore results belong to their own run artifacts. Unrun or blocked work is visible, and no end-to-end exactly-once guarantee is claimed.” |

## Expected checkpoints to compare with the replay

The source of these fixture amounts and branches is [Pr20DemoIT.java:64](../../src/test/java/com/paymentsledger/payment/Pr20DemoIT.java#L64). Compare each checkpoint's `expected`, `actual` and independent `audit` in the produced report; do not copy this table into an “actual results” column.

| Selected branch | Expected synthetic outcome in USD minor units |
|---|---|
| A capture + share | PSP 9700; share expense/payable 2000 |
| A refund hold | H 4000; PSP still 9700; share 1200 |
| A successful refund BT | PSP 5700; share 1200 |
| A payout BT | PSP 0; transit 5700; bank 0 |
| A bank-confirmed ending | PSP 0; transit 0; simulated bank 5700; closing debit/credit balances each 11200 |
| A alternative full payout return | PSP 5700; transit 0; bank 0; independent branch from the pre-bank node |
| B UNKNOWN then early failure | H retained while unknown; explicit failure releases H; PSP 9700; share restored to 2000 only after eligibility checks |
| B dispute lost then late win | PSP 5200 after actual debit and fee; 8200 after principal return; loss/receivable 0; share 2000; dispute fee 1500 remains |
| Independent difference | Supplied closing 9701 versus ledger 9700; expected reconciliation FAIL; independent local rebuild still matches |

The A success and A return endings are mutually exclusive fixtures. The selected B branch is early failure without a refund debit; it does not demonstrate every B3/B5 variant. The [frozen acceptance examples](../product/payment-platform-acceptance.md#5-可以手算的两条演示路线) and prior [refund](../evidence/pr11/README.md)/[dispute](../evidence/pr13/README.md) reports explain the other branches.

If a replay fails, show its retained per-case report and the actual failed assertion. A product output of `FAIL` in the difference case is expected; a missing residual check, missing branch or unexplained effect count is a failed demo. This script can be rehearsed from the source, but completed-demo claims require the actual saved replay.
