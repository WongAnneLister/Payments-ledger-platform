package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.ledger.*;
import com.paymentsledger.pr18.Pr18Artifacts;
import java.util.*;
import org.junit.jupiter.api.Test;

class Pr18ScopeIsolationIT extends Pr18Fixture {
    @Test
    void existingForeignTenantsEnvironmentsProvidersCurrenciesCannotReadOrWriteLocalMoney() {
        reset(180020);
        createPayment();
        capturePayment();
        post(bt("capture", "charge", 10000, 300, chargeId, "charge"));
        admin.update("INSERT INTO platform.organization(id,code) VALUES (?,'foreign-org')", id(90));
        admin.update("""
                INSERT INTO platform.environment_config(id,organization_id,code,livemode)
                VALUES (?,?,'foreign-test',false),(?,?,'other-test',false)""", id(91), id(90), id(94), id(1));
        admin.update("""
                INSERT INTO platform.application(id,organization_id,environment_id,code)
                VALUES (?,?,?,'foreign-app'),(?,?,?,'other-app')""", id(92), id(90), id(91), id(95), id(1), id(94));
        admin.update("""
                INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                    provider_account_ref,livemode)
                VALUES
                    (?,?,?,'FAKE','foreign-provider',false),
                    (?,?,?,'FAKE','other-environment-provider',false),
                    (?,?,?,'FAKE','other-provider',false)""", id(93), id(90), id(91), id(96), id(1), id(94), id(97), id(1), id(2));
        admin.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('EUR',2)");
        admin.update("""
                INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)
                VALUES (?,?,'USD'),(?,?,'USD'),(?,?,'EUR')""", id(90), id(91), id(1), id(94), id(1), id(2));
        long journals = count("journal"), operations = count("provider_operation");
        var rejected = new ArrayList<String>();
        for (var tenant : List.of(
                new TenantContext(
                        id(90), id(91), id(92), id(93), Set.of("USD"), false, Set.of("payments:read", "refunds:write")),
                new TenantContext(
                        id(1), id(94), id(95), id(96), Set.of("USD"), false, Set.of("payments:read", "refunds:write")),
                new TenantContext(
                        id(1), id(2), id(3), id(97), Set.of("USD"), false, Set.of("payments:read", "refunds:write")),
                new TenantContext(
                        id(1), id(2), id(3), id(4), Set.of("EUR"), false, Set.of("payments:read", "refunds:write")))) {
            assertThatThrownBy(() -> repository.read(tenant, paymentId)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() ->
                            refunds.prepare(tenant, "foreign-refund-key", refundRequest("foreign-refund", 100), "FAKE"))
                    .isInstanceOf(ApiException.class);
            rejected.add(tenant.toString());
        }
        admin.update("""
                INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,
                    source_domain)
                VALUES (?,?,?,'USD','SALES_REVENUE','INTERNAL')""", id(98), id(90), id(91));
        admin.update("""
                INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,
                    source_domain)
                VALUES (?,?,?,'EUR','SALES_REVENUE','INTERNAL')""", id(99), id(1), id(2));
        UUID local = jdbc.queryForObject("""
                SELECT id
                FROM platform.ledger_account
                WHERE currency='USD' AND account_purpose='SALES_REVENUE' AND organization_id=?""", UUID.class, id(1));
        for (UUID foreign : List.of(id(98), id(99)))
            assertThatThrownBy(() -> posting.post(
                            new LedgerScope(id(1), id(2), "USD", id(4)),
                            new PostingCommand(
                                    "cross-scope-" + foreign,
                                    "fixture",
                                    "INTERNAL",
                                    "cross-scope",
                                    json.createObjectNode(),
                                    List.of(
                                            new PostingLine(local, Side.DEBIT, 100),
                                            new PostingLine(foreign, Side.CREDIT, 100)),
                                    null)))
                    .isInstanceOf(ApiException.class);
        assertThat(count("journal")).isEqualTo(journals);
        assertThat(count("financial_event")).isEqualTo(journals);
        assertThat(count("provider_operation")).isEqualTo(operations);
        assertThat(count("refund")).isZero();
        assertThat(balance("PSP_FUNDS")).isEqualTo(9700);
        assertThat(balance("PARTNER_SHARE_EXPENSE")).isEqualTo(2000);
        Pr18Artifacts.write(
                "scope-isolation.json",
                Map.of(
                        "status",
                        "PASS",
                        "existingActiveScopes",
                        rejected,
                        "checks",
                        List.of(
                                "crossOrganizationReadWriteRejected",
                                "crossEnvironmentReadWriteRejected",
                                "crossProviderReadWriteRejected",
                                "currencyAuthorizationRejected",
                                "crossOrganizationAndCurrencyLedgerLinesRejected",
                                "noResidue"),
                        "databaseProviderMessageEvidence",
                        snapshot()));
    }
}
