package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PaymentSchemaIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    @Test
    void upgradesV3WithSevenDurablePaymentTablesAndAppendOnlyRuntimeEvidence() {
        var config = Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform");
        config.target("3").load().migrate();
        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .load()
                .migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), "payments_app", "pr02_test_only"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema='platform' AND table_name IN ('business_order','payment','api_idempotency_record','provider_operation','payment_attempt','recovery_task','provider_observation')",
                        Integer.class))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,'platform.provider_observation','SELECT,INSERT')",
                        Boolean.class))
                .isTrue();
        for (String privilege : new String[] {"UPDATE", "DELETE", "TRUNCATE"})
            assertThat(jdbc.queryForObject(
                            "SELECT has_table_privilege(current_user,'platform.provider_observation',?)",
                            Boolean.class,
                            privilege))
                    .isFalse();
    }
}
