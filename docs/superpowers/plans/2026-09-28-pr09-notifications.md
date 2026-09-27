# PR09 Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development for implementation, then specification and independent quality review. One implementation agent owns the durable core; parent owns HTTP security, the independent merchant fixture, and documentation. Do not run concurrent Maven commands in the shared worktree.

**Goal:** Payment changes durably reach a cooperating merchant without losing obligations or repeating fulfillment.

**Architecture:** A payment transaction trigger invokes the shared Outbox writer; leased publication sends to Kafka outside transactions. Consumer Inbox and delivery creation commit before Kafka ack. Leased HTTP delivery uses parent-provided MerchantTransport; an independent merchant database enforces business-key idempotency.

**Tech Stack:** Java21, Spring Boot3.5.16, PostgreSQL16/Flyway V7, Spring Kafka managed by Boot BOM, Kafka3.9.0, Apache HttpClient5, Testcontainers1.21.4.

## Task 1 — Schema, Outbox, Kafka, consumer, delivery orchestration (core agent)

Create `src/main/resources/db/migration/V7__payment_notifications.sql`; `src/main/java/com/paymentsledger/notification/{NotificationEvent,OutboxWriter,OutboxRepository,OutboxPublisher,ConsumerInboxService,DeliveryRepository,MerchantNotifier,FulfillmentEligibility,NotificationWorker,NotificationKafkaConfiguration,NotificationMetrics}.java`; targeted support types may be separate. Own new `src/test/java/com/paymentsledger/payment/PaymentOutboxIT.java`, `src/test/java/com/paymentsledger/notification/{NotificationPipelineIT,NotificationKafkaIT}.java`, and only legacy migration-count assertions. Parent owns pom, application configuration, HTTP transport/fixture and docs.

- [x] Write failing PG transaction test around existing PaymentRepository merge:
```java
assertThat(count("outbox_event")).isZero();
repository.mergeQuery(payment, operation, List.of(), capturedFacts);
assertThat(countSuccessEvents(payment)).isEqualTo(1);
repository.mergeQuery(payment, operation, List.of(), capturedFacts);
assertThat(countSuccessEvents(payment)).isEqualTo(1);
```
Also inject outbox insert failure and require payment transition rollback. Run `./mvnw -B -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=PaymentOutboxIT verify`, record expected RED before production implementation.
- [x] Implement V7. Database event writer is idempotent and invoked by AFTER payment INSERT/UPDATE trigger. Confirmed-state predicate and terminal uniqueness are specified in design. Eligibility auto-row only for newly created business orders; no historical auto-grant/backfill events. Scope FKs and immutable guards cover all message identities. Runtime role can only mutate delivery control fields, never event identity/body or delete evidence.
- [x] Implement OutboxWriter requiring an existing transaction; same SQL function as trigger, no independent commit. Define the flat NotificationEvent fields exactly as design. Persist exact body and digest; publisher never regenerates event IDs.
- [x] Implement atomic leased claim, expiry/fencing, retry and visible terminal states in repositories. All network calls follow committed claims; no database transaction spans Kafka/HTTP. Use injected Clock and capped exponential delay, with an explicit HTTP retry maximum; publisher obligations persist with capped exponential backoff until Kafka recovers.
- [x] Wire Kafka producer/listener only in worker profile and `platform.messaging.enabled=true`; auto-commit false, commit offset only after ConsumerInboxService returns from its local transaction. Trusted Outbox comparison prevents forged Kafka payloads from choosing target/scope. Persist poison-message rejection before advancing offset; DB failure must not ack. Use stable consumer name and consumer+event uniqueness.
- [x] Consumer transaction creates delivery_task from server-owned scoped target. Missing target/eligibility is durable HELD, never dropped. Freeze target identity/version; disabled or changed target cannot silently redirect an existing task. FulfillmentEligibility default denies RECONCILIATION or missing/invalid eligibility, validates original order scope/currency/full captured amount, and exposes a future PR15 extension.
- [x] MerchantNotifier consumes parent MerchantTransport below; transport Result drives delivered/retry/dead control writes. Re-check eligibility before attempt, preserve task under failed/expired workers, never mark delivered after a lost lease. Include delivery/retry/dead/held counts and oldest lag gauges; log only event/task IDs and controlled reason codes.
- [x] Add real PG cases for both send/mark crash windows, consumer rollback/commit-before-ack, two consumers, old lease ownership, permanent failure and retry cutoff, all existing state-confirmation paths and no-effect repeat. Include upgrade expectation changes.
- [x] Add actual Kafka3.9.0 testcontainer: publish persistent event, consume into durable Inbox/delivery, duplicate send before mark, restart/pause broker for interruption and recovery; no mocked broker success. Verify consumer offsets align with committed effects. Record commands/counts; release Maven slot before parent tests.

## Task 2 — Transport security and independent merchant fixture (parent)

Create `src/main/java/com/paymentsledger/notification/{MerchantTransport,NotificationTargetPolicy,HttpMerchantTransport,NotificationSignature}.java`; `src/main/java/com/paymentsledger/notification/fixture/SimulatedMerchantServer.java`; `src/test/java/com/paymentsledger/notification/{NotificationTargetPolicyTest,NotificationSignatureTest,MerchantDeliveryIT}.java`. Parent owns dependency additions in `pom.xml` and config/docs.

- [x] Freeze the inter-task interface before delegation:
```java
public interface MerchantTransport {
  record Target(java.net.URI url, String keyRef) {}
  enum Outcome { DELIVERED, RETRYABLE, PERMANENT_REJECT }
  record Result(Outcome outcome, int status, String code) {}
  Result send(Target target, java.util.UUID eventId, String businessKey, byte[] payload);
}
```
- [x] Add BOM-managed spring-kafka/httpclient5 and versioned Testcontainers kafka dependency. Resolve exact versions, retain existing Kafka3.9.0 image, record actual versions in evidence.
- [x] Write RED tests rejecting local/private/link-local/multicast/unspecified/mappedIPv4/DNS-mixture, userinfo/query/fragment/non443/nonHTTPS/nonallowlisted hosts; DNS addresses must be pinned for actual connection. Add signature byte-tamper/time-window tests. Run targeted unit tests before implementation.
- [x] Implement strict production policy and separate exact-loopback fixture factory; Apache client uses custom DnsResolver for pinned validated addresses and default TLS hostname verification, disable redirects/automatic retry/system proxies. Bound request16KiB and timeouts; status only, never read unbounded response body. HMAC includes all stable identities + raw bytes + current timestamp. No active local transaction allowed.
- [x] Implement standalone loopback fixture server with separate database and signature verification. Merchant inbox and fulfillment effect use one local transaction. Stable business key unique; repeated event/payload cannot add effect; changed content rejected. Add explicit one-shot response-drop after commit.
- [x] Test actual notifier→HTTP→independent merchant DB with response dropped after fulfillment: first result retryable, second delivered, merchant effects=1 and receipts stable. Add redirect, permanent4xx, transient5xx, signature tampering and no network under DB transaction.

## Task 3 — Integration and evidence (parent)

Create `docs/contracts/merchant-notifications.md`, `docs/runbook/merchant-notifications.md`, `docs/evidence/pr09/README.md`; update README, `.env.example`, application.yml, version matrix and existing runbook crosslinks. Add focused schema tests if not covered by core fixture.

- [x] Reconcile concrete runtime defaults and contract with implemented classes. No endpoint URL from public requests. Existing application profiles start without Kafka enabled. New tests call fixture server only; no external messages.
- [x] Request independent specification review, fix/re-review. After PASS request independent whole-change quality review, fix/re-review. Parent and core agent never run Maven together.
- [x] Run `JAVA_HOME=/Users/anne_lister/.jdks/amazon-corretto-21.jdk/Contents/Home ./mvnw -B clean verify` and require exit0/zero failures. Check OpenAPI and documentation links, `git diff --check`, migration empty/upgrade and message safety.
- [x] Archive build output and text reports, local Kafka/merchant fault scenarios, source SHA256, review results. Mark real external merchant/Stripe calls NOT_RUN. Nonprinting configured-secret scan must pass before staging.
- [x] Commit feature branch; confirm clean worktree and main untouched. User will request main integration separately.
