package com.paymentsledger.revenueshare;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
abstract class RevenueShareCoreFixture {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    @TempDir
    Path directory;

    JdbcTemplate admin, jdbc;
    DataSourceTransactionManager tx;
    com.zaxxer.hikari.HikariDataSource pool;
    PaymentRepository repository;
    PaymentExecutor executor;
    PaymentService service;
    FakeStripeProvider fake;
    final TenantContext context = new TenantContext(
            id(1), id(2), id(3), id(4), Set.of("USD"), false, Set.of("payments:read", "payments:write"));
    final ProviderScope scope = new ProviderScope(id(1), id(2), id(4), false);

    static UUID id(int value) {
        return new UUID(0, value);
    }

    @BeforeEach
    void setup() {
        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .load()
                .migrate();
        admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        pool = new com.zaxxer.hikari.HikariDataSource();
        pool.setJdbcUrl(DB.getJdbcUrl());
        pool.setUsername("payments_app");
        pool.setPassword("pr02_test_only");
        pool.setMaximumPoolSize(12);
        pool.setAutoCommit(true);
        jdbc = new JdbcTemplate(pool);
        tx = new DataSourceTransactionManager(pool);
        admin.execute("TRUNCATE platform.organization,platform.currency_config CASCADE");
        admin.update("INSERT INTO platform.organization(id,code) VALUES (?,'org')", id(1));
        admin.update(
                "INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES (?,?,'test',false)",
                id(2),
                id(1));
        admin.update(
                "INSERT INTO platform.application(id,organization_id,environment_id,code) VALUES (?,?,?,'app')",
                id(3),
                id(1),
                id(2));
        admin.update(
                "INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES (?,?,?,'FAKE','fixture',false)",
                id(4),
                id(1),
                id(2));
        admin.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2)");
        admin.update(
                "INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES (?,?,'USD')",
                id(1),
                id(2));
        admin.update(
                "INSERT INTO platform.share_policy_fixture(id,organization_id,environment_id,product_ref,policy_version,partner_ref,share_basis_points) VALUES (?,?,?,'demo',1,'partner',2000)",
                id(5),
                id(1),
                id(2));
        admin.update(
                "INSERT INTO platform.price_fixture(id,organization_id,environment_id,product_ref,price_version,currency,amount_minor,share_policy_id) VALUES (?,?,?,'demo',1,'USD',10000,?)",
                id(6),
                id(1),
                id(2),
                id(5));
        fake = new FakeStripeProvider(directory, 42, Instant.now(), Duration.ofDays(1));
        repository = new PaymentRepository(jdbc, tx, new ObjectMapper().findAndRegisterModules(), fake.clock());
        executor = new PaymentExecutor(repository, fake, fake, fake, "FAKE");
        service = new PaymentService(repository, executor);
    }

    @AfterEach
    void close() {
        if (pool != null) pool.close();
    }

    PaymentRequests.Create command(String order) {
        return new PaymentRequests.Create(
                order,
                "demo",
                1,
                10000,
                "USD",
                1,
                "https://merchant.test/ok",
                "https://merchant.test/cancel",
                null,
                Map.of());
    }

    UUID payment(Map<String, Object> resource) {
        return PaymentIds.payment(resource.get("id").toString());
    }

    String session(Map<String, Object> resource) {
        return ((Map<?, ?>) resource.get("provider")).get("checkoutSessionId").toString();
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM platform." + table, Long.class);
    }
}
