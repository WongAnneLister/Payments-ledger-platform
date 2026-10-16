package com.paymentsledger.payment.domain;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.payment.domain.RefundFactRules.Merge;
import com.paymentsledger.payment.domain.RefundFactRules.Settlement;
import com.paymentsledger.provider.ProviderCallClassification;
import com.paymentsledger.provider.ProviderModels.RefundFact;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundFactRulesTest {
    static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");

    static LocalRefund refund(String source, String status, ResolutionStatus resolution, String providerId, String bt) {
        return new LocalRefund(
                new UUID(0, 1),
                new UUID(0, 2),
                new UUID(0, 3),
                new UUID(0, 4),
                new UUID(0, 5),
                new UUID(0, 6),
                "USD",
                600,
                source,
                status,
                resolution,
                status.toLowerCase(),
                providerId,
                "ch_1",
                bt,
                null,
                null);
    }

    static LocalRefund reserved() {
        return refund("LOCAL", "RESERVED", ResolutionStatus.KNOWN, null, null);
    }

    static RefundFact fact(String status, long amount, String bt) {
        return new RefundFact("re_1", "ch_1", status, amount, "usd", bt, null, null, NOW);
    }

    @Test
    void contradictionsCoverMoneyChargeAndIdentities() {
        assertThat(RefundFactRules.contradicts(reserved(), fact("pending", 600, null)))
                .isFalse();
        assertThat(RefundFactRules.contradicts(reserved(), fact("pending", 500, null)))
                .isTrue();
        assertThat(RefundFactRules.contradicts(
                        reserved(), new RefundFact("re_1", "ch_1", "pending", 600, "EUR", null, null, null, NOW)))
                .isTrue();
        assertThat(RefundFactRules.contradicts(
                        reserved(), new RefundFact("re_1", "ch_2", "pending", 600, "USD", null, null, null, NOW)))
                .isTrue();
        var bound = refund("LOCAL", "PENDING", ResolutionStatus.KNOWN, "re_other", "txn_a");
        assertThat(RefundFactRules.contradicts(bound, fact("pending", 600, null)))
                .as("another provider id")
                .isTrue();
        var withBt = refund("LOCAL", "PENDING", ResolutionStatus.KNOWN, "re_1", "txn_a");
        assertThat(RefundFactRules.contradicts(withBt, fact("succeeded", 600, "txn_b")))
                .isTrue();
        assertThat(RefundFactRules.contradicts(withBt, fact("succeeded", 600, null)))
                .as("an older fact without the transaction")
                .isFalse();
        assertThat(RefundFactRules.contradicts(withBt, fact("succeeded", 600, "txn_a")))
                .isFalse();
    }

    @Test
    void nonTerminalFactsAdvanceTheStatus() {
        assertThat(RefundFactRules.merge(reserved(), fact("pending", 600, null)))
                .isEqualTo(new Merge(false, "PENDING", true, "pending", null));
        assertThat(RefundFactRules.merge(reserved(), fact("requires_action", 600, null)))
                .isEqualTo(new Merge(false, "REQUIRES_ACTION", true, "requires_action", null));
        assertThat(RefundFactRules.merge(reserved(), fact("mystery", 600, null)))
                .as("an unknown status keeps the current one and is not trusted")
                .isEqualTo(new Merge(false, "RESERVED", false, "mystery", null));
    }

    @Test
    void reachingATerminalStatusSettlesTheReservationOnce() {
        assertThat(RefundFactRules.merge(reserved(), fact("succeeded", 600, null))
                        .settlement())
                .isEqualTo(new Settlement(600, 600));
        assertThat(RefundFactRules.merge(reserved(), fact("failed", 600, null)).settlement())
                .isEqualTo(new Settlement(600, 0));
        assertThat(RefundFactRules.merge(reserved(), fact("canceled", 600, null))
                        .settlement())
                .isEqualTo(new Settlement(600, 0));
        var external = refund("EXTERNAL", "PENDING", ResolutionStatus.KNOWN, "re_1", null);
        assertThat(RefundFactRules.merge(external, fact("succeeded", 600, null)).settlement())
                .as("an external refund held nothing")
                .isEqualTo(new Settlement(0, 600));
    }

    @Test
    void terminalStatusIsFinal() {
        var succeeded = refund("LOCAL", "SUCCEEDED", ResolutionStatus.KNOWN, "re_1", null);
        assertThat(RefundFactRules.merge(succeeded, fact("succeeded", 600, "txn_1")))
                .isEqualTo(new Merge(false, "SUCCEEDED", true, "succeeded", null));
        assertThat(RefundFactRules.merge(succeeded, fact("pending", 600, null)))
                .as("an older pending fact neither regresses nor settles again")
                .isEqualTo(new Merge(false, "SUCCEEDED", true, "succeeded", null));
        assertThat(RefundFactRules.merge(succeeded, fact("failed", 600, null)).conflict())
                .as("another terminal status is a contradiction")
                .isTrue();
    }

    @Test
    void conflictsAreStickyAndApplyNothing() {
        var conflicted = refund("LOCAL", "PENDING", ResolutionStatus.CONFLICT, "re_1", null);
        assertThat(RefundFactRules.merge(conflicted, fact("succeeded", 600, null)))
                .isEqualTo(new Merge(true, "PENDING", false, null, null));
        assertThat(RefundFactRules.merge(reserved(), fact("succeeded", 500, null))
                        .conflict())
                .isTrue();
    }

    @Test
    void bindingBelongsToOneLocalRefund() {
        var a = reserved();
        var b = new LocalRefund(
                new UUID(0, 9),
                a.paymentId(),
                a.organizationId(),
                a.environmentId(),
                a.applicationId(),
                a.providerAccountId(),
                "USD",
                600,
                "LOCAL",
                "RESERVED",
                ResolutionStatus.KNOWN,
                null,
                null,
                "ch_1",
                null,
                null,
                null);
        assertThat(RefundFactRules.boundElsewhere(a, b)).isTrue();
        assertThat(RefundFactRules.boundElsewhere(a, a)).isFalse();
        assertThat(RefundFactRules.boundElsewhere(null, a)).isFalse();
        assertThat(RefundFactRules.boundElsewhere(a, null)).isFalse();
    }

    @Test
    void onlyAnUnanchoredRejectedCreateReleasesTheHold() {
        var reject = ProviderCallClassification.DEFINITIVE_REJECT;
        assertThat(RefundFactRules.rejectionReleasesHold(false, reject, reserved()))
                .isTrue();
        assertThat(RefundFactRules.rejectionReleasesHold(true, reject, reserved()))
                .as("a query")
                .isFalse();
        assertThat(RefundFactRules.rejectionReleasesHold(false, ProviderCallClassification.TIMEOUT_UNKNOWN, reserved()))
                .isFalse();
        assertThat(RefundFactRules.rejectionReleasesHold(
                        false, reject, refund("LOCAL", "SUBMITTED", ResolutionStatus.KNOWN, "re_1", null)))
                .as("known to the provider")
                .isFalse();
        assertThat(RefundFactRules.rejectionReleasesHold(
                        false, reject, refund("LOCAL", "FAILED", ResolutionStatus.KNOWN, null, null)))
                .isFalse();
    }

    @Test
    void onlyUnsentCertainReservationsUnderANormalControlAreSent() {
        assertThat(RefundFactRules.sendable(reserved(), RefundControlState.NORMAL))
                .isTrue();
        assertThat(RefundFactRules.sendable(reserved(), RefundControlState.EXTERNAL_CONFLICT))
                .isFalse();
        assertThat(RefundFactRules.sendable(reserved(), RefundControlState.DISPUTE_BLOCKED))
                .isFalse();
        assertThat(RefundFactRules.sendable(
                        refund("LOCAL", "SUBMITTED", ResolutionStatus.KNOWN, "re_1", null), RefundControlState.NORMAL))
                .isFalse();
        assertThat(RefundFactRules.sendable(
                        refund("LOCAL", "CANCELED", ResolutionStatus.KNOWN, null, null), RefundControlState.NORMAL))
                .isFalse();
        assertThat(RefundFactRules.sendable(
                        refund("LOCAL", "RESERVED", ResolutionStatus.CONFLICT, null, null), RefundControlState.NORMAL))
                .isFalse();
        assertThat(RefundFactRules.sendable(
                        refund("LOCAL", "SUBMITTED", ResolutionStatus.UNKNOWN, null, null), RefundControlState.NORMAL))
                .as("a retry after a timeout")
                .isTrue();
    }
}
