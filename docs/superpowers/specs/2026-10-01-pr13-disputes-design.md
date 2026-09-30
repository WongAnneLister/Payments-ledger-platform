# PR13 dispute lifecycle design

The user requested the frozen PR13 milestone. This spec applies its approved product, accounting, acceptance and lock-order rules. Implementation stays on codex/pr13-disputes; main merge/push is a later action.

## Chosen approach

Use one canonical DisputeService over PostgreSQL, called from verified Webhook snapshots and read-only DisputePort query recovery. Preserve all scoped source observations, raw provider states, evidence deadline and BT links. Missing charge associations remain durable dependencies. Multiple disputes per charge are supported; never infer a local payment from equal amounts. Provider effects remain outside transactions. A separate asynchronous accounting service would violate atomic share withdrawal; embedding dispute rules in each ingress would duplicate state and accounting logic.

V11 adds dispute, dispute_case, dispute_evidence, dispute BT links and immutable dispute_financial_fact. A dispute stores scoped payment association, original charge/amount/currency, canonical status timestamp, conservative blocking/conflict state and auditable outstanding receivable/loss/debit/return totals. Case rows retain investigation reason; evidence retains sanitized snapshots and digest. Existing provider ports already expose dispute query/list; no evidence submission or funds-write API is added.

## Business and accounting

Any unresolved inquiry, formal dispute, loss or unexplained contradiction blocks new ordinary refunds and sets share eligible base to zero. A closed warning with no money evidence may resolve. A formal won dispute needs all referenced BTs posted, actual debit/return coverage and zero outstanding receivable/loss, with no conflicting evidence or overlap; status alone cannot restore sharing. All disputes on the payment must clear before sharing restores. Keep dispute values separate from R/H. Existing or externally observed refund obligations overlapping a blocking dispute freeze refund control and preserve both facts for investigation.

Only trusted linked BTs move PSP. Negative dispute principal debits DISPUTE_RECEIVABLE; actual signed fee affects DISPUTE_FEE_EXPENSE; PSP equals net movement. Positive principal returns credit outstanding receivable first, then previously recognized loss, then SUSPENSE for excess and an investigation case. A return received before its debit stays PENDING_EVIDENCE. Lost status reclassifies only outstanding receivable into DISPUTE_LOSS using an immutable generation key and never touches PSP. Late win appends new returns, never changes old journals. A zero principal BT can carry actual fee charge/refund. Do not manufacture fee refunds from status. Each financial fact links its sealed Journal; projected counters must equal immutable financial history.

## Lock and ingress integration

Use payment(20), refund_control(30), all relevant dispute(40), allocation(50), BT, ledger then deferred Outbox. RevenueShareService.lockAll adds a rank-wide dispute phase before allocations. Dispute accept binds/locks its row before calling share lock; postLocked never acquires new business locks. Business association writers lock existing BTs by provider BT ID with C collation. Import/replay discover dispute source ownership and recheck under BT locks, using the existing bounded whole-local-transaction retry if association raced. Late BT conflict is included in share eligibility and persistent blocking checks.

FinancialReplayService receives an optional DisputeService; after common validation it delegates dispute BT posting to postLocked and reconciles share with its pre-acquired handle. The service updates dispute money counters and BT linkage, optionally loss-reclassifies and reevaluates blocking inside that transaction. Existing financial worker resolves pending dispute sources through read-only query outside replay transactions; a bounded worker also refreshes known unresolved disputes. Webhook receipt ownership and acknowledgment remain atomic with canonical business changes. Internal scoped read services expose deadline, cases and financial history for PR16.

## Verification and delivery

Test manual PSP 9700 -> 5200 -> 8200, share 2000 -> 0 -> 2000; lost PSP stays5200; late win loss clears without duplicate PSP; fees remain unless a BT returns them. Include 1000 receivable + 2000 loss + 3500 return => 500 suspense, unknown/out-of-order/duplicate snapshots, BT-before-status, multiple disputes, refund/dispute PostgreSQL concurrency, external overlap, rollback injection, scope, deadline, V10 upgrade and immutable history. Verify actual Spring/worker/Webhook integration and adapter contract. Real-account dispute experimentation is NOT_RUN unless independently available; fixtures are labeled fixture-only.

Primary references: https://docs.stripe.com/api/disputes/object and https://docs.stripe.com/api/balance_transactions/object . Dispute source identity may be an expandable object or a Stripe dispute ID; the provider adapter must preserve its normalized kind instead of guessing by amount.
