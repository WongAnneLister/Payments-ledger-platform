package com.paymentsledger.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal ledger query. No public controller or application-wide accounting permission is implied. */
@Service
@Transactional(readOnly = true)
public class JournalQueryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JournalQueryService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public record Journal(UUID id, LedgerScope scope, String eventKey, String eventType, String sourceDomain,
                          String sourceRef, JsonNode facts, UUID reversalOf, Instant sealedAt, List<PostingLine> entries) {
        public Journal { facts = facts.deepCopy(); entries = List.copyOf(entries); }
        @Override public JsonNode facts() { return facts.deepCopy(); }
    }

    public Journal get(LedgerScope scope, UUID journalId) {
        if (scope == null || journalId == null) throw new ApiException(ErrorCode.VALIDATION_ERROR, "Ledger scope and journal required");
        var rows = jdbc.query("""
                SELECT j.id,j.reversal_of_journal_id,j.sealed_at,f.event_key,f.event_type,f.source_domain,f.source_ref,f.facts
                FROM platform.journal j JOIN platform.financial_event f ON f.id=j.financial_event_id
                WHERE j.id=? AND j.organization_id=? AND j.environment_id=? AND j.currency=?
                  AND f.provider_account_id IS NOT DISTINCT FROM ?::uuid AND j.sealed_at IS NOT NULL
                """, (rs, n) -> {
            try {
                return new Journal(rs.getObject("id", UUID.class), scope, rs.getString("event_key"), rs.getString("event_type"),
                        rs.getString("source_domain"), rs.getString("source_ref"), mapper.readTree(rs.getString("facts")),
                        rs.getObject("reversal_of_journal_id", UUID.class), rs.getTimestamp("sealed_at").toInstant(), List.of());
            } catch (JsonProcessingException ex) { throw new IllegalStateException("Stored ledger facts are not valid JSON", ex); }
        }, journalId, scope.organizationId(), scope.environmentId(), scope.currency(), scope.providerAccountId());
        if (rows.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "Journal not found in caller scope");
        var journal = rows.getFirst();
        var entries = jdbc.query("""
                SELECT ledger_account_id,side,amount_minor FROM platform.journal_entry
                WHERE journal_id=? AND organization_id=? AND environment_id=? AND currency=? ORDER BY line_no
                """, (rs,n) -> new PostingLine(rs.getObject("ledger_account_id", UUID.class), Side.valueOf(rs.getString("side")), rs.getLong("amount_minor")),
                journalId, scope.organizationId(), scope.environmentId(), scope.currency());
        return new Journal(journal.id(), scope, journal.eventKey(), journal.eventType(), journal.sourceDomain(), journal.sourceRef(),
                journal.facts(), journal.reversalOf(), journal.sealedAt(), entries);
    }
}
