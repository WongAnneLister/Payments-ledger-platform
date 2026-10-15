package com.paymentsledger.reconciliation;

import com.paymentsledger.provider.ProviderScope;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Compare canonical object reads retained by their existing domain owners. No new provider importer. */
final class SourceComparison {
    record Result(String outcome, String reason, BigInteger residual, List<Map<String, Object>> inputs) {}

    private final JdbcTemplate jdbc;

    SourceComparison(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Result compare(ProviderScope scope, Map<String, Object> bt, Instant closingCutoff) {
        String type = Objects.toString(bt.get("source_object_type"), "");
        Object source = bt.get("source_object_id");
        String business = Objects.toString(bt.get("business_type"), "").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows;
        if (type.equals("charge")) {
            rows = jdbc.queryForList(
                    """
                SELECT a.*,p.payment_status,p.resolution_status,p.captured_amount_minor AS payment_capture
                FROM platform.payment_attempt a JOIN platform.payment p ON p.id=a.payment_id
                WHERE a.organization_id=? AND a.environment_id=? AND a.provider_account_id=? AND a.provider_object_id=?
                """, scope.organizationId(), scope.environmentId(), scope.providerAccountId(), source);
            if (rows.size() != 1) return missing(rows);
            var charge = rows.getFirst();
            BigInteger captured = number(charge, "amount_captured_minor"), amount = number(bt, "amount_minor");
            boolean mismatch = !Objects.equals(charge.get("currency"), bt.get("currency"))
                    || !Objects.equals(charge.get("balance_transaction_id"), bt.get("bt_id"))
                    || !Boolean.TRUE.equals(charge.get("captured"))
                    || !"succeeded".equals(charge.get("provider_status"))
                    || !"SUCCEEDED".equals(charge.get("payment_status"))
                    || !captured.equals(amount)
                    || !number(charge, "payment_capture").equals(amount)
                    || bt.get("payment_id") != null && !Objects.equals(bt.get("payment_id"), charge.get("payment_id"));
            return result(mismatch, captured.subtract(amount), rows);
        }
        if (type.equals("refund")) {
            rows = jdbc.queryForList(
                    """
                    SELECT *
                    FROM platform.refund
                    WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND provider_refund_id=?""", scope.organizationId(), scope.environmentId(), scope.providerAccountId(), source);
            if (rows.size() != 1) return missing(rows);
            var refund = rows.getFirst();
            boolean reversed = business.equals("refund_failure");
            BigInteger expected =
                    number(refund, "amount_minor").multiply(reversed ? BigInteger.ONE : BigInteger.ONE.negate());
            boolean mismatch = !Objects.equals(refund.get("currency"), bt.get("currency"))
                    || !expected.equals(number(bt, "amount_minor"))
                    || !Objects.equals(
                            refund.get(reversed ? "failure_balance_transaction_id" : "balance_transaction_id"),
                            bt.get("bt_id"))
                    || bt.get("refund_id") != null && !Objects.equals(bt.get("refund_id"), refund.get("id"));
            if (!mismatch && !Objects.equals(refund.get("refund_status"), reversed ? "FAILED" : "SUCCEEDED")) {
                // An actual refund debit can precede business resolution. A later failed read with
                // a linked return does not contradict the old debit or establish its historical status.
                boolean later = refund.get("last_provider_confirmed_at") instanceof Timestamp observed
                        && observed.toInstant().isAfter(closingCutoff);
                if (later
                        || refund.get("failure_balance_transaction_id") != null
                        || !"KNOWN".equals(refund.get("resolution_status")))
                    return new Result(
                            "INCOMPLETE", "SOURCE_STATUS_NOT_COMPARABLE_TO_CUTOFF", BigInteger.ZERO, List.copyOf(rows));
                mismatch = true;
            }
            return result(mismatch, number(bt, "amount_minor").subtract(expected), rows);
        }
        if (type.equals("dispute")) {
            rows = jdbc.queryForList(
                    """
                    SELECT *
                    FROM platform.dispute
                    WHERE organization_id=?
                        AND environment_id=?
                        AND provider_account_id=?
                        AND livemode=?
                        AND provider_dispute_id=?""",
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.providerAccountId(),
                    scope.livemode(),
                    source);
            if (rows.size() != 1) return missing(rows);
            var dispute = rows.getFirst();
            BigInteger principal = number(dispute, "disputed_principal_minor"),
                    actual = number(bt, "amount_minor").abs();
            boolean mismatch = !Objects.equals(dispute.get("currency"), bt.get("currency"))
                    || actual.compareTo(principal) > 0
                    || Boolean.TRUE.equals(dispute.get("conflict"))
                    || bt.get("dispute_id") != null && !Objects.equals(bt.get("dispute_id"), dispute.get("id"));
            return result(mismatch, actual.subtract(principal).max(BigInteger.ZERO), rows);
        }
        if (type.equals("payout")) {
            rows = jdbc.queryForList(
                    """
                    SELECT *
                    FROM platform.payout
                    WHERE organization_id=?
                        AND environment_id=?
                        AND provider_account_id=?
                        AND livemode=?
                        AND provider_payout_id=?""",
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.providerAccountId(),
                    scope.livemode(),
                    source);
            if (rows.size() != 1) return missing(rows);
            var payout = rows.getFirst();
            BigInteger actual = number(bt, "amount_minor").abs(), expected = number(payout, "amount_minor");
            boolean outgoing = business.equals("payout");
            boolean linked =
                    jdbc.queryForObject("""
                            SELECT count(*)
                            FROM platform.payout_bt_link
                            WHERE payout_id=? AND bt_id=? AND role=?""", Long.class, payout.get("id"), bt.get("bt_id"), outgoing ? "OUT" : "RETURN")
                            == 1;
            boolean mismatch = !Objects.equals(payout.get("currency"), bt.get("currency"))
                    || !linked
                    || (outgoing ? !actual.equals(expected) : actual.compareTo(expected) > 0)
                    || Boolean.TRUE.equals(payout.get("conflict"))
                    || bt.get("payout_id") != null && !Objects.equals(bt.get("payout_id"), payout.get("id"));
            return result(
                    mismatch,
                    outgoing
                            ? actual.subtract(expected)
                            : actual.subtract(expected).max(BigInteger.ZERO),
                    rows);
        }
        return new Result("INCOMPLETE", "UNCLASSIFIED_SOURCE_OBJECT", BigInteger.ZERO, List.of());
    }

    private static Result missing(List<Map<String, Object>> rows) {
        return new Result("INCOMPLETE", "SOURCE_OBJECT_EVIDENCE_UNAVAILABLE", BigInteger.ZERO, List.copyOf(rows));
    }

    private static Result result(boolean mismatch, BigInteger residual, List<Map<String, Object>> rows) {
        return new Result(
                mismatch ? "FAIL" : "PASS",
                mismatch ? "SOURCE_OBJECT_MISMATCH" : "SOURCE_OBJECT_MATCH",
                residual,
                List.copyOf(rows));
    }

    private static BigInteger number(Map<String, Object> row, String key) {
        return row.get(key) == null
                ? BigInteger.ZERO
                : new BigInteger(row.get(key).toString());
    }
}
