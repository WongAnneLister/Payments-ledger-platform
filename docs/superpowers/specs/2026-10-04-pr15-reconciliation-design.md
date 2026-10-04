# PR15 L3 recovery and independent reconciliation

Scope is the frozen PR15 prompt and AC-V1-15/16/27/28/29/30/33. Base: f0ce128. Deliver locally on codex/pr15-reconciliation; merging/pushing requires a later user instruction.

## Approach and boundaries

Reuse FinancialImportService/import_window/import_page for provider pagination and immutable BT acceptance. Reuse recovery_task and its lease fields for missing-payment discovery; reuse FinancialReplayService and BalanceRebuildService rather than adding another posting/projection engine. A centralized reconciliation coordinator persists per-run evidence and seven checks. A distributed collection of stateless queries would lose coherent watermarks; an independent importer would duplicate PR10 cursor and accounting ownership. The chosen coordinator wraps existing services.

No external monetary writes, fabricated orders, guessed FIFO associations, balancing adjustments or edits to old journals. Bank evidence remains SIMULATED_BANK_EVIDENCE. Fee models explain expectations only; authoritative fees are actual BT/bank facts.

## Run and evidence

Each run fixes ProviderScope, enabled currency/payments domain, business date/ZoneId, half-open UTC interval, rule version, import window, API/cursor chain and read high-water marks. DST uses local midnights, never subtracting 24 hours. Backfills accept explicit completed intervals. Reruns are new immutable result versions; late facts can be discovered by new overlapping windows.

ReconciliationService orchestrates createDay/createBackfill, bounded advance and finish/query. Durable reconciliation_run/cursor/exception/evidence/check rows use scoped foreign keys and immutable histories. COMPLETE denotes all applicable checks conclusively PASS/FAIL; unresolved pagination/source/independent evidence keeps INCOMPLETE even if some local checks FAIL. Every report preserves residual, reason, input IDs/version, snapshot, owner and completeness.

Independent balance evidence must be explicitly sourced (period report, controlled quiescent snapshot, or independent provider fixture), versioned/digested and match account/environment/livemode/currency/domain/opening and closing cutoff. A current snapshot cannot represent yesterday. Opening+complete BT net, independently scanned PSP entries and independent closing are separate inputs. Bound opening evidence and exclude historical BT already in opening; never double count opening Journal. Known mismatches FAIL; missing comparability is INCOMPLETE. ReportPort/BalancePort reads happen outside local transactions, and unavailable capabilities remain INCOMPLETE.

Transaction comparisons retain source-object reads, scope and status/amount mismatches. Unknown payments-domain types may be mapped to SUSPENSE but remain explicitly unclassified; other domains are quarantined. Availability changes never re-add net money. Payout checks inspect actual financial facts, bank allocations/completeness/date/fees and return overlap; missing bank remains INCOMPLETE and evidenced short receipts/conflicts are explicit exceptions.

## L3 trust and lease ownership

Enumerated capture BT -> provider Charge -> PaymentIntent reads outside transactions. Only full capture, exact matching amount/currency/BT/PI IDs, successful status, active test scope, and an existing uniquely bound server-authored order proof authorize a payment link. Metadata alone and same amounts are insufficient. Persist an immutable server-side order/provider identity binding; missing/ambiguous/conflicting/ineligible proof creates an exception and unallocated financial observation without order/fulfillment creation.

Extend existing recovery_task for RECONCILIATION_SOURCE scoped run/BT identity, unique execution qualification, SKIP LOCKED claims with expiry/token/version and CAS completion. Old operation/refund claims exclude this task kind. Query before claiming local final transaction; stale claims cannot link. Lock business_order rank10, payment20, financial source/BT ownership then ledger ranks; no backward parent locking. Source evidence + provenance(origin RECONCILIATION,runId,BT,digest) + payment/attempt/control link are atomic, retries return prior association. Existing payment recovery handles known dropped Webhook cases.

Reconciliation fulfillment requires a still-eligible order, trusted immutable recovered association and full known capture; explicit notification origin/provenance must survive the canonical writer and default denial. No order means no fulfillment. Native and refund behavior remain intact.

## Fee model and seven checks

Versioned scoped fee models specify currency, actual business type/domain, effective UTC interval, basis points, flat minor fee and rounding. Missing model is UNMODELED_FEE, never implicit zero. Match actual signed fees, preserve residual/version; bank fees remain distinct. No model posts anything.

Exactly seven checks: (1) I-01/02 sealed trial balance and bidirectional accepted event/Journal coverage; (2) I-03 control reconstruction, aged reservations and unresolved lease alerts; (3) I-04 policy caps/effective allocations/journal coverage; (4) I-05 BT page chain/event coverage/unclassified counts; (5) I-05 independent PSP three-way balance; (6) I-06 payout/cash/transit/returns/fee evidence; (7) I-07 independent projection rebuild/source isolation. REPEATABLE_READ read snapshots pin inputs. Nightly configured account business timezone triggers T+1 plus bounded overlap recovery; no new recovery or projection store.

## Verification

Real PostgreSQL migration V13 upgrade/reapply/privileges, multi-connection stale lease/concurrency, independent fixture omissions, absent/malformed pages, crash/resume/duplicate/late/conflicting BT, pending->available, opening cutoffs, wrong scope/currency, fee missing/model mismatch, DST23/25h, missing payment with valid proof/metadata-only/no order, known dropped Webhook, rollback and notification eligibility. Independent fixtures supply constants and expected math; production calculators are never oracles. Seven persisted outcomes include recurrence and tampered projection/bidirectional coverage detection. Final clean verify plus contracts/doc/secret/diff and source hash checks; real external paths only claim actually observed evidence.
