# PR16 scoped operations API, approval and audit

Approved scope: the user requested PR16 after the explanation of PR16–20. The frozen PR16 prompt, OpenAPI and ADR-0006 define requirements; no new product choice is needed.

## Architecture

Expose the existing payment/refund/dispute/payout/share, sealed ledger, reconciliation and single recovery-task services through API-profile operations controllers. Trusted OpsContext determines organization/environment; selectors must resolve active test provider accounts within that identity. Reads across scope are 404; roles default deny. PARTNER_VIEWER can only read its own share lines and must not receive full policy, other partners, journals or generic finance details. OPS_REVIEWER alone approves; operators/admins initiate writes but cannot approve. All six write commands require an Idempotency-Key and fingerprint; replay cannot change payload or cross identity authorization.

Add V16 migration only, with immutable command/audit/action evidence and scoped adjustment requests. DB enforces independent author/reviewer and freezes final approval. Approved entries are validated against active scoped accounts/currency and balanced integer totals, then appended via existing LedgerPostingService in the same transaction as approval. Rejected requests post nothing. There are no APIs to change old journals or balances.

## Writes and evidence

Reconciliation commands persist run association and asynchronously wake existing worker behavior; no provider network inside DB transactions. Expose the frozen runType/businessDate/lookback request and preserve seven-check evidence semantics. Bank import wraps existing fixed simulation importer/matcher; the external frozen legacy line shape requires an explicit translation. Optional coverage/account fields may be added to the contract, but inferred date coverage must remain incomplete. Validate canonical digest and immutable fixture identity; repeated digest returns the original batch without rematching. Exception actions refer to scoped immutable evidence or a scoped posted adjustment Journal; cannot resolve merely by arbitrary nonempty strings. Operational disposition is separate from immutable historical reconciliation findings. Retry only wakes the existing obligation after eligibility checks; active leases/final tasks and expired unknown create operations are not blindly reset.

Authenticated writes, replay and denial are audited. Audit uses requestId, stable actor/role/target/scope/action, reason/evidence summaries and result; raw requests, tokens, secrets and sensitive bank descriptions are never persisted. Failures are written in independent transactions after rolling back command effects. Authentication/scope rejections in the filter must be included without logging credentials.

## Queries and API contract

Deliver all 11 GET and six POST paths frozen in docs/contracts/openapi.yaml, plus scoped GET /ops/adjustments/{id} so the reviewer can inspect entries/evidence before approving without database access. Return specified camelCase resource fields and controlled status translations, including UNKNOWN/PENDING_EVIDENCE/INCOMPLETE. Trace provider objects, BTs, journal/entries and bank evidence; preserve source versions and times. Limit/cursor queries are bounded and scoped. asOf balances use ledger reconstruction at the requested cutoff and do not relabel a current projection as historical. Details include approved target-specific manual adjustment Journal links. Bounded trace collections retain the newest 100 summaries and explicitly declare traceCompleteness/truncatedSections; financial truth remains independently derived. Latest exception disposition follows a monotonic action order assigned after serialization with insertion-time timestamps. Source recovery retries acquire task before BT in the same order as the Worker. New additions remain explicit in OpenAPI.

## Verification and limits

TDD PostgreSQL/HTTP tests exercise all roles, dual org/env/currency, invalid selectors, same-key changed intent, duplicate imports/retries, independent/self/concurrent approval, rollback, evidence ownership, immutable DB privileges and audit redaction. Capture real RED and GREEN results, focused runs, full Java21 clean verify, doc/OpenAPI/diff and configured-secret checks. Independent spec then quality reviews precede local commit on codex/pr16-ops. No merge/push is authorized for this phase. Real historical Stripe reports and real bank integration remain unverified/out of scope.
