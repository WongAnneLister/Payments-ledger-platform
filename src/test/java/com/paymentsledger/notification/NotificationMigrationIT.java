package com.paymentsledger.notification;

import static org.assertj.core.api.Assertions.*;
import com.paymentsledger.provider.fake.FakeControls;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationMigrationIT extends NotificationFixture {
    @Override String migrationTarget(){return "6";}
    @Test void v6UpgradeCreatesNoHistoricalEventsOrImplicitFulfillmentPermission() {
        var old=service.create(context,"old-key-000",command("old-order")).resource();
        var intent=fake.controls().attemptPayment(scope,session(old),FakeControls.AttemptOutcome.SUCCEED);
        // Seed the historical V6 capture directly: current application code requires the current schema.
        admin.update("""
                INSERT INTO platform.payment_attempt(organization_id,environment_id,application_id,provider_account_id,
                currency,payment_id,provider_object_id,provider_status,payment_intent_id,captured,amount_minor,
                amount_captured_minor,provider_created_at,fetched_at)
                SELECT organization_id,environment_id,application_id,provider_account_id,currency,id,?,'succeeded',?,
                true,amount_minor,amount_minor,now(),now() FROM platform.payment WHERE id=?
                """,intent.latestChargeId(),intent.paymentIntentId(),payment(old));
        admin.update("""
                UPDATE platform.payment SET payment_status='SUCCEEDED',captured_amount_minor=amount_minor,
                financial_status='PENDING_EVIDENCE',latest_charge_id=?,payment_intent_id=?,last_provider_confirmed_at=now()
                WHERE id=?
                """,intent.latestChargeId(),intent.paymentIntentId(),payment(old));
        String identity=jdbc.queryForObject("SELECT row(organization_id,environment_id,application_id,provider_account_id,business_order_id,amount_minor)::text FROM platform.payment",String.class);
        admin.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT SELECT,INSERT ON TABLES TO payments_app");
        Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).schemas("platform").load().migrate();
        assertThat(count("outbox_event")).isZero();assertThat(count("order_fulfillment_eligibility")).isZero();
        for(String table:new String[]{"outbox_event","order_fulfillment_eligibility","merchant_notification_target"})
            assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'INSERT')",Boolean.class,"platform."+table))
                    .as("V7 must revoke inherited default INSERT on %s",table).isFalse();
        assertThat(jdbc.queryForObject("SELECT row(organization_id,environment_id,application_id,provider_account_id,business_order_id,amount_minor)::text FROM platform.payment",String.class)).isEqualTo(identity);
        service.get(context,payment(old));assertThat(count("outbox_event")).isZero();
        var replay=new TransactionTemplate(tx).execute(s->new OutboxWriter(jdbc).write(payment(old)));
        inbox().accept(outbox().find(replay).bytes());target();fake.clock().advance(Duration.ofMinutes(1));
        assertThat(delivery().claim(Duration.ofSeconds(30))).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM platform.delivery_task",String.class)).isEqualTo("HELD");
        service.create(context,"new-key-000",command("new-order"));
        assertThat(count("order_fulfillment_eligibility")).isEqualTo(1);
        for(String table:new String[]{"outbox_event","consumer_inbox","delivery_task","notification_rejection","order_fulfillment_eligibility","merchant_notification_target"})
            assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'DELETE')",Boolean.class,"platform."+table)).isFalse();
    }
}
