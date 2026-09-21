package com.paymentsledger.provider;

import static com.paymentsledger.provider.ProviderCallClassification.*;
import static com.paymentsledger.provider.ProviderModels.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderModelsTest {
    private final Instant now = Instant.parse("2026-09-10T00:00:00Z");
    private final ProviderScope scope = new ProviderScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false);
    private final ProviderEvidence evidence = new ProviderEvidence("req_fixture", null, now, null, null, null, null, "fixture-v1");

    @Test void everyResultPreservesEvidenceAndOnlySuccessOrAuthenticationCanCarryFacts() {
        assertThat(ProviderCallClassification.values()).hasSize(11);
        for (var classification : ProviderCallClassification.values()) {
            assertThat(new ProviderCallResult<>(classification, null, evidence).evidence()).isSameAs(evidence);
            if (classification == SUCCESS || classification == AUTH_REQUIRED) {
                assertThat(new ProviderCallResult<>(classification, "fact", evidence).payload()).isEqualTo("fact");
            } else {
                assertThatThrownBy(() -> new ProviderCallResult<>(classification, "fact", evidence))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThatThrownBy(() -> new ProviderCallResult<>(SUCCESS, "fact", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProviderCallResult<>(null, null, evidence)).isInstanceOf(NullPointerException.class);
    }

    @Test void scopeRequiresExplicitIdentityAndCanRepresentLiveModeForAdapterRejection() {
        assertThat(new ProviderScope(scope.organizationId(), scope.environmentId(), scope.providerAccountId(), true).livemode()).isTrue();
        assertThatThrownBy(() -> new ProviderScope(null, scope.environmentId(), scope.providerAccountId(), false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProviderScope(scope.organizationId(), scope.environmentId(), null, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test void checkoutCopiesBothMetadataMapsWithoutImplicitPropagation() {
        var sessionMetadata = new HashMap<>(Map.of("order", "a"));
        var piMetadata = new HashMap<>(Map.of("operation", "b"));
        var command = checkout(100, "USD", sessionMetadata, piMetadata, Duration.ofMinutes(30));
        sessionMetadata.put("order", "changed");
        piMetadata.clear();
        assertThat(command.sessionMetadata()).containsExactlyEntriesOf(Map.of("order", "a"));
        assertThat(command.paymentIntentMetadata()).containsExactlyEntriesOf(Map.of("operation", "b"));
        assertThatThrownBy(() -> command.sessionMetadata().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> command.paymentIntentMetadata().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsInvalidCreateAmountsCurrenciesAndExpiry() {
        for (long amount : new long[] {0, -1}) {
            assertThatThrownBy(() -> checkout(amount, "USD", Map.of(), Map.of(), Duration.ofMinutes(30)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CreateRefundCommand(scope, "refund", "ch_1", null, amount, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> checkout(100, "US", Map.of(), Map.of(), Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkout(100, "USD", Map.of(), Map.of(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void refundRequiresExactlyOneTarget() {
        assertThat(new CreateRefundCommand(scope, "r1", "ch_1", null, 100, null).chargeId()).isEqualTo("ch_1");
        assertThat(new CreateRefundCommand(scope, "r2", null, "pi_1", 100, null).paymentIntentId()).isEqualTo("pi_1");
        assertThatThrownBy(() -> new CreateRefundCommand(scope, "r3", null, null, 100, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateRefundCommand(scope, "r3", "ch_1", "pi_1", 100, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateRefundCommand(scope, "r3", " ", null, 100, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void queryWindowsAndPaginationCannotSilentlyLoseContinuation() {
        assertThatThrownBy(() -> new ListBalanceTransactionsQuery(scope, now, now, null, 10, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageQuery(scope, now.plusSeconds(1), now, null, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageQuery(scope, now, now.plusSeconds(1), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportRequest(scope, "balance", now, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Page<String>(List.of("one"), true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BalanceTransactionPage(List.of(), true, " ")).isInstanceOf(IllegalArgumentException.class);
        var items = new ArrayList<>(List.of("one"));
        var page = new Page<>(items, true, "one");
        items.clear();
        assertThat(page.items()).containsExactly("one");
        assertThatThrownBy(() -> page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void balanceTransactionsAllowSignedMoneyButRequireExactNetWithoutOverflow() {
        assertThat(bt(-100, 5, -105, List.of()).netMinor()).isEqualTo(-105);
        assertThatThrownBy(() -> bt(100, 5, 100, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bt(Long.MIN_VALUE, 1, Long.MAX_VALUE, List.of())).isInstanceOf(IllegalArgumentException.class);
        var fees = new ArrayList<>(List.of(new FeeDetail(5, "USD", "provider_fee", null)));
        var fact = bt(100, 5, 95, fees);
        fees.clear();
        assertThat(fact.feeDetails()).hasSize(1);
        assertThatThrownBy(() -> fact.feeDetails().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void factsDefensivelyCopyMetadataAttemptsDisputesAndReports() {
        var ids = new ArrayList<>(List.of("ch_1", "ch_2"));
        var metadata = new HashMap<>(Map.of("order", "1"));
        var pi = new PaymentIntentFact("pi_1", "succeeded", 100, 100L, "USD", ids, "ch_2", null, metadata, now);
        var session = new CheckoutSessionFact("cs_1", CheckoutSessionStatus.OPEN, "https://fixture.invalid", null, "unpaid", now, metadata);
        var dispute = new DisputeFact("dp_1", "ch_2", "needs_response", "fraudulent", 100, "USD", now, ids);
        var balances = new ArrayList<>(List.of(new BalanceSnapshot("USD", "PAYMENTS", -10, 20, now)));
        var report = new ReportFact("rp_1", "available", now, now.plusSeconds(1), balances, "digest");
        var transactions = new ArrayList<>(List.of(bt(100, 5, 95, List.of())));
        var page = new BalanceTransactionPage(transactions, false, null);
        ids.clear(); metadata.clear(); balances.clear(); transactions.clear();
        assertThat(pi.chargeIds()).containsExactly("ch_1", "ch_2");
        assertThat(pi.metadata()).containsEntry("order", "1");
        assertThat(session.metadata()).containsEntry("order", "1");
        assertThat(dispute.balanceTransactionIds()).hasSize(2);
        assertThat(report.balances()).hasSize(1);
        assertThat(page.items()).hasSize(1);
        assertThatThrownBy(() -> pi.chargeIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> dispute.balanceTransactionIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.balances().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private CreateCheckoutSessionCommand checkout(long amount, String currency, Map<String, String> session,
                                                   Map<String, String> pi, Duration expiresAfter) {
        return new CreateCheckoutSessionCommand(scope, "checkout", amount, currency, "https://fixture.invalid/success",
                "https://fixture.invalid/cancel", session, pi, expiresAfter);
    }

    private BalanceTransactionFact bt(long amount, long fee, long net, List<FeeDetail> fees) {
        return new BalanceTransactionFact("bt_1", "charge", amount, fee, net, "USD", "PAYMENTS", "ch_1", "charge",
                "pending", now, null, fees, "digest");
    }
}
