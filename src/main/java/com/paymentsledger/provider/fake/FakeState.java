package com.paymentsledger.provider.fake;

import static com.paymentsledger.provider.fake.FakeStateStore.JSON;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.paymentsledger.provider.ProviderScope;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

final class FakeState {
    final ObjectNode root;
    final ObjectNode scope;
    final Instant now;
    final String scopeKey;

    FakeState(ObjectNode root, ProviderScope binding) {
        this.root = root;
        this.now = Instant.parse(root.path("now").asText());
        String key = binding.organizationId() + "/" + binding.environmentId() + "/" + binding.providerAccountId() + "/"
                + binding.livemode();
        scopeKey = key;
        ObjectNode scopes = (ObjectNode) root.path("scopes");
        if (!scopes.has(key)) {
            ObjectNode created = scopes.putObject(key);
            for (String field : List.of("objects", "cache", "counters")) created.putObject(field);
            for (String counter : List.of(
                    "requestCount", "acceptedCount", "sessionCreatedCount", "paymentEffectCount", "refundEffectCount"))
                ((ObjectNode) created.path("counters")).put(counter, 0);
            for (String field : List.of("scripts", "calls", "effects", "events", "deliveries")) created.putArray(field);
        }
        scope = (ObjectNode) scopes.get(key);
    }

    ObjectNode map(String key) {
        return (ObjectNode) scope.path(key);
    }

    ArrayNode array(String key) {
        return (ArrayNode) scope.path(key);
    }

    ObjectNode objects(String kind) {
        ObjectNode all = map("objects");
        if (!all.has(kind)) all.putObject(kind);
        return (ObjectNode) all.get(kind);
    }

    void put(String kind, String id, Object fact, Instant visibleAt) {
        ObjectNode wrapper = objects(kind).putObject(id);
        wrapper.set("fact", JSON.valueToTree(fact));
        wrapper.put("visibleAt", visibleAt.toString());
    }

    JsonNode find(String kind, String id) {
        JsonNode wrapper = objects(kind).get(id);
        return wrapper == null
                        || Instant.parse(wrapper.path("visibleAt").asText()).isAfter(now)
                ? null
                : wrapper.get("fact");
    }

    ObjectNode require(String kind, String id) {
        JsonNode fact = find(kind, id);
        if (!(fact instanceof ObjectNode object))
            throw new IllegalStateException("Missing visible fixture " + kind + ":" + id);
        return object;
    }

    <T> T fact(String kind, String id, Class<T> type) {
        return JSON.convertValue(require(kind, id), type);
    }

    long increment(String counter) {
        long n = Math.addExact(map("counters").path(counter).asLong(), 1);
        map("counters").put(counter, n);
        return n;
    }

    ProviderCounters counters() {
        ObjectNode c = map("counters");
        return new ProviderCounters(
                c.path("requestCount").asLong(),
                c.path("acceptedCount").asLong(),
                c.path("sessionCreatedCount").asLong(),
                c.path("paymentEffectCount").asLong(),
                c.path("refundEffectCount").asLong());
    }

    String id(String prefix) {
        long sequence = Math.addExact(root.path("sequence").asLong(), 1);
        root.put("sequence", sequence);
        return prefix + "_fake_"
                + UUID.nameUUIDFromBytes((root.path("seed").asText() + ":" + prefix + ":" + sequence)
                                .getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "");
    }

    void effect(String type, String id, long amount, String currency) {
        array("effects")
                .addObject()
                .put("type", type)
                .put("objectId", id)
                .put("amountMinor", amount)
                .put("currency", currency)
                .put("occurredAt", now.toString());
        if (type.equals("PAYMENT")) increment("paymentEffectCount");
        if (type.equals("REFUND")) increment("refundEffectCount");
    }

    void expireDue(String sessionId) {
        JsonNode fact = find("sessions", sessionId);
        if (fact instanceof ObjectNode session
                && session.path("status").asText().equals("OPEN")
                && !Instant.parse(session.path("expiresAt").asText()).isAfter(now)) {
            session.put("status", "EXPIRED");
            session.putNull("hostedUrl");
        }
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String digest(JsonNode node) {
        return digest(canonical(node).toString().getBytes(StandardCharsets.UTF_8));
    }

    static JsonNode canonical(JsonNode node) {
        if (node.isIntegralNumber()) return JsonNodeFactory.instance.numberNode(node.bigIntegerValue());
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            var keys = new TreeSet<String>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(k -> sorted.set(k, canonical(node.get(k))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            node.forEach(n -> array.add(canonical(n)));
            return array;
        }
        return node;
    }
}
