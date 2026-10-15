package com.paymentsledger.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.request.RequestContext;
import com.paymentsledger.common.request.SafeCorrelationId;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Structured message JSON and matching MDC contain only bounded, actually known associations. */
public final class CorrelationLog {
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationLog.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of(
            "businessOrderId",
            "paymentId",
            "operationId",
            "providerRequestId",
            "balanceTransactionId",
            "financialEventId",
            "journalId",
            "runId");
    private static final Set<String> EVENTS = Set.of("ops.read", "ops.command", "ops.observability");

    private CorrelationLog() {}

    public static String encode(String event, Map<String, ?> known) {
        var out = JSON.createObjectNode();
        if (!EVENTS.contains(event)) throw new IllegalArgumentException("Unrecognized correlation event");
        out.put("event", event);
        String request = MDC.get(RequestContext.MDC_KEY);
        if (SafeCorrelationId.accepts(request, 64)) out.put("requestId", request);
        for (String field : new TreeSet<>(FIELDS)) {
            Object raw = known.get(field);
            String value = raw instanceof UUID ? raw.toString() : raw instanceof String ? (String) raw : null;
            if (SafeCorrelationId.acceptsAssociation(field, value)) out.put(field, value);
        }
        return out.toString();
    }

    public static void read(String event, JsonNode dto) {
        emit(event, associations(dto, Map.of()));
        // Traverse only DTO sections explicitly projected by OpsQueryService. Never inspect raw facts,
        // requests, exception reasons, arbitrary metadata, or a generic recursive tree.
        for (String section : List.of(
                "journals",
                "balanceTransactions",
                "operations",
                "providerObservations",
                "reconciliationResiduals",
                "items")) {
            int seen = 0;
            for (var item : dto.path(section)) {
                if (++seen > 100) break;
                var known = associations(item, associations(dto, Map.of()));
                if (section.equals("balanceTransactions")) {
                    alias(known, item, "btId", "balanceTransactionId");
                    alias(known, item, "journalId", "journalId");
                }
                if (!known.isEmpty()) emit(event, known);
            }
        }
    }

    private static Map<String, Object> associations(JsonNode dto, Map<String, Object> inherited) {
        var known = new LinkedHashMap<String, Object>(inherited);
        for (String field : FIELDS) alias(known, dto, field, field);
        if (dto.path("id").isTextual() && dto.path("id").asText().startsWith("pay_"))
            alias(known, dto, "id", "paymentId");
        alias(known, dto, "orderId", "businessOrderId");
        alias(known, dto, "sourceBalanceTransactionId", "balanceTransactionId");
        return known;
    }

    private static void alias(Map<String, Object> known, JsonNode dto, String source, String target) {
        if (dto.path(source).isTextual()) known.put(target, dto.path(source).asText());
    }

    private static void emit(String event, Map<String, Object> known) {
        String message = encode(event, known);
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.clear();
            String request = previous == null ? null : previous.get(RequestContext.MDC_KEY);
            if (SafeCorrelationId.accepts(request, 64)) MDC.put(RequestContext.MDC_KEY, request);
            for (String field : FIELDS) {
                Object value = known.get(field);
                String text =
                        value instanceof UUID ? value.toString() : value instanceof String ? (String) value : null;
                if (SafeCorrelationId.acceptsAssociation(field, text)) MDC.put(field, text);
            }
            LOG.info(message);
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }
}
