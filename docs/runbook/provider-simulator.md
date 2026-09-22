# PR04 Provider simulator

Source mode: **PROVIDER_SIMULATOR**. This is a deterministic local remote-provider model; it does not contact Stripe, move real money, or establish any real Stripe API/SDK/retention guarantee. The real adapter belongs to PR05. No public business or simulator-management HTTP route is added.

## Ports and wiring

`com.paymentsledger.provider` contains ten SDK-free ports and owned immutable records in `ProviderModels`. Results contain a classification and evidence even on errors; `SUCCESS` and `AUTH_REQUIRED` may carry facts. Callers use the classification, not provider error text. All calls must happen outside active local Spring transactions. A call made inside one fails before the simulator records a request.

| Port | Operations |
|---|---|
| CheckoutPort | createSession, querySession, expireSession |
| PaymentIntentPort | queryPaymentIntent |
| ChargePort | queryCharge, listChargeAttempts |
| RefundPort | createRefund, queryRefund |
| BalanceTransactionPort | listBalanceTransactions, getBalanceTransaction |
| DisputePort / PayoutPort | list/getDisputes, list/getPayouts |
| BalancePort | getBalance(scope, currency, balanceDomain) |
| ReportPort | requestReport, fetchReport |
| WebhookVerifierPort | verify original bytes with the explicitly fake signature scheme |

A Java harness can construct `FakeStripeProvider` directly. Runtime wiring requires **both** the `test` profile and `platform.provider.fake.enabled=true`. API/Worker alone never registers Fake. All fixture parameters are explicit:

```properties
spring.profiles.active=api,test
platform.provider.fake.enabled=true
platform.provider.fake.directory=/tmp/payments-ledger-demo/provider
platform.provider.fake.seed=42
platform.provider.fake.initial-time=2026-01-01T00:00:00Z
platform.provider.fake.retention=PT10M
```

`PT10M` is an arbitrary fixture setting, not Stripe's retention. Do not put simulator state under a source-controlled directory. The Fake has no credentials and rejects livemode. Its hosted URL uses `checkout.fake.invalid` and is a fact for tests, not a working checkout webpage.

## Repeatable unknown-result scenario

Use a fresh directory for a fresh run; reuse the directory to resume. The same seed, initial time, retention and sequence of inputs produce the same identifiers and response evidence.

```java
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

var scope = new ProviderScope(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), false);
var fake = new FakeStripeProvider(Path.of("/tmp/pr04-example"), 42,
    Instant.parse("2026-01-01T00:00:00Z"), Duration.ofMinutes(10));
var command = new CreateCheckoutSessionCommand(scope, "checkout:order:1", 10000, "USD",
    "https://example.test/success", "https://example.test/cancel",
    Map.of("sessionOnly", "s"), Map.of("order", "1"), fake.clock().instant().plusSeconds(3600));
fake.controls().enqueue(scope, FailureScript.Operation.CREATE_SESSION,
    command.operationIdempotencyKey(), FailureScript.Outcome.RESPONSE_LOST);
var unknown = fake.createSession(command); // TIMEOUT_UNKNOWN; payload is null
var recovered = fake.createSession(command); // same remote session, only one creation
var pi = fake.controls().attemptPayment(scope, recovered.payload().sessionId(),
    FakeControls.AttemptOutcome.SUCCEED);
var counters = fake.counters(scope); // sessionCreatedCount=1, paymentEffectCount=1
```

Session creation immediately supplies a PI in this model; the contract permits creation-time null IDs and later supplementation by the real adapter. Session and PI metadata are separately supplied and never implicitly inherited. Customer actions are simulator controls, not a made-up `confirmPayment` business port. REQUIRE_AUTH preserves an actionable PI, DECLINE adds a failed Charge, SUCCEED adds a captured Charge. All attempts remain queryable. Once captured, later actions cannot undo success. Explicit or clock-driven session expiry prevents later capture; the file lock serializes expiry/capture races.

## Scripts, counters and evidence

Scripts are persisted queues matched by full ProviderScope, operation, and exact target. A create target is its operation idempotency key; object reads use the object ID; list operations use `list`; balance uses `currency:balanceDomain`; report requests use reportType. Cached replay does not consume a new script.

| Outcome | Remote effect / result |
|---|---|
| BEFORE_SEND_FAILURE | no acceptance or object; RETRYABLE_TRANSIENT, no response/request ID |
| RESPONSE_LOST | commit operation and cached result, return TIMEOUT_UNKNOWN with no payload/response timestamp |
| CRASH_AFTER_COMMIT | same durable commit and timeout evidence, then throw SimulatedCrash before returning to caller |
| TRANSIENT_EMPTY | one empty observation, leaving stored facts unchanged |
| TRANSIENT_ERROR / RATE_LIMIT / REJECT / CONTRACT_MISMATCH | classified one-shot failure, no new operation |
| CACHED_TRANSIENT_ERROR | cache a transient error for keyed create operations until retention expires |
| AUTH_REQUIRED | accepted Checkout session and PI requiring action; explicit customer control later resolves it |

Same key + same canonical parameters inside the window returns the cached fact. Parameter changes cause IDEMPOTENCY_CONFLICT, including metadata changes. At the exact expiry instant the old entry no longer protects the operation; the same key may create a new session/refund. Queries read current facts; cached create responses may describe earlier states. The Fake deliberately does not implement local UNKNOWN recovery, which belongs to PR07/PR11.

Counters are scoped and stored with remote state:

- requestCount: all port invocations, including cache replay, modeled pre-send failure, reads and rejection; a local transaction guard failure never sends a call.
- acceptedCount: successful uncached create/expire operations (including accepted AUTH_REQUIRED and lost responses), excluding reads/replays and customer fixture controls.
- sessionCreatedCount: newly created Checkout sessions only.
- paymentEffectCount: successful captures only, never session creation or failed attempts.
- refundEffectCount: newly accepted refund debit effects only, never replay or status/BT updates. In this fixture, a newly accepted refund debits remotely while its status may still be pending. This is a scenario choice, not a universal Stripe timing claim.

`controls().calls(scope)` preserves every provider-call classification/evidence. `controls().effects(scope)` is an independent remote effect history for PAYMENT, REFUND and explicit REFUND_RETURN. Neither reads local payment nor Journal counts. Refund status, debit BT, and failure-return BT can be supplied at different times. A second link to the same return is idempotent; conflicting references fail. Refund creation rejects uncaptured or over-refunded charges; controls preserve terminal refund status. Evidence uses `apiVersion=FAKE-PR04-v1`, never a fabricated Stripe version.

## Financial and delivery fixtures

BT fixtures preserve signed amount/fee/net (`net=amount-fee`), domain, source, fee details, raw digest and availability. `putBalanceTransaction` allows a later pending→available observation but rejects changed immutable economics. `visibleAt` controls late discovery independently of provider `createdAt`.

Pages use explicit UTC `[createdGte, createdLt)` bounds, ascending `(createdAt, objectId)` order and an opaque continuation scoped to account and query. Dispute/payout fixture installation takes a separate createdAt because those owned facts do not contain it. This ascending order is a Fake contract, not a promise about Stripe order. A scan is not a snapshot across pages: data that becomes visible behind a cursor needs a later overlap/backfill scan. This intentionally preserves PR10's completeness problem. Automatic payout membership is supplied explicitly; manual payout membership queries are rejected, never guessed with FIFO.

`putBalance` supplies an independent provider fixture balance. It is not calculated from the local ledger or automatically from incomplete BT pages. `getBalance` stamps the current simulated observation time. `ReportPort` is unavailable by default; `putReport` explicitly supplies fixture evidence for a fixed window. Current balance and historical report are never silently substituted.

`emitWebhook` creates an event but sends nothing. Omit scheduleDelivery to model loss, schedule twice for duplication, and vary deliveryAt for reordering. `takeDeliveries` drains only due deliveries, preserving the same event ID/raw body across duplicates. There is no outgoing HTTP call, automatic webhook dispatch or Inbox implementation in PR04.

`FakeWebhookVerifier` uses `fake_t=...,fake_v1=...` HMAC over timestamp plus original bytes. It tests raw-byte integrity, secret mismatch and timestamp tolerance. It is **not Stripe-Signature compatible**. Real Stripe signing fixtures are PR05/PR08 work. Never use fixture signatures as real-contract evidence.

## Persistence and boundaries

Each operation reloads one JSON state file under a JVM lock and a separate OS file lock, then forces a temporary file and atomically replaces state.json. Scripts, simulated clock, retained keys, identifiers, facts, counters, calls, effects and pending deliveries survive reinstantiation/process restart. Interrupted operations publish either the old file or the complete new file on filesystems supporting atomic rename. Failure after publication is injected outside the write operation. Reopening with a different seed/origin/retention fails. A SHA-256 checksum over canonical state is stored in the same atomic file and checked before any mutation, so malformed or accidentally changed facts/cache fail closed. This is corruption detection, not protection from an administrator who intentionally edits state and recomputes its checksum. Response digests also use canonical field order and integer representation, independent of JVM map iteration order.

This is a bounded local fixture store. It rewrites the state per operation and retains history; there is no compaction or production performance promise. Multi-host/network-filesystem behavior, abrupt power loss and directory-entry fsync guarantees are NOT RUN. Do not reuse the store as an application database. No application schema changes or migration are needed: migration addition checks are N/A; V1–V3 regression still runs.

Tests use temporary fixture directories and disposable PostgreSQL. Run:

```bash
JAVA_HOME="$HOME/.jdks/amazon-corretto-21.jdk/Contents/Home" ./mvnw -B clean verify
python3 scripts/ci/check_openapi.py
python3 scripts/ci/check_doc_links.py
git diff --check
```

Full recovery, real Stripe contracts, end-to-end payment/refund APIs and real financial reconciliation remain NOT RUN. Passing Fake tests provides controllable evidence for later acceptance tests; it does not complete their cross-module acceptance criteria.
