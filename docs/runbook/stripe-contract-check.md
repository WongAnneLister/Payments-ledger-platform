# Stripe test-mode contract check

`StripeRealContractIT` is an opt-in network check of the real adapters. It does not use the fake provider or injected HTTP fixtures. It creates one USD 1.00 hosted Checkout Session, queries it, and attempts to expire it in `finally`. A separately gated refund mutation requires all three explicit refund opt-in variables below. It never enters payment details or creates/confirms a PaymentIntent directly. Session creation is not evidence of payment success.

The smoke sends distinct, non-secret metadata markers: Session metadata `pr05_session=<run-id>` and PaymentIntent metadata `pr05_payment=<run-id>`. `metadataSession` becomes VERIFIED only after both the created and queried Session contain the Session marker and exclude the PaymentIntent marker. `metadataPaymentIntent` remains BLOCKED: the smoke expires without completing payment, and an externally supplied completed Session cannot prove this run's creation parameters.

No real Stripe execution is claimed by this change. Deterministic adapter tests and a skipped integration test do not verify a Stripe account's capabilities. Reports remain **unverified / `CAPABILITY_UNAVAILABLE` by design**.

## Default and explicit execution

Use JDK 21. Normal `./mvnw verify` includes this integration test, but JUnit reports it **SKIPPED / NOT RUN** unless the environment variable `STRIPE_CONTRACT_TESTS` is exactly `true`. A skipped run makes no Stripe calls and produces no contract evidence file. Other integration tests can still require Docker.

Provide these variables through your secret manager or a private shell environment:

| Variable | Required value |
| --- | --- |
| `STRIPE_API_KEY` | Test secret/restricted credential (`sk_test_` or `rk_test_`); never a live credential |
| `STRIPE_ACCOUNT_ID` | The `acct_…` identity belonging to that credential |
| `STRIPE_ORGANIZATION_ID` | Trusted local organization UUID |
| `STRIPE_ENVIRONMENT_ID` | Trusted local environment UUID |
| `STRIPE_PROVIDER_ACCOUNT_ID` | Trusted local provider-account UUID |

Use the three UUIDs from the intended local account binding; do not derive scope from incoming webhook data. The gateway checks the Stripe account identity before provider work and rejects a live scope or credential. These are test-account calls, not live-money validation.

Once the variables are present, run only this integration test:

```sh
STRIPE_CONTRACT_TESTS=true ./mvnw -B -DskipTests=false -Dit.test=StripeRealContractIT verify
```

The command still runs ordinary unit tests, but selects only `StripeRealContractIT` for Failsafe. Do not put secret values in command-line literals, commit credentials, enable shell tracing, print the environment, or enable HTTP request/response logging. Maven does **not** automatically source `.env`; loading an approved private environment is a separate operator step. An enabled run with a missing or malformed required variable fails, with only the variable name/fixed diagnostic recorded. It must never silently count as a verified contract.

## Optional read fixtures

Supply existing test-mode objects from the same account when available:

| Variable | What the harness checks |
| --- | --- |
| `STRIPE_CONTRACT_SESSION_ID` | A completed, paid hosted Checkout Session → associated succeeded PaymentIntent → full Charge attempt list → every available Charge Balance Transaction |
| `STRIPE_CONTRACT_REFUND_ID` | Existing Refund facts; follows refund/failure Balance Transaction references when present |
| `STRIPE_CONTRACT_DISPUTE_ID` | Existing Dispute facts and its Balance Transactions |
| `STRIPE_CONTRACT_PAYOUT_ID` | Existing Payout facts; follows payout/failure Balance Transaction references when present |

Absent optional IDs produce **BLOCKED**, not verified. A supplied session must actually be completed and paid; an open session is not a substitute. If its Charge Balance Transaction is not yet available, the chain remains BLOCKED. Nullable financial references are not invented. A verified refund/dispute/payout read covers the returned facts and available links only; it does not prove every lifecycle or failure variant.

The completed-session chain requires at least one succeeded Charge and checks that every succeeded Charge has `captured=true` and `amountCapturedMinor=amountMinor`. This observes the capture result; it does **not** prove the original PaymentIntent `capture_method` was `automatic`. The separate `automaticCapture` case therefore remains BLOCKED pending verified creation-parameter evidence.

The run also requests the current USD `PAYMENTS` balance and one Balance Transaction page with a limit of five and a fixed one-day window ending just after the run begins. A currency/account capability unavailable to the credential is BLOCKED. An empty transaction window is BLOCKED for transaction-fact verification; a nonempty page only verifies that bounded page. `hasMore` is preserved, but `balanceTransactionContinuation` remains explicitly BLOCKED because one page cannot verify pagination. This is not a complete reconciliation or historical balance proof.

Prepare payment fixtures through a hosted Checkout test-mode integration in the same Stripe account. Complete the hosted form using Stripe's documented test payment methods, obtain the Checkout Session ID from the trusted integration or account request logs, and place only that ID in the environment. Keep card numbers, CVCs, client secrets, hosted URLs, and raw provider payloads out of this repository, shell history, evidence files, and tickets. The automated smoke session is immediately expired and is not the manual fixture. Use existing test-mode refunds, disputes, and payouts prepared through an approved test workflow; do not repurpose production objects.

Real decline, authentication-required, multiple-attempt, cancellation-race, and endpoint-signature cases are explicitly **BLOCKED** pending controlled manual fixtures and a dedicated verifier. A completed-session chain with multiple Charges does not automatically certify those scenarios; expiration of an open smoke Session does not verify a cancellation race. For those future checks, preserve a hosted Checkout attempt history and Stripe request IDs; for signature checks, deliver a real signed test event to the intended endpoint and retain only sanitized verification evidence. Synthetic transport tests alone cannot close these cases.

## Explicit refund mutation opt-in

`refundCreate` stays BLOCKED unless **all three** variables are present. Providing these variables authorizes one test-account refund create request followed by a query of that returned Refund:

| Variable | Required value |
| --- | --- |
| `STRIPE_CONTRACT_REFUND_CHARGE_ID` | The intended test Charge ID, matching `ch_` plus letters/digits |
| `STRIPE_CONTRACT_REFUND_AMOUNT_MINOR` | A positive integer within Java's signed 64-bit range, in the Charge currency's minor units |
| `STRIPE_CONTRACT_REFUND_OPERATION_KEY` | An operator-retained stable key, exactly `pr05-refund-` plus a lowercase canonical UUID |

Use a test Charge with sufficient refundable amount. The reason is fixed to `requested_by_customer`. Missing any variable blocks the case without creating a refund; malformed supplied values fail before the refund request. For read-only verification, supply only `STRIPE_CONTRACT_REFUND_ID` and leave the mutation opt-ins absent.

Before sending, the artifact records the exact amount, fixed reason, stable operation key, and SHA-256 hash of the Charge ID. Retain the **exact original Charge ID, amount, key, account/scope, and adapter revision externally**; the hash cannot reconstruct the Charge ID. The harness sends no automatic retry and never creates a replacement refund after an uncertain response. The subsequent query follows the returned Refund ID and preserves absent/late Balance Transaction references; a successful API call is not proof that a pending refund has settled.

For an exact refund replay, reuse the original target, amount, reason, account/scope, and key within Stripe's supported idempotency retention window. Repeated harness invocations with those unchanged parameters and key replay that refund operation within the window, although each invocation creates its own separate Checkout smoke operation. **Never generate a fresh refund key to recover a timeout.** Inspect the original request and Refund first. After the retention window, the same key cannot be assumed to prevent another refund. A different amount or target with the old key is not a valid replay.

## Evidence and uncertain outcomes

Each enabled invocation writes `target/stripe-contract/<run-id>.json`, including partial failures. It contains an explicit allowlist: SDK/API versions, UTC run/request/response times, scope UUIDs, hashed Stripe account/object references, sanitized request IDs, HTTP status, normalized classifications, counts, case statuses, fixed diagnostics, and allowlisted refund status/reference-presence facts. Each available adapter redacted-evidence digest is included as `redactedEvidenceSha256` only when it matches 64 lowercase hexadecimal characters. It never serializes provider result DTOs or `ProviderEvidence` wholesale. Raw payloads, exception messages, arbitrary metadata, arbitrary provider idempotency keys, API secrets, Checkout URLs, and card data are excluded. The generated smoke operation key and strictly formatted refund operation key are intentional non-secret keys stored for operation recovery.

Before creating Checkout, the file records on disk the generated run operation key, amount/currency, command-template version, and the **fixed absolute expiry** of run start plus one hour. The `checkout-smoke-v2` command uses amount `100`, currency `usd`, Session metadata exactly `{"pr05_session":"<original-run-id>"}`, PaymentIntent metadata exactly `{"pr05_payment":"<original-run-id>"}`, `https://example.com/success`, and `https://example.com/cancel`. Reconstruct both markers from the persisted original `runId`; never generate a replacement marker on replay. All other request construction is pinned by the adapter revision. Preserve the artifact together with that revision when diagnosing an uncertain result. The evidence does not serialize arbitrary returned metadata.

Every invocation creates a new run ID and therefore a **new Checkout operation**. The harness has no automatic Checkout resume mode. Do not rerun it expecting to resolve an earlier Checkout timeout. An exact Checkout replay must reuse the original account/scope, operation key, absolute expiry, and all original request parameters within Stripe's supported idempotency retention rules. Do not recompute expiry or change any field. Outside that retention window, the same key cannot be assumed to prevent a new operation. Review the original account request logs and existing session before deciding whether a replay is appropriate. Refund keys are operator-supplied and have the separate replay rules above.

Cleanup always attempts expiration when a created session ID is known. A timeout, process termination, or mapping failure can leave an unknown remote session: inspect the account's request logs using the persisted operation key/request IDs, locate any session, and expire it through the approved test workflow if still open. Evidence hashes cannot recover object IDs. Cleanup failure is a failed case; elapsed expiry or an uncertain response must not be reported as successful cleanup. The harness never cancels a PaymentIntent as a substitute for expiring Checkout.

`VERIFIED` applies only to the named assertions that actually ran. `BLOCKED` and `NOT_RUN` remain outstanding; even a passing Maven invocation reports `PARTIAL_VERIFICATION` because manual cases and reports are unresolved. Preserve the JSON alongside the Failsafe result when recording verification. A skipped run or missing artifact is not a successful real contract check.
