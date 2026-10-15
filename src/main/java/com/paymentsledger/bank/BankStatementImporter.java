package com.paymentsledger.bank;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentsledger.provider.ProviderScope;
import com.paymentsledger.webhook.WebhookNormalizer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Fixed simulation schema. Stable source identities and a canonical digest make whole-file reimport idempotent. */
@Service
public class BankStatementImporter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion",
            "evidenceType",
            "fixtureId",
            "bankAccountRef",
            "currency",
            "coverageFrom",
            "coverageUntil",
            "complete",
            "lines");
    private static final Set<String> LINE_FIELDS =
            Set.of("lineId", "kind", "bookedAt", "principalMinor", "feeMinor", "payoutReferences");

    public BankStatementImporter(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.json = json.copy().findAndRegisterModules();
    }

    public UUID importStatement(ProviderScope scope, JsonNode input) {
        JsonNode data = validated(input);
        validateScope(scope, data.path("currency").textValue());
        String digest = WebhookNormalizer.digest(data.toString().getBytes(StandardCharsets.UTF_8));
        try {
            return tx.execute(status -> {
                UUID id = UUID.randomUUID();
                String bank = data.path("bankAccountRef").textValue(),
                        fixture = data.path("fixtureId").textValue(),
                        currency = data.path("currency").textValue();
                int inserted = jdbc.update(
                        """
                        INSERT INTO platform.bank_statement(id,organization_id,environment_id,provider_account_id,livemode,
                            currency,bank_account_ref,fixture_id,coverage_from,coverage_until,complete,evidence_type,
                            payload,digest)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,'SIMULATED_BANK_EVIDENCE',?::jsonb,?)
                        ON CONFLICT(organization_id,environment_id,provider_account_id,livemode,bank_account_ref,fixture_id)
                        DO NOTHING""",
                        id,
                        scope.organizationId(),
                        scope.environmentId(),
                        scope.providerAccountId(),
                        scope.livemode(),
                        currency,
                        bank,
                        fixture,
                        stamp(data, "coverageFrom"),
                        stamp(data, "coverageUntil"),
                        data.path("complete").booleanValue(),
                        data.toString(),
                        digest);
                var existing = jdbc.queryForMap(
                        """
                        SELECT id,digest
                        FROM platform.bank_statement
                        WHERE organization_id=?
                            AND environment_id=?
                            AND provider_account_id=?
                            AND livemode=?
                            AND bank_account_ref=?
                            AND fixture_id=?""",
                        scope.organizationId(),
                        scope.environmentId(),
                        scope.providerAccountId(),
                        scope.livemode(),
                        bank,
                        fixture);
                if (!digest.equals(existing.get("digest")))
                    throw new IllegalArgumentException("Fixture identity has different immutable evidence");
                if (inserted == 0) return (UUID) existing.get("id");
                for (var line : data.path("lines")) {
                    String refs = "{"
                            + String.join(
                                    ",",
                                    refs(line).stream()
                                            .map(x -> "\"" + x + "\"")
                                            .toList()) + "}";
                    jdbc.update(
                            """
                            INSERT INTO platform.bank_statement_line(statement_id,organization_id,environment_id,
                                provider_account_id,livemode,currency,bank_account_ref,external_line_id,kind,booked_at,
                                principal_minor,fee_minor,payout_references)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::text[])""",
                            id,
                            scope.organizationId(),
                            scope.environmentId(),
                            scope.providerAccountId(),
                            scope.livemode(),
                            currency,
                            bank,
                            line.path("lineId").textValue(),
                            line.path("kind").textValue(),
                            stamp(line, "bookedAt"),
                            line.path("principalMinor").longValue(),
                            line.path("feeMinor").longValue(),
                            refs);
                }
                return id;
            });
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalArgumentException("Bank source line already exists in another fixture", duplicate);
        }
    }

    private JsonNode validated(JsonNode input) {
        exactFields(input, FIELDS);
        if (!input.path("schemaVersion").isInt()
                || input.path("schemaVersion").intValue() != 1
                || !"SIMULATED_BANK_EVIDENCE".equals(text(input, "evidenceType")))
            throw new IllegalArgumentException("Simulation schemaVersion 1 required");
        identifier(input, "fixtureId");
        identifier(input, "bankAccountRef");
        String currency = text(input, "currency");
        if (currency == null || !currency.matches("[A-Za-z]{3}")) throw invalid();
        Instant from = time(input, "coverageFrom"), until = time(input, "coverageUntil");
        if (!from.isBefore(until)
                || !input.path("complete").isBoolean()
                || !input.path("lines").isArray()
                || input.path("lines").size() > 1000) throw invalid();
        var result = (ObjectNode) canonical(input);
        result.put("currency", currency.toUpperCase(Locale.ROOT))
                .put("coverageFrom", from.toString())
                .put("coverageUntil", until.toString());
        var lines = new TreeMap<String, JsonNode>();
        for (var raw : input.path("lines")) {
            exactFields(raw, LINE_FIELDS);
            String id = identifier(raw, "lineId"), kind = text(raw, "kind");
            if (!Set.of("CREDIT", "RETURN").contains(Objects.toString(kind, ""))) throw invalid();
            Instant booked = time(raw, "bookedAt");
            if (booked.isBefore(from) || !booked.isBefore(until)) throw invalid();
            long principal = number(raw, "principalMinor"), fee = number(raw, "feeMinor");
            if (principal <= 0 || fee < 0 || fee > principal || kind.equals("RETURN") && fee != 0) throw invalid();
            if (!raw.path("payoutReferences").isArray()
                    || raw.path("payoutReferences").isEmpty()
                    || raw.path("payoutReferences").size() > 100) throw invalid();
            var refs = new TreeSet<String>();
            for (var ref : raw.path("payoutReferences")) {
                if (!ref.isTextual() || !ref.textValue().matches("[A-Za-z0-9_]{1,255}") || !refs.add(ref.textValue()))
                    throw invalid();
            }
            var line = (ObjectNode) canonical(raw);
            line.put("bookedAt", booked.toString());
            var a = line.putArray("payoutReferences");
            refs.forEach(a::add);
            if (lines.put(id, line) != null) throw new IllegalArgumentException("Duplicate line ID within fixture");
        }
        var a = result.putArray("lines");
        lines.values().forEach(a::add);
        return canonical(result);
    }

    private JsonNode canonical(JsonNode n) {
        if (n.isObject()) {
            var out = json.createObjectNode();
            var keys = new TreeSet<String>();
            n.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) out.set(key, canonical(n.get(key)));
            return out;
        }
        if (n.isArray()) {
            var out = json.createArrayNode();
            n.forEach(v -> out.add(canonical(v)));
            return out;
        }
        return n.deepCopy();
    }

    private void validateScope(ProviderScope s, String currency) {
        if (s == null
                || s.livemode()
                || jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM platform.provider_account p
                                JOIN platform.environment_config e ON e.id=p.environment_id AND e.organization_id=p.organization_id
                                JOIN platform.organization o ON o.id=p.organization_id
                                JOIN platform.org_currency_enabled c ON c.organization_id=p.organization_id
                                    AND c.environment_id=p.environment_id
                                WHERE p.id=?
                                    AND p.organization_id=?
                                    AND p.environment_id=?
                                    AND NOT p.livemode
                                    AND NOT e.livemode
                                    AND p.status='ACTIVE'
                                    AND e.status='ACTIVE'
                                    AND o.status='ACTIVE'
                                    AND c.currency=?""", Long.class, s.providerAccountId(), s.organizationId(), s.environmentId(), currency)
                        != 1) throw new IllegalArgumentException("Trusted enabled test scope required");
    }

    private static void exactFields(JsonNode n, Set<String> expected) {
        if (n == null || !n.isObject()) throw invalid();
        var found = new HashSet<String>();
        n.fieldNames().forEachRemaining(found::add);
        if (!found.equals(expected)) throw invalid();
    }

    private static String text(JsonNode n, String key) {
        return n.path(key).isTextual() ? n.path(key).textValue() : null;
    }

    private static String identifier(JsonNode n, String key) {
        String value = text(n, key);
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,255}")) throw invalid();
        return value;
    }

    private static long number(JsonNode n, String key) {
        var v = n.path(key);
        if (!v.isIntegralNumber() || !v.canConvertToLong()) throw invalid();
        return v.longValue();
    }

    private static Instant time(JsonNode n, String key) {
        try {
            return Instant.parse(Objects.requireNonNull(text(n, key)));
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static Timestamp stamp(JsonNode n, String key) {
        return Timestamp.from(time(n, key));
    }

    private static List<String> refs(JsonNode n) {
        var r = new ArrayList<String>();
        n.path("payoutReferences").forEach(v -> r.add(v.textValue()));
        return r;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid fixed simulated bank fixture schema");
    }
}
