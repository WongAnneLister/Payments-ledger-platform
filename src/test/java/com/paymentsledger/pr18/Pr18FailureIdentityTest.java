package com.paymentsledger.pr18;

import static org.assertj.core.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Pr18FailureIdentityTest {
    @Test
    void realNewlinePrefixedEqualityAndMembershipFailuresStayDistinct() {
        var equality = equalityFailure();
        var membership = membershipFailure("refund");
        assertThat(equality.getMessage()).startsWith("\n");
        assertThat(membership.getMessage()).startsWith("\n");
        assertThat(Pr18FailureIdentity.of(equality)).isNotEqualTo(Pr18FailureIdentity.of(membership));
    }

    @Test
    void differentFailuresAtTheSameAssertionSiteDoNotCollapseToBlankOrFirstLine() {
        var refund = membershipFailure("refund");
        var payout = membershipFailure("payout");
        assertThat(Pr18FailureIdentity.of(refund)).isNotEqualTo(Pr18FailureIdentity.of(payout));
    }

    @Test
    void sameAssertionReplaysWithOnlySeedAndUuidNoiseRemainTheSameFailure() {
        var first = seedUuidFailure(180018, UUID.fromString("11111111-1111-1111-1111-111111111111"));
        var replayed = seedUuidFailure(-815872335101841148L, UUID.fromString("22222222-2222-2222-2222-222222222222"));
        assertThat(Pr18FailureIdentity.of(first)).isEqualTo(Pr18FailureIdentity.of(replayed));
    }

    private AssertionError equalityFailure() {
        try {
            assertThat("actual").isEqualTo("expected");
            throw new IllegalStateException("assertion did not fail");
        } catch (AssertionError failure) {
            return failure;
        }
    }

    private AssertionError membershipFailure(String missing) {
        try {
            assertThat(Set.of("capture")).contains(missing);
            throw new IllegalStateException("assertion did not fail");
        } catch (AssertionError failure) {
            return failure;
        }
    }

    private AssertionError seedUuidFailure(long seed, UUID identity) {
        try {
            assertThat("actual seed=" + seed + " id=" + identity)
                    .as("I04 seed=" + seed + " id=" + identity)
                    .isEqualTo("expected seed=" + seed + " id=" + identity);
            throw new IllegalStateException("assertion did not fail");
        } catch (AssertionError failure) {
            return failure;
        }
    }
}
