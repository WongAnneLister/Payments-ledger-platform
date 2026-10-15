package com.paymentsledger.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Sole production balance write entry. Joins caller transactions; never performs external I/O. */
@Service
public class LedgerPostingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public LedgerPostingService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID post(LedgerScope scope, PostingCommand command) {
        if (scope == null || command == null)
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Ledger scope and command required");
        try {
            return jdbc.queryForObject(
                    "SELECT platform.post_ledger(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)",
                    UUID.class,
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.currency(),
                    scope.providerAccountId(),
                    command.eventKey(),
                    command.eventType(),
                    command.sourceDomain(),
                    command.sourceRef(),
                    command.facts().toString(),
                    mapper.valueToTree(command.entries()).toString(),
                    command.reversalOf());
        } catch (DataAccessException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    String state = sql.getSQLState();
                    if ("P0003".equals(state) || "23505".equals(state))
                        throw new ApiException(
                                ErrorCode.EVIDENCE_CONFLICT,
                                "Ledger event or reversal conflicts with existing evidence");
                    if ("P0002".equals(state))
                        throw new ApiException(ErrorCode.NOT_FOUND, "Ledger resource not found in scope");
                    if ("23514".equals(state)
                            || "23503".equals(state)
                            || "22003".equals(state)
                            || "22P02".equals(state))
                        throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid ledger scope, amount or entry set");
                }
            }
            // 40P01/40001 and other infrastructure failures remain visible to the outer transaction owner.
            throw ex;
        }
    }
}
