package com.paymentsledger.webhook;

import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class WebhookSchemaIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("db/pr02-runtime-role.sql");

    @Test void upgradePreservesRecoveryAndEnforcesScopedImmutableWebhookEvidence() {
        Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform").target("4").load().migrate();
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
    INSERT INTO platform.recovery_task(organization_id,environment_id,application_id,provider_account_id,currency,operation_id,status) VALUES(org,env,app,account,'USD',op,'WAITING');
   END $$;
   """);
        Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform").target("5").load().migrate();
        admin.update("UPDATE platform.payment SET last_provider_confirmed_at='2026-09-26T10:00:00Z'");
        var original = admin.queryForMap("SELECT p.id payment_id,o.id operation_id,t.id recovery_id,o.operation_key,o.command FROM platform.payment p JOIN platform.provider_operation o ON o.payment_id=p.id JOIN platform.recovery_task t ON t.operation_id=o.id");
        var migration = Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()).schemas("platform").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(6);
        assertThat(migration.migrate().migrationsExecuted).isZero();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), "payments_app", "pr02_test_only"));
        assertThat(jdbc.queryForMap("SELECT p.id payment_id,o.id operation_id,t.id recovery_id,o.operation_key,o.command FROM platform.payment p JOIN platform.provider_operation o ON o.payment_id=p.id JOIN platform.recovery_task t ON t.operation_id=o.id")).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT last_query_confirmed_at=last_provider_confirmed_at AND last_webhook_created_at IS NULL FROM platform.payment", Boolean.class)).isTrue();
        jdbc.update("""
          INSERT INTO platform.webhook_inbox(id,organization_id,environment_id,provider_account_id,livemode,
          provider_account_ref,event_id,event_type,object_id,object_type,event_created_at,received_at,verified_at,
          api_version,raw_digest,object_snapshot,available_at)
          SELECT gen_random_uuid(),organization_id,environment_id,provider_account_id,false,'acct_fixture',
          'evt_fixture','payment_intent.succeeded','pi_fixture','payment_intent',now(),now(),now(),
          '2026-07-29.dahlia',repeat('a',64),'{}',now() FROM platform.payment
          """);
        jdbc.update("""
          INSERT INTO platform.normalized_observation(organization_id,environment_id,provider_account_id,
          fact_key,fact_kind,object_id,payment_id,fact,first_source,last_source,first_confirmed_at,last_confirmed_at)
          SELECT organization_id,environment_id,provider_account_id,'PI:pi_fixture','PI','pi_fixture',id,'{}',
          'WEBHOOK','WEBHOOK',now(),now() FROM platform.payment
          """);
        jdbc.update("""
          INSERT INTO platform.dependency_wait(organization_id,environment_id,provider_account_id,inbox_id,
          dependency_type,dependency_id,reason,first_seen_at,last_attempt_at)
          SELECT organization_id,environment_id,provider_account_id,id,'PI','pi_fixture','MISSING_PI',now(),now()
          FROM platform.webhook_inbox
          """);
        for (String sql : new String[]{
            "UPDATE platform.webhook_inbox SET raw_digest=repeat('b',64)",
            "UPDATE platform.webhook_inbox SET object_snapshot='{\"amount\":999}'",
            "UPDATE platform.normalized_observation SET fact='{\"amount\":999}'",
            "UPDATE platform.dependency_wait SET first_seen_at=now()+interval '1 hour'"
        }) assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.update("UPDATE platform.webhook_inbox SET status='WAITING',reason='MISSING_PI'")).isEqualTo(1);
        assertThat(jdbc.update("UPDATE platform.normalized_observation SET last_source='QUERY',last_confirmed_at=now()")).isEqualTo(1);
        assertThat(jdbc.update("UPDATE platform.dependency_wait SET resolved_at=now()")).isEqualTo(1);
        for (String table : new String[]{"webhook_inbox", "normalized_observation", "dependency_wait"}) {
            assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT,INSERT,UPDATE')", Boolean.class, "platform."+table)).isTrue();
            for (String privilege : new String[]{"DELETE", "TRUNCATE"})
                assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,?)", Boolean.class, "platform."+table, privilege)).isFalse();
        }
        jdbc.update("UPDATE platform.payment SET payment_intent_id='pi_owned'");
        admin.update("""
          INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,provider_account_id,
          currency,order_key,request_fingerprint,product_ref,price_version,quantity,unit_amount_minor,amount_minor,
          price_snapshot,share_policy_snapshot)
          SELECT gen_random_uuid(),organization_id,environment_id,application_id,provider_account_id,currency,
          'second-order','second-fingerprint',product_ref,price_version,quantity,unit_amount_minor,amount_minor,
          price_snapshot,share_policy_snapshot FROM platform.business_order
          """);
        assertThatThrownBy(() -> jdbc.update("""
          INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,
          currency,business_order_id,amount_minor,expires_at,payment_intent_id)
          SELECT gen_random_uuid(),organization_id,environment_id,application_id,provider_account_id,currency,id,
          amount_minor,now()+interval '1 hour','pi_owned' FROM platform.business_order WHERE order_key='second-order'
          """)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        admin.update("INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) SELECT gen_random_uuid(),organization_id,environment_id,'STRIPE','acct_other',false FROM platform.payment");
        assertThatThrownBy(() -> jdbc.update("""
          INSERT INTO platform.normalized_observation(organization_id,environment_id,provider_account_id,
          fact_key,fact_kind,object_id,payment_id,fact,first_source,last_source,first_confirmed_at,last_confirmed_at)
          SELECT p.organization_id,p.environment_id,a.id,'CROSS_SCOPE','PI','pi_other',p.id,'{}','WEBHOOK','WEBHOOK',now(),now()
          FROM platform.payment p JOIN platform.provider_account a ON a.provider_account_ref='acct_other'
          """)).isInstanceOf(DataAccessException.class);
        admin.update("DELETE FROM platform.dependency_wait");
        assertThatThrownBy(() -> jdbc.update("""
          INSERT INTO platform.dependency_wait(organization_id,environment_id,provider_account_id,inbox_id,
          dependency_type,reason,first_seen_at,last_attempt_at)
          SELECT i.organization_id,i.environment_id,a.id,i.id,'PI','CROSS_SCOPE',now(),now()
          FROM platform.webhook_inbox i JOIN platform.provider_account a ON a.provider_account_ref='acct_other'
          """)).isInstanceOf(DataAccessException.class);
    }
    @Test void ambiguousLegacyIntentOwnershipStopsUpgradeWithoutDeletingEvidence() {
        var connection = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        new JdbcTemplate(connection).execute("CREATE DATABASE pr08_duplicate_legacy");
        String url = "jdbc:postgresql://"+DB.getHost()+":"+DB.getFirstMappedPort()+"/pr08_duplicate_legacy";
        Flyway.configure().dataSource(url,DB.getUsername(),DB.getPassword()).schemas("platform").target("4").load().migrate();
        var admin = new JdbcTemplate(new DriverManagerDataSource(url,DB.getUsername(),DB.getPassword()));
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
    INSERT INTO platform.recovery_task(organization_id,environment_id,application_id,provider_account_id,currency,operation_id,status) VALUES(org,env,app,account,'USD',op,'WAITING');
   END $$;
   """);
        Flyway.configure().dataSource(url,DB.getUsername(),DB.getPassword()).schemas("platform").target("5").load().migrate();
        admin.update("UPDATE platform.payment SET payment_intent_id='pi_legacy_duplicate'");
        admin.update("""
          INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,provider_account_id,
          currency,order_key,request_fingerprint,product_ref,price_version,quantity,unit_amount_minor,amount_minor,
          price_snapshot,share_policy_snapshot)
          SELECT gen_random_uuid(),organization_id,environment_id,application_id,provider_account_id,currency,
          'duplicate-order','duplicate-fingerprint',product_ref,price_version,quantity,unit_amount_minor,amount_minor,
          price_snapshot,share_policy_snapshot FROM platform.business_order
          """);
        admin.update("""
          INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,
          currency,business_order_id,amount_minor,expires_at,payment_intent_id)
          SELECT gen_random_uuid(),organization_id,environment_id,application_id,provider_account_id,currency,id,
          amount_minor,now()+interval '1 hour','pi_legacy_duplicate' FROM platform.business_order WHERE order_key='duplicate-order'
          """);
        var upgrade = Flyway.configure().dataSource(url,DB.getUsername(),DB.getPassword()).schemas("platform").load();
        assertThatThrownBy(upgrade::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.payment WHERE payment_intent_id='pi_legacy_duplicate'",Integer.class)).isEqualTo(2);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.provider_operation",Integer.class)).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.recovery_task",Integer.class)).isEqualTo(1);
        assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("5");
        assertThat(admin.queryForObject("SELECT to_regclass('platform.webhook_inbox') IS NULL",Boolean.class)).isTrue();
    }
}
