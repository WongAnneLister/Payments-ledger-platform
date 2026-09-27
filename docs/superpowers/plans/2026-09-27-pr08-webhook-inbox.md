# PR08 Webhook Inbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement the tasks with specification then independent quality review.

**Goal:** Durably receive verified Stripe webhooks and converge scoped payment facts without duplicate or out-of-order effects.

**Architecture:** Raw-byte signature verification and durable Inbox receipt are separate from leased asynchronous processing. V6 adds scoped transport/semantic deduplication and dependency waits; payment queries and webhook facts use one merger.

**Tech Stack:** Java 21, Spring Boot/JDBC, PostgreSQL/Flyway, Stripe SDK verifier, Testcontainers/JUnit.

## Task 1 — Durable Inbox and common observation core (implementation agent)

Files: create `src/main/resources/db/migration/V6__webhook_inbox.sql`; create `src/main/java/com/paymentsledger/webhook/{WebhookInboxRepository,WebhookProcessor,WebhookWorker}.java` and focused sanitizer/normalizer helpers; modify `src/main/java/com/paymentsledger/payment/PaymentRepository.java` and focused payment observation support. Tests: new `src/test/java/com/paymentsledger/payment/WebhookProcessingIT.java`, `src/test/java/com/paymentsledger/webhook/WebhookSchemaIT.java`, normalizer tests; adjust Flyway migration expectations only as needed.

- [ ] Write PG failing tests: receive twice => 1 Inbox; process verified capture twice => 1 attempt/capture amount and 0 journal; Charge-before-PI => dependency_wait then same item converges. Run targeted Maven and inspect expected assertion failures before implementation.
- [ ] Implement V6 scoped uniqueness/FKs, durable received evidence and sanitized payload, normalized identities, dependency retry/lease fields, runtime grants and immutable evidence guards. Upgrade cannot reset old payment or operation identities.
- [ ] Implement `public UUID receive(ProviderScope scope,String accountRef,byte[] raw,ProviderCallResult<VerifiedWebhook> verified)` with verified fact consistency, active exact STRIPE account binding and atomic commit. UUID replay returns existing row; content conflict cannot overwrite first receipt. Parse/store only allowlisted object fields; no raw body/secret/signature persistence.
- [ ] Implement injected-clock `public void processDue(int limit)` and a bounded Worker scheduled loop. Atomic claim in one short transaction; payment locks before Inbox completion locks; token fencing and retry after crash. No Provider calls in transactions.
- [ ] Integrate shared fact merger and normalized identity with query results. Test stable Charge capture identity, conflicting amount/currency, duplicate events and mixed query/event entry paths; repeated evidence must not create a second effect or regress success. Keep confirmation timestamps/evidence truthful.
- [ ] Add deterministic cases for three event orders, delayed local resources, unknown/wrong scoped relationships, old failure after success, expired Session not proving unpaid, lease expiry and two handlers, processing crash rollback, query conflict retention, deferred refund/dispute/payout, and payload sanitization. Keep tests meaningful and runtime role based.
- [ ] Run focused tests and self-review. Report exact Maven commands/results, sanitizer fields, config defaults, observed RED→GREEN and any boundaries. Do not commit or access live credentials; coordinate the Maven slot with parent.

## Task 2 — Raw HTTP endpoint, contract and runbook (parent, disjoint files)

Files: create `src/main/java/com/paymentsledger/webhook/StripeWebhookController.java` and `StripeWebhookEndpoint.java`; create `src/test/java/com/paymentsledger/webhook/StripeWebhookHttpIT.java`; modify `docs/contracts/openapi.yaml`, `README.md`, `.env.example` if appropriate; create `docs/runbook/webhook-inbox.md`, `docs/evidence/pr08/README.md`.

- [ ] Add real HTTP/PG tests asserting signed raw bytes yield 200 + committed Inbox before any processing, invalid/tampered/stale/mismatched-account bytes yield 400 with no effects, absent binding/DB failure yield 503, oversized input rejects, replay returns 200, and Worker has no public webhook route. Observe endpoint missing RED before implementing it.
- [ ] Implement endpoint enabled only on API with trusted deployment binding, one signature header, bounded raw input, verifier call then `receive`; sanitize errors and never log body/signature. Default off without blocking unrelated startup; explicit enable requires valid secret/scope/API version.
- [ ] Update contract to clarify bounded in-memory raw-byte verification, persisted digest/allowlist and asynchronous ack. Document exact config, received/processing/waiting/conflict meanings, semantic dedup, safe retries and PR09/10/11/13/14 boundaries.

## Task 3 — Review, full verification, evidence, commit (parent)

- [ ] Once implementation is ready request independent spec review, fix and re-review, then independent quality review. No concurrent Maven writers.
- [ ] Run `JAVA_HOME=<Corretto21> ./mvnw -B verify`; require zero failures/errors. Run `python3 scripts/ci/check_openapi.py`, `python3 scripts/ci/check_doc_links.py`, staged diff checks.
- [ ] Archive text reports/build output, scenario results and source SHA-256; distinguish LOCAL_SIGNED_FIXTURE/real PG from historical real Stripe evidence and opt-in NOT RUN. Check configured secrets never enter staged files.
- [ ] Commit PR08 on its feature branch, verify clean state and matching evidence; leave main unchanged.
