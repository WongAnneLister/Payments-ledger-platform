package com.paymentsledger.payment.domain;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.provider.ProviderCallClassification;
import com.paymentsledger.provider.ProviderCallResult;
import com.paymentsledger.provider.ProviderEvidence;
import com.paymentsledger.provider.ProviderModels.CheckoutSessionFact;
import com.paymentsledger.provider.ProviderModels.CheckoutSessionStatus;
import com.paymentsledger.provider.ProviderModels.PaymentIntentFact;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderResultRulesTest {
    static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");
    static final UUID PAYMENT = new UUID(0, 9);
    static final ProviderEvidence EVIDENCE = new ProviderEvidence("req", "key", NOW, NOW, 200, null, null, "v1");

    static CheckoutSessionFact session(UUID payment, CheckoutSessionStatus status, String hostedUrl, Instant expires) {
        return new CheckoutSessionFact(
                "cs_1", status, hostedUrl, "pi_1", "unpaid", expires, Map.of("payment_id", payment.toString()));
    }

    static ProviderCallResult<CheckoutSessionFact> success(CheckoutSessionFact fact) {
        return new ProviderCallResult<>(ProviderCallClassification.SUCCESS, fact, EVIDENCE);
    }

    static ProviderCallResult<CheckoutSessionFact> failure(ProviderCallClassification classification) {
        return new ProviderCallResult<>(classification, null, EVIDENCE);
    }

    static PaymentProgress progress(ResolutionStatus resolution, String session, String intent, long captured) {
        return new PaymentProgress(3, PaymentStatus.PROCESSING, null, resolution, session, intent, captured, null);
    }

    static PaymentFacts facts(boolean complete, boolean conflict) {
        return new PaymentFacts(null, null, List.of(), complete, conflict, NOW);
    }

    @Test
    void onlySuccessOrAuthenticationRequiredResultsCarryFacts() {
        var fact = session(PAYMENT, CheckoutSessionStatus.OPEN, "https://p", NOW);
        assertThat(ProviderResultRules.carriesFact(success(fact))).isTrue();
        assertThat(ProviderResultRules.carriesFact(
                        new ProviderCallResult<>(ProviderCallClassification.AUTH_REQUIRED, fact, EVIDENCE)))
                .isTrue();
        assertThat(ProviderResultRules.carriesFact(
                        new ProviderCallResult<>(ProviderCallClassification.SUCCESS, null, EVIDENCE)))
                .isFalse();
        assertThat(ProviderResultRules.carriesFact(failure(ProviderCallClassification.TIMEOUT_UNKNOWN)))
                .isFalse();
    }

    @Test
    void usableSessionNamesThisPaymentExpiresAndHasAUrlWhileOpen() {
        assertThat(ProviderResultRules.usableSession(
                        success(session(PAYMENT, CheckoutSessionStatus.OPEN, "https://p", NOW)), PAYMENT))
                .isTrue();
        assertThat(ProviderResultRules.usableSession(
                        success(session(PAYMENT, CheckoutSessionStatus.COMPLETE, null, NOW)), PAYMENT))
                .as("a closed session needs no URL")
                .isTrue();
        assertThat(ProviderResultRules.usableSession(
                        success(session(new UUID(0, 1), CheckoutSessionStatus.OPEN, "https://p", NOW)), PAYMENT))
                .isFalse();
        assertThat(ProviderResultRules.usableSession(
                        success(session(PAYMENT, CheckoutSessionStatus.OPEN, " ", NOW)), PAYMENT))
                .isFalse();
        assertThat(ProviderResultRules.usableSession(
                        success(session(PAYMENT, CheckoutSessionStatus.OPEN, "https://p", null)), PAYMENT))
                .isFalse();
        assertThat(ProviderResultRules.usableSession(failure(ProviderCallClassification.TIMEOUT_UNKNOWN), PAYMENT))
                .isFalse();
    }

    @Test
    void unusableResultsConflictOnlyWhenTheProviderAnsweredUnacceptably() {
        assertThat(ProviderResultRules.unusableResultConflicts(failure(ProviderCallClassification.TIMEOUT_UNKNOWN)))
                .isFalse();
        assertThat(ProviderResultRules.unusableResultConflicts(failure(ProviderCallClassification.RATE_LIMITED)))
                .isFalse();
        assertThat(ProviderResultRules.unusableResultConflicts(failure(ProviderCallClassification.CONTRACT_MISMATCH)))
                .isTrue();
        assertThat(ProviderResultRules.unusableResultConflicts(
                        failure(ProviderCallClassification.IDEMPOTENCY_CONFLICT)))
                .isTrue();
        assertThat(ProviderResultRules.unusableResultConflicts(
                        success(session(new UUID(0, 1), CheckoutSessionStatus.OPEN, "https://p", NOW))))
                .as("a fact we cannot accept")
                .isTrue();
    }

    @Test
    void rejectionCancelsOnlyWithoutRemoteTrace() {
        assertThat(ProviderResultRules.rejectLeftRemoteTrace(progress(ResolutionStatus.KNOWN, null, null, 0)))
                .isFalse();
        assertThat(ProviderResultRules.rejectLeftRemoteTrace(progress(ResolutionStatus.KNOWN, "cs", null, 0)))
                .isTrue();
        assertThat(ProviderResultRules.rejectLeftRemoteTrace(progress(ResolutionStatus.KNOWN, null, "pi", 0)))
                .isTrue();
        assertThat(ProviderResultRules.rejectLeftRemoteTrace(progress(ResolutionStatus.KNOWN, null, null, 1)))
                .isTrue();
    }

    @Test
    void aPaymentIsNeverRebound() {
        var fact = session(PAYMENT, CheckoutSessionStatus.OPEN, "https://p", NOW);
        assertThat(ProviderResultRules.contradictsAnchors(progress(ResolutionStatus.KNOWN, null, null, 0), fact))
                .isFalse();
        assertThat(ProviderResultRules.contradictsAnchors(progress(ResolutionStatus.KNOWN, "cs_1", "pi_1", 0), fact))
                .isFalse();
        assertThat(ProviderResultRules.contradictsAnchors(progress(ResolutionStatus.KNOWN, "cs_2", null, 0), fact))
                .isTrue();
        assertThat(ProviderResultRules.contradictsAnchors(progress(ResolutionStatus.KNOWN, null, "pi_2", 0), fact))
                .isTrue();
        var noIntent =
                new CheckoutSessionFact("cs_1", CheckoutSessionStatus.OPEN, "https://p", null, null, NOW, Map.of());
        assertThat(ProviderResultRules.contradictsAnchors(progress(ResolutionStatus.KNOWN, null, "pi_2", 0), noIntent))
                .as("a session without an intent does not contradict one")
                .isFalse();
    }

    @Test
    void recordedSessionKeepsTerminalStatuses() {
        var open = session(PAYMENT, CheckoutSessionStatus.OPEN, "https://p", NOW);
        var complete = session(PAYMENT, CheckoutSessionStatus.COMPLETE, null, NOW);
        assertThat(ProviderResultRules.statusAfterSession(open, PaymentStatus.CREATED))
                .isEqualTo(PaymentStatus.AWAITING_CUSTOMER);
        assertThat(ProviderResultRules.statusAfterSession(complete, PaymentStatus.AWAITING_CUSTOMER))
                .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(ProviderResultRules.statusAfterSession(open, PaymentStatus.SUCCEEDED))
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(ProviderResultRules.statusAfterSession(open, PaymentStatus.CANCELED))
                .isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void cancelResolvesOnlyWithCompleteConsistentEvidence() {
        var clean = progress(ResolutionStatus.UNKNOWN, "cs", null, 0);
        assertThat(ProviderResultRules.cancelOutcome(true, true, clean, facts(true, false)))
                .isEqualTo(new ProviderResultRules.CancelOutcome(true, false, "RESOLVED", "KNOWN"));
        assertThat(ProviderResultRules.cancelOutcome(true, true, clean, facts(false, false)))
                .isEqualTo(new ProviderResultRules.CancelOutcome(false, false, "UNKNOWN", "UNKNOWN"));
        assertThat(ProviderResultRules.cancelOutcome(true, false, clean, facts(true, true)))
                .isEqualTo(new ProviderResultRules.CancelOutcome(false, false, "UNKNOWN", "CONFLICT"));

        var captured = progress(ResolutionStatus.KNOWN, "cs", "pi", 100);
        assertThat(ProviderResultRules.cancelOutcome(true, true, captured, facts(true, false)))
                .as("money moved: the cancel is a conflict, though the capture itself is known")
                .isEqualTo(new ProviderResultRules.CancelOutcome(false, true, "RESOLVED", "KNOWN"));
        assertThat(ProviderResultRules.cancelOutcome(false, true, captured, facts(true, false)))
                .as("a refused cancel of a captured payment is settled")
                .isEqualTo(new ProviderResultRules.CancelOutcome(false, false, "RESOLVED", "KNOWN"));
        assertThat(ProviderResultRules.cancelOutcome(false, true, clean, facts(true, false)))
                .isEqualTo(new ProviderResultRules.CancelOutcome(false, false, "UNKNOWN", "UNKNOWN"));
    }

    @Test
    void queriesAndLocalCancels() {
        assertThat(ProviderResultRules.isQuery("QUERY_SESSION")).isTrue();
        assertThat(ProviderResultRules.isQuery("LIST_CHARGE_ATTEMPTS")).isTrue();
        assertThat(ProviderResultRules.isQuery("EXPIRE_SESSION")).isFalse();
        assertThat(ProviderResultRules.queryResolves(true, facts(true, false))).isTrue();
        assertThat(ProviderResultRules.queryResolves(true, facts(false, false))).isFalse();
        assertThat(ProviderResultRules.queryResolves(true, facts(true, true))).isFalse();
        assertThat(ProviderResultRules.queryResolves(false, facts(true, false))).isFalse();

        assertThat(ProviderResultRules.cancelableLocally(progress(ResolutionStatus.KNOWN, "cs", null, 0)))
                .as("a session alone is not a remote payment")
                .isTrue();
        assertThat(ProviderResultRules.cancelableLocally(progress(ResolutionStatus.CONFLICT, null, null, 0)))
                .isFalse();
        assertThat(ProviderResultRules.cancelableLocally(progress(ResolutionStatus.KNOWN, null, "pi", 0)))
                .isFalse();
        assertThat(ProviderResultRules.cancelableLocally(progress(ResolutionStatus.KNOWN, null, null, 5)))
                .isFalse();
    }

    @Test
    void recoveryQueriesExactlyOneAnchor() {
        assertThat(ProviderResultRules.sessionAnchor(null, null, List.of())).isNull();
        assertThat(ProviderResultRules.sessionAnchor("cs_1", "cs_1", List.of("cs_1")))
                .isEqualTo("cs_1");
        assertThat(ProviderResultRules.sessionAnchor(null, "pi_1", List.of()))
                .as("only session ids are session anchors")
                .isNull();
        assertThat(ProviderResultRules.sessionAnchor("cs_1", null, List.of("cs_2")))
                .isEqualTo(ProviderResultRules.CONFLICTING_SESSION_ANCHORS);

        assertThat(ProviderResultRules.refundAnchor(null, "re_1", List.of())).isEqualTo("re_1");
        assertThat(ProviderResultRules.refundAnchor("re_1", "re_2", List.of()))
                .isEqualTo(ProviderResultRules.CONFLICTING_REFUND_ANCHORS);
        assertThat(ProviderResultRules.refundAnchor(null, null, List.of())).isNull();
    }

    @Test
    void ageLimitAppliesToUncertainPaymentsOnly() {
        Duration hour = Duration.ofHours(1);
        Instant since = NOW.minus(hour);
        assertThat(ProviderResultRules.ageLimitReached(ResolutionStatus.UNKNOWN, since, hour, NOW))
                .isTrue();
        assertThat(ProviderResultRules.ageLimitReached(ResolutionStatus.CONFLICT, since, hour, NOW))
                .isTrue();
        assertThat(ProviderResultRules.ageLimitReached(ResolutionStatus.UNKNOWN, since.plusSeconds(1), hour, NOW))
                .isFalse();
        assertThat(ProviderResultRules.ageLimitReached(ResolutionStatus.KNOWN, since, hour, NOW))
                .isFalse();
        assertThat(ProviderResultRules.ageLimitReached(ResolutionStatus.UNKNOWN, null, hour, NOW))
                .isFalse();
    }

    @Test
    void redactionKeepsOnlyProjectOwnedFields() {
        var session = new CheckoutSessionFact(
                "cs_1", CheckoutSessionStatus.OPEN, "https://secret", null, null, NOW, Map.of("k", "v"));
        assertThat(ProviderResultRules.redacted(session))
                .isEqualTo(Map.of(
                        "sessionId",
                        "cs_1",
                        "status",
                        CheckoutSessionStatus.OPEN,
                        "paymentStatus",
                        "",
                        "paymentIntentId",
                        ""));
        var intent = new PaymentIntentFact(
                "pi_1", "succeeded", 100, 100L, "USD", List.of("ch_1"), "ch_1", null, Map.of("k", "v"), NOW);
        assertThat(List.copyOf(((Map<?, ?>) ProviderResultRules.redacted(intent)).keySet()))
                .isEqualTo(List.of(
                        "paymentIntentId", "status", "amountMinor", "currency", "amountReceivedMinor", "chargeIds"));
        assertThat(ProviderResultRules.redacted(List.of(session)))
                .isEqualTo(List.of(ProviderResultRules.redacted(session)));
        assertThat(ProviderResultRules.redacted(null)).isNull();
        assertThatThrownBy(() -> ProviderResultRules.redacted("raw")).isInstanceOf(IllegalArgumentException.class);
    }
}
