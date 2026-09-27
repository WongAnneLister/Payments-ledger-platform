package com.paymentsledger.notification;

import static org.assertj.core.api.Assertions.*;
import com.paymentsledger.notification.fixture.SimulatedMerchantServer;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

class MerchantDeliveryIT extends NotificationFixture {
    @Container static final PostgreSQLContainer<?> MERCHANT_DB=new PostgreSQLContainer<>("postgres:16");
    private static final String SECRET="fixture-only-merchant-signing-secret-32bytes";
    @Test void lostResponseAfterMerchantCommitRetriesWithoutRepeatingFulfillment() throws Exception {
        var ds=new DriverManagerDataSource(MERCHANT_DB.getJdbcUrl(),MERCHANT_DB.getUsername(),MERCHANT_DB.getPassword());
        new org.springframework.jdbc.core.JdbcTemplate(ds).execute("DROP SCHEMA IF EXISTS merchant_fixture CASCADE");
        try(var merchant=new SimulatedMerchantServer(new InetSocketAddress("127.0.0.1",0),ds,SECRET,fake.clock())) {
            admin.update("INSERT INTO platform.merchant_notification_target(organization_id,environment_id,application_id,url,key_ref,version) VALUES (?,?,?,?,?,1)",id(1),id(2),id(3),merchant.uri().toString(),"fixture");
            var event=success(); inbox().accept(event.bytes());
            var transport=new HttpMerchantTransport(NotificationTargetPolicy.localFixture(merchant.uri()),ref->SECRET,fake.clock());
            var notifier=new MerchantNotifier(delivery(),transport);
            merchant.dropNextResponse(); notifier.drain(1);
            assertThat(merchant.effects()).isEqualTo(1);
            assertThat(merchant.receipts()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM platform.delivery_task WHERE event_id=?",String.class,event.id())).isEqualTo("READY");
            fake.clock().advance(Duration.ofHours(1));
            notifier.drain(1);
            assertThat(jdbc.queryForObject("SELECT status FROM platform.delivery_task WHERE event_id=?",String.class,event.id())).isEqualTo("DELIVERED");
            assertThat(merchant.effects()).isEqualTo(1);
            assertThat(merchant.receipts()).isEqualTo(1);
            // Merchant business dedup also survives a distinct event id for the same success.
            UUID another=UUID.randomUUID();
            byte[] repeated=event.payload().replace(event.id().toString(),another.toString()).getBytes(StandardCharsets.UTF_8);
            assertThat(transport.send(new MerchantTransport.Target(merchant.uri(),"fixture"),another,event.businessKey(),repeated).outcome()).isEqualTo(MerchantTransport.Outcome.DELIVERED);
            assertThat(merchant.effects()).isEqualTo(1);
            assertThat(merchant.receipts()).isEqualTo(2);
        }
    }
    @Test void rejectsTamperingAndEventIdentityReuse() throws Exception {
        var ds=new DriverManagerDataSource(MERCHANT_DB.getJdbcUrl(),MERCHANT_DB.getUsername(),MERCHANT_DB.getPassword());
        new org.springframework.jdbc.core.JdbcTemplate(ds).execute("DROP SCHEMA IF EXISTS merchant_fixture CASCADE");
        try(var merchant=new SimulatedMerchantServer(new InetSocketAddress("127.0.0.1",0),ds,SECRET,fake.clock())) {
            var event=success();
            var wrongKey=new HttpMerchantTransport(NotificationTargetPolicy.localFixture(merchant.uri()),ref->"incorrect-merchant-signing-secret-32bytes",fake.clock());
            assertThat(wrongKey.send(new MerchantTransport.Target(merchant.uri(),"fixture"),event.id(),event.businessKey(),event.bytes()).status()).isEqualTo(401);
            var good=new HttpMerchantTransport(NotificationTargetPolicy.localFixture(merchant.uri()),ref->SECRET,fake.clock());
            assertThat(good.send(new MerchantTransport.Target(merchant.uri(),"fixture"),event.id(),event.businessKey(),event.bytes()).status()).isEqualTo(200);
            byte[] different=(event.payload()+" ").getBytes(StandardCharsets.UTF_8);
            assertThat(good.send(new MerchantTransport.Target(merchant.uri(),"fixture"),event.id(),event.businessKey(),different).status()).isEqualTo(409);
        }
    }
    @Test void redirectIsNeverFollowedAndHttpIsNeverPerformedUnderTransaction() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger received=new AtomicInteger(),redirected=new AtomicInteger();
        server.createContext("/notifications",exchange->{received.incrementAndGet();exchange.getResponseHeaders().set("Location","/redirected");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/redirected",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();}); server.start();
        try {
            URI uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/notifications");
            var transport=new HttpMerchantTransport(NotificationTargetPolicy.localFixture(uri),ref->SECRET,fake.clock());
            var target=new MerchantTransport.Target(uri,"fixture");
            assertThat(transport.send(target,UUID.randomUUID(),"key","{}".getBytes()).outcome()).isEqualTo(MerchantTransport.Outcome.PERMANENT_REJECT);
            assertThat(received).hasValue(1);assertThat(redirected).hasValue(0);
            assertThatThrownBy(()->new TransactionTemplate(tx).execute(s->transport.send(target,UUID.randomUUID(),"key","{}".getBytes()))).isInstanceOf(IllegalStateException.class);
            assertThat(received).hasValue(1);
        } finally {server.stop(0);}
    }
}
