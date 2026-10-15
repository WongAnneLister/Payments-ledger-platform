package com.paymentsledger.notification;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.*;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Profile("worker")
@ConditionalOnProperty(name = "platform.messaging.enabled", havingValue = "true")
public final class HttpMerchantTransport implements MerchantTransport {
    // DNS calls can ignore interruption on some platforms. Bound both running work and backlog.
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(
            2,
            2,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),
            r -> {
                Thread t = new Thread(r, "notification-dns");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "notification-deadline");
        t.setDaemon(true);
        return t;
    });

    static {
        DEADLINES.setRemoveOnCancelPolicy(true);
    }

    private final NotificationTargetPolicy policy;
    private final Function<String, String> keys;
    private final Clock clock;

    @Autowired
    public HttpMerchantTransport(Environment environment, Clock clock) {
        this(
                NotificationTargetPolicy.production(
                        new HashSet<>(Arrays.stream(environment
                                        .getProperty("platform.notifications.allowed-hosts", "")
                                        .split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList()),
                        HttpMerchantTransport::resolveBounded),
                ref -> environment.getProperty(
                        "platform.notifications.signing-keys." + ref,
                        environment.getProperty("MERCHANT_NOTIFICATION_KEY_"
                                + ref.toUpperCase(Locale.ROOT).replace('-', '_'))),
                clock);
    }

    public HttpMerchantTransport(NotificationTargetPolicy policy, Function<String, String> keys, Clock clock) {
        this.policy = Objects.requireNonNull(policy);
        this.keys = Objects.requireNonNull(keys);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Result send(Target target, UUID id, String key, byte[] payload) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("HTTP_IN_TRANSACTION");
        if (id == null
                || key == null
                || !key.matches("[A-Za-z0-9:._-]{1,512}")
                || payload == null
                || payload.length == 0
                || payload.length > 16384) return new Result(Outcome.PERMANENT_REJECT, 0, "INVALID_PAYLOAD");
        String secret;
        try {
            if (target == null || target.keyRef() == null || !target.keyRef().matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
                return new Result(Outcome.RETRYABLE, 0, "KEY_UNAVAILABLE");
            secret = keys.apply(target.keyRef());
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
                return new Result(Outcome.RETRYABLE, 0, "KEY_UNAVAILABLE");
        } catch (RuntimeException e) {
            return new Result(Outcome.RETRYABLE, 0, "KEY_UNAVAILABLE");
        }
        NotificationTargetPolicy.Endpoint endpoint;
        try {
            endpoint = policy.validate(target.url());
        } catch (NotificationTargetPolicy.ResolutionException e) {
            return new Result(Outcome.RETRYABLE, 0, "DNS_UNAVAILABLE");
        } catch (IllegalArgumentException e) {
            return new Result(Outcome.PERMANENT_REJECT, 0, "INVALID_DESTINATION");
        }
        String host = endpoint.uri().getHost();
        DnsResolver pinned = new DnsResolver() {
            public InetAddress[] resolve(String requested) throws UnknownHostException {
                if (!host.equalsIgnoreCase(requested)) throw new UnknownHostException("UNAPPROVED_HOST");
                // One address per attempt bounds connection time; future attempts resolve afresh.
                return new InetAddress[] {endpoint.addresses()[0]};
            }

            public String resolveCanonicalHostname(String requested) throws UnknownHostException {
                resolve(requested);
                return requested;
            }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinned)
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
                        .http1Config(Http1Config.custom()
                                .setMaxHeaderCount(32)
                                .setMaxLineLength(8192)
                                .build())
                        .build())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(3))
                        .setSocketTimeout(Timeout.ofSeconds(5))
                        .build())
                .setDefaultTlsConfig(TlsConfig.custom()
                        .setHandshakeTimeout(Timeout.ofSeconds(3))
                        .build())
                .build();
        var client = HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(1))
                        .setResponseTimeout(Timeout.ofSeconds(5))
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .disableAuthCaching()
                .build();
        var request = new HttpPost(endpoint.uri());
        var deadline = DEADLINES.schedule(() -> request.cancel(), 10, TimeUnit.SECONDS);
        try {
            long timestamp = clock.instant().getEpochSecond();
            request.setEntity(new ByteArrayEntity(payload, ContentType.APPLICATION_JSON));
            request.setHeader("X-Payments-Event-Id", id.toString());
            request.setHeader("X-Payments-Business-Key", key);
            request.setHeader("X-Payments-Timestamp", Long.toString(timestamp));
            request.setHeader("X-Payments-Signature", NotificationSignature.sign(payload, id, key, timestamp, secret));
            // executeOpen avoids response-handler auto-draining an untrusted response entity.
            var response = client.executeOpen(null, request, null);
            int status = response.getCode();
            client.close(CloseMode.IMMEDIATE);
            if (status >= 200 && status < 300) return new Result(Outcome.DELIVERED, status, "HTTP_2XX");
            if (status == 408 || status == 429 || (status >= 500 && status < 600))
                return new Result(Outcome.RETRYABLE, status, "HTTP_RETRYABLE");
            return new Result(Outcome.PERMANENT_REJECT, status, "HTTP_REJECTED");
        } catch (java.io.IOException | RuntimeException e) {
            return new Result(Outcome.RETRYABLE, 0, "TRANSPORT_FAILURE");
        } finally {
            deadline.cancel(false);
            client.close(CloseMode.IMMEDIATE);
            manager.close(CloseMode.IMMEDIATE);
        }
    }

    private static InetAddress[] resolveBounded(String host) throws UnknownHostException {
        Future<InetAddress[]> future = null;
        try {
            future = DNS.submit(() -> InetAddress.getAllByName(host));
            return future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnknownHostException("DNS_UNAVAILABLE");
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            throw new UnknownHostException("DNS_UNAVAILABLE");
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
        }
    }
}
