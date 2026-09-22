# PR05 evidence boundary

This directory records local adapter verification, not a real Stripe account experiment. Source labels are `LOCAL_SDK_HTTP_FIXTURE`, `LOCAL_SIGNATURE_FIXTURE`, and the existing PR04 `PROVIDER_SIMULATOR`.

SDK `33.3.0` and generated request API `2026-07-29.dahlia` are pinned locally. The real account ID and webhook endpoint version are **not yet verified**. R0 remains **BLOCKED** and AC-V1-05/06/07 are not claimed PASS.

| Contract | Local verification | Real-account status |
| --- | --- | --- |
| Checkout create/query/expire and hosted URL | SDK transport fixtures; expiry never cancels a PI | BLOCKED: no completed account run |
| Metadata at Session and payment_intent_data | Distinct request maps and parsed facts | BLOCKED: propagation requires real completed Checkout |
| Automatic capture | Explicit SDK request parameter | BLOCKED: real creation/payment evidence required |
| PI ↔ all Charge attempts | Multi-page attempts, declines, authentication, empty/inconsistent history | BLOCKED: no real payment fixtures |
| Idempotency / timeout | Stable key and absolute expiry across restart; no SDK retry; unknown preserved | BLOCKED: remote behavior/retention not measured |
| Refund create/query and late/failure BT | Typed requests, separate operation key, nullable facts | BLOCKED: no configured real refund fixture |
| BT amounts/fees/net/source/domain/status/times | Signed amount mapping, actual balance_type, strict pages | BLOCKED: no real financial observations |
| Dispute/Payout | Local facts and automatic-payout membership guard | BLOCKED: account capability unknown |
| Current Balance | PAYMENTS domain only, current capturedAt | BLOCKED: account capability unknown |
| Historical reports / other balance snapshots | Explicit CAPABILITY_UNAVAILABLE | BLOCKED: contract not implemented/verified; no historical balance claim |
| Raw-byte webhook | SDK signatures, exact bytes, symmetric tolerance, mode/account/version | BLOCKED: no real received event or configured endpoint |

`StripeRealContractIT` is disabled by default and is **SKIPPED / NOT RUN**, not passed. Its enabled missing-credential guard was checked locally with no network requests. Once explicitly configured, its per-case allowlisted artifact goes to ignored `target/stripe-contract/`; unexecuted manual cases remain BLOCKED even if smoke cases pass. See [runbook](../../runbook/stripe-contract-check.md).

`./mvnw -B clean verify` passed on 2026-09-23 (Asia/Shanghai): 127 unit/context tests + 45 integration tests passed, 1 opt-in real test skipped, 0 failures/errors. OpenAPI validation and relative documentation links passed.

`verification.json` contains the final command results, test counts and SHA-256 source snapshot; `build-output.txt` and `test-reports/` provide local execution evidence. The saved build log only normalizes trailing whitespace. No XML system-property dumps or credential files are committed. Existing PR04 evidence remains an immutable record of the PR04 revision; PR05 changes its Checkout command from relative Duration to persisted absolute Instant and reruns its regressions.

The [official idempotency contract](https://docs.stripe.com/api/idempotent_requests) permits pruning after keys are at least 24 hours old. This is not a measured account TTL or authority to blindly retry old unknown operations. Version source: [official stripe-java 33.3.0 release](https://github.com/stripe/stripe-java/releases/tag/v33.3.0) and its generated `ApiVersion.CURRENT`.
