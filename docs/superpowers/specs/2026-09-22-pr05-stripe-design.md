# PR05 Stripe adapter design

Scope: implement the frozen PR05 prompt behind PR04's project-owned ports. No business API, table, payment state mutation or ledger posting. Single explicitly bound test account per adapter set; fail closed on live keys, scope mismatch, account mismatch, active database transaction and incompatible responses.

Use stripe-java 33.3.0 / its generated API version 2026-07-29.dahlia. These are local dependency/fixture versions until an actual account run proves remote behavior. Disable SDK network retries; supply operation keys for Checkout/refund creation. Return normalized classification and redacted evidence, never SDK exceptions or raw responses to application callers. Read all Charge attempt pages. Explicitly send both metadata locations and automatic capture. Expire only invokes the Session expiry endpoint.

Shared gateway owns request options, transaction/scope guards, account credential binding, response evidence and error handling. Checkout, refund and financial adapters map SDK objects to owned facts. The webhook verifier checks the original UTF-8 bytes, timestamp, signature, mode, API version and endpoint account binding. An opt-in Spring configuration keeps normal startup and PR04 Fake unchanged.

Financial evidence preserves signed amounts, fee/net, balance domain, source and explicit pagination. Unknown/missing domain is not guessed as PAYMENTS. Payout membership is restricted to verified automatic payouts. Current balance is never labeled historical. Historical report parsing cannot be claimed without a verified report contract; report capability remains explicitly unavailable until one is established.

Tests use the real SDK with a deterministic in-memory HTTP transport and source-labeled fixtures. This is not a Stripe account experiment. An opt-in real contract harness performs only small test-account operations and emits allowlisted evidence. Credentials are read from local environment only, never committed or printed.

Current external dependency: no STRIPE_* configuration found on 2026-09-22. Real Checkout/PI/Charge/BT/refund/webhook/Dispute/Payout/report claims remain BLOCKED. The official idempotency contract permits pruning after at least 24 hours; no measured retention window or automatic recovery policy is inferred.

Implementation refinement: replace relative Checkout expiresAfter with immutable absolute expiresAt; persisted operation retries must retain identical request parameters. Fake cache lookup remains before new-session expiry-window validation.
