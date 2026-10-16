package com.paymentsledger.payment.domain;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.domain.RefundReservationRules.UnfreezeEvidence;
import com.paymentsledger.provider.ProviderScope;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundReservationRulesTest {
    static final TenantContext TENANT = new TenantContext(
            new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), new UUID(0, 4), Set.of("USD"), false, Set.of());

    static RefundablePayment payment(ResolutionStatus resolution, long captured, String charge) {
        return new RefundablePayment(
                new UUID(0, 9),
                new UUID(0, 1),
                new UUID(0, 2),
                new UUID(0, 3),
                new UUID(0, 4),
                "USD",
                resolution,
                captured,
                charge);
    }

    static RefundControl control(RefundControlState state, long captured, long refunded, long reserved, long version) {
        return new RefundControl(new UUID(0, 9), captured, refunded, reserved, state, version);
    }

    static void rejects(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void lockKeysAreScopedToTheApplication() {
        assertThat(RefundReservationRules.lockKey(TENANT, "CREATE_REFUND", "k"))
                .isEqualTo(new UUID(0, 1) + ":" + new UUID(0, 2) + ":" + new UUID(0, 3) + ":CREATE_REFUND:k");
    }

    @Test
    void replaysMustRepeatTheRequest() {
        RefundReservationRules.requireSameRequest("fp", "fp");
        rejects(() -> RefundReservationRules.requireSameRequest("fp", "other"), ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void onlyCapturedCertainPaymentsWithAChargeAreRefundable() {
        RefundReservationRules.requireRefundable(TENANT, payment(ResolutionStatus.KNOWN, 100, "ch"), "USD");
        rejects(() -> RefundReservationRules.requireRefundable(TENANT, null, "USD"), ErrorCode.NOT_FOUND);
        var euroTenant = new TenantContext(
                new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), new UUID(0, 4), Set.of("EUR"), false, Set.of());
        rejects(
                () -> RefundReservationRules.requireRefundable(
                        euroTenant, payment(ResolutionStatus.KNOWN, 100, "ch"), "USD"),
                ErrorCode.NOT_FOUND);
        rejects(
                () -> RefundReservationRules.requireRefundable(
                        TENANT, payment(ResolutionStatus.KNOWN, 100, "ch"), "EUR"),
                ErrorCode.CURRENCY_NOT_ENABLED);
        rejects(
                () -> RefundReservationRules.requireRefundable(
                        TENANT, payment(ResolutionStatus.CONFLICT, 100, "ch"), "USD"),
                ErrorCode.EVIDENCE_CONFLICT);
        rejects(
                () -> RefundReservationRules.requireRefundable(TENANT, payment(ResolutionStatus.KNOWN, 0, "ch"), "USD"),
                ErrorCode.REFUND_BLOCKED);
        rejects(
                () -> RefundReservationRules.requireRefundable(
                        TENANT, payment(ResolutionStatus.KNOWN, 100, null), "USD"),
                ErrorCode.REFUND_BLOCKED);
    }

    @Test
    void reservationsFitTheAvailableAmountOfANormalControl() {
        var normal = control(RefundControlState.NORMAL, 1000, 300, 200, 0);
        RefundReservationRules.requireReservable(normal, 500);
        rejects(() -> RefundReservationRules.requireReservable(normal, 501), ErrorCode.REFUND_LIMIT_EXCEEDED);
        rejects(
                () -> RefundReservationRules.requireReservable(
                        control(RefundControlState.EXTERNAL_CONFLICT, 1000, 0, 0, 0), 1),
                ErrorCode.EVIDENCE_CONFLICT);
        rejects(
                () -> RefundReservationRules.requireReservable(
                        control(RefundControlState.DISPUTE_BLOCKED, 1000, 0, 0, 0), 1),
                ErrorCode.REFUND_BLOCKED);
    }

    @Test
    void providerReasonsAreTheProvidersVocabulary() {
        assertThat(RefundReservationRules.providerReason("DUPLICATE")).isEqualTo("duplicate");
        assertThat(RefundReservationRules.providerReason("FRAUDULENT")).isEqualTo("fraudulent");
        assertThat(RefundReservationRules.providerReason("REQUESTED_BY_CUSTOMER"))
                .isEqualTo("requested_by_customer");
        assertThat(RefundReservationRules.providerReason("MERCHANT_DECISION")).isNull();
        assertThat(RefundReservationRules.providerReason("OTHER")).isNull();
    }

    @Test
    void unfreezeRequestsAreTestModeWithABoundedAuditKey() {
        var scope = new ProviderScope(new UUID(0, 1), new UUID(0, 2), new UUID(0, 4), false);
        RefundReservationRules.requireUnfreezeRequest(scope, "a".repeat(255));
        rejects(
                () -> RefundReservationRules.requireUnfreezeRequest(
                        new ProviderScope(new UUID(0, 1), new UUID(0, 2), new UUID(0, 4), true), "audit"),
                ErrorCode.VALIDATION_ERROR);
        rejects(() -> RefundReservationRules.requireUnfreezeRequest(scope, null), ErrorCode.VALIDATION_ERROR);
        rejects(() -> RefundReservationRules.requireUnfreezeRequest(scope, " "), ErrorCode.VALIDATION_ERROR);
        rejects(
                () -> RefundReservationRules.requireUnfreezeRequest(scope, "a".repeat(256)),
                ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void unfreezeNeedsSettledMatchedRefundsAtTheExpectedVersion() {
        var frozen = control(RefundControlState.EXTERNAL_CONFLICT, 1000, 600, 0, 4);
        var clean = new UnfreezeEvidence(true, 0, 0, 600);
        assertThat(RefundReservationRules.unfreezable(frozen, 4, clean)).isTrue();
        assertThat(RefundReservationRules.unfreezable(frozen, 3, clean))
                .as("stale version")
                .isFalse();
        assertThat(RefundReservationRules.unfreezable(frozen, 4, new UnfreezeEvidence(false, 0, 0, 600)))
                .isFalse();
        assertThat(RefundReservationRules.unfreezable(frozen, 4, new UnfreezeEvidence(true, 1, 0, 600)))
                .isFalse();
        assertThat(RefundReservationRules.unfreezable(frozen, 4, new UnfreezeEvidence(true, 0, 1, 600)))
                .isFalse();
        assertThat(RefundReservationRules.unfreezable(frozen, 4, new UnfreezeEvidence(true, 0, 0, 500)))
                .as("succeeded refunds must equal the refunded amount")
                .isFalse();
        assertThat(RefundReservationRules.unfreezable(
                        control(RefundControlState.EXTERNAL_CONFLICT, 1000, 600, 10, 4), 4, clean))
                .as("nothing may be reserved")
                .isFalse();
        assertThat(RefundReservationRules.unfreezable(
                        control(RefundControlState.EXTERNAL_CONFLICT, 500, 600, 0, 4), 4, clean))
                .as("never more refunded than captured")
                .isFalse();
        assertThat(RefundReservationRules.unfreezable(control(RefundControlState.NORMAL, 1000, 600, 0, 4), 4, clean))
                .isFalse();
    }

    @Test
    void auditRecordsTheControlBeforeAndAfter() {
        var frozen = control(RefundControlState.EXTERNAL_CONFLICT, 1000, 600, 0, 4);
        assertThat(RefundReservationRules.auditedControl(RefundControlState.NORMAL, frozen, 600, 5))
                .isEqualTo(Map.of(
                        "controlState", "NORMAL",
                        "capturedMinor", 1000L,
                        "refundedMinor", 600L,
                        "reservedMinor", 0,
                        "version", 5L));
    }
}
