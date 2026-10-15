package com.paymentsledger.bank.repository;

import com.paymentsledger.bank.domain.BankStatement;
import com.paymentsledger.bank.domain.BankStatementLine;
import com.paymentsledger.provider.ProviderScope;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for imported simulated bank statements and their lines. */
@Repository
public class BankStatementRepository {
    /** The stored identity of a statement: its row ID and the digest of its canonical evidence. */
    public record StoredStatement(UUID id, String digest) {}

    private final NamedParameterJdbcTemplate jdbc;

    public BankStatementRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** Active test organization, environment and provider account with the currency enabled. */
    public boolean isActiveTestScope(ProviderScope scope, String currency) {
        Long matches = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM platform.provider_account p
                JOIN platform.environment_config e ON e.id=p.environment_id AND e.organization_id=p.organization_id
                JOIN platform.organization o ON o.id=p.organization_id
                JOIN platform.org_currency_enabled c ON c.organization_id=p.organization_id
                    AND c.environment_id=p.environment_id
                WHERE p.id=:providerAccountId
                    AND p.organization_id=:organizationId
                    AND p.environment_id=:environmentId
                    AND NOT p.livemode
                    AND NOT e.livemode
                    AND p.status='ACTIVE'
                    AND e.status='ACTIVE'
                    AND o.status='ACTIVE'
                    AND c.currency=:currency""",
                new MapSqlParameterSource()
                        .addValue("providerAccountId", scope.providerAccountId())
                        .addValue("organizationId", scope.organizationId())
                        .addValue("environmentId", scope.environmentId())
                        .addValue("currency", currency),
                Long.class);
        return matches == 1;
    }

    /** Inserts the statement unless its (scope, bank account, fixture) identity exists; returns rows inserted. */
    public int insertIfAbsent(UUID id, ProviderScope scope, BankStatement statement) {
        return jdbc.update(
                """
                INSERT INTO platform.bank_statement(id,organization_id,environment_id,provider_account_id,livemode,
                    currency,bank_account_ref,fixture_id,coverage_from,coverage_until,complete,evidence_type,
                    payload,digest)
                VALUES (:id,:organizationId,:environmentId,:providerAccountId,:livemode,:currency,:bankAccountRef,
                    :fixtureId,:coverageFrom,:coverageUntil,:complete,'SIMULATED_BANK_EVIDENCE',:payload::jsonb,:digest)
                ON CONFLICT(organization_id,environment_id,provider_account_id,livemode,bank_account_ref,fixture_id)
                DO NOTHING""",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("organizationId", scope.organizationId())
                        .addValue("environmentId", scope.environmentId())
                        .addValue("providerAccountId", scope.providerAccountId())
                        .addValue("livemode", scope.livemode())
                        .addValue("currency", statement.currency())
                        .addValue("bankAccountRef", statement.bankAccountRef())
                        .addValue("fixtureId", statement.fixtureId())
                        .addValue("coverageFrom", Timestamp.from(statement.coverageFrom()))
                        .addValue("coverageUntil", Timestamp.from(statement.coverageUntil()))
                        .addValue("complete", statement.complete())
                        .addValue("payload", statement.canonicalJson())
                        .addValue("digest", statement.digest()));
    }

    public StoredStatement find(ProviderScope scope, BankStatement statement) {
        return jdbc.queryForObject(
                """
                SELECT id,digest
                FROM platform.bank_statement
                WHERE organization_id=:organizationId
                    AND environment_id=:environmentId
                    AND provider_account_id=:providerAccountId
                    AND livemode=:livemode
                    AND bank_account_ref=:bankAccountRef
                    AND fixture_id=:fixtureId""",
                new MapSqlParameterSource()
                        .addValue("organizationId", scope.organizationId())
                        .addValue("environmentId", scope.environmentId())
                        .addValue("providerAccountId", scope.providerAccountId())
                        .addValue("livemode", scope.livemode())
                        .addValue("bankAccountRef", statement.bankAccountRef())
                        .addValue("fixtureId", statement.fixtureId()),
                (rs, row) -> new StoredStatement(rs.getObject("id", UUID.class), rs.getString("digest")));
    }

    /** A line ID already used by another fixture of the same bank account fails with DuplicateKeyException. */
    public void insertLine(UUID statementId, ProviderScope scope, BankStatement statement, BankStatementLine line) {
        jdbc.update(
                """
                INSERT INTO platform.bank_statement_line(statement_id,organization_id,environment_id,
                    provider_account_id,livemode,currency,bank_account_ref,external_line_id,kind,booked_at,
                    principal_minor,fee_minor,payout_references)
                VALUES (:statementId,:organizationId,:environmentId,:providerAccountId,:livemode,:currency,
                    :bankAccountRef,:lineId,:kind,:bookedAt,:principal,:fee,:references::text[])""",
                new MapSqlParameterSource()
                        .addValue("statementId", statementId)
                        .addValue("organizationId", scope.organizationId())
                        .addValue("environmentId", scope.environmentId())
                        .addValue("providerAccountId", scope.providerAccountId())
                        .addValue("livemode", scope.livemode())
                        .addValue("currency", statement.currency())
                        .addValue("bankAccountRef", statement.bankAccountRef())
                        .addValue("lineId", line.lineId())
                        .addValue("kind", line.kind().name())
                        .addValue("bookedAt", Timestamp.from(line.bookedAt()))
                        .addValue("principal", line.principalMinor())
                        .addValue("fee", line.feeMinor())
                        .addValue("references", arrayLiteral(line)));
    }

    /** PostgreSQL array literal; safe because references are validated as [A-Za-z0-9_]{1,255}. */
    private static String arrayLiteral(BankStatementLine line) {
        return line.payoutReferences().stream()
                .map(ref -> "\"" + ref + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }
}
