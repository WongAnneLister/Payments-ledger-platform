# PR05 evidence boundary

The top-level build artifacts record the offline baseline at commit `4478ee6`. The `real/` directory now contains two real test-account smoke runs (2026-09-23 Asia/Shanghai). Local source labels are `LOCAL_SDK_HTTP_FIXTURE`, `LOCAL_SIGNATURE_FIXTURE`, and the existing PR04 `PROVIDER_SIMULATOR`.

SDK `33.3.0` / request API `2026-07-29.dahlia` have now passed real Checkout smoke validation. Account identity is bound and retained as a SHA-256 reference. An existing test webhook endpoint uses `2026-06-24.dahlia`; it has not been established as this project receiver, and was not modified. R0 remains **BLOCKED** and complete AC-V1-05/06/07 are not claimed PASS.

| Contract | Local verification | Real-account status |
| --- | --- | --- |
| Checkout create/query/expire and hosted URL | SDK transport fixtures; expiry never cancels a PI | VERIFIED: real open session create/query/expire; no payment or cancellation-race claim |
| Metadata at Session and payment_intent_data | Distinct request maps and parsed facts | Session roundtrip VERIFIED; PaymentIntent metadata BLOCKED without completed Checkout |
| Automatic capture | Explicit SDK request parameter | BLOCKED: real creation/payment evidence required |
| PI ↔ all Charge attempts | Multi-page attempts, declines, authentication, empty/inconsistent history | BLOCKED: no real payment fixtures |
| Idempotency / timeout | Stable key and absolute expiry across restart; no SDK retry; unknown preserved | BLOCKED: remote behavior/retention not measured |
| Refund create/query and late/failure BT | Typed requests, separate operation key, nullable facts | BLOCKED: no configured real refund fixture |
| BT amounts/fees/net/source/domain/status/times | Signed amount mapping, actual balance_type, strict pages | BLOCKED: no real financial observations |
| Dispute/Payout | Local facts and automatic-payout membership guard | BLOCKED: account capability unknown |
| Current Balance | PAYMENTS domain only, current capturedAt | VERIFIED: JPY current snapshot; initial USD query was unavailable |
| Historical reports / other balance snapshots | Explicit CAPABILITY_UNAVAILABLE | BLOCKED: contract not implemented/verified; no historical balance claim |
| Raw-byte webhook | SDK signatures, exact bytes, symmetric tolerance, mode/account/version | BLOCKED: no real received event or configured endpoint |

`StripeRealContractIT` remains disabled by default. The offline baseline skipped it; the explicitly enabled real run now passed with 6 VERIFIED cases and the remaining cases explicitly BLOCKED. Its enabled missing-credential guard was checked locally with no network requests. Its per-case allowlisted artifact goes to ignored `target/stripe-contract/`; selected sanitized runs are copied to `real/` for audit. Unexecuted manual cases remain BLOCKED even if smoke cases pass. See [runbook](../../runbook/stripe-contract-check.md).

`./mvnw -B clean verify` passed on 2026-09-23 (Asia/Shanghai): 127 unit/context tests + 45 integration tests passed, 1 opt-in real test skipped, 0 failures/errors. OpenAPI validation and relative documentation links passed.

`verification.json` contains the final command results, test counts and SHA-256 source snapshot; `build-output.txt` and `test-reports/` provide local execution evidence. The saved build log only normalizes trailing whitespace. No XML system-property dumps or credential files are committed. Existing PR04 evidence remains an immutable record of the PR04 revision; PR05 changes its Checkout command from relative Duration to persisted absolute Instant and reruns its regressions.

The [official idempotency contract](https://docs.stripe.com/api/idempotent_requests) permits pruning after keys are at least 24 hours old. This is not a measured account TTL or authority to blindly retry old unknown operations. Version source: [official stripe-java 33.3.0 release](https://github.com/stripe/stripe-java/releases/tag/v33.3.0) and its generated `ApiVersion.CURRENT`.

## Real test-account execution, 2026-09-23

[Latest per-case artifact](real/1d607fcf-e1fc-4c5a-80b6-107de9818799.json) · [execution and source snapshot](real/verification.json). Targeted Maven verification passed 127 unit/context tests and one enabled real contract test. Each smoke created one USD 1.00 **unpaid** Checkout Session and successfully expired it. No test card was submitted and no refund mutation opt-ins were supplied. The account's actual balance currency is JPY, independent of the USD Checkout amount. The 24-hour BT window was empty, so BT fields/pagination and payment/refund/dispute/payout chains are not proven.

The first run establishes USD balance unavailability for this account; the second uses explicitly configured `STRIPE_CONTRACT_BALANCE_CURRENCY=jpy` and verifies the adapter's current balance read. Missing card-payment samples and the endpoint version mismatch still block the remaining real contracts. Local secrets, full account IDs, endpoint URLs and raw responses are excluded from these records.
