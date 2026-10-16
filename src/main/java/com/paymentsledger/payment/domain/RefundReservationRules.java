package com.paymentsledger.payment.domain;

import static com.paymentsledger.payment.domain.PaymentIntentRules.rejected;

import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.provider.ProviderScope;
import java.util.Map;

/**
 * Pure rules for reserving refunds and for the audited unfreeze of a payment's refund control.
 *
 * <ul>
 *   <li>A refund is reserved only against a captured, certain payment with a known charge, in the caller's scope and
 *       currency, while its control is NORMAL and the amount fits what is still available.
 *   <li>An unfreeze is allowed only when every local refund is settled, every provider refund for the payment matches
 *       a local one, nothing is reserved, and succeeded refunds equal the refunded amount.
 * </ul>
 */
public final class RefundReservationRules {
    private RefundReservationRules() {}

    /** The advisory lock serializing one kind of request key within an application. */
    public static String lockKey(TenantContext tenant, String kind, String key) {
        return tenant.organizationId() + ":" + tenant.environmentId() + ":" + tenant.applicationId() + ":" + kind + ":"
                + key;
    }

    /** A replay must repeat the original request exactly. */
    public static void requireSameRequest(String storedFingerprint, String fingerprint) {
        if (!fingerprint.equals(storedFingerprint)) throw rejected(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    /** {@code payment} is null when it is not in the caller's provider account. */
    public static void requireRefundable(TenantContext tenant, RefundablePayment payment, String currency) {
        if (payment == null || !tenant.allowedCurrencies().contains(payment.currency()))
            throw rejected(ErrorCode.NOT_FOUND);
        if (!currency.equals(payment.currency())) throw rejected(ErrorCode.CURRENCY_NOT_ENABLED);
        if (payment.resolution() == ResolutionStatus.CONFLICT) throw rejected(ErrorCode.EVIDENCE_CONFLICT);
        if (payment.capturedMinor() <= 0 || payment.latestChargeId() == null) throw rejected(ErrorCode.REFUND_BLOCKED);
    }

    public static void requireReservable(RefundControl control, long amountMinor) {
        if (control.state() != RefundControlState.NORMAL)
            throw rejected(
                    control.state() == RefundControlState.EXTERNAL_CONFLICT
                            ? ErrorCode.EVIDENCE_CONFLICT
                            : ErrorCode.REFUND_BLOCKED);
        if (amountMinor > control.availableMinor()) throw rejected(ErrorCode.REFUND_LIMIT_EXCEEDED);
    }

    /** The provider's refund reason; reasons the provider does not know are sent as none. */
    public static String providerReason(String reason) {
        return switch (reason) {
            case "DUPLICATE" -> "duplicate";
            case "FRAUDULENT" -> "fraudulent";
            case "REQUESTED_BY_CUSTOMER" -> "requested_by_customer";
            default -> null;
        };
    }

    /** Unfreezing is an audited test-mode operation with a bounded audit key. */
    public static void requireUnfreezeRequest(ProviderScope scope, String auditKey) {
        if (scope.livemode() || auditKey == null || auditKey.isBlank() || auditKey.length() > 255)
            throw rejected(ErrorCode.VALIDATION_ERROR);
    }

    /** What the unfreeze found when it rechecked the payment's refunds under lock. */
    public record UnfreezeEvidence(
            boolean observationFound, long unsettledRefunds, long unmatchedObservations, long succeededMinor) {}

    public static boolean unfreezable(RefundControl control, long expectedVersion, UnfreezeEvidence evidence) {
        return evidence.observationFound()
                && control.version() == expectedVersion
                && control.state() == RefundControlState.EXTERNAL_CONFLICT
                && evidence.unsettledRefunds() == 0
                && evidence.unmatchedObservations() == 0
                && control.reservedMinor() == 0
                && evidence.succeededMinor() == control.refundedMinor()
                && evidence.succeededMinor() <= control.capturedMinor();
    }

    /** The control as recorded in the audit before and after an unfreeze. */
    public static Map<String, Object> auditedControl(
            RefundControlState state, RefundControl control, long succeededMinor, long version) {
        return Map.of(
                "controlState",
                state.name(),
                "capturedMinor",
                control.capturedMinor(),
                "refundedMinor",
                succeededMinor,
                "reservedMinor",
                0,
                "version",
                version);
    }
}
