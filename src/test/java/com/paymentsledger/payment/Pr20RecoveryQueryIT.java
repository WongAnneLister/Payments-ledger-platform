package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** A source read is a query, including when its timestamp equals the last confirmed query. */
class Pr20RecoveryQueryIT extends Pr20RecoveryFixture {
    @Test
    void confirmedSameInstantQueryRescanDoesNotDuplicateOrRejectKnownCapture() throws Exception {
        var report = Pr20RecoveryReports.begin("regressions", "SOURCE-QUERY-SAME-INSTANT");
        var output = Pr20RecoveryReports.root("regressions");
        try {
            createPayment();
            capturePayment();
            var captured = bt("capture", "charge", 10000, 300, chargeId, "charge");
            fake.controls().putBalanceTransaction(scope, captured, fake.clock().instant(), null);
            post(captured);
            Object confirmed = jdbc.queryForObject(
                    "SELECT last_query_confirmed_at FROM platform.payment WHERE id=?",
                    java.sql.Timestamp.class,
                    paymentId);
            var effects = fake.controls().effects(scope);
            long journals = count("journal");
            UUID run = rescanAndRecoverSources();
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM platform.recovery_task WHERE reconciliation_run_id=? AND task_type='RECONCILIATION_SOURCE'",
                            String.class,
                            run))
                    .isEqualTo("DONE");
            assertThat(count("payment")).isEqualTo(1);
            assertThat(count("journal")).isEqualTo(journals);
            assertThat(fake.controls().effects(scope)).isEqualTo(effects);
            assertThat(jdbc.queryForObject(
                            "SELECT last_query_confirmed_at FROM platform.payment WHERE id=?",
                            java.sql.Timestamp.class,
                            paymentId))
                    .isEqualTo(confirmed);
            assertThat(jdbc.queryForObject(
                            "SELECT last_webhook_created_at FROM platform.payment WHERE id=?",
                            java.sql.Timestamp.class,
                            paymentId))
                    .isNull();
            report.put("run", run);
            report.put("confirmedQueryAt", confirmed);
            report.put("effectsBefore", effects);
            report.put("effectsAfter", fake.controls().effects(scope));
            report.put("journalCount", journals);
            report.put("status", "PASS");
        } finally {
            persistFixture(output, report);
            Pr20RecoveryReports.write(output.resolve("source-query-same-instant.json"), report);
        }
    }
}
