package com.paymentsledger.revenueshare;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class RevenueShareSchemaIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    @Test
    void v9UpgradeRestrictsHistoryAndProvisioningAndPreservesPaymentSnapshot() {
        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .target("9")
                .load()
                .migrate();
        var admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        admin.execute("""
   DO $$ DECLARE org uuid=gen_random_uuid();env uuid=gen_random_uuid();app uuid=gen_random_uuid();provider uuid=gen_random_uuid();ord uuid=gen_random_uuid();pay uuid=gen_random_uuid();BEGIN
    INSERT INTO platform.organization(id,code) VALUES(org,'upgrade');
    INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES(env,org,'test',false);
    INSERT INTO platform.application(id,organization_id,environment_id,code) VALUES(app,org,env,'app');
    INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES(provider,org,env,'FAKE','upgrade',false);
    INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES('USD',2);
    INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES(org,env,'USD');
    INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,provider_account_id,currency,order_key,request_fingerprint,product_ref,price_version,quantity,unit_amount_minor,amount_minor,price_snapshot,share_policy_snapshot) VALUES(ord,org,env,app,provider,'USD','order','fp','demo',1,1,10000,10000,'{}','{"partner_ref":"partner","share_basis_points":2000,"policy_version":1}');
    INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,currency,business_order_id,amount_minor,expires_at) VALUES(pay,org,env,app,provider,'USD',ord,10000,now()+interval '1 hour');
   END $$;
  """);
        String before =
                admin.queryForObject("SELECT share_policy_snapshot::text FROM platform.business_order", String.class);
        var flyway = Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(7);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), "payments_app", "pr02_test_only"));
        assertThat(jdbc.queryForObject("SELECT share_policy_snapshot::text FROM platform.business_order", String.class))
                .isEqualTo(before);
        jdbc.queryForObject("SELECT platform.ensure_share_accounts((SELECT id FROM platform.payment))", Object.class);
        jdbc.queryForObject("SELECT platform.ensure_share_accounts((SELECT id FROM platform.payment))", Object.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.ledger_account WHERE source_domain='INTERNAL' AND provider_account_id IS NULL",
                        Long.class))
                .isEqualTo(2);
        for (String table : new String[] {"share_adjustment", "ledger_account"})
            for (String permission : new String[] {"UPDATE", "DELETE"})
                assertThat(jdbc.queryForObject(
                                "SELECT has_table_privilege(current_user,?,?)",
                                Boolean.class,
                                "platform." + table,
                                permission))
                        .isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,'platform.ledger_account','INSERT')", Boolean.class))
                .isFalse();
        assertThatThrownBy(
                        () -> jdbc.update(
                                "INSERT INTO platform.share_allocation(organization_id,environment_id,application_id,provider_account_id,currency,payment_id,partner_ref,policy_version,rate_bps,effective_minor,cap_minor) SELECT organization_id,environment_id,application_id,provider_account_id,currency,id,'partner','1',2000,1,1 FROM platform.payment"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () -> jdbc.update(
                                "INSERT INTO platform.share_allocation(organization_id,environment_id,application_id,provider_account_id,currency,payment_id,partner_ref,policy_version,rate_bps) SELECT organization_id,environment_id,application_id,provider_account_id,currency,id,'wrong','1',2000 FROM platform.payment"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.share_allocation", Long.class))
                .isZero();
        for (String name : admin.queryForList(
                "SELECT oid::regprocedure::text FROM pg_proc WHERE pronamespace='platform'::regnamespace AND proname LIKE 'pr12_%'",
                String.class))
            assertThat(jdbc.queryForObject(
                            "SELECT has_function_privilege(current_user,?,'EXECUTE')", Boolean.class, name))
                    .isFalse();
    }
}
