package com.paymentsledger.provider.fake;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class FakeTransactionBoundaryIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    Path directory;

    @Test
    void committedRemoteEffectSurvivesFailedLocalWritebackAndRestart() {
        var dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE local_writeback(id text primary key)");
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var scope = new ProviderScope(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), false);
        var start = Instant.parse("2026-01-01T00:00:00Z");
        var fake = new FakeStripeProvider(directory, 42, start, Duration.ofMinutes(10));
        var command = new CreateCheckoutSessionCommand(
                scope,
                "pay",
                10000,
                "USD",
                "https://example.test/ok",
                "https://example.test/no",
                Map.of(),
                Map.of(),
                start.plusSeconds(3600));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> fake.createSession(command)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fake.counters(scope).requestCount()).isZero();
        var session = fake.createSession(command).payload();
        var pi = fake.controls().attemptPayment(scope, session.sessionId(), FakeControls.AttemptOutcome.SUCCEED);
        var refund = fake.createRefund(new CreateRefundCommand(
                        scope, "refund", pi.latestChargeId(), null, 1000, "requested_by_customer"))
                .payload();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    jdbc.update("INSERT INTO local_writeback(id) VALUES (?)", refund.refundId());
                    throw new IllegalStateException("crash before local commit");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM local_writeback", Long.class))
                .isZero();
        var restarted = new FakeStripeProvider(directory, 42, start, Duration.ofMinutes(10));
        assertThat(restarted.queryRefund(scope, refund.refundId()).payload()).isEqualTo(refund);
        assertThat(restarted.counters(scope).refundEffectCount()).isEqualTo(1);
        assertThat(restarted.counters(scope).paymentEffectCount()).isEqualTo(1);
    }
}
