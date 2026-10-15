package com.paymentsledger.financialingestion;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class FinancialSchemaIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    static JdbcTemplate admin;

    @BeforeAll
    static void migrate() {
        admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        admin.execute("""
                ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT SELECT,INSERT ON TABLES TO payments_app""");
        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .load()
                .migrate();
    }

    @Test
    void durableSchemaExistsAndRuntimeCannotProvisionOrMutateEvidence() {
        assertThat(admin.queryForObject("SELECT to_regclass('platform.balance_transaction')::text", String.class))
                .isEqualTo("platform.balance_transaction");
        for (String table : List.of("provider_observation", "quarantine_observation", "bt_evidence_conflict"))
            for (String privilege : List.of("UPDATE", "DELETE"))
                assertThat(admin.queryForObject(
                                "SELECT has_table_privilege('payments_app',?,?)",
                                Boolean.class,
                                "platform." + table,
                                privilege))
                        .isFalse();
        for (String privilege : List.of("INSERT", "UPDATE", "DELETE"))
            assertThat(admin.queryForObject(
                            "SELECT has_table_privilege('payments_app','platform.ingestion_policy',?)",
                            Boolean.class,
                            privilege))
                    .isFalse();
        assertThat(admin.queryForObject(
                        "SELECT has_table_privilege('payments_app','platform.ledger_account','INSERT')", Boolean.class))
                .isFalse();
        assertThat(admin.queryForObject("""
                        SELECT has_table_privilege('payments_app','platform.psp_availability_projection','SELECT')""", Boolean.class)).isTrue();
        for (String privilege : List.of("INSERT", "UPDATE", "DELETE"))
            assertThat(admin.queryForObject("""
                            SELECT has_table_privilege('payments_app','platform.psp_availability_projection',?)""", Boolean.class, privilege)).isFalse();
    }

    @Test
    void financialTriggerFunctionsCannotBeAttachedToTempTables() {
        var functions = admin.queryForList("""
                SELECT oid::regprocedure::text AS name
                FROM pg_proc
                WHERE pronamespace='platform'::regnamespace AND proname LIKE 'pr10_%'""", String.class);
        assertThat(functions).isNotEmpty();
        for (String function : functions)
            assertThat(admin.queryForObject(
                            "SELECT has_function_privilege('payments_app',?,'EXECUTE')", Boolean.class, function))
                    .as(function)
                    .isFalse();
    }
}
