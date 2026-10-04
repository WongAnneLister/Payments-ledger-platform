package com.paymentsledger.dispute;
import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
@Testcontainers
class DisputeSchemaIT {
 @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");
 @Test void upgradeProvidesScopedImmutableDisputeHistoryAndRestrictedAccountProvisioning(){
  Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).schemas("platform").target("10").load().migrate();
  var migration=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).schemas("platform").load();
  assertThat(migration.migrate().migrationsExecuted).isEqualTo(6);
  assertThat(migration.migrate().migrationsExecuted).isZero();
  var jdbc=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),"payments_app","pr02_test_only"));
  for(String table:new String[]{"dispute_observation","dispute_case","dispute_bt_link","dispute_financial_fact"}) {
   assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'INSERT')",Boolean.class,"platform."+table)).isTrue();
   for(String permission:new String[]{"UPDATE","DELETE"})assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,?)",Boolean.class,"platform."+table,permission)).isFalse();
  }
  assertThat(jdbc.queryForObject("SELECT has_function_privilege(current_user,'platform.ensure_dispute_accounts(uuid)','EXECUTE')",Boolean.class)).isTrue();
  assertThat(jdbc.queryForObject("SELECT has_function_privilege(current_user,'platform.lock_dispute_accounts(uuid)','EXECUTE')",Boolean.class)).isTrue();
  for(String function:new String[]{"pr13_identity_guard()","pr13_history_guard()","pr13_bt_link_guard()"})assertThat(jdbc.queryForObject("SELECT has_function_privilege(current_user,?,'EXECUTE')",Boolean.class,"platform."+function)).isFalse();
  assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,'platform.ledger_account','INSERT')",Boolean.class)).isFalse();
 }
}
