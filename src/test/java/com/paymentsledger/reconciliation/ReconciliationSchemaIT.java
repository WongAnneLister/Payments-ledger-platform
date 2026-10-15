package com.paymentsledger.reconciliation;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ReconciliationSchemaIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    static JdbcTemplate admin;

    @BeforeAll
    static void migrate() {
        var flyway = Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .target("13")
                .load();
        flyway.migrate();
        admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .load()
                .migrate();
    }

    @Test
    void upgradesV13AndReapplyHasNoPendingMigration() {
        for (String table : List.of(
                "reconciliation_run",
                "reconciliation_cursor",
                "reconciliation_evidence",
                "reconciliation_exception",
                "reconciliation_check",
                "fee_model"))
            assertThat(admin.queryForObject("SELECT to_regclass(?)::text", String.class, "platform." + table))
                    .isEqualTo("platform." + table);
        assertThat(Flyway.configure()
                        .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                        .schemas("platform")
                        .load()
                        .migrate()
                        .migrationsExecuted)
                .isZero();
    }

    @Test
    void evidenceChecksAndFeeConfigurationHaveMinimalPrivileges() {
        for (String table :
                List.of("reconciliation_evidence", "reconciliation_exception", "reconciliation_check", "fee_model"))
            for (String privilege : List.of("UPDATE", "DELETE"))
                assertThat(admin.queryForObject(
                                "SELECT has_table_privilege('payments_app',?,?)",
                                Boolean.class,
                                "platform." + table,
                                privilege))
                        .isFalse();
        assertThat(admin.queryForObject(
                        "SELECT has_table_privilege('payments_app','platform.fee_model','INSERT')", Boolean.class))
                .isFalse();
    }

    @Test
    void feeDirectionIsVersionedAndDefaultsToPrincipalCompatibility() {
        assertThat(admin.queryForObject(
                        "SELECT count(*) FROM information_schema.columns WHERE table_schema='platform' AND table_name='fee_model' AND column_name='fee_direction'",
                        Long.class))
                .isEqualTo(1);
        assertThat(admin.queryForObject(
                        "SELECT column_default FROM information_schema.columns WHERE table_schema='platform' AND table_name='fee_model' AND column_name='fee_direction'",
                        String.class))
                .contains("FOLLOW_PRINCIPAL");
    }
}
