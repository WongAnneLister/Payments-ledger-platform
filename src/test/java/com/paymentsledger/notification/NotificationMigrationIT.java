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
        fake.controls().attemptPayment(scope,session(old),FakeControls.AttemptOutcome.SUCCEED);
        service.get(context,payment(old));
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
