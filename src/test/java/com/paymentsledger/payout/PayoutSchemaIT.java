package com.paymentsledger.payout;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PayoutSchemaIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    @Test
    void upgradeFromV11ProvidesAppendOnlyHistoryAndNarrowProvisioningPrivileges() {
        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .target("11")
                .load()
                .migrate();
        var flyway = Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .target("12")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), "payments_app", "pr02_test_only"));
        for (String table : new String[] {
            "payout_observation", "payout_bt_link", "payout_case", "payout_fact", "bank_statement", "payout_bank_match"
        }) {
            assertThat(jdbc.queryForObject(
                            "SELECT has_table_privilege(current_user,?,'INSERT')", Boolean.class, "platform." + table))
                    .isTrue();
            for (String permission : new String[] {"UPDATE", "DELETE"})
                assertThat(jdbc.queryForObject(
                                "SELECT has_table_privilege(current_user,?,?)",
                                Boolean.class,
                                "platform." + table,
                                permission))
                        .isFalse();
        }
        for (String function : new String[] {"ensure_payout_accounts(uuid)", "lock_payout_accounts(uuid)"})
            assertThat(jdbc.queryForObject(
                            "SELECT has_function_privilege(current_user,?,'EXECUTE')",
                            Boolean.class,
                            "platform." + function))
                    .isTrue();
        for (String table : new String[] {"ledger_account", "balance_projection"})
            assertThat(jdbc.queryForObject(
                            "SELECT has_table_privilege(current_user,?,'UPDATE')", Boolean.class, "platform." + table))
                    .isFalse();
    }
}
