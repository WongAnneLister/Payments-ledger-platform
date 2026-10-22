package com.paymentsledger.payment.domain;

import com.paymentsledger.provider.ProviderCallClassification;
import com.paymentsledger.provider.ProviderCallResult;
import com.paymentsledger.provider.ProviderModels.ChargeFact;
import com.paymentsledger.provider.ProviderModels.PaymentIntentFact;
import com.paymentsledger.provider.ProviderScope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure rules for reconciliation source recovery (L3): linking a captured provider balance transaction that the
 * import found to the server-proven order it pays for.
 *
 * <ul>
 *   <li>Only a positive capture BT in the run's currency can be recovered.
 *   <li>The charge and its payment intent, read from the provider, must agree with the BT and with each other on API
 *       version, identity, amount, currency and success; any disagreement is a conflict, never a guess.
 *   <li>The order is found only through an immutable server binding for the payment intent, never through metadata.
 *       The binding and order must match the captured amount and still be eligible for fulfillment.
 *   <li>A payment that already exists for the order must not contradict the capture.
 *   <li>Unreadable evidence leaves the task waiting for another attempt; every other problem escalates it.
 * </ul>
 */
public final class ReconciliationSourceRules {
    private ReconciliationSourceRules() {}

    /** The run a source task belongs to. */
    public record SourceRun(UUID id, String currency, UUID importWindowId) {}

    /** A provider balance transaction as the import recorded it. */
    public record SourceBalanceTransaction(
            UUID id,
            String btId,
            String balanceDomain,
            String businessType,
            String sourceObjectType,
            String sourceObjectId,
            long amountMinor,
            String currency,
            String apiVersion,
            String state) {}

    /** A reconciliation source task and the lease that fences its writes. */
    public record SourceTask(
            UUID id,
            String status,
            UUID leaseToken,
            long version,
            UUID runId,
            UUID sourceBtId,
            UUID organizationId,
            UUID environmentId,
            UUID providerAccountId,
            Boolean livemode,
            Instant leaseUntil) {}

    /** The server's immutable record that a payment intent pays for an order. */
    public record OrderBinding(UUID id, UUID orderId, String currency, long amountMinor, Instant eligibleUntil) {}

    public record RecoverableOrder(
            UUID id,
            UUID organizationId,
            UUID environmentId,
            UUID applicationId,
            UUID providerAccountId,
            String currency,
            long amountMinor) {}

    /** A payment that already exists for the order. */
    public record ExistingPayment(
            UUID id, long amountMinor, String paymentIntentId, String paymentStatus, String resolutionStatus) {}

    /** What a problem does to the task and what recovery reports. */
    public record Disposition(String taskStatus, String outcome) {}

    public static final String READ_UNAVAILABLE = "SOURCE_READ_UNAVAILABLE";
    public static final String EVIDENCE_CONFLICT = "SOURCE_EVIDENCE_CONFLICT";
    public static final String NO_TRUSTED_ORDER = "NO_TRUSTED_ORDER";
    public static final String ORDER_NOT_ELIGIBLE = "ORDER_NOT_ELIGIBLE";

    public static boolean recoverableCapture(SourceBalanceTransaction bt, String runCurrency) {
        return bt != null
                && "PAYMENTS".equals(bt.balanceDomain())
                && "charge".equals(bt.businessType())
                && "charge".equals(bt.sourceObjectType())
                && bt.amountMinor() > 0
                && runCurrency.equals(bt.currency());
    }

    /** A provider read whose payload can be used as evidence: a successful 200 with a version and digest. */
    public static boolean trusted(ProviderCallResult<?> r) {
        if (r == null
                || r.payload() == null
                || r.classification() != ProviderCallClassification.SUCCESS
                || r.evidence() == null) return false;
        var e = r.evidence();
        return Objects.equals(e.httpStatus(), 200)
                && e.apiVersion() != null
                && !e.apiVersion().isBlank()
                && e.rawDigest() != null
                && e.rawDigest().matches("[a-f0-9]{64}")
                && e.respondedAt() != null
                && !e.respondedAt().isBefore(e.requestedAt());
    }

    /** Whether the charge and intent reads prove a succeeded, fully captured payment for this BT. */
    public static boolean provesCapture(
            SourceBalanceTransaction bt,
            ProviderCallResult<ChargeFact> chargeRead,
            ProviderCallResult<PaymentIntentFact> intentRead) {
        ChargeFact charge = chargeRead.payload();
        PaymentIntentFact intent = intentRead.payload();
        return Objects.equals(chargeRead.evidence().apiVersion(), bt.apiVersion())
                && Objects.equals(intentRead.evidence().apiVersion(), bt.apiVersion())
                && Objects.equals(charge.chargeId(), bt.sourceObjectId())
                && Objects.equals(charge.balanceTransactionId(), bt.btId())
                && Objects.equals(charge.paymentIntentId(), intent.paymentIntentId())
                && charge.captured()
                && "succeeded".equals(charge.status())
                && "succeeded".equals(intent.status())
                && charge.amountMinor() == bt.amountMinor()
                && Objects.equals(charge.amountCapturedMinor(), charge.amountMinor())
                && intent.amountMinor() == charge.amountMinor()
                && Objects.equals(intent.amountReceivedMinor(), charge.amountMinor())
                && charge.currency().equalsIgnoreCase(bt.currency())
                && intent.currency().equalsIgnoreCase(charge.currency())
                && Objects.equals(intent.latestChargeId(), charge.chargeId())
                && intent.chargeIds().contains(charge.chargeId())
                && "PAYMENTS".equals(bt.balanceDomain())
                && "charge".equals(bt.businessType())
                && "charge".equals(bt.sourceObjectType())
                && !"QUARANTINED".equals(bt.state())
                && !"EXCLUDED".equals(bt.state());
    }

    /** The BT, re-read under its lock, no longer exists in scope or was quarantined or excluded by the import. */
    public static boolean withdrawn(SourceBalanceTransaction current) {
        return current == null || Set.of("QUARANTINED", "EXCLUDED").contains(current.state());
    }

    /** Binding and order disagree with the BT's currency or the captured amount. */
    public static boolean orderMismatch(
            OrderBinding binding, RecoverableOrder order, ChargeFact charge, String btCurrency) {
        return !Objects.equals(binding.currency(), btCurrency)
                || binding.amountMinor() != charge.amountMinor()
                || order.amountMinor() != charge.amountMinor();
    }

    public static boolean bindingExpired(OrderBinding binding, Instant now) {
        return binding.eligibleUntil() != null && !binding.eligibleUntil().isAfter(now);
    }

    /** An existing payment contradicts the capture: another amount or intent, canceled, or already in conflict. */
    public static boolean paymentContradicts(ExistingPayment payment, ChargeFact charge, PaymentIntentFact intent) {
        return payment.amountMinor() != charge.amountMinor()
                || payment.paymentIntentId() != null
                        && !payment.paymentIntentId().equals(intent.paymentIntentId())
                || "CANCELED".equals(payment.paymentStatus())
                || "CONFLICT".equals(payment.resolutionStatus());
    }

    public static Disposition disposition(String problem) {
        return problem.equals(READ_UNAVAILABLE)
                ? new Disposition("WAITING", "WAITING_EVIDENCE")
                : new Disposition("ESCALATED", "UNALLOCATED");
    }

    /** The claim still holds the task's live lease, for the same run, BT and scope. */
    public static boolean owns(
            SourceTask task, ProviderScope scope, UUID runId, UUID btId, UUID token, long version, Instant now) {
        return task != null
                && "RUNNING".equals(task.status())
                && Objects.equals(task.leaseToken(), token)
                && task.version() == version
                && Objects.equals(task.runId(), runId)
                && Objects.equals(task.sourceBtId(), btId)
                && Objects.equals(task.organizationId(), scope.organizationId())
                && Objects.equals(task.environmentId(), scope.environmentId())
                && Objects.equals(task.providerAccountId(), scope.providerAccountId())
                && Objects.equals(task.livemode(), scope.livemode())
                && task.leaseUntil() != null
                && task.leaseUntil().isAfter(now);
    }

    /** The normalized provider facts an observation records and its digest covers. */
    public static Map<String, Object> observedFact(ChargeFact charge, PaymentIntentFact intent) {
        var fact = new LinkedHashMap<String, Object>();
        if (charge != null) {
            fact.put("chargeId", charge.chargeId());
            fact.put("paymentIntentId", charge.paymentIntentId());
            fact.put("btId", charge.balanceTransactionId());
            fact.put("amountMinor", charge.amountMinor());
            fact.put("capturedAmountMinor", charge.amountCapturedMinor());
            fact.put("currency", charge.currency().toUpperCase(Locale.ROOT));
            fact.put("status", charge.status());
            fact.put("captured", charge.captured());
        }
        if (intent != null) {
            fact.put("intentStatus", intent.status());
            fact.put("intentReceivedMinor", intent.amountReceivedMinor());
        }
        return fact;
    }

    /** What an observation keeps of a provider read: its classification and evidence, never the payload. */
    public static Map<String, Object> readEvidence(ProviderCallResult<?> r) {
        var map = new LinkedHashMap<String, Object>();
        if (r == null) {
            map.put("classification", "MISSING_RESULT");
            return map;
        }
        map.put("classification", r.classification());
        map.put("evidence", r.evidence());
        return map;
    }
}
