package com.paymentsledger.revenueshare;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.financialingestion.*;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.ledger.LedgerPostingService;
import com.paymentsledger.payment.*;
import com.paymentsledger.provider.ProviderModels.BalanceTransactionFact;
import com.paymentsledger.provider.fake.FakeControls;
import com.paymentsledger.refund.RefundFinancialEvidence;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

class RevenueShareCoreIT extends RevenueShareCoreFixture {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    RevenueShareService shares;
    UUID paymentId;
    String bt;

    @BeforeEach
    void shareSetup() {
        var start = java.sql.Timestamp.from(fake.clock().instant().minusSeconds(86400));
        admin.update("UPDATE platform.price_fixture SET valid_from=?", start);
        admin.update("UPDATE platform.share_policy_fixture SET valid_from=?", start);
        admin.update(
                "INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,opening_mode) VALUES (?,?,?,false,'ZERO')",
                id(1),
                id(2),
                id(4));
        for (String purpose : List.of("PSP_FUNDS", "PSP_FEE_EXPENSE", "SALES_REVENUE", "SALES_RETURNS", "SUSPENSE")) {
            boolean provider = !purpose.startsWith("SALES_");
            admin.update(
                    "INSERT INTO platform.ledger_account(organization_id,environment_id,currency,account_purpose,source_domain,provider_account_id,balance_domain) VALUES (?,?,'USD',?,?,?,?)",
                    id(1),
                    id(2),
                    purpose,
                    provider ? "PROVIDER" : "INTERNAL",
                    provider ? id(4) : null,
                    provider ? "PAYMENTS" : null);
        }
        shares = new RevenueShareService(jdbc, tx, json, new LedgerPostingService(jdbc, json), true);
    }

    void paymentAndCapture(boolean historical) {
        var resource = service.create(context, "core-pay", command("core-pay")).resource();
        paymentId = payment(resource);
        String charge = fake.controls()
                .attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED)
                .latestChargeId();
        service.get(context, paymentId);
        bt = "txn_core_capture";
        fake.controls().linkChargeBalanceTransaction(scope, charge, bt);
        service.get(context, paymentId);
        var at = fake.clock().instant().truncatedTo(ChronoUnit.SECONDS);
        fake.controls()
                .putBalanceTransaction(
                        scope,
                        new BalanceTransactionFact(
                                bt,
                                "charge",
                                10000,
                                300,
                                9700,
                                "usd",
                                "PAYMENTS",
                                charge,
                                "charge",
                                "pending",
                                at,
                                at.plusSeconds(86400),
                                List.of(),
                                "a".repeat(64)),
                        fake.clock().instant(),
                        null);
        fake.clock().advance(Duration.ofSeconds(2));
        var importer = new FinancialImportService(jdbc, tx, json, fake, fake.clock());
        var window = importer.createWindow(
                scope,
                fake.clock().instant().minusSeconds(86400).truncatedTo(ChronoUnit.SECONDS),
                fake.clock().instant().truncatedTo(ChronoUnit.SECONDS),
                "BACKFILL");
        importer.runWindow(window, 10);
        var refunds = new RefundRepository(jdbc, tx, json, fake.clock());
        var replay = new FinancialReplayService(
                jdbc,
                tx,
                json,
                new LedgerPostingService(jdbc, json),
                new RefundFinancialEvidence(refunds),
                historical ? RevenueShareService.disabled() : shares);
        assertThat(replay.replay(scope, bt)).isEqualTo("POSTED");
    }

    long effective() {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(effective_minor),0) FROM platform.share_allocation", Long.class);
    }

    @Test
    void historicalPostedCaptureCatchesUpOnlyOnceAndQueriesHonorTenantCurrency() {
        paymentAndCapture(true);
        assertThat(count("share_allocation")).isZero();
        assertThat(count("journal")).isEqualTo(1);
        assertThat(shares.catchUp(id(1), id(2), id(4), 25)).isEqualTo(1);
        assertThat(effective()).isEqualTo(2000);
        assertThat(count("journal")).isEqualTo(2);
        shares.catchUp(id(1), id(2), id(4), 25);
        assertThat(count("share_adjustment")).isEqualTo(1);
        assertThat(count("journal")).isEqualTo(2);
        assertThat(shares.allocations(context, paymentId)).hasSize(1);
        assertThat(shares.adjustments(context, paymentId)).hasSize(1);
        var alien = new TenantContext(id(90), id(2), id(3), id(4), Set.of("USD"), false, Set.of("payments:read"));
        assertThat(shares.allocations(alien, paymentId)).isEmpty();
        assertThat(shares.adjustments(alien, paymentId)).isEmpty();
        var disallowed = new TenantContext(id(1), id(2), id(3), id(4), Set.of("EUR"), false, Set.of("payments:read"));
        assertThatThrownBy(() -> shares.allocations(disallowed, paymentId)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void multiplePartnersShareOnlyOneInternalAccountPairAndRoundCumulatively() {
        admin.update(
                "UPDATE platform.share_policy_fixture SET allocations='[{\"partner_ref\":\"a\",\"share_basis_points\":3333},{\"partner_ref\":\"b\",\"share_basis_points\":6667}]'::jsonb");
        paymentAndCapture(false);
        assertThat(effective()).isEqualTo(10000);
        assertThat(count("share_adjustment")).isEqualTo(2);
        var refunds = new RefundRepository(jdbc, tx, json, fake.clock(), repository, shares);
        var rc = new TenantContext(
                id(1), id(2), id(3), id(4), Set.of("USD"), false, Set.of("refunds:read", "refunds:write"));
        for (int held = 1; held <= 3; held++) {
            refunds.prepare(
                    rc,
                    "fragment" + held,
                    new RefundRequests.Create(paymentId, 1, "USD", null, null, "fragment" + held),
                    "FAKE");
            var amounts = jdbc.queryForList(
                    "SELECT effective_minor FROM platform.share_allocation ORDER BY partner_ref", Long.class);
            assertThat(amounts).containsExactly((10000L - held) * 3333 / 10000, (10000L - held) * 6667 / 10000);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.ledger_account WHERE account_purpose IN ('PARTNER_SHARE_EXPENSE','PARTNER_PAYABLE') AND provider_account_id IS NULL",
                        Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.share_adjustment h JOIN platform.journal j ON j.id=h.journal_id WHERE j.sealed_at IS NOT NULL",
                        Long.class))
                .isEqualTo(count("share_adjustment"));
    }

    @Test
    void historyAndProjectionCannotBeDirectlyRewrittenOrLinkedToAnotherJournal() {
        paymentAndCapture(false);
        long histories = count("share_adjustment"), journals = count("journal");
        for (String sql : List.of(
                "UPDATE platform.share_adjustment SET delta_minor=-1",
                "DELETE FROM platform.share_adjustment",
                "UPDATE platform.share_allocation SET effective_minor=-1",
                "UPDATE platform.share_allocation SET effective_minor=1000",
                "UPDATE platform.share_allocation SET policy_version='2'"))
            assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () -> jdbc.update(
                                "INSERT INTO platform.share_adjustment(organization_id,environment_id,currency,allocation_id,generation,delta_minor,journal_id,cause) SELECT a.organization_id,a.environment_id,a.currency,a.id,a.generation+1,1,b.journal_id,'bad' FROM platform.share_allocation a JOIN platform.balance_transaction b ON b.payment_id=a.payment_id"))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("share_adjustment")).isEqualTo(histories);
        assertThat(count("journal")).isEqualTo(journals);
        assertThat(effective()).isEqualTo(2000);
    }

    @Test
    void excessCoverageAndUnassociatedQuarantinedCanonicalSourceBothBlockSharing() {
        paymentAndCapture(false);
        new TransactionTemplate(tx).executeWithoutResult(status -> {
            var locked = shares.lock(paymentId);
            jdbc.update(
                    "UPDATE platform.payment SET captured_amount_minor=9999,version=version+1 WHERE id=?", paymentId);
            shares.reconcile(locked, "excess-capture-evidence");
        });
        assertThat(effective()).isZero();
        new TransactionTemplate(tx).executeWithoutResult(status -> {
            var locked = shares.lock(paymentId);
            jdbc.update(
                    "UPDATE platform.payment SET captured_amount_minor=10000,version=version+1 WHERE id=?", paymentId);
            shares.reconcile(locked, "evidence-aligned");
        });
        assertThat(effective()).isEqualTo(2000);
        new TransactionTemplate(tx).executeWithoutResult(status -> {
            var locked = shares.lock(paymentId);
            jdbc.update("""
    INSERT INTO platform.balance_transaction(organization_id,environment_id,provider_account_id,livemode,bt_id,amount_minor,fee_minor,net_minor,currency,source_object_id,source_object_type,business_type,balance_domain,provider_created_at,provider_status,api_version,fetched_at,immutable_fact,fact,first_observation_id,state,reason)
    SELECT organization_id,environment_id,provider_account_id,livemode,'txn_quarantined',amount_minor,fee_minor,net_minor,currency,source_object_id,source_object_type,business_type,balance_domain,provider_created_at,provider_status,api_version,fetched_at,immutable_fact,fact,first_observation_id,'QUARANTINED','AMOUNT_CONFLICT' FROM platform.balance_transaction WHERE bt_id=?
    """, bt);
            shares.reconcile(locked, "quarantined-canonical-source");
        });
        assertThat(effective()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT payment_id IS NULL FROM platform.balance_transaction WHERE bt_id='txn_quarantined'",
                        Boolean.class))
                .isTrue();
    }

    @Test
    void failedLegacySnapshotRotatesWithoutStarvingLaterEligiblePayments() {
        paymentAndCapture(true);
        admin.update("""
   INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,provider_account_id,currency,order_key,request_fingerprint,product_ref,price_version,quantity,unit_amount_minor,amount_minor,price_snapshot,share_policy_snapshot)
   SELECT ?,organization_id,environment_id,application_id,provider_account_id,currency,'legacy-malformed','legacy','demo',1,1,10000,10000,'{}','{}' FROM platform.business_order LIMIT 1
   """, id(70));
        admin.update("""
   INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,currency,business_order_id,amount_minor,captured_amount_minor,expires_at)
   SELECT ?,organization_id,environment_id,application_id,provider_account_id,currency,?,10000,10000,expires_at FROM platform.payment LIMIT 1
   """, id(7), id(70));
        assertThat(shares.catchUp(id(1), id(2), id(4), 1)).isEqualTo(1);
        assertThat(effective()).isZero();
        assertThat(shares.catchUp(id(1), id(2), id(4), 1)).isEqualTo(1);
        assertThat(effective()).isEqualTo(2000);
        assertThat(count("share_catchup_cursor")).isEqualTo(2);
    }

    @Test
    void reservedEligibilityPolicyCanWithdrawAndRestoreOnceAndHandlesCannotEscapeTransaction() {
        paymentAndCapture(false);
        var allow = new AtomicBoolean(false);
        var controlled =
                new RevenueShareService(jdbc, tx, json, new LedgerPostingService(jdbc, json), true, id -> allow.get());
        controlled.reconcile(paymentId, "reserved-policy-block");
        assertThat(effective()).isZero();
        allow.set(true);
        controlled.reconcile(paymentId, "reserved-policy-release");
        assertThat(effective()).isEqualTo(2000);
        long journals = count("journal");
        controlled.reconcile(paymentId, "repeat");
        assertThat(count("journal")).isEqualTo(journals);
        var locked = new TransactionTemplate(tx).execute(status -> shares.lock(paymentId));
        assertThatThrownBy(() -> new TransactionTemplate(tx)
                        .executeWithoutResult(status -> shares.reconcile(locked, "expired-handle")))
                .isInstanceOf(IllegalStateException.class);
    }
}
