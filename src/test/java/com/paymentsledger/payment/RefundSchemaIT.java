package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class RefundSchemaIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    @Test
    void v8UpgradePreservesPaymentOperationAndNotificationBytes() {
        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .target("8")
                .load()
                .migrate();
        var admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        admin.execute("""
   DO $$ DECLARE org uuid=gen_random_uuid();env uuid=gen_random_uuid();app uuid=gen_random_uuid();account uuid=gen_random_uuid();ord uuid=gen_random_uuid();pay uuid=gen_random_uuid();op uuid=gen_random_uuid();BEGIN
    INSERT INTO platform.organization(id,code) VALUES(org,'v4');
    INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES(env,org,'test',false);
    INSERT INTO platform.application(id,organization_id,environment_id,code) VALUES(app,org,env,'app');
    INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES(account,org,env,'FAKE','v4',false);
    INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES('USD',2);
    INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES(org,env,'USD');
    INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,provider_account_id,currency,order_key,request_fingerprint,product_ref,price_version,quantity,unit_amount_minor,amount_minor,price_snapshot,share_policy_snapshot) VALUES(ord,org,env,app,account,'USD','order','fingerprint','demo',1,1,100,100,'{}','{}');
    INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,currency,business_order_id,amount_minor,expires_at) VALUES(pay,org,env,app,account,'USD',ord,100,now()+interval '1 hour');
    INSERT INTO platform.provider_operation(id,organization_id,environment_id,application_id,provider_account_id,currency,operation_target,payment_id,operation_type,operation_key,request_fingerprint,command,expires_at,status,attempt_count) VALUES(op,org,env,app,account,'USD','PAYMENT',pay,'CREATE_CHECKOUT','original','fp','{}',now()+interval '1 hour','UNKNOWN',1);
    INSERT INTO platform.recovery_task(organization_id,environment_id,application_id,provider_account_id,currency,operation_id,payment_id,status) VALUES(org,env,app,account,'USD',op,pay,'WAITING');
   END $$;
   """);
        String before = admin.queryForObject("SELECT payload FROM platform.outbox_event LIMIT 1", String.class);
        assertThat(admin.queryForObject("SELECT to_regclass('platform.refund') IS NULL", Boolean.class))
                .isTrue();
        assertThatThrownBy(() -> admin.update("""
                                INSERT INTO platform.provider_operation(organization_id,environment_id,application_id,
                                    provider_account_id,currency,operation_target,refund_id,operation_type,operation_key,
                                    request_fingerprint,command,expires_at)
                                SELECT
                                    organization_id,
                                    environment_id,
                                    application_id,
                                    provider_account_id,
                                    currency,
                                    'REFUND',
                                    gen_random_uuid(),
                                    'CREATE_CHECKOUT',
                                    'forbidden-refund',
                                    'fp',
                                    '{}',
                                    expires_at
                                FROM platform.payment""")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var migration = Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(8);
        assertThat(migration.migrate().migrationsExecuted).isZero();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), "payments_app", "pr02_test_only"));
        assertThat(jdbc.queryForObject("SELECT payload FROM platform.outbox_event LIMIT 1", String.class))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT operation_key FROM platform.provider_operation", String.class))
                .isEqualTo("original");
        assertThat(jdbc.queryForObject("""
                        SELECT t.payment_id=o.payment_id AND t.refund_id IS NULL FROM platform.recovery_task t
                        JOIN platform.provider_operation o ON o.id=t.operation_id""", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                        SELECT has_table_privilege(current_user,'platform.refund','SELECT,INSERT,UPDATE')""", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                        SELECT has_table_privilege(current_user,'platform.refund_source_observation','UPDATE')""", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,'platform.outbox_event','INSERT')", Boolean.class))
                .isFalse();
    }
}
