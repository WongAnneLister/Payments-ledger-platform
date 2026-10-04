package com.paymentsledger.ops;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class OpsSchemaIT {
  @Container
  static final PostgreSQLContainer<?> DB =
      new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

  static JdbcTemplate admin;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
        .schemas("platform")
        .target("15")
        .load()
        .migrate();
    admin =
        new JdbcTemplate(
            new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
    Flyway.configure()
        .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
        .schemas("platform")
        .load()
        .migrate();
  }

  @Test
  void upgradesV15AndHasImmutableOpsEvidenceAndApprovalConstraints() {
    for (String t : List.of("audit_log", "ops_command", "ops_adjustment", "ops_exception_action"))
      assertThat(admin.queryForObject("SELECT to_regclass(?)::text", String.class, "platform." + t))
          .isEqualTo("platform." + t);
    assertThat(
            admin.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE"
                    + " conrelid='platform.ops_adjustment'::regclass AND pg_get_constraintdef(oid)"
                    + " LIKE '%created_by%approved_by%'",
                Long.class))
        .isPositive();
    assertThat(
            Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .load()
                .migrate()
                .migrationsExecuted)
        .isZero();
  }

  @Test
  void runtimeCannotDeleteOpsOrAlterAuditAndHistoricalEvidence() {
    for (String t : List.of("audit_log", "ops_command", "ops_adjustment", "ops_exception_action"))
      assertThat(
              admin.queryForObject(
                  "SELECT has_table_privilege('payments_app',?,'DELETE')",
                  Boolean.class,
                  "platform." + t))
          .isFalse();
    for (String t :
        List.of(
            "audit_log",
            "ops_exception_action",
            "reconciliation_exception",
            "journal",
            "journal_entry",
            "balance_projection"))
      assertThat(
              admin.queryForObject(
                  "SELECT has_table_privilege('payments_app',?,'UPDATE')",
                  Boolean.class,
                  "platform." + t))
          .isFalse();
  }

  @Test
  void runtimeAdjustmentTargetMustExistAndMatchScopeCurrencyAndProvider() {
    var app =
        new JdbcTemplate(
            new DriverManagerDataSource(DB.getJdbcUrl(), "payments_app", "pr02_test_only"));
    UUID org = UUID.randomUUID(),
        env = UUID.randomUUID(),
        provider = UUID.randomUUID(),
        author = UUID.randomUUID(),
        foreignOrg = UUID.randomUUID(),
        foreignEnv = UUID.randomUUID(),
        foreignProvider = UUID.randomUUID(),
        foreignAuthor = UUID.randomUUID(),
        account = UUID.randomUUID(),
        foreignAccount = UUID.randomUUID(),
        providerAccount = UUID.randomUUID();
    admin.update(
        "INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2),('JPY',0)"
            + " ON CONFLICT(code) DO NOTHING");
    for (var v :
        List.of(
            new UUID[] {org, env, provider, author, account},
            new UUID[] {foreignOrg, foreignEnv, foreignProvider, foreignAuthor, foreignAccount})) {
      admin.update(
          "INSERT INTO platform.organization(id,code) VALUES (?,?)", v[0], v[0].toString());
      admin.update(
          "INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES"
              + " (?,?,'target-test',false)",
          v[1],
          v[0]);
      admin.update(
          "INSERT INTO"
              + " platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode)"
              + " VALUES (?,?,?,'FAKE',?,false)",
          v[2],
          v[0],
          v[1],
          v[2].toString());
      admin.update(
          "INSERT INTO platform.ops_identity(id,organization_id,subject,role) VALUES"
              + " (?,?,?,'OPS_OPERATOR')",
          v[3],
          v[0],
          v[3].toString());
      admin.update(
          "INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)"
              + " VALUES (?,?,'USD'),(?,?,'JPY')",
          v[0],
          v[1],
          v[0],
          v[1]);
      admin.update(
          "INSERT INTO"
              + " platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,source_domain)"
              + " VALUES (?,?,?,'USD','OPENING_EQUITY','INTERNAL')",
          v[4],
          v[0],
          v[1]);
    }
    admin.update(
        "INSERT INTO"
            + " platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,source_domain,provider_account_id,balance_domain)"
            + " VALUES (?,?,?,'USD','PSP_FUNDS','PROVIDER',?,'PAYMENTS')",
        providerAccount,
        org,
        env,
        provider);
    String sql =
        "INSERT INTO"
            + " platform.ops_adjustment(organization_id,environment_id,provider_account_id,currency,target_type,target_id,created_by,entries,evidence_refs,reason_digest,reason_length)"
            + " VALUES (?,?,?,?,?,?,?,'[{},{}]','[\"test\"]','digest',1)";
    assertThat(app.update(sql, org, env, null, "USD", "BALANCE", account, author)).isEqualTo(1);
    assertThat(app.update(sql, org, env, provider, "USD", "BALANCE", providerAccount, author))
        .isEqualTo(1);
    for (var values :
        List.of(
            new Object[] {null, "USD", "UNSUPPORTED", account},
            new Object[] {null, "USD", "BALANCE", UUID.randomUUID()},
            new Object[] {null, "USD", "BALANCE", foreignAccount},
            new Object[] {null, "JPY", "BALANCE", account},
            new Object[] {null, "USD", "BALANCE", providerAccount},
            new Object[] {provider, "USD", "BALANCE", account})) {
      assertThatThrownBy(
              () -> app.update(sql, org, env, values[0], values[1], values[2], values[3], author))
          .as("target shape %s", Arrays.toString(values))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
  }

  @Test
  void runtimeSqlCannotCrossScopeEvidenceSelfApproveOrAlterFinalDecision() {
    var app =
        new JdbcTemplate(
            new DriverManagerDataSource(DB.getJdbcUrl(), "payments_app", "pr02_test_only"));
    UUID org = UUID.randomUUID(),
        foreignOrg = UUID.randomUUID(),
        env = UUID.randomUUID(),
        foreignEnv = UUID.randomUUID(),
        provider = UUID.randomUUID(),
        foreignProvider = UUID.randomUUID(),
        author = UUID.randomUUID(),
        reviewer = UUID.randomUUID(),
        foreignActor = UUID.randomUUID();
    admin.update(
        "INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2) ON"
            + " CONFLICT(code) DO NOTHING");
    for (var values :
        List.of(
            new UUID[] {org, env, provider, author},
            new UUID[] {foreignOrg, foreignEnv, foreignProvider, foreignActor})) {
      admin.update(
          "INSERT INTO platform.organization(id,code) VALUES (?,?)",
          values[0],
          values[0].toString());
      admin.update(
          "INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES"
              + " (?,?,'test',false)",
          values[1],
          values[0]);
      admin.update(
          "INSERT INTO"
              + " platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode)"
              + " VALUES (?,?,?,'FAKE',?,false)",
          values[2],
          values[0],
          values[1],
          values[2].toString());
      admin.update(
          "INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)"
              + " VALUES (?,?,'USD')",
          values[0],
          values[1]);
      admin.update(
          "INSERT INTO platform.ops_identity(id,organization_id,subject,role) VALUES"
              + " (?,?,?,'OPS_OPERATOR')",
          values[3],
          values[0],
          values[3].toString());
    }
    admin.update(
        "INSERT INTO platform.ops_identity(id,organization_id,subject,role) VALUES"
            + " (?,?,?,'OPS_REVIEWER')",
        reviewer,
        org,
        reviewer.toString());
    UUID statement = UUID.randomUUID();
    admin.update(
        "INSERT INTO"
            + " platform.bank_statement(id,organization_id,environment_id,provider_account_id,livemode,currency,bank_account_ref,fixture_id,coverage_from,coverage_until,complete,evidence_type,payload,digest)"
            + " VALUES (?,?,?,?,false,'USD','bank','fixture',now()-interval '2 days',now()-interval"
            + " '1 day',false,'SIMULATED_BANK_EVIDENCE','{}',?)",
        statement,
        org,
        env,
        provider,
        "a".repeat(64));
    assertThatThrownBy(
            () ->
                app.update(
                    "INSERT INTO"
                        + " platform.ops_bank_import(organization_id,environment_id,provider_account_id,content_digest,statement_id,fixture_id,schema_version)"
                        + " VALUES (?,?,?,?,?,'foreign-fixture','1')",
                    foreignOrg,
                    foreignEnv,
                    foreignProvider,
                    "b".repeat(64),
                    statement))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    admin.update(
        "INSERT INTO"
            + " platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,opening_mode)"
            + " VALUES (?,?,?,false,'ZERO')",
        org,
        env,
        provider);
    UUID window = UUID.randomUUID(), run = UUID.randomUUID(), exception = UUID.randomUUID();
    admin.update(
        "INSERT INTO"
            + " platform.import_window(id,organization_id,environment_id,provider_account_id,livemode,created_gte,created_lt,purpose)"
            + " VALUES (?,?,?,?,false,now()-interval '2 days',now()-interval '1 day','BACKFILL')",
        window,
        org,
        env,
        provider);
    admin.update(
        "INSERT INTO"
            + " platform.reconciliation_run(id,organization_id,environment_id,provider_account_id,livemode,currency,business_date,business_zone,from_at,until_at,import_window_id,rule_version)"
            + " VALUES (?,?,?,?,false,'USD','2026-09-01','UTC',now()-interval '2"
            + " days',now()-interval '1 day',?,'v1')",
        run,
        org,
        env,
        provider,
        window);
    admin.update(
        "INSERT INTO"
            + " platform.reconciliation_exception(id,run_id,organization_id,environment_id,provider_account_id,livemode,currency,reason,owner,input_versions,evidence)"
            + " VALUES (?,?,?,?,?,false,'USD','BALANCE_RESIDUAL','OPS','{}','{}')",
        exception,
        run,
        org,
        env,
        provider);
    assertThatThrownBy(
            () ->
                app.update(
                    "INSERT INTO"
                        + " platform.ops_exception_action(exception_id,organization_id,environment_id,actor_id,status,reason_digest)"
                        + " VALUES (?,?,?,?,'INVESTIGATING','digest')",
                    exception,
                    foreignOrg,
                    foreignEnv,
                    foreignActor))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    UUID adjustment = UUID.randomUUID(), account = UUID.randomUUID();
    admin.update(
        "INSERT INTO"
            + " platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,source_domain)"
            + " VALUES (?,?,?,'USD','OPENING_EQUITY','INTERNAL')",
        account,
        org,
        env);
    app.update(
        "INSERT INTO"
            + " platform.ops_adjustment(id,organization_id,environment_id,currency,target_type,target_id,created_by,entries,evidence_refs,reason_digest,reason_length)"
            + " VALUES (?,?,?,'USD','BALANCE',?,?, '[{},{}]','[\"test\"]','digest',1)",
        adjustment,
        org,
        env,
        account,
        author);
    assertThatThrownBy(
            () ->
                app.update(
                    "UPDATE platform.ops_adjustment SET"
                        + " status='REJECTED',approved_by=?,approved_at=now() WHERE id=?",
                    author,
                    adjustment))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    app.update(
        "UPDATE platform.ops_adjustment SET status='REJECTED',approved_by=?,approved_at=now() WHERE"
            + " id=?",
        reviewer,
        adjustment);
    assertThatThrownBy(
            () ->
                app.update(
                    "UPDATE platform.ops_adjustment SET"
                        + " status='PENDING_APPROVAL',approved_by=NULL,approved_at=NULL WHERE id=?",
                    adjustment))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    UUID audit = UUID.randomUUID();
    app.update(
        "INSERT INTO"
            + " platform.audit_log(id,organization_id,environment_id,actor_id,role,action,target,request_id,result)"
            + " VALUES (?,?,?,?,'OPS_OPERATOR','TEST','scope','request','DENIED')",
        audit,
        org,
        env,
        author);
    assertThatThrownBy(
            () -> app.update("UPDATE platform.audit_log SET result='SUCCEEDED' WHERE id=?", audit))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> app.update("DELETE FROM platform.audit_log WHERE id=?", audit))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
