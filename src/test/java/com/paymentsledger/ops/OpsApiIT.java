package com.paymentsledger.ops;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@ActiveProfiles({"api", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsApiIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    static final String SECRET = "o".repeat(43), HASH = new BCryptPasswordEncoder(10).encode(SECRET);
    static final Path FAKE = directory();

    static Path directory() {
        try {
            return Files.createTempDirectory("pr16-ops-");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", () -> "payments_app");
        r.add("spring.datasource.password", () -> "pr02_test_only");
        r.add("spring.flyway.url", DB::getJdbcUrl);
        r.add("spring.flyway.user", DB::getUsername);
        r.add("spring.flyway.password", DB::getPassword);
        r.add("platform.provider.fake.enabled", () -> true);
        r.add("platform.provider.fake.directory", FAKE::toString);
        r.add("platform.provider.fake.seed", () -> 16);
        r.add("platform.provider.fake.initial-time", () -> Instant.now().toString());
        r.add("platform.provider.fake.retention", () -> "P1D");
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    JdbcTemplate admin;

    static UUID id(int n) {
        return new UUID(16, n);
    }

    @BeforeEach
    void seed() {
        admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        admin.execute("TRUNCATE platform.organization,platform.currency_config CASCADE");
        admin.update("""
                INSERT INTO platform.currency_config(code,minor_unit_exponent)
                VALUES ('USD',2),('JPY',0)""");
        for (int o : List.of(1, 2)) {
            admin.update("INSERT INTO platform.organization(id,code) VALUES (?,?)", id(o), "ops-" + o);
            for (int e : List.of(10 + o, 20 + o)) {
                admin.update("""
                        INSERT INTO platform.environment_config(id,organization_id,code,livemode)
                        VALUES (?,?,?,false)""", id(e), id(o), "test-" + e);
                admin.update("""
                        INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                            provider_account_ref,livemode)
                        VALUES (?,?,?,'FAKE',?,false)""", id(100 + e), id(o), id(e), "ops-" + e);
                admin.update("""
                        INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,
                            opening_mode)
                        VALUES (?,?,?,false,'ZERO')""", id(o), id(e), id(100 + e));
                admin.update("""
                        INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)
                        VALUES (?,?,'USD'),(?,?,'JPY')""", id(o), id(e), id(o), id(e));
                admin.update("""
                        INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,
                            source_domain)
                        VALUES (?,?,?,'USD','BANK_CASH','SIMULATED_BANK'),(?,?,?,'USD','OPENING_EQUITY','INTERNAL')""", id(200 + e), id(o), id(e), id(300 + e), id(o), id(e));
            }
        }
        String[] roles = {
            "OPS_VIEWER",
            "OPS_OPERATOR",
            "OPS_REVIEWER",
            "OPS_ADMIN",
            "PARTNER_VIEWER",
            "OPS_REVIEWER",
            "OPS_OPERATOR",
            "OPS_OPERATOR"
        };
        for (int a = 1; a <= roles.length; a++) {
            int org = a == 7 ? 2 : 1, env = a == 8 ? 21 : 10 + org;
            admin.update("""
                    INSERT INTO platform.ops_identity(id,organization_id,subject,role,partner_id)
                    VALUES (?,?,?,?,?)""", id(400 + a), id(org), "actor-" + a, roles[a - 1], a == 5 ? id(900) : null);
            admin.update("""
                    INSERT INTO platform.ops_credential(id,ops_identity_id,organization_id,environment_id,key_id,
                        secret_hash,not_before,expires_at)
                    VALUES (?,?,?,?,'ops',?,now()-interval '1 day',now()+interval '1 day')""", id(500 + a), id(400 + a), id(org), id(env), HASH);
        }
    }

    ResponseEntity<JsonNode> call(int actor, String path, HttpMethod method, JsonNode body, String key) {
        var h = new HttpHeaders();
        if (actor > 0) h.set("Authorization", "OpsBearer ops_" + id(500 + actor) + "." + SECRET);
        if (key != null) h.set("Idempotency-Key", key);
        if (body != null) h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    ObjectNode run() {
        return json.createObjectNode()
                .put("runType", "BALANCE_LEVEL")
                .put("businessDate", "2026-09-01")
                .put("currency", "USD")
                .put("reason", "secret-token-do-not-persist");
    }

    ObjectNode adjustment() {
        var b = json.createObjectNode()
                .put("targetType", "BALANCE")
                .put("targetId", id(211).toString())
                .put("reason", "secret-token-do-not-persist");
        b.putArray("evidenceRefs").add("ledger-account:" + id(211));
        var a = b.putArray("proposedEntries");
        for (int n = 0; n < 2; n++)
            a.addObject()
                    .put("entryId", "draft-" + n)
                    .put("ledgerAccountId", id(n == 0 ? 211 : 311).toString())
                    .put("accountPurpose", n == 0 ? "BANK_CASH" : "OPENING_EQUITY")
                    .put("direction", n == 0 ? "DEBIT" : "CREDIT")
                    .put("amountMinor", 100)
                    .put("currency", "USD");
        return b;
    }

    @Autowired
    com.paymentsledger.ledger.LedgerPostingService ledger;

    void resources() {
        admin.update("""
                INSERT INTO platform.application(id,organization_id,environment_id,code)
                VALUES (?,?,?,'ops-app')""", id(600), id(1), id(11));
        String policy = "{\"policy_version\":1,\"allocations\":[{\"partner_ref\":\""
                + id(900)
                + "\",\"share_basis_points\":1000},{\"partner_ref\":\""
                + id(901)
                + "\",\"share_basis_points\":1000}]}";
        admin.update("""
                INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,
                    provider_account_id,currency,order_key,request_fingerprint,product_ref,price_version,quantity,
                    unit_amount_minor,amount_minor,price_snapshot,share_policy_snapshot)
                VALUES (?,?,?,?,?,'USD','ops-order','fp','demo',1,1,100,100,'{}',?::jsonb)""", id(601), id(1), id(11), id(600), id(111), policy);
        admin.update("""
                INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,
                    currency,business_order_id,amount_minor,expires_at,resolution_status,financial_status)
                VALUES (?,?,?,?,?,'USD',?,100,now()+interval '1 day','UNKNOWN','PENDING_EVIDENCE')""", id(602), id(1), id(11), id(600), id(111), id(601));
        admin.update("""
                INSERT INTO platform.refund_control(payment_id,organization_id,environment_id,application_id,
                    provider_account_id,currency,captured_minor)
                VALUES (?,?,?,?,?,'USD',0)""", id(602), id(1), id(11), id(600), id(111));
        for (int partner : List.of(900, 901))
            admin.update(
                    """
                    INSERT INTO platform.share_allocation(id,organization_id,environment_id,application_id,
                        provider_account_id,currency,payment_id,partner_ref,policy_version,rate_bps)
                    VALUES (?,?,?,?,?,'USD',?,?, '1',1000)""",
                    id(1000 + partner),
                    id(1),
                    id(11),
                    id(600),
                    id(111),
                    id(602),
                    id(partner).toString());
        admin.update("""
                INSERT INTO platform.refund(id,organization_id,environment_id,application_id,provider_account_id,
                    currency,payment_id,amount_minor,request_fingerprint,source,provider_charge_id)
                VALUES (?,?,?,?,?,'USD',?,10,'fp','LOCAL','ch_ops')""", id(603), id(1), id(11), id(600), id(111), id(602));
        admin.update("""
                INSERT INTO platform.dispute(id,organization_id,environment_id,provider_account_id,livemode,
                    provider_dispute_id,currency,provider_status,disputed_principal_minor,last_observed_at)
                VALUES (?,?,?,?,false,'dp_ops','USD','needs_response',10,now())""", id(604), id(1), id(11), id(111));
        admin.update("""
                INSERT INTO platform.payout(id,organization_id,environment_id,provider_account_id,livemode,
                    provider_payout_id,currency,amount_minor,automatic,provider_status,last_observed_at)
                VALUES (?,?,?,?,false,'po_ops','USD',100,true,'paid',now())""", id(605), id(1), id(11), id(111));
        admin.update("""
                INSERT INTO platform.provider_operation(id,organization_id,environment_id,application_id,
                    provider_account_id,currency,operation_target,payment_id,operation_type,operation_key,
                    request_fingerprint,command,expires_at)
                VALUES (?,?,?,?,?,'USD','PAYMENT',?,'CREATE_CHECKOUT','ops-key','fp','{}',now()+interval '1 day')""", id(606), id(1), id(11), id(600), id(111), id(602));
        admin.update("""
                INSERT INTO platform.recovery_task(id,organization_id,environment_id,application_id,
                    provider_account_id,currency,operation_id,payment_id)
                VALUES (?,?,?,?,?,'USD',?,?)""", id(607), id(1), id(11), id(600), id(111), id(606), id(602));
    }

    UUID reconciliationException() {
        var response = call(2, "/ops/reconciliation-runs", HttpMethod.POST, run(), "run-seed");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID runId = UUID.fromString(response.getBody().path("runId").asText());
        admin.update("""
                INSERT INTO platform.reconciliation_exception(id,run_id,organization_id,environment_id,
                    provider_account_id,livemode,currency,reason,owner,residual,input_versions,evidence)
                VALUES (?,?,?,?,?,false,'USD','BALANCE_RESIDUAL','OPS',10,'{}','{}')""", id(608), runId, id(1), id(11), id(111));
        return runId;
    }

    @Test
    void allReadResourcesReturnCamelCaseContractAndPartnerOnlyOwnAllocation() {
        resources();
        UUID runId = reconciliationException();
        UUID journal = ledger.post(
                new com.paymentsledger.ledger.LedgerScope(id(1), id(11), "USD", null),
                new com.paymentsledger.ledger.PostingCommand(
                        "ops-fixture",
                        "L-OPENING",
                        "INTERNAL",
                        "ops-fixture",
                        json.createObjectNode(),
                        List.of(
                                new com.paymentsledger.ledger.PostingLine(
                                        id(211), com.paymentsledger.ledger.Side.DEBIT, 10),
                                new com.paymentsledger.ledger.PostingLine(
                                        id(311), com.paymentsledger.ledger.Side.CREDIT, 10)),
                        null));
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("/ops/payments/" + id(602), "paymentStatus");
        paths.put("/ops/refunds/" + id(603), "refundStatus");
        paths.put("/ops/disputes/" + id(604), "localHandling");
        paths.put("/ops/payouts/" + id(605), "bankMatchState");
        paths.put("/ops/revenue-shares/" + id(602), "lines");
        paths.put("/ops/ledger/journals", "items");
        paths.put("/ops/ledger/journals/" + journal, "entries");
        paths.put("/ops/balances", "items");
        paths.put("/ops/reconciliation-runs/" + runId, "dataCompleteness");
        paths.put("/ops/exceptions", "items");
        paths.put("/ops/tasks", "items");
        for (var entry : paths.entrySet()) {
            var response = call(1, entry.getKey(), HttpMethod.GET, null, null);
            assertThat(response.getStatusCode()).as(entry.getKey()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().has(entry.getValue()))
                    .as(entry.getKey())
                    .isTrue();
            assertThat(response.getBody().toString()).doesNotContain("lease_token", "command", HASH, SECRET);
        }
        var payment =
                call(1, "/ops/payments/" + id(602), HttpMethod.GET, null, null).getBody();
        assertThat(payment.path("resolutionStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(payment.path("financialStatus").asText()).isEqualTo("PENDING_EVIDENCE");
        var share = call(5, "/ops/revenue-shares/" + id(602), HttpMethod.GET, null, null)
                .getBody();
        assertThat(share.path("lines").size()).isEqualTo(1);
        assertThat(share.path("lines").get(0).path("partnerId").asText()).isEqualTo(id(900).toString());
        assertThat(share.has("policySnapshot")).isFalse();
        assertThat(share.has("journals")).isFalse();
        assertThat(share.toString()).doesNotContain(id(901).toString());
        for (int actor : List.of(7, 8))
            for (String path : paths.keySet().stream()
                    .filter(p -> !p.endsWith("journals")
                            && !p.endsWith("balances")
                            && !p.endsWith("exceptions")
                            && !p.endsWith("tasks"))
                    .toList())
                assertThat(call(actor, path, HttpMethod.GET, null, null).getStatusCode())
                        .as(path)
                        .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(1, "/ops/tasks?limit=201", HttpMethod.GET, null, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void scopedExceptionEvidenceAndRetryNeverResetFinalLeaseOrExpiredUnknown() {
        resources();
        reconciliationException();
        var action = json.createObjectNode().put("actionType", "RESOLVE").put("reason", "reviewed");
        assertThat(call(2, "/ops/exceptions/" + id(608) + "/actions", HttpMethod.POST, action, "resolve-empty")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        action.putArray("evidenceRefs").add("arbitrary");
        assertThat(call(2, "/ops/exceptions/" + id(608) + "/actions", HttpMethod.POST, action, "resolve-arbitrary")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        action.put("actionType", "INVESTIGATE");
        action.putArray("evidenceRefs");
        var investigated = call(2, "/ops/exceptions/" + id(608) + "/actions", HttpMethod.POST, action, "investigate");
        assertThat(investigated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(investigated.getBody().path("status").asText()).isEqualTo("INVESTIGATING");
        assertThat(admin.queryForObject(
                        "SELECT reason FROM platform.reconciliation_exception WHERE id=?", String.class, id(608)))
                .isEqualTo("BALANCE_RESIDUAL");
        var reason = json.createObjectNode().put("reason", "wake existing task");
        String path = "/ops/tasks/" + id(607) + "/retry";
        assertThat(call(2, path, HttpMethod.POST, reason, "retry-1").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(2, path, HttpMethod.POST, reason, "retry-1")
                        .getHeaders()
                        .getFirst("Idempotent-Replay"))
                .isEqualTo("true");
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.recovery_task", Long.class))
                .isEqualTo(1);
        admin.update("""
                UPDATE platform.recovery_task
                SET status='RUNNING',lease_until=now()+interval '1 hour'
                WHERE id=?""", id(607));
        assertThat(call(2, path, HttpMethod.POST, reason, "retry-lease").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        admin.update("UPDATE platform.recovery_task SET status='DONE',lease_until=NULL WHERE id=?", id(607));
        assertThat(call(2, path, HttpMethod.POST, reason, "retry-final").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        admin.update("UPDATE platform.recovery_task SET status='WAITING' WHERE id=?", id(607));
        admin.update("""
                UPDATE platform.provider_operation
                SET status='UNKNOWN',first_send_at=now()-interval '2 days',retry_until=now()-interval '1 day'
                WHERE id=?""", id(606));
        assertThat(call(2, path, HttpMethod.POST, reason, "retry-unknown").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(admin.queryForObject(
                        "SELECT status FROM platform.provider_operation WHERE id=?", String.class, id(606)))
                .isEqualTo("UNKNOWN");
    }

    @Test
    void authenticatedFilterDenialsAndMalformedPathNeverPersistSecrets() {
        assertThat(call(
                                2,
                                "/ops/reconciliation-runs",
                                HttpMethod.POST,
                                run().put("organizationId", id(2).toString()),
                                "scope-deny")
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(
                                2,
                                "/ops/tasks/sk_test_SECRETINPATH/retry",
                                HttpMethod.POST,
                                json.createObjectNode().put("reason", "secret body"),
                                "invalid")
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(
                                0,
                                "/ops/tasks/" + id(999) + "/retry",
                                HttpMethod.POST,
                                json.createObjectNode().put("reason", "secret body"),
                                "anonymous")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        String audit =
                admin.queryForObject("SELECT jsonb_agg(to_jsonb(a))::text FROM platform.audit_log a", String.class);
        assertThat(audit).doesNotContain("sk_test_SECRETINPATH", "secret body", SECRET, HASH);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.audit_log", Long.class))
                .isEqualTo(3);
    }

    @Test
    void concurrentApprovalAndPostingFailureRollbackLeaveOneFinalJournal() throws Exception {
        var created = call(2, "/ops/adjustments", HttpMethod.POST, adjustment(), "concurrent");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String path =
                "/ops/adjustments/" + created.getBody().path("adjustmentId").asText() + "/approve";
        var b = json.createObjectNode().put("decision", "APPROVE").put("reason", "reviewed");
        admin.update("UPDATE platform.ops_identity SET role='OPS_REVIEWER' WHERE id=?", id(402));
        assertThat(call(2, path, HttpMethod.POST, b, "actual-self")
                        .getBody()
                        .path("code")
                        .asText())
                .isEqualTo("APPROVAL_SELF_NOT_ALLOWED");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> call(3, path, HttpMethod.POST, b, "parallel-1"));
            var second = pool.submit(() -> call(6, path, HttpMethod.POST, b, "parallel-2"));
            assertThat(first.get().getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.journal", Long.class))
                .isEqualTo(1);
        admin.update("UPDATE platform.ops_identity SET role='OPS_OPERATOR' WHERE id=?", id(402));
        var next = call(2, "/ops/adjustments", HttpMethod.POST, adjustment(), "rollback");
        String adjustment = next.getBody().path("adjustmentId").asText();
        admin.execute("""
                CREATE FUNCTION platform.test_fail_approval() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE
                    EXCEPTION 'test approval rollback'; END $$""");
        admin.execute("""
                CREATE TRIGGER test_fail_approval BEFORE
                UPDATE ON platform.ops_adjustment FOR EACH ROW EXECUTE FUNCTION platform.test_fail_approval()""");
        try {
            assertThat(call(3, "/ops/adjustments/" + adjustment + "/approve", HttpMethod.POST, b, "rollback-approve")
                            .getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(admin.queryForObject(
                            "SELECT status FROM platform.ops_adjustment WHERE id=?",
                            String.class,
                            UUID.fromString(adjustment)))
                    .isEqualTo("PENDING_APPROVAL");
            assertThat(admin.queryForObject("SELECT count(*) FROM platform.journal", Long.class))
                    .isEqualTo(1);
            assertThat(admin.queryForObject(
                            "SELECT count(*) FROM platform.audit_log WHERE result='REJECTED'", Long.class))
                    .isPositive();
        } finally {
            admin.execute("DROP TRIGGER test_fail_approval ON platform.ops_adjustment");
            admin.execute("DROP FUNCTION platform.test_fail_approval()");
        }
    }

    @Test
    void auditFailureRollsBackSuccessfulApprovalAndCanRetry() {
        var created = call(2, "/ops/adjustments", HttpMethod.POST, adjustment(), "audit-rollback");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(created.getBody().path("adjustmentId").asText());
        var approve = json.createObjectNode().put("decision", "APPROVE").put("reason", "reviewed");
        admin.execute("""
                CREATE FUNCTION platform.test_fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF
                    NEW.action='APPROVE_ADJUSTMENT' AND NEW.result='SUCCEEDED' THEN RAISE EXCEPTION
                    'test audit rollback'; END IF; RETURN NEW; END $$""");
        admin.execute("""
                CREATE TRIGGER test_fail_audit BEFORE INSERT ON platform.audit_log FOR EACH ROW EXECUTE FUNCTION
                    platform.test_fail_audit()""");
        try {
            assertThat(call(3, "/ops/adjustments/" + id + "/approve", HttpMethod.POST, approve, "audit-fail")
                            .getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(admin.queryForObject("SELECT status FROM platform.ops_adjustment WHERE id=?", String.class, id))
                    .isEqualTo("PENDING_APPROVAL");
            assertThat(admin.queryForObject("SELECT count(*) FROM platform.journal", Long.class))
                    .isZero();
            assertThat(admin.queryForObject(
                            "SELECT count(*) FROM platform.ops_command WHERE action='APPROVE_ADJUSTMENT'", Long.class))
                    .isZero();
        } finally {
            admin.execute("DROP TRIGGER test_fail_audit ON platform.audit_log");
            admin.execute("DROP FUNCTION platform.test_fail_audit()");
        }
        assertThat(call(3, "/ops/adjustments/" + id + "/approve", HttpMethod.POST, approve, "audit-fail")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void nativePaymentAndRefundResourceIdsAreAccepted() {
        resources();
        assertThat(call(
                                1,
                                "/ops/payments/" + com.paymentsledger.payment.PaymentIds.format("pay", id(602)),
                                HttpMethod.GET,
                                null,
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(
                                1,
                                "/ops/refunds/" + com.paymentsledger.payment.PaymentIds.format("ref", id(603)),
                                HttpMethod.GET,
                                null,
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Autowired
    com.paymentsledger.payout.PayoutService payouts;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    @Autowired
    JdbcTemplate runtime;

    void bt(
            UUID bt,
            String external,
            long amount,
            String type,
            String sourceType,
            String source,
            UUID payment,
            UUID refund,
            UUID journal) {
        UUID observation = UUID.randomUUID();
        admin.update("""
                INSERT INTO platform.provider_observation(id,organization_id,environment_id,provider_account_id,
                    observation_domain,livemode,call_type,classification,evidence,fact,fetched_at)
                VALUES (?,?,?,?,'FINANCIAL',false,'QUERY','SUCCESS','{}','{}',now())""", observation, id(1), id(11), id(111));
        admin.update(
                """
                INSERT INTO platform.balance_transaction(id,organization_id,environment_id,provider_account_id,
                    livemode,bt_id,amount_minor,fee_minor,net_minor,currency,source_object_id,source_object_type,
                    business_type,balance_domain,provider_created_at,provider_status,api_version,fetched_at,
                    immutable_fact,fact,first_observation_id,payment_id,refund_id,journal_id,state)
                VALUES (?,?,?,?,false,?,?,0,?,'USD',?,?,?,'PAYMENTS','2026-09-01T00:00:00Z','available','v1',now(),
                    '{}','{}',?,?,?,?,?)""",
                bt,
                id(1),
                id(11),
                id(111),
                external,
                amount,
                amount,
                source,
                sourceType,
                type,
                observation,
                payment,
                refund,
                journal,
                journal == null ? "PENDING_EVIDENCE" : "POSTED");
    }

    @Test
    void partialRefundEvidenceDoesNotBecomePostedAndTimelinePreservesObservationTime() {
        resources();
        UUID journal = ledger.post(
                new com.paymentsledger.ledger.LedgerScope(id(1), id(11), "USD", null),
                new com.paymentsledger.ledger.PostingCommand(
                        "refund-fixture",
                        "L-OPENING",
                        "INTERNAL",
                        "ops-fixture",
                        json.createObjectNode(),
                        List.of(
                                new com.paymentsledger.ledger.PostingLine(
                                        id(211), com.paymentsledger.ledger.Side.DEBIT, 10),
                                new com.paymentsledger.ledger.PostingLine(
                                        id(311), com.paymentsledger.ledger.Side.CREDIT, 10)),
                        null));
        bt(id(709), "bt_refund_out", -10, "refund", "refund", "re_ops", id(602), id(603), journal);
        admin.update("""
                UPDATE platform.refund
                SET
                    refund_status='FAILED',
                    balance_transaction_id='bt_refund_out',
                    failure_balance_transaction_id='bt_refund_missing'
                WHERE id=?""", id(603));
        var refund =
                call(1, "/ops/refunds/" + id(603), HttpMethod.GET, null, null).getBody();
        assertThat(refund.path("financialStatus").asText()).isEqualTo("PARTIALLY_POSTED");
        admin.update(
                """
                INSERT INTO platform.provider_observation(id,organization_id,environment_id,application_id,
                    provider_account_id,currency,operation_id,payment_id,call_type,classification,
                    external_object_id,evidence,fact)
                VALUES (?,?,?,?,?,'USD',?,?,'QUERY','SUCCESS','cs_ops','{\"rawDigest\":\"""" + "a".repeat(64) + "\",\"apiVersion\":\"v1\"}','{\"secret\":\"raw-fact-must-not-be-returned\"}')",
                id(711),
                id(1),
                id(11),
                id(600),
                id(111),
                id(606),
                id(602));
        var payment =
                call(1, "/ops/payments/" + id(602), HttpMethod.GET, null, null).getBody();
        assertThat(payment.path("provider").has("attempts")).isTrue();
        assertThat(payment.path("timeline").size()).isGreaterThan(1);
        assertThat(payment.toString()).doesNotContain("raw-fact-must-not-be-returned");
    }

    @Test
    void realBankMatchingPersistsOneJournalAndShortReceiptTruth() {
        var scope = new com.paymentsledger.provider.ProviderScope(id(1), id(11), id(111), false);
        Instant at = Instant.parse("2026-09-01T00:00:00Z");
        UUID payout = payouts.accept(
                scope,
                new com.paymentsledger.provider.ProviderModels.PayoutFact(
                        "po_bank_ops", "paid", 100, "USD", "bt_bank_out", null, true, at, null),
                new com.paymentsledger.provider.ProviderEvidence(
                        "req_fixture", null, at, at, 200, null, "a".repeat(64), "v1"));
        bt(id(712), "bt_bank_out", -100, "payout", "payout", "po_bank_ops", null, null, null);
        var fact = new com.paymentsledger.provider.ProviderModels.BalanceTransactionFact(
                "bt_bank_out",
                "payout",
                -100,
                0,
                -100,
                "USD",
                "PAYMENTS",
                "po_bank_ops",
                "payout",
                "available",
                at,
                at,
                List.of(),
                "a".repeat(64));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            payouts.lock(scope, "po_bank_ops");
            runtime.queryForMap("SELECT id FROM platform.balance_transaction WHERE id=? FOR UPDATE", id(712));
            assertThat(payouts.postLocked(scope, fact, id(712))).isEqualTo("POSTED");
        });
        var b = bank().put("fixtureId", "short-receipt")
                .put("coverageFrom", "2026-09-01T00:00:00Z")
                .put("coverageUntil", "2026-09-02T00:00:00Z")
                .put("complete", true);
        ((ObjectNode) b.path("lines").get(0)).put("amountMinor", 50).put("bankRef", "po_bank_ops");
        digest(b);
        var imported = call(2, "/ops/bank-statements/import", HttpMethod.POST, b, "match-bank");
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(imported.getBody().path("matchedCount").asInt()).isEqualTo(1);
        var detail =
                call(1, "/ops/payouts/" + payout, HttpMethod.GET, null, null).getBody();
        assertThat(detail.path("providerStatus").asText()).isEqualTo("paid");
        assertThat(detail.path("bankMatchState").asText()).isEqualTo("PARTIAL");
        assertThat(detail.path("shortReceiptState").asText()).isEqualTo("SHORT_RECEIPT");
        assertThat(detail.path("confirmedBankPrincipalMinor").asLong()).isEqualTo(50);
        assertThat(detail.path("bankEvidence").size()).isEqualTo(1);
        assertThat(call(2, "/ops/bank-statements/import", HttpMethod.POST, b, "match-bank-duplicate")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.journal", Long.class))
                .isEqualTo(2);
    }

    @Test
    void reviewerCanInspectScopedDraftThroughApiAndForeignSelectorsAreRejected() {
        var create = call(2, "/ops/adjustments", HttpMethod.POST, adjustment(), "review-draft");
        String a = create.getBody().path("adjustmentId").asText();
        var draft = call(3, "/ops/adjustments/" + a, HttpMethod.GET, null, null);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(draft.getBody().path("proposedEntries").size()).isEqualTo(2);
        assertThat(draft.getBody().path("evidenceRefs").size()).isEqualTo(1);
        assertThat(draft.getBody().path("reasonDigest").asText()).hasSize(64);
        assertThat(draft.getBody().toString()).doesNotContain("secret-token-do-not-persist");
        assertThat(call(7, "/ops/adjustments/" + a, HttpMethod.GET, null, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(5, "/ops/adjustments/" + a, HttpMethod.GET, null, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(1, "/ops/balances?providerAccountId=" + id(112), HttpMethod.GET, null, null)
                        .getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        assertThat(call(1, "/ops/tasks?providerAccountId=" + id(121), HttpMethod.GET, null, null)
                        .getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    @Test
    void requiredFieldsAndRoleMatrixReturnControlledErrors() {
        for (var malformed : List.of(
                adjustment().put("targetType", 1),
                adjustment().putNull("targetType"),
                adjustment().put("evidenceRefs", "not-an-array"))) {
            assertThat(call(2, "/ops/adjustments", HttpMethod.POST, malformed, "malformed-" + malformed.hashCode())
                            .getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(call(
                                2,
                                "/ops/exceptions/" + id(999) + "/actions",
                                HttpMethod.POST,
                                json.createObjectNode().put("actionType", 1).put("reason", "wrong type"),
                                "malformed-action")
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        for (int actor : List.of(2, 4))
            assertThat(call(
                                    actor,
                                    "/ops/adjustments",
                                    HttpMethod.POST,
                                    json.createObjectNode().put("reason", "missing fields"),
                                    "missing-" + actor)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        for (int actor : List.of(1, 3, 5))
            for (String path : List.of(
                    "/ops/reconciliation-runs",
                    "/ops/bank-statements/import",
                    "/ops/adjustments",
                    "/ops/tasks/" + id(999) + "/retry",
                    "/ops/exceptions/" + id(999) + "/actions"))
                assertThat(call(actor, path, HttpMethod.POST, run(), "matrix").getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN);
        for (int actor : List.of(1, 2, 4, 5))
            assertThat(call(
                                    actor,
                                    "/ops/adjustments/" + id(999) + "/approve",
                                    HttpMethod.POST,
                                    json.createObjectNode()
                                            .put("decision", "APPROVE")
                                            .put("reason", "review"),
                                    "approve-matrix")
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(4, "/ops/adjustments", HttpMethod.POST, adjustment(), "admin-create")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(call(2, "/ops/adjustments", HttpMethod.POST, adjustment(), null)
                        .getBody()
                        .path("code")
                        .asText())
                .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void resolutionJournalMustBelongToTheExceptionAdjustment() {
        UUID runId = reconciliationException();
        var created = call(2, "/ops/adjustments", HttpMethod.POST, adjustment(), "unrelated-adjustment");
        String a = created.getBody().path("adjustmentId").asText();
        var approve = json.createObjectNode().put("decision", "APPROVE").put("reason", "reviewed");
        var approved = call(3, "/ops/adjustments/" + a + "/approve", HttpMethod.POST, approve, "unrelated-approval");
        String journal = approved.getBody().path("postedJournalId").asText();
        var resolve = json.createObjectNode()
                .put("actionType", "RESOLVE")
                .put("reason", "resolved by journal")
                .put("resolutionJournalId", journal);
        assertThat(call(2, "/ops/exceptions/" + id(608) + "/actions", HttpMethod.POST, resolve, "unrelated-resolution")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        admin.update("""
                INSERT INTO platform.reconciliation_evidence(id,run_id,organization_id,environment_id,
                    provider_account_id,livemode,currency,evidence_kind,source,evidence_version,digest,payload)
                VALUES (?,?,?,?,?,false,'USD','INDEPENDENT_FIXTURE','ops-fixture','v1',?,'{}')""", id(713), runId, id(1), id(11), id(111), "e".repeat(64));
        var proposal = adjustment().put("targetType", "EXCEPTION").put("targetId", id(608).toString());
        proposal.putArray("evidenceRefs").add("reconciliation-evidence:" + id(713));
        var scoped = call(2, "/ops/adjustments", HttpMethod.POST, proposal, "exception-adjustment");
        assertThat(scoped.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var finalApproval = call(
                3,
                "/ops/adjustments/" + scoped.getBody().path("adjustmentId").asText() + "/approve",
                HttpMethod.POST,
                approve,
                "exception-approval");
        assertThat(finalApproval.getStatusCode()).isEqualTo(HttpStatus.OK);
        resolve.put(
                "resolutionJournalId",
                finalApproval.getBody().path("postedJournalId").asText());
        assertThat(call(2, "/ops/exceptions/" + id(608) + "/actions", HttpMethod.POST, resolve, "correct-resolution")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(admin.queryForObject(
                        "SELECT reason FROM platform.reconciliation_exception WHERE id=?", String.class, id(608)))
                .isEqualTo("BALANCE_RESIDUAL");
    }

    @Test
    void adjustmentAliasesShareOneIdempotencyNamespace() {
        resources();
        var proposal = adjustment().put("targetType", "PAYMENT").put("targetId", id(602).toString());
        var first = call(2, "/ops/adjustments", HttpMethod.POST, proposal, "same-target-key");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        proposal.put("targetId", com.paymentsledger.payment.PaymentIds.format("pay", id(602)));
        assertThat(call(2, "/ops/adjustments", HttpMethod.POST, proposal, "same-target-key")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.ops_adjustment", Long.class))
                .isEqualTo(1);
    }

    void check(UUID runId, int number, String outcome, String versions) {
        admin.update("""
                INSERT INTO platform.reconciliation_check(run_id,check_number,name,organization_id,environment_id,
                    provider_account_id,livemode,currency,outcome,residual,reason,owner,input_versions,snapshot,
                    completeness)
                VALUES (?,?,'fixture check',?,?,?,false,'USD',?,0,'fixture','OPS',?::jsonb,'fixture','{}')""", runId, number, id(1), id(11), id(111), outcome, versions);
    }

    @Test
    void runCheckResultUsesOnlyOutcomeAndPrioritizesDefiniteFailure() {
        UUID runId = reconciliationException();
        String path = "/ops/reconciliation-runs/" + runId;
        assertThat(call(1, path, HttpMethod.GET, null, null)
                        .getBody()
                        .path("checkResult")
                        .asText())
                .isEqualTo("INCOMPLETE");
        check(runId, 1, "PASS", "{\"fixtureVersion\":\"FAIL\",\"otherVersion\":\"INCOMPLETE\"}");
        assertThat(call(1, path, HttpMethod.GET, null, null)
                        .getBody()
                        .path("checkResult")
                        .asText())
                .isEqualTo("INCOMPLETE");
        for (int n = 2; n <= 7; n++) check(runId, n, "PASS", "{}");
        assertThat(call(1, path, HttpMethod.GET, null, null)
                        .getBody()
                        .path("checkResult")
                        .asText())
                .isEqualTo("PASS");
        var second = call(
                2,
                "/ops/reconciliation-runs",
                HttpMethod.POST,
                run().put("businessDate", "2026-09-02"),
                "priority-run");
        UUID secondId = UUID.fromString(second.getBody().path("runId").asText());
        String secondPath = "/ops/reconciliation-runs/" + secondId;
        check(secondId, 1, "INCOMPLETE", "{}");
        assertThat(call(1, secondPath, HttpMethod.GET, null, null)
                        .getBody()
                        .path("checkResult")
                        .asText())
                .isEqualTo("INCOMPLETE");
        check(secondId, 2, "FAIL", "{}");
        assertThat(call(1, secondPath, HttpMethod.GET, null, null)
                        .getBody()
                        .path("checkResult")
                        .asText())
                .isEqualTo("FAIL");
    }

    @Test
    void pendingTaskFilterIncludesWaitingAndReadyTasks() {
        resources();
        admin.update("UPDATE platform.recovery_task SET status='WAITING' WHERE id=?", id(607));
        var all = call(1, "/ops/tasks", HttpMethod.GET, null, null);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(all.getBody().path("items").get(0).path("status").asText()).isEqualTo("PENDING");
        var filtered = call(1, "/ops/tasks?status=PENDING", HttpMethod.GET, null, null);
        assertThat(filtered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(filtered.getBody().path("items").size()).isEqualTo(1);
        assertThat(filtered.getBody().path("items").get(0).path("taskId").asText())
                .isEqualTo(id(607).toString());
        admin.update("UPDATE platform.recovery_task SET status='READY' WHERE id=?", id(607));
        assertThat(call(1, "/ops/tasks?status=PENDING", HttpMethod.GET, null, null)
                        .getBody()
                        .path("items")
                        .size())
                .isEqualTo(1);
    }

    @Test
    void directUnrelatedJournalEvidenceCannotResolveException() {
        reconciliationException();
        var created = call(2, "/ops/adjustments", HttpMethod.POST, adjustment(), "unrelated-draft");
        var approved = call(
                3,
                "/ops/adjustments/" + created.getBody().path("adjustmentId").asText() + "/approve",
                HttpMethod.POST,
                json.createObjectNode().put("decision", "APPROVE").put("reason", "reviewed"),
                "unrelated-journal");
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        var resolve = json.createObjectNode().put("actionType", "RESOLVE").put("reason", "wrong finding");
        resolve.putArray("evidenceRefs")
                .add("journal:" + approved.getBody().path("postedJournalId").asText());
        assertThat(call(2, "/ops/exceptions/" + id(608) + "/actions", HttpMethod.POST, resolve, "wrong-journal-ref")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.ops_exception_action", Long.class))
                .isZero();
    }

    @Test
    void otherRunEvidenceCannotResolveExceptionButOwnRunEvidenceCan() {
        UUID ownRun = reconciliationException();
        var other = call(
                2, "/ops/reconciliation-runs", HttpMethod.POST, run().put("businessDate", "2026-09-02"), "another-run");
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID otherRun = UUID.fromString(other.getBody().path("runId").asText());
        for (int n : List.of(713, 714))
            admin.update("""
                    INSERT INTO platform.reconciliation_evidence(id,run_id,organization_id,environment_id,
                        provider_account_id,livemode,currency,evidence_kind,source,evidence_version,digest,payload)
                    VALUES (?,?,?,?,?,false,'USD','INDEPENDENT_FIXTURE','ops-fixture','v1',?,'{}')""", id(n), n == 713 ? otherRun : ownRun, id(1), id(11), id(111), "e".repeat(64));
        var resolve = json.createObjectNode().put("actionType", "RESOLVE").put("reason", "independent review");
        resolve.putArray("evidenceRefs").add("reconciliation-evidence:" + id(713));
        assertThat(call(2, "/ops/exceptions/" + id(608) + "/actions", HttpMethod.POST, resolve, "wrong-run-ref")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        resolve.putArray("evidenceRefs").add("reconciliation-evidence:" + id(714));
        assertThat(call(2, "/ops/exceptions/" + id(608) + "/actions", HttpMethod.POST, resolve, "own-run-ref")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void exceptionAuditBeforeReflectsSerializedCurrentDisposition() throws Exception {
        reconciliationException();
        var action = json.createObjectNode().put("actionType", "INVESTIGATE").put("reason", "initial review");
        assertThat(call(2, "/ops/exceptions/" + id(608) + "/actions", HttpMethod.POST, action, "first-disposition")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        action.put("actionType", "WAIT_EVIDENCE");
        var waiting = call(2, "/ops/exceptions/" + id(608) + "/actions", HttpMethod.POST, action, "next-disposition");
        assertThat(waiting.getStatusCode()).isEqualTo(HttpStatus.OK);
        String before = admin.queryForObject(
                "SELECT before_summary::text FROM platform.audit_log WHERE id=?",
                String.class,
                UUID.fromString(waiting.getBody().path("auditLogId").asText()));
        assertThat(json.readTree(before).path("status").asText()).isEqualTo("INVESTIGATING");
        assertThat(waiting.getBody().path("status").asText()).isEqualTo("WAITING_EVIDENCE");
    }

    @Test
    void rejectedOpsReadsAreAuditedWithoutCallerScopeOrSecrets() {
        assertThat(call(5, "/ops/balances", HttpMethod.GET, null, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(1, "/ops/balances?organizationId=" + id(2), HttpMethod.GET, null, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.audit_log WHERE result='DENIED'", Long.class))
                .isEqualTo(2);
        assertThat(admin.queryForObject("""
                        SELECT count(*)
                        FROM platform.audit_log
                        WHERE organization_id=? AND environment_id=?""", Long.class, id(1), id(11))).isEqualTo(2);
        assertThat(admin.queryForObject(
                        "SELECT string_agg(row_to_json(a)::text,'') FROM platform.audit_log a", String.class))
                .doesNotContain(SECRET, "OpsBearer", id(2).toString());
        assertThat(call(1, "/ops/balances", HttpMethod.GET, null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.audit_log", Long.class))
                .isEqualTo(2);
    }

    @Autowired
    com.paymentsledger.bank.BankStatementImporter bankImporter;

    @Autowired
    com.paymentsledger.bank.BankMatcher bankMatcher;

    @Autowired
    OpsAuditService auditService;

    @Autowired
    com.paymentsledger.payment.RecoveryTaskOperations taskOperations;

    @Test
    void reconciliationCompletedDayUsesConfiguredBusinessZoneOnly() {
        admin.execute("TRUNCATE platform.ingestion_policy CASCADE");
        admin.update("""
                INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,
                    opening_mode,business_zone)
                VALUES (?,?,?,false,'ZERO','Asia/Shanghai')""", id(1), id(11), id(111));
        var commands = new OpsCommandService(
                runtime,
                json,
                transactions,
                ledger,
                bankImporter,
                bankMatcher,
                java.time.Clock.fixed(Instant.parse("2026-09-01T20:00:00Z"), java.time.ZoneOffset.UTC),
                auditService,
                taskOperations);
        var context = new com.paymentsledger.identity.OpsContext(id(402), id(1), id(11), "OPS_OPERATOR", null);
        var captured = new java.util.concurrent.atomic.AtomicReference<OpsCommandService.Result>();
        assertThatCode(() -> captured.set(commands.execute(
                        context,
                        "CREATE_RECONCILIATION",
                        null,
                        "completed-shanghai-day",
                        run().put("overlapLookbackDays", 0))))
                .doesNotThrowAnyException();
        var created = captured.get();
        var view = call(
                1, "/ops/reconciliation-runs/" + created.body().path("runId").asText(), HttpMethod.GET, null, null);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view.getBody().path("businessDate").asText()).isEqualTo("2026-09-01");
        assertThat(view.getBody().path("businessTimezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(Instant.parse(view.getBody().path("windowEndUtc").asText()))
                .isEqualTo(Instant.parse("2026-09-01T16:00:00Z"));
        assertThatThrownBy(() -> commands.execute(
                        context,
                        "CREATE_RECONCILIATION",
                        null,
                        "future-shanghai-day",
                        run().put("businessDate", "2026-09-02")))
                .isInstanceOf(com.paymentsledger.common.error.ApiException.class);
    }

    JsonNode auditSummary(JsonNode result, String column) throws Exception {
        return json.readTree(admin.queryForObject(
                "SELECT " + column + "::text FROM platform.audit_log WHERE id=?",
                String.class,
                UUID.fromString(result.path("auditLogId").asText())));
    }

    @Test
    void replayAuditReportsCurrentResourceWhileOriginalResponseIsPreserved() throws Exception {
        reconciliationException();
        String path = "/ops/exceptions/" + id(608) + "/actions";
        var investigate =
                json.createObjectNode().put("actionType", "INVESTIGATE").put("reason", "initial review");
        assertThat(call(2, path, HttpMethod.POST, investigate, "replay-investigate")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(
                                2,
                                path,
                                HttpMethod.POST,
                                investigate.deepCopy().put("actionType", "WAIT_EVIDENCE"),
                                "wait-later")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var replay = call(2, path, HttpMethod.POST, investigate, "replay-investigate");
        assertThat(replay.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(replay.getBody().path("status").asText()).isEqualTo("INVESTIGATING");
        JsonNode exceptionBefore = auditSummary(replay.getBody(), "before_summary"),
                exceptionAfter = auditSummary(replay.getBody(), "after_summary");
        var proposal = adjustment();
        var created = call(2, "/ops/adjustments", HttpMethod.POST, proposal, "replay-create-draft");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(call(
                                3,
                                "/ops/adjustments/"
                                        + created.getBody().path("adjustmentId").asText()
                                        + "/approve",
                                HttpMethod.POST,
                                json.createObjectNode()
                                        .put("decision", "APPROVE")
                                        .put("reason", "reviewed"),
                                "approved-later")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var replayDraft = call(2, "/ops/adjustments", HttpMethod.POST, proposal, "replay-create-draft");
        assertThat(replayDraft.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(replayDraft.getBody().path("status").asText()).isEqualTo("PENDING_APPROVAL");
        JsonNode draftBefore = auditSummary(replayDraft.getBody(), "before_summary"),
                draftAfter = auditSummary(replayDraft.getBody(), "after_summary");
        org.assertj.core.api.SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(exceptionBefore.path("status").asText()).isEqualTo("WAITING_EVIDENCE");
            soft.assertThat(exceptionAfter.path("status").asText()).isEqualTo("WAITING_EVIDENCE");
            soft.assertThat(draftBefore.path("status").asText()).isEqualTo("APPROVED");
            soft.assertThat(draftAfter.path("status").asText()).isEqualTo("APPROVED");
        });
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.ops_adjustment", Long.class))
                .isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.journal", Long.class))
                .isEqualTo(1);
    }

    @Test
    void runEvidenceReferencesAreDiscoverableWithoutRawPayload() {
        UUID runId = reconciliationException();
        admin.update(
                """
                INSERT INTO platform.reconciliation_evidence(id,run_id,organization_id,environment_id,
                    provider_account_id,livemode,currency,evidence_kind,source,evidence_version,digest,payload)
                VALUES (?,?,?,?,?,false,'USD','INDEPENDENT_FIXTURE','ops-fixture','fixture-v2',?,?::jsonb)""",
                id(713),
                runId,
                id(1),
                id(11),
                id(111),
                "e".repeat(64),
                "{\"credential\":\"sk_test_should_never_leak_evidence\"}");
        var response = call(1, "/ops/reconciliation-runs/" + runId, HttpMethod.GET, null, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("evidence").size()).isEqualTo(1);
        JsonNode evidence = response.getBody().path("evidence").get(0);
        assertThat(evidence.path("id").asText()).isEqualTo(id(713).toString());
        assertThat(evidence.path("evidenceRef").asText()).isEqualTo("reconciliation-evidence:" + id(713));
        assertThat(evidence.path("kind").asText()).isEqualTo("INDEPENDENT_FIXTURE");
        assertThat(evidence.path("source").asText()).isEqualTo("ops-fixture");
        assertThat(evidence.path("version").asText()).isEqualTo("fixture-v2");
        assertThat(evidence.path("digest").asText()).isEqualTo("e".repeat(64));
        assertThat(Instant.parse(evidence.path("createdAt").asText())).isNotNull();
        assertThat(response.getBody().toString())
                .doesNotContain("payload", "credential", "sk_test_should_never_leak_evidence");
        var resolve = json.createObjectNode().put("actionType", "RESOLVE").put("reason", "operator inspected evidence");
        resolve.putArray("evidenceRefs").add(evidence.path("evidenceRef").asText());
        assertThat(call(
                                2,
                                "/ops/exceptions/" + id(608) + "/actions",
                                HttpMethod.POST,
                                resolve,
                                "discoverable-resolution")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void sourceRetryUsesWorkerTaskThenBalanceTransactionLockOrder() throws Exception {
        UUID runId = reconciliationException();
        bt(id(720), "bt_source_lock", 100, "charge", "charge", "ch_lock", null, null, null);
        admin.update("""
                INSERT INTO platform.recovery_task(id,organization_id,environment_id,provider_account_id,currency,
                    task_type,reconciliation_run_id,source_bt_id,livemode,status)
                VALUES (?,?,?,?,'USD','RECONCILIATION_SOURCE',?,?,false,'WAITING')""", id(721), id(1), id(11), id(111), runId, id(720));
        try (var worker = java.sql.DriverManager.getConnection(DB.getJdbcUrl(), "payments_app", "pr02_test_only");
                var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            worker.setAutoCommit(false);
            try (var taskLock =
                    worker.prepareStatement("SELECT id FROM platform.recovery_task WHERE id=? FOR UPDATE")) {
                taskLock.setObject(1, id(721));
                taskLock.executeQuery().close();
            }
            var retry = pool.submit(() -> call(
                    2,
                    "/ops/tasks/" + id(721) + "/retry",
                    HttpMethod.POST,
                    json.createObjectNode().put("reason", "manual wake concurrent with worker"),
                    "source-lock-order"));
            try {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = admin.queryForObject("""
                                    SELECT count(*)
                                    FROM pg_stat_activity
                                    WHERE usename='payments_app'
                                        AND wait_event_type='Lock'
                                        AND query LIKE '%platform.recovery_task%'
                                        AND query LIKE '%FOR UPDATE%'""", Long.class) > 0;
                    if (waiting) break;
                    Thread.sleep(10);
                }
                assertThat(waiting)
                        .as("HTTP retry reached the worker-held task row lock")
                        .isTrue();
                // ReconciliationSourceRecovery.link owns this exact task->BT order. A retry must not
                // hold the BT while waiting for this task, or the worker cannot acquire its next lock.
                assertThatCode(() -> {
                            try (var btLock = worker.prepareStatement(
                                    "SELECT id FROM platform.balance_transaction WHERE id=? FOR UPDATE NOWAIT")) {
                                btLock.setObject(1, id(720));
                                btLock.executeQuery().close();
                            }
                        })
                        .doesNotThrowAnyException();
            } finally {
                worker.rollback();
            }
            assertThat(retry.get(10, java.util.concurrent.TimeUnit.SECONDS).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Autowired
    OpsCommandService liveCommands;

    @Test
    void exceptionLatestDispositionFollowsSerializedActionOrderNotTransactionStart() throws Exception {
        reconciliationException();
        var started = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var older = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .execute(tx -> {
                        runtime.queryForObject("SELECT transaction_timestamp()", java.sql.Timestamp.class);
                        started.countDown();
                        try {
                            if (!proceed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException("newer action did not complete");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return liveCommands.execute(
                                new com.paymentsledger.identity.OpsContext(
                                        id(402), id(1), id(11), "OPS_OPERATOR", null),
                                "EXCEPTION_ACTION",
                                id(608),
                                "older-tx-later-action",
                                json.createObjectNode()
                                        .put("actionType", "WAIT_EVIDENCE")
                                        .put("reason", "later serialized action"));
                    }));
            assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(call(
                                        2,
                                        "/ops/exceptions/" + id(608) + "/actions",
                                        HttpMethod.POST,
                                        json.createObjectNode()
                                                .put("actionType", "INVESTIGATE")
                                                .put("reason", "first serialized action"),
                                        "newer-tx-first-action")
                                .getStatusCode())
                        .isEqualTo(HttpStatus.OK);
            } finally {
                proceed.countDown();
            }
            var later = older.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(later.body().path("status").asText()).isEqualTo("WAITING_EVIDENCE");
            var view = call(1, "/ops/exceptions", HttpMethod.GET, null, null);
            assertThat(view.getBody().path("items").get(0).path("status").asText())
                    .isEqualTo("WAITING_EVIDENCE");
            var reopen = call(
                    2,
                    "/ops/exceptions/" + id(608) + "/actions",
                    HttpMethod.POST,
                    json.createObjectNode().put("actionType", "REOPEN").put("reason", "next serialized action"),
                    "next-after-late-tx");
            assertThat(auditSummary(reopen.getBody(), "before_summary")
                            .path("status")
                            .asText())
                    .isEqualTo("WAITING_EVIDENCE");
        }
    }

    @Test
    void targetDetailsIncludeTheirApprovedManualAdjustmentJournal() {
        resources();
        var types = List.of("PAYMENT", "REFUND", "DISPUTE", "PAYOUT");
        for (int n = 0; n < types.size(); n++) {
            String type = types.get(n);
            UUID target = id(602 + n);
            var draft = call(
                    2,
                    "/ops/adjustments",
                    HttpMethod.POST,
                    adjustment().put("targetType", type).put("targetId", target.toString()),
                    "detail-adjustment-" + type);
            assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            var approved = call(
                    3,
                    "/ops/adjustments/" + draft.getBody().path("adjustmentId").asText() + "/approve",
                    HttpMethod.POST,
                    json.createObjectNode().put("decision", "APPROVE").put("reason", "reviewed"),
                    "detail-approval-" + type);
            assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
            String journal = approved.getBody().path("postedJournalId").asText();
            String resource =
                    List.of("payments", "refunds", "disputes", "payouts").get(n);
            var detail = call(1, "/ops/" + resource + "/" + target, HttpMethod.GET, null, null);
            assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(detail.getBody().path("journals").findValuesAsText("journalId"))
                    .as(type)
                    .contains(journal);
        }
    }

    @Test
    void cappedTraceShowsNewestEvidenceAndDeclaresIncompleteSections() {
        resources();
        for (int n = 0; n < 101; n++) {
            admin.update(
                    """
                    INSERT INTO platform.provider_observation(id,organization_id,environment_id,application_id,
                        provider_account_id,currency,operation_id,payment_id,call_type,classification,
                        external_object_id,evidence,fact,created_at)
                    VALUES (?,?,?,?,?,'USD',?,?,'QUERY','SUCCESS',?,'{}','{\"secret\":\"never-return-raw-trace\"}',?)""",
                    id(8000 + n),
                    id(1),
                    id(11),
                    id(600),
                    id(111),
                    id(606),
                    id(602),
                    "cs_trace_" + n,
                    java.sql.Timestamp.from(
                            Instant.parse("2026-09-01T00:00:00Z").plusSeconds(n)));
        }
        var response = call(1, "/ops/payments/" + id(602), HttpMethod.GET, null, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.path("traceCompleteness").asText()).isEqualTo("INCOMPLETE");
        assertThat(body.path("truncatedSections").toString()).contains("providerObservations");
        assertThat(body.path("providerObservations").size()).isEqualTo(100);
        assertThat(body.path("providerObservations").findValuesAsText("id")).contains(id(8100).toString());
        assertThat(body.toString()).doesNotContain("never-return-raw-trace");
        assertThat(body.path("financialStatus").asText()).isEqualTo("PENDING_EVIDENCE");
    }

    @Test
    void truncatedJournalHistoryDoesNotMislabelCompleteRevenueShareLines() {
        resources();
        for (int n = 0; n < 101; n++) {
            UUID adjustmentId = id(9000 + n);
            UUID journal = ledger.post(
                    new com.paymentsledger.ledger.LedgerScope(id(1), id(11), "USD", id(111)),
                    new com.paymentsledger.ledger.PostingCommand(
                            "ops.adjustment:" + adjustmentId,
                            "OPS_ADJUSTMENT",
                            "INTERNAL",
                            adjustmentId.toString(),
                            json.createObjectNode(),
                            List.of(
                                    new com.paymentsledger.ledger.PostingLine(
                                            id(211), com.paymentsledger.ledger.Side.DEBIT, 1),
                                    new com.paymentsledger.ledger.PostingLine(
                                            id(311), com.paymentsledger.ledger.Side.CREDIT, 1)),
                            null));
            admin.update("""
                    INSERT INTO platform.ops_adjustment(id,organization_id,environment_id,provider_account_id,currency,
                        target_type,target_id,created_by,approved_by,status,approved_at,posted_journal_id,entries,
                        evidence_refs,reason_digest,reason_length)
                    VALUES (?,?,?,?,'USD','PAYMENT',?,?,?,'APPROVED',now(),?,'[{},{}]','[\"fixture\"]','digest',1)""", adjustmentId, id(1), id(11), id(111), id(602), id(402), id(403), journal);
        }
        var detail = call(1, "/ops/payments/" + id(602), HttpMethod.GET, null, null);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().path("journals").size()).isEqualTo(100);
        assertThat(detail.getBody().path("revenueShares").size()).isEqualTo(2);
        assertThat(detail.getBody().path("truncatedSections").toString())
                .contains("journals")
                .doesNotContain("revenueShares");
        assertThat(detail.getBody().path("traceCompleteness").asText()).isEqualTo("INCOMPLETE");
    }

    @Test
    void everyFrozenReadRouteAuthenticatesAndAuthorizes() {
        for (String path : List.of(
                "/ops/payments/" + id(999),
                "/ops/refunds/" + id(999),
                "/ops/disputes/" + id(999),
                "/ops/payouts/" + id(999),
                "/ops/revenue-shares/" + id(999),
                "/ops/ledger/journals",
                "/ops/ledger/journals/" + id(999),
                "/ops/balances",
                "/ops/reconciliation-runs/" + id(999),
                "/ops/exceptions",
                "/ops/tasks")) {
            assertThat(call(0, path, HttpMethod.GET, null, null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            if (!path.contains("revenue-shares"))
                assertThat(call(5, path, HttpMethod.GET, null, null).getStatusCode())
                        .as(path)
                        .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(1, path, HttpMethod.GET, null, null).getStatusCode().value())
                    .as(path)
                    .isIn(200, 404);
        }
    }

    @Test
    void everyWriteDenialIsAuditedWithoutRawReason() {
        for (String path : List.of(
                "/ops/reconciliation-runs",
                "/ops/bank-statements/import",
                "/ops/exceptions/" + id(999) + "/actions",
                "/ops/tasks/" + id(999) + "/retry",
                "/ops/adjustments",
                "/ops/adjustments/" + id(999) + "/approve"))
            assertThat(call(1, path, HttpMethod.POST, run(), "deny").getStatusCode())
                    .as(path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.audit_log WHERE result='DENIED'", Long.class))
                .isEqualTo(6);
        assertThat(admin.queryForObject("SELECT jsonb_agg(to_jsonb(a))::text FROM platform.audit_log a", String.class))
                .doesNotContain(SECRET, "secret-token-do-not-persist", HASH);
    }

    @Test
    void reconciliationReplayConflictCurrencyAndTrustedScope() {
        var first = call(2, "/ops/reconciliation-runs", HttpMethod.POST, run(), "run-1");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var again = call(2, "/ops/reconciliation-runs", HttpMethod.POST, run(), "run-1");
        assertThat(again.getBody().path("runId")).isEqualTo(first.getBody().path("runId"));
        assertThat(again.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(call(
                                2,
                                "/ops/reconciliation-runs",
                                HttpMethod.POST,
                                run().put("businessDate", "2026-09-02"),
                                "run-1")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        String path =
                "/ops/reconciliation-runs/" + first.getBody().path("runId").asText();
        for (int a : List.of(7, 8))
            assertThat(call(a, path, HttpMethod.GET, null, null).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(2, "/ops/reconciliation-runs", HttpMethod.POST, run().put("currency", "EUR"), "eur")
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
        assertThat(call(
                                2,
                                "/ops/reconciliation-runs",
                                HttpMethod.POST,
                                run().put("organizationId", id(2).toString()),
                                "forge")
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var detail = call(1, path, HttpMethod.GET, null, null).getBody();
        assertThat(detail.path("runType").asText()).isEqualTo("BALANCE_LEVEL");
        assertThat(detail.path("dataCompleteness").asText()).isEqualTo("INCOMPLETE");
    }

    ObjectNode bank() {
        var b = json.createObjectNode()
                .put("fixtureId", "fixture-16")
                .put("schemaVersion", "1")
                .put("reason", "bank reason secret");
        b.putArray("lines")
                .addObject()
                .put("lineNo", 1)
                .put("valueDate", "2026-09-01")
                .put("amountMinor", 100)
                .put("currency", "USD")
                .put("bankRef", "po_missing")
                .put("description", "sensitive-bank-description");
        return b;
    }

    void digest(ObjectNode b) {
        var copy = b.deepCopy();
        copy.remove(List.of("reason", "contentDigest"));
        b.put(
                "contentDigest",
                com.paymentsledger.webhook.WebhookNormalizer.digest(
                        canonical(copy).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    JsonNode canonical(JsonNode n) {
        if (n.isObject()) {
            var out = json.createObjectNode();
            var keys = new TreeSet<String>();
            n.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) out.set(k, canonical(n.get(k)));
            return out;
        }
        if (n.isArray()) {
            var out = json.createArrayNode();
            n.forEach(v -> out.add(canonical(v)));
            return out;
        }
        return n.deepCopy();
    }

    @Test
    void bankLegacyTranslationDigestAndDuplicateFixtureAreSafe() {
        var b = bank();
        digest(b);
        var first = call(2, "/ops/bank-statements/import", HttpMethod.POST, b, "bank-1");
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        var replay = call(2, "/ops/bank-statements/import", HttpMethod.POST, b, "bank-2");
        assertThat(replay.getBody().path("bankStatementId"))
                .isEqualTo(first.getBody().path("bankStatementId"));
        assertThat(replay.getBody().path("duplicate").asBoolean()).isTrue();
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.bank_statement", Long.class))
                .isEqualTo(1);
        assertThat(admin.queryForObject("SELECT complete FROM platform.bank_statement", Boolean.class))
                .isFalse();
        assertThat(admin.queryForObject("SELECT payload::text FROM platform.bank_statement", String.class))
                .doesNotContain("sensitive-bank-description");
        var changed = bank();
        ((ObjectNode) changed.path("lines").get(0)).put("amountMinor", 101);
        digest(changed);
        assertThat(call(2, "/ops/bank-statements/import", HttpMethod.POST, changed, "bank-3")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(
                                2,
                                "/ops/bank-statements/import",
                                HttpMethod.POST,
                                b.put("contentDigest", "0".repeat(64)),
                                "bank-invalid")
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void adjustmentRequiresScopedEvidenceAndIndependentApprovalPostsExactlyOnce() {
        var invalid = adjustment();
        invalid.putArray("evidenceRefs").add("arbitrary-evidence");
        assertThat(call(2, "/ops/adjustments", HttpMethod.POST, invalid, "invalid")
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
        var created = call(2, "/ops/adjustments", HttpMethod.POST, adjustment(), "adjust-1");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String a = created.getBody().path("adjustmentId").asText();
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.journal", Long.class))
                .isZero();
        var approval = json.createObjectNode().put("decision", "APPROVE").put("reason", "reviewed");
        assertThat(call(2, "/ops/adjustments/" + a + "/approve", HttpMethod.POST, approval, "approve-self")
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var approved = call(3, "/ops/adjustments/" + a + "/approve", HttpMethod.POST, approval, "approve-1");
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().path("postedJournalId").asText()).isNotBlank();
        assertThat(call(6, "/ops/adjustments/" + a + "/approve", HttpMethod.POST, approval, "approve-2")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.journal", Long.class))
                .isEqualTo(1);
        assertThat(call(
                                1,
                                "/ops/ledger/journals/"
                                        + approved.getBody()
                                                .path("postedJournalId")
                                                .asText(),
                                HttpMethod.GET,
                                null,
                                null)
                        .getBody()
                        .path("entries")
                        .size())
                .isEqualTo(2);
        assertThat(call(1, "/ops/balances?asOf=2020-01-01T00:00:00Z", HttpMethod.GET, null, null)
                        .getBody()
                        .path("items")
                        .get(0)
                        .path("balanceMinor")
                        .asLong())
                .isZero();
    }
}
