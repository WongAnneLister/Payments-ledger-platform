package com.paymentsledger.provider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Project-owned provider contracts. Provider status strings and currency case are preserved as evidence. */
public final class ProviderModels {
    private ProviderModels() {}

    public enum CheckoutSessionStatus { OPEN, COMPLETE, EXPIRED }

    public record CreateCheckoutSessionCommand(
            ProviderScope scope, String operationIdempotencyKey,
            long amountMinor, String currency, String successUrl, String cancelUrl,
            Map<String, String> sessionMetadata, Map<String, String> paymentIntentMetadata,
            Instant expiresAt) {
        public CreateCheckoutSessionCommand {
            Objects.requireNonNull(scope, "scope");
            requireText(operationIdempotencyKey, "operationIdempotencyKey");
            requirePositive(amountMinor, "amountMinor");
            requireCurrency(currency);
            requireText(successUrl, "successUrl");
            requireText(cancelUrl, "cancelUrl");
            sessionMetadata = Map.copyOf(sessionMetadata);
            paymentIntentMetadata = Map.copyOf(paymentIntentMetadata);
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (expiresAt.getNano() != 0)
                throw new IllegalArgumentException("expiresAt must have whole-second precision");
        }
    }

    public record CheckoutSessionFact(
            String sessionId, CheckoutSessionStatus status, String hostedUrl, String paymentIntentId,
            String paymentStatus, Instant expiresAt, Map<String, String> metadata) {
        public CheckoutSessionFact {
            requireText(sessionId, "sessionId");
            Objects.requireNonNull(status, "status");
            metadata = Map.copyOf(metadata);
        }
    }

    public record PaymentIntentFact(
            String paymentIntentId, String status, long amountMinor, Long amountReceivedMinor, String currency,
            List<String> chargeIds, String latestChargeId, String nextActionType,
            Map<String, String> metadata, Instant createdAt) {
        public PaymentIntentFact {
            requireText(paymentIntentId, "paymentIntentId");
            requireCurrency(currency);
            requireNonnegative(amountMinor, "amountMinor");
            if (amountReceivedMinor != null) requireNonnegative(amountReceivedMinor, "amountReceivedMinor");
            chargeIds = List.copyOf(chargeIds);
            metadata = Map.copyOf(metadata);
        }
    }

    public record ChargeFact(
            String chargeId, String paymentIntentId, String status, boolean captured,
            long amountMinor, Long amountCapturedMinor, String currency, String balanceTransactionId,
            String failureCode, String outcomeType, Instant createdAt) {
        public ChargeFact {
            requireText(chargeId, "chargeId");
            requireCurrency(currency);
            requireNonnegative(amountMinor, "amountMinor");
            if (amountCapturedMinor != null) requireNonnegative(amountCapturedMinor, "amountCapturedMinor");
        }
    }

    public record CreateRefundCommand(
            ProviderScope scope, String operationIdempotencyKey, String chargeId, String paymentIntentId,
            long amountMinor, String reason) {
        public CreateRefundCommand {
            Objects.requireNonNull(scope, "scope");
            requireText(operationIdempotencyKey, "operationIdempotencyKey");
            if ((chargeId == null) == (paymentIntentId == null))
                throw new IllegalArgumentException("Exactly one refund target is required");
            if (chargeId != null) requireText(chargeId, "chargeId");
            if (paymentIntentId != null) requireText(paymentIntentId, "paymentIntentId");
            requirePositive(amountMinor, "amountMinor");
        }
    }

    public record RefundFact(
            String refundId, String chargeId, String status, long amountMinor, String currency,
            String balanceTransactionId, String failureReason, String failureBalanceTransactionId, Instant createdAt) {
        public RefundFact {
            requireText(refundId, "refundId");
            requirePositive(amountMinor, "amountMinor");
            requireCurrency(currency);
        }
    }

    public record ListBalanceTransactionsQuery(
            ProviderScope scope, Instant createdGte, Instant createdLt,
            String startingAfterCursor, int limit, String payoutId) {
        public ListBalanceTransactionsQuery {
            Objects.requireNonNull(scope, "scope");
            requireWindow(createdGte, createdLt);
            requirePositive(limit, "limit");
            if (startingAfterCursor != null) requireText(startingAfterCursor, "startingAfterCursor");
        }
    }

    public record FeeDetail(long amountMinor, String currency, String type, String description) {
        public FeeDetail { requireCurrency(currency); }
    }

    public record BalanceTransactionFact(
            String btId, String type, long amountMinor, long feeMinor, long netMinor,
            String currency, String balanceDomain, String sourceObjectId, String sourceObjectType,
            String status, Instant createdAt, Instant availableOn, List<FeeDetail> feeDetails, String rawDigest) {
        public BalanceTransactionFact {
            requireText(btId, "btId");
            requireCurrency(currency);
            requireText(balanceDomain, "balanceDomain");
            try {
                if (Math.subtractExact(amountMinor, feeMinor) != netMinor)
                    throw new IllegalArgumentException("netMinor must equal amountMinor minus feeMinor");
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Balance transaction net overflows minor units", overflow);
            }
            feeDetails = List.copyOf(feeDetails);
        }
    }

    public record BalanceTransactionPage(List<BalanceTransactionFact> items, boolean hasMore, String nextCursor) {
        public BalanceTransactionPage {
            items = List.copyOf(items);
            requireContinuation(hasMore, nextCursor);
        }
    }

    public record DisputeFact(
            String disputeId, String chargeId, String status, String reason, long amountMinor, String currency,
            Instant evidenceDueBy, List<String> balanceTransactionIds) {
        public DisputeFact {
            requireText(disputeId, "disputeId");
            requirePositive(amountMinor, "amountMinor");
            requireCurrency(currency);
            balanceTransactionIds = List.copyOf(balanceTransactionIds);
        }
    }

    public record PayoutFact(
            String payoutId, String status, long amountMinor, String currency,
            String balanceTransactionId, String failureBalanceTransactionId,
            boolean automatic, Instant arrivalDate, String failureCode) {
        public PayoutFact {
            requireText(payoutId, "payoutId");
            requirePositive(amountMinor, "amountMinor");
            requireCurrency(currency);
        }
    }

    /** A current observation, not an inferred historic closing balance. */
    public record BalanceSnapshot(String currency, String balanceDomain, long availableMinor, long pendingMinor,
                                  Instant capturedAt) {
        public BalanceSnapshot {
            requireCurrency(currency);
            requireText(balanceDomain, "balanceDomain");
            Objects.requireNonNull(capturedAt, "capturedAt");
        }
    }

    public record PageQuery(ProviderScope scope, Instant createdGte, Instant createdLt,
                            String startingAfterCursor, int limit) {
        public PageQuery {
            Objects.requireNonNull(scope, "scope");
            requireWindow(createdGte, createdLt);
            requirePositive(limit, "limit");
            if (startingAfterCursor != null) requireText(startingAfterCursor, "startingAfterCursor");
        }
    }

    public record Page<T>(List<T> items, boolean hasMore, String nextCursor) {
        public Page {
            items = List.copyOf(items);
            requireContinuation(hasMore, nextCursor);
        }
    }

    public record ReportRequest(ProviderScope scope, String reportType, Instant createdGte, Instant createdLt) {
        public ReportRequest {
            Objects.requireNonNull(scope, "scope");
            requireText(reportType, "reportType");
            requireWindow(createdGte, createdLt);
        }
    }

    public record ReportFact(String reportId, String status, Instant createdGte, Instant createdLt,
                             List<BalanceSnapshot> balances, String rawDigest) {
        public ReportFact {
            requireText(reportId, "reportId");
            requireWindow(createdGte, createdLt);
            balances = List.copyOf(balances);
        }
    }

    public record VerifiedWebhook(String eventId, String eventType, String objectId, Instant createdAt, boolean livemode) {
        public VerifiedWebhook {
            requireText(eventId, "eventId");
            requireText(eventType, "eventType");
            requireText(objectId, "objectId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private static void requireCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Za-z]{3}"))
            throw new IllegalArgumentException("currency must contain three alphabetic letters");
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    private static void requireNonnegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must not be negative");
    }

    private static void requireWindow(Instant from, Instant until) {
        Objects.requireNonNull(from, "createdGte");
        Objects.requireNonNull(until, "createdLt");
        if (!from.isBefore(until)) throw new IllegalArgumentException("createdGte must precede createdLt");
    }

    private static void requireContinuation(boolean hasMore, String nextCursor) {
        if (hasMore) requireText(nextCursor, "nextCursor for an incomplete page");
    }
}
