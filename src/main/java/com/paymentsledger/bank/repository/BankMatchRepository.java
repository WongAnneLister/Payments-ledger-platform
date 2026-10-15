package com.paymentsledger.bank.repository;

import com.paymentsledger.bank.domain.BankLine;
import com.paymentsledger.bank.domain.BankLineKind;
import com.paymentsledger.bank.domain.BankMatchState;
import com.paymentsledger.bank.domain.PayoutPosition;
import com.paymentsledger.provider.ProviderScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Data access for bank matching: payouts, bank statement lines, their matches and ledger accounts.
 *
 * <p>Methods named {@code lock...} take row locks and must run inside the caller's transaction, in this order: payout
 * (rank 60), then bank line (rank 65), then payout ledger accounts. See ADR-0007.
 */
@Repository
public class BankMatchRepository {
    public record PriorMatch(UUID id, long principalMinor) {}

    private static final String SCOPED_PAYOUT = """
            SELECT *
            FROM platform.payout
            WHERE id=:id AND organization_id=:organizationId AND environment_id=:environmentId \
            AND provider_account_id=:providerAccountId AND livemode=:livemode""";

    private static final RowMapper<PayoutPosition> PAYOUT = (rs, row) -> new PayoutPosition(
            rs.getObject("id", UUID.class),
            rs.getString("provider_payout_id"),
            rs.getString("currency"),
            instant(rs, "arrival_date"),
            rs.getBoolean("conflict"),
            BankMatchState.valueOf(rs.getString("bank_match_state")),
            rs.getLong("outgoing_minor"),
            rs.getLong("transit_minor"),
            rs.getLong("bank_confirmed_minor"),
            rs.getLong("returned_minor"),
            rs.getLong("suspense_minor"));

    private static final RowMapper<BankLine> LINE = (rs, row) -> new BankLine(
            rs.getObject("id", UUID.class),
            rs.getObject("organization_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getObject("provider_account_id", UUID.class),
            rs.getBoolean("livemode"),
            rs.getString("currency"),
            BankLineKind.valueOf(rs.getString("kind")),
            instant(rs, "booked_at"),
            rs.getLong("principal_minor"),
            rs.getLong("fee_minor"),
            rs.getLong("allocated_minor"),
            rs.getLong("fee_allocated_minor"),
            List.of((String[]) rs.getArray("payout_references").getArray()));

    private final NamedParameterJdbcTemplate jdbc;

    public BankMatchRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public Optional<PayoutPosition> findPayout(ProviderScope scope, UUID payoutId) {
        return first(jdbc.query(SCOPED_PAYOUT, scoped(scope).addValue("id", payoutId), PAYOUT));
    }

    public Optional<PayoutPosition> lockPayout(ProviderScope scope, UUID payoutId) {
        return first(jdbc.query(SCOPED_PAYOUT + " FOR UPDATE", scoped(scope).addValue("id", payoutId), PAYOUT));
    }

    public Optional<BankLine> lockLine(UUID lineId) {
        return first(jdbc.query(
                "SELECT * FROM platform.bank_statement_line WHERE id=:id FOR UPDATE", Map.of("id", lineId), LINE));
    }

    public Optional<PriorMatch> findMatch(UUID payoutId, UUID lineId, String kind) {
        return first(jdbc.query(
                """
                SELECT *
                FROM platform.payout_bank_match
                WHERE payout_id=:payoutId AND line_id=:lineId AND kind=:kind""",
                Map.of("payoutId", payoutId, "lineId", lineId, "kind", kind),
                (rs, row) -> new PriorMatch(rs.getObject("id", UUID.class), rs.getLong("principal_minor"))));
    }

    /** Flags the payout as conflicting and records the overlap case once. */
    public void recordReturnOverlap(UUID payoutId, String evidenceJson) {
        jdbc.update("""
                UPDATE platform.payout
                SET conflict=true,bank_match_state='CONFLICT',updated_at=now()
                WHERE id=:payoutId""", Map.of("payoutId", payoutId));
        jdbc.update("""
                INSERT INTO platform.payout_case(payout_id,reason,evidence)
                VALUES (:payoutId,'BANK_RECEIPT_RETURN_OVERLAP',:evidence::jsonb)
                ON CONFLICT(payout_id,reason)
                DO NOTHING""", Map.of("payoutId", payoutId, "evidence", evidenceJson));
    }

    /** Creates the payout's ledger accounts if needed and locks them, after the payout and line locks. */
    public void lockPayoutLedgerAccounts(UUID payoutId) {
        var payout = Map.of("payoutId", payoutId);
        jdbc.queryForObject("SELECT platform.ensure_payout_accounts(:payoutId)", payout, Object.class);
        jdbc.queryForObject("SELECT platform.lock_payout_accounts(:payoutId)", payout, Object.class);
    }

    /** Simulated-bank accounts plus the provider's payment accounts, keyed by account purpose. */
    public Map<String, UUID> ledgerAccounts(ProviderScope scope, String currency) {
        var accounts = new HashMap<String, UUID>();
        var rows = jdbc.query(
                """
                SELECT id,account_purpose
                FROM platform.ledger_account
                WHERE organization_id=:organizationId
                    AND environment_id=:environmentId
                    AND currency=:currency
                    AND ((source_domain='SIMULATED_BANK' AND provider_account_id IS NULL)
                        OR(source_domain='PROVIDER' AND provider_account_id=:providerAccountId \
                AND balance_domain='PAYMENTS'))""",
                new MapSqlParameterSource()
                        .addValue("organizationId", scope.organizationId())
                        .addValue("environmentId", scope.environmentId())
                        .addValue("currency", currency)
                        .addValue("providerAccountId", scope.providerAccountId()),
                (rs, row) -> Map.entry(rs.getString("account_purpose"), rs.getObject("id", UUID.class)));
        rows.forEach(entry -> accounts.put(entry.getKey(), entry.getValue()));
        return accounts;
    }

    public void insertMatch(
            UUID matchId,
            UUID payoutId,
            UUID lineId,
            ProviderScope scope,
            String currency,
            String kind,
            long principalMinor,
            long feeMinor,
            UUID journalId) {
        jdbc.update(
                """
                INSERT INTO platform.payout_bank_match(id,payout_id,line_id,organization_id,environment_id,currency,
                    kind,principal_minor,fee_minor,journal_id)
                VALUES (:id,:payoutId,:lineId,:organizationId,:environmentId,:currency,:kind,:principal,:fee,:journal)""",
                new MapSqlParameterSource()
                        .addValue("id", matchId)
                        .addValue("payoutId", payoutId)
                        .addValue("lineId", lineId)
                        .addValue("organizationId", scope.organizationId())
                        .addValue("environmentId", scope.environmentId())
                        .addValue("currency", currency)
                        .addValue("kind", kind)
                        .addValue("principal", principalMinor)
                        .addValue("fee", feeMinor)
                        .addValue("journal", journalId));
    }

    public void updatePayoutPosition(
            UUID payoutId, long transit, long bankConfirmed, long returned, long suspense, BankMatchState state) {
        jdbc.update(
                """
                UPDATE platform.payout
                SET
                    transit_minor=:transit,
                    bank_confirmed_minor=:bankConfirmed,
                    returned_minor=:returned,
                    suspense_minor=:suspense,
                    bank_match_state=:state,
                    updated_at=now()
                WHERE id=:payoutId""",
                new MapSqlParameterSource()
                        .addValue("transit", transit)
                        .addValue("bankConfirmed", bankConfirmed)
                        .addValue("returned", returned)
                        .addValue("suspense", suspense)
                        .addValue("state", state.name())
                        .addValue("payoutId", payoutId));
    }

    public void updateLineAllocation(UUID lineId, long allocatedMinor, long feeAllocatedMinor) {
        jdbc.update("""
                UPDATE platform.bank_statement_line
                SET allocated_minor=:allocated,fee_allocated_minor=:feeAllocated
                WHERE id=:lineId""", Map.of("allocated", allocatedMinor, "feeAllocated", feeAllocatedMinor, "lineId", lineId));
    }

    /** True if the payout has receipts and every receipt's statement is complete and covers the arrival. */
    public boolean everyReceiptCovered(PayoutPosition payout) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT count(*)>0 AND coalesce(bool_and(s.complete
                  AND s.coverage_from<=:arrival AND s.coverage_until>:arrival \
                AND s.coverage_until<=clock_timestamp()),false)
                FROM platform.payout_bank_match m
                JOIN platform.bank_statement_line l ON l.id=m.line_id
                JOIN platform.bank_statement s ON s.id=l.statement_id
                WHERE m.payout_id=:payoutId AND m.kind='MATCH'
                """,
                new MapSqlParameterSource()
                        .addValue("arrival", Timestamp.from(payout.arrivalDate()))
                        .addValue("payoutId", payout.id()),
                Boolean.class));
    }

    /** Fully allocated credit lines matched to the payout on complete statements covering its arrival. */
    public long countCompleteMatchedReceipts(ProviderScope scope, PayoutPosition payout) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.bank_statement_line l
                JOIN platform.bank_statement s ON s.id=l.statement_id
                JOIN platform.payout_bank_match m ON m.line_id=l.id AND m.payout_id=:payoutId AND m.kind='MATCH'
                WHERE l.organization_id=:organizationId
                    AND l.environment_id=:environmentId
                    AND l.provider_account_id=:providerAccountId
                    AND NOT l.livemode
                    AND l.currency=:currency
                    AND :providerPayoutId=ANY(l.payout_references)
                    AND l.kind='CREDIT'
                    AND s.complete
                    AND s.coverage_from<=:arrival
                    AND s.coverage_until>:arrival
                    AND s.coverage_until<=clock_timestamp()
                    AND l.allocated_minor=l.principal_minor
                    AND (l.booked_at AT TIME ZONE 'UTC')::date BETWEEN ((CAST(:arrival AS timestamptz) AT TIME ZONE
                        'UTC')::date-7)
                    AND ((CAST(:arrival AS timestamptz) AT TIME ZONE 'UTC')::date+7)""", receiptParameters(scope, payout).addValue("payoutId", payout.id()), Long.class);
    }

    /** Credit lines referencing the payout that still have principal to allocate. */
    public long countUnallocatedReceipts(ProviderScope scope, PayoutPosition payout) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.bank_statement_line l
                WHERE l.organization_id=:organizationId
                    AND l.environment_id=:environmentId
                    AND l.provider_account_id=:providerAccountId
                    AND NOT l.livemode
                    AND l.currency=:currency
                    AND :providerPayoutId=ANY(l.payout_references)
                    AND l.kind='CREDIT'
                    AND l.allocated_minor<l.principal_minor""", receiptParameters(scope, payout), Long.class);
    }

    private static MapSqlParameterSource scoped(ProviderScope scope) {
        return new MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId())
                .addValue("environmentId", scope.environmentId())
                .addValue("providerAccountId", scope.providerAccountId())
                .addValue("livemode", scope.livemode());
    }

    private static MapSqlParameterSource receiptParameters(ProviderScope scope, PayoutPosition payout) {
        return new MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId())
                .addValue("environmentId", scope.environmentId())
                .addValue("providerAccountId", scope.providerAccountId())
                .addValue("currency", payout.currency())
                .addValue("providerPayoutId", payout.providerPayoutId())
                .addValue("arrival", payout.arrivalDate() == null ? null : Timestamp.from(payout.arrivalDate()));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
