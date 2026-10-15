package com.paymentsledger.payment.domain;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.payment.domain.PaymentViewRules.NextAction;
import com.paymentsledger.payment.domain.PaymentViewRules.RecoverySummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentViewRulesTest {
    static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");

    static PaymentRecord payment(PaymentStatus status, ResolutionStatus resolution, String hostedUrl, Instant expires) {
        return new PaymentRecord(
                new UUID(0, 9),
                new UUID(0, 10),
                "order",
                10000,
                "USD",
                status,
                "open",
                resolution,
                FinancialStatus.NOT_APPLICABLE,
                0,
                "cs",
                "pi",
                null,
                hostedUrl,
                expires,
                NOW,
                NOW,
                null);
    }

    @Test
    void redirectOnlyWhileTheCheckoutIsPayable() {
        Instant later = NOW.plusSeconds(60);
        assertThat(PaymentViewRules.nextAction(
                        payment(PaymentStatus.AWAITING_CUSTOMER, ResolutionStatus.KNOWN, "https://pay.test", later),
                        NOW))
                .isEqualTo(new NextAction("REDIRECT_TO_HOSTED_CHECKOUT", "https://pay.test", later));
        assertThat(PaymentViewRules.nextAction(
                                payment(
                                        PaymentStatus.REQUIRES_ACTION,
                                        ResolutionStatus.KNOWN,
                                        "https://pay.test",
                                        later),
                                NOW)
                        .type())
                .isEqualTo("REDIRECT_TO_HOSTED_CHECKOUT");
        for (var waiting : List.of(
                payment(PaymentStatus.AWAITING_CUSTOMER, ResolutionStatus.UNKNOWN, "https://pay.test", later),
                payment(PaymentStatus.AWAITING_CUSTOMER, ResolutionStatus.KNOWN, "https://pay.test", NOW),
                payment(PaymentStatus.AWAITING_CUSTOMER, ResolutionStatus.KNOWN, null, later),
                payment(PaymentStatus.PROCESSING, ResolutionStatus.KNOWN, "https://pay.test", later),
                payment(PaymentStatus.SUCCEEDED, ResolutionStatus.CONFLICT, null, later)))
            assertThat(PaymentViewRules.nextAction(waiting, NOW))
                    .isEqualTo(new NextAction("WAIT_FOR_CONFIRMATION", null, null));
        for (var settled : List.of(PaymentStatus.SUCCEEDED, PaymentStatus.CANCELED))
            assertThat(PaymentViewRules.nextAction(payment(settled, ResolutionStatus.KNOWN, null, later), NOW))
                    .isEqualTo(new NextAction("NONE", null, null));
    }

    static RecoveryTaskView task(String status, int attempts, Instant leaseUntil, String reason, Instant queried) {
        return new RecoveryTaskView(status, attempts, NOW.plusSeconds(300), leaseUntil, reason, queried);
    }

    @Test
    void recoverySummaryUsesTheMostUrgentTaskAndTotalsAllOfThem() {
        assertThat(PaymentViewRules.recoverySummary(List.of(), NOW))
                .isEqualTo(new RecoverySummary("IDLE", null, 0, null, null));
        Instant earlier = NOW.minusSeconds(60), latest = NOW.minusSeconds(10);
        var summary = PaymentViewRules.recoverySummary(
                List.of(
                        task("ESCALATED", 3, null, "IDEMPOTENCY_WINDOW_EXPIRED", earlier),
                        task("WAITING", 2, null, null, latest)),
                NOW);
        assertThat(summary).isEqualTo(new RecoverySummary("ESCALATED", null, 5, latest, "IDEMPOTENCY_WINDOW_EXPIRED"));
    }

    @Test
    void anExpiredLeaseIsReportedAsWaiting() {
        assertThat(PaymentViewRules.recoverySummary(List.of(task("RUNNING", 1, NOW.plusSeconds(1), null, null)), NOW)
                        .status())
                .isEqualTo("RUNNING");
        for (Instant lease : new Instant[] {NOW, NOW.minusSeconds(1), null}) {
            var summary = PaymentViewRules.recoverySummary(List.of(task("RUNNING", 1, lease, null, null)), NOW);
            assertThat(summary.status()).isEqualTo("WAITING");
            assertThat(summary.nextAttemptAt()).isEqualTo(NOW.plusSeconds(300));
        }
        assertThat(PaymentViewRules.recoverySummary(List.of(task("READY", 0, null, null, null)), NOW)
                        .status())
                .isEqualTo("WAITING");
        assertThat(PaymentViewRules.recoverySummary(List.of(task("DONE", 1, null, "x", null)), NOW))
                .isEqualTo(new RecoverySummary("IDLE", null, 1, null, "x"));
    }
}
