package com.paymentsledger.provider.fake;

import static java.nio.file.StandardOpenOption.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** A bounded, local simulator store. Never participates in the application database transaction. */
final class FakeStateStore {
    static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final Path directory;
    private final ReentrantLock lock;
    private final long seed;
    private final Instant initialTime;
    private final Duration retention;

    FakeStateStore(Path directory, long seed, Instant initialTime, Duration retention) {
        if (directory == null
                || initialTime == null
                || retention == null
                || retention.isZero()
                || retention.isNegative())
            throw new IllegalArgumentException(
                    "Explicit directory, clock origin and positive fixture retention required");
        try {
            Files.createDirectories(directory);
            this.directory = directory.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open simulator directory", e);
        }
        this.lock = LOCKS.computeIfAbsent(this.directory, ignored -> new ReentrantLock());
        // PostgreSQL timestamptz keeps microseconds; finer origins round on write and diverge from Java comparisons.
        this.seed = seed;
        this.initialTime = initialTime.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.retention = retention;
        update(s -> null);
    }

    private static String integrity(ObjectNode state) {
        ObjectNode content = state.deepCopy();
        content.remove("integritySha256");
        return FakeState.digest(content);
    }

    private static void validate(ObjectNode state) {
        try {
            if (!state.path("sequence").isIntegralNumber()
                    || !state.path("sequence").canConvertToLong()
                    || state.path("sequence").longValue() < 0
                    || !state.path("scopes").isObject()
                    || !state.path("seed").isIntegralNumber()
                    || !state.path("seed").canConvertToLong())
                throw new IllegalArgumentException("Invalid sequence or scopes");
            Instant now = Instant.parse(state.path("now").asText());
            if (now.isBefore(Instant.parse(state.path("initialTime").asText())))
                throw new IllegalArgumentException("Clock before origin");
            for (var scope : state.path("scopes")) {
                for (String field : java.util.List.of("objects", "cache", "counters"))
                    if (!scope.path(field).isObject()) throw new IllegalArgumentException("Invalid scope map");
                for (String field : java.util.List.of("scripts", "calls", "effects", "events", "deliveries"))
                    if (!scope.path(field).isArray()) throw new IllegalArgumentException("Invalid scope log");
                for (String counter : java.util.List.of(
                        "requestCount",
                        "acceptedCount",
                        "sessionCreatedCount",
                        "paymentEffectCount",
                        "refundEffectCount")) {
                    var value = scope.path("counters").path(counter);
                    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
                        throw new IllegalArgumentException("Invalid persisted counter");
                }
                for (var kind : scope.path("objects")) {
                    if (!kind.isObject()) throw new IllegalArgumentException("Invalid object collection");
                    for (var object : kind) {
                        if (!object.path("fact").isObject()) throw new IllegalArgumentException("Invalid object fact");
                        Instant.parse(object.path("visibleAt").asText());
                    }
                }
                for (var cache : scope.path("cache")) {
                    if (!cache.path("parameters").isObject() || !cache.has("payload"))
                        throw new IllegalArgumentException("Invalid retained response");
                    Instant.parse(cache.path("expiresAt").asText());
                    com.paymentsledger.provider.ProviderCallClassification.valueOf(
                            cache.path("classification").asText());
                }
            }
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Corrupt simulator state", invalid);
        }
    }

    <T> T update(Function<ObjectNode, T> operation) {
        lock.lock();
        try (var channel = FileChannel.open(directory.resolve("state.lock"), CREATE, WRITE);
                var ignored = channel.lock()) {
            Path statePath = directory.resolve("state.json");
            ObjectNode state;
            if (Files.exists(statePath)) {
                var parsed = JSON.readTree(Files.readAllBytes(statePath));
                if (!(parsed instanceof ObjectNode object)) throw new IllegalStateException("Invalid simulator state");
                state = object;
                if (!integrity(state).equals(state.path("integritySha256").asText()))
                    throw new IllegalStateException("Simulator state checksum mismatch");
                if (state.path("format").asInt() != 1
                        || state.path("seed").asLong() != seed
                        || !initialTime
                                .toString()
                                .equals(state.path("initialTime").asText())
                        || !retention.toString().equals(state.path("retention").asText()))
                    throw new IllegalStateException("Simulator configuration or format mismatch");
            } else {
                state = JSON.createObjectNode();
                state.put("format", 1)
                        .put("seed", seed)
                        .put("initialTime", initialTime.toString())
                        .put("retention", retention.toString())
                        .put("now", initialTime.toString())
                        .put("sequence", 0);
                for (String name : new String[] {"scopes"}) state.putObject(name);
            }
            validate(state);
            T result = operation.apply(state);
            validate(state);
            state.put("integritySha256", integrity(state));
            byte[] data = JSON.writeValueAsBytes(state);
            Path pending = Files.createTempFile(directory, "state-", ".pending");
            try {
                try (var out = FileChannel.open(pending, WRITE, TRUNCATE_EXISTING)) {
                    var buffer = ByteBuffer.wrap(data);
                    while (buffer.hasRemaining()) out.write(buffer);
                    out.force(true);
                }
                Files.move(pending, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pending);
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read or commit simulator state", e);
        } finally {
            lock.unlock();
        }
    }
}
