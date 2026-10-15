package com.paymentsledger.notification;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpMerchantTransportTest {
    private static final String SECRET = "fixture-only-merchant-signing-secret-32bytes";

    @Test
    void mapsStatusesWithoutFollowingRedirectsOrReadingResponseBody() throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger status = new AtomicInteger(204), calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        server.setExecutor(executor);
        server.createContext("/notifications", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/forbidden");
            int code = status.get();
            exchange.sendResponseHeaders(code, code == 204 ? -1 : 10000000);
            if (code != 204) {
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/notifications");
            var client = new HttpMerchantTransport(
                    NotificationTargetPolicy.localFixture(uri), ref -> SECRET, Clock.systemUTC());
            for (int code : new int[] {204, 200, 302, 400, 401, 408, 429, 503, 600}) {
                status.set(code);
                long started = System.nanoTime();
                var result = client.send(
                        new MerchantTransport.Target(uri, "fixture"), UUID.randomUUID(), "key", "{}".getBytes());
                assertThat(result.status()).isEqualTo(code);
                var expected = code < 300
                        ? MerchantTransport.Outcome.DELIVERED
                        : code == 408 || code == 429 || (code >= 500 && code < 600)
                                ? MerchantTransport.Outcome.RETRYABLE
                                : MerchantTransport.Outcome.PERMANENT_REJECT;
                assertThat(result.outcome()).isEqualTo(expected);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            }
            assertThat(calls).hasValue(9);
        } finally {
            release.countDown();
            server.stop(0);
            executor.close();
        }
    }

    @Test
    void absoluteDeadlineStopsTrickledResponseHeaders() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = executor.submit(() -> {
                try (var connection = socket.accept()) {
                    connection.getOutputStream().write("HTTP/1.1 200 OK\r\nX-Slow: ".getBytes());
                    connection.getOutputStream().flush();
                    for (int i = 0; i < 14; i++) {
                        Thread.sleep(1000);
                        connection.getOutputStream().write('x');
                        connection.getOutputStream().flush();
                    }
                    connection.getOutputStream().write("\r\nContent-Length: 0\r\n\r\n".getBytes());
                    connection.getOutputStream().flush();
                } catch (Exception expectedOnCancellation) {
                }
            });
            URI uri = URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/notifications");
            var client = new HttpMerchantTransport(
                    NotificationTargetPolicy.localFixture(uri), ref -> SECRET, Clock.systemUTC());
            long started = System.nanoTime();
            var result = client.send(
                    new MerchantTransport.Target(uri, "fixture"), UUID.randomUUID(), "key", "{}".getBytes());
            server.cancel(true);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(12));
            assertThat(result.outcome()).isEqualTo(MerchantTransport.Outcome.RETRYABLE);
        }
    }

    @Test
    void invalidConfigurationAndOversizedBodiesNeverSend() {
        URI uri = URI.create("http://127.0.0.1:1/notifications");
        var target = new MerchantTransport.Target(uri, "fixture");
        var policy = NotificationTargetPolicy.localFixture(uri);
        var missing = new HttpMerchantTransport(policy, ref -> null, Clock.systemUTC());
        assertThat(missing.send(target, UUID.randomUUID(), "key", "{}".getBytes())
                        .code())
                .isEqualTo("KEY_UNAVAILABLE");
        var client = new HttpMerchantTransport(policy, ref -> SECRET, Clock.systemUTC());
        assertThat(client.send(target, UUID.randomUUID(), "key", new byte[16385])
                        .code())
                .isEqualTo("INVALID_PAYLOAD");
        assertThat(client.send(
                                new MerchantTransport.Target(URI.create("http://127.0.0.1:2/notifications"), "fixture"),
                                UUID.randomUUID(),
                                "key",
                                "{}".getBytes())
                        .code())
                .isEqualTo("INVALID_DESTINATION");
    }
}
