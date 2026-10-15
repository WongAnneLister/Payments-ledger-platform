package com.paymentsledger.pr18;

import java.util.*;

/** Independent executable specification. It consumes commands, never database rows or production math. */
public final class SequenceOracle {
    public final PaymentModel payment = new PaymentModel();
    public final RefundModel refunds = new RefundModel(10000);
    public final RevenueShareModel shares = new RevenueShareModel(2000);
    public final LedgerModel ledger = new LedgerModel();
    public final PayoutModel payout = new PayoutModel();
    private long effective;
    private int generation;

    public void apply(SequenceCommand c) {
        switch (c.kind()) {
            case PAYMENT_UNKNOWN -> payment.known(false);
            case PAYMENT_RECOVER -> payment.known(true);
            case CAPTURE -> payment.capture(10000);
            case POST_CAPTURE -> {
                payment.posted();
                ledger.post("capture", Map.of("PSP_FUNDS", 9700L, "PSP_FEE_EXPENSE", 300L, "SALES_REVENUE", -10000L));
            }
            case RESERVE -> {
                if (!refunds.reserve(c.key(), c.amount(), payment.blocked()))
                    throw new IllegalArgumentException("Illegal generated reservation");
            }
            case REFUND_UNKNOWN -> refunds.observe(c.key(), "unknown");
            case REFUND_FAILED -> refunds.observe(c.key(), "failed");
            case REFUND_STALE -> refunds.observe(c.key(), "pending");
            case REFUND_SUCCEEDED -> refunds.observe(c.key(), "succeeded");
            case POST_REFUND -> ledger.post("refund", Map.of("PSP_FUNDS", -c.amount(), "SALES_RETURNS", c.amount()));
            case DISPUTE_WARNING, DISPUTE_LOST, DISPUTE_WON -> payment.blocked(true);
            case DISPUTE_CLOSED -> payment.blocked(false);
            case DISPUTE_DEBIT -> {
                ledger.post(
                        "dispute-debit",
                        Map.of("PSP_FUNDS", -4500L, "DISPUTE_RECEIVABLE", 3000L, "DISPUTE_FEE_EXPENSE", 1500L));
                ledger.post("loss", Map.of("DISPUTE_LOSS", 3000L, "DISPUTE_RECEIVABLE", -3000L));
            }
            case DISPUTE_RETURN -> {
                ledger.post("dispute-return", Map.of("PSP_FUNDS", 3000L, "DISPUTE_LOSS", -3000L));
                payment.blocked(false);
            }
            case PAYOUT_OUT -> {
                payout.outgoing(5700, 0);
                ledger.post("payout-out", Map.of("PSP_FUNDS", -5700L, "BANK_PAYOUT_IN_TRANSIT", 5700L));
            }
            case PAYOUT_RETURN -> {
                payout.returned(1200, 0);
                ledger.post("payout-return", Map.of("PSP_FUNDS", 1200L, "BANK_PAYOUT_IN_TRANSIT", -1200L));
            }
            case BANK_RECEIPT -> {
                if (!payout.allocate(4500, 4500, 11)) throw new IllegalArgumentException();
                ledger.post(
                        "bank", Map.of("BANK_CASH", 4489L, "BANK_FEE_EXPENSE", 11L, "BANK_PAYOUT_IN_TRANSIT", -4500L));
            }
            default -> {}
        }
        long target = shares.ceiling(payment.captured(), refunds.refunded(), refunds.held(), payment.eligible());
        if (target != effective) {
            long delta = target - effective;
            ledger.post("share:" + (++generation), Map.of("PARTNER_SHARE_EXPENSE", delta, "PARTNER_PAYABLE", -delta));
            effective = target;
        }
    }

    public long effective() {
        return effective;
    }

    public Set<String> providerJournals() {
        var result = new HashSet<>(ledger.events());
        result.removeIf(k -> k.startsWith("share:") || Set.of("bank", "loss").contains(k));
        return Set.copyOf(result);
    }
}
