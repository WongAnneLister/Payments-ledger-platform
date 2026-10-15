package com.paymentsledger.ledger;

import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Independent audit, never a balance mutation path. Scan arithmetic does not call the posting function. */
@Service
public class BalanceRebuildService {
    public enum Mode {
        OPENING_JOURNAL,
        EXTERNAL_OPENING_SNAPSHOT
    }

    public record OpeningSnapshot(String source, String digest, Instant cutoff, Map<UUID, BigInteger> balances) {
        public OpeningSnapshot {
            if (source == null
                    || source.isBlank()
                    || digest == null
                    || !digest.matches("[a-fA-F0-9]{64}")
                    || cutoff == null
                    || balances == null
                    || balances.isEmpty()) throw invalid("Verified opening evidence is required");
            balances = Map.copyOf(balances);
            if (balances.values().stream()
                    .anyMatch(v -> v.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                            || v.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0))
                throw invalid("Opening amount exceeds bigint");
        }
    }

    /** Amounts use debit-minus-credit; a normal credit balance is negative. */
    public record AccountBalance(
            UUID accountId,
            String purpose,
            BigInteger rebuilt,
            BigInteger projected,
            BigInteger residual,
            long scannedEntries,
            boolean projectionPresent) {
        public boolean matches() {
            return residual.signum() == 0 && (scannedEntries == 0 || projectionPresent);
        }
    }

    public record Report(
            UUID runId,
            String ruleVersion,
            String sourceMode,
            LedgerScope scope,
            Mode mode,
            Instant startedAt,
            Instant completedAt,
            String databaseSnapshot,
            Instant openingCutoff,
            String snapshotSource,
            String snapshotDigest,
            boolean cutoffExclusionVerified,
            List<AccountBalance> accounts) {
        public Report {
            accounts = List.copyOf(accounts);
        }

        public boolean matches() {
            return accounts.stream().allMatch(AccountBalance::matches);
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BalanceRebuildService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Report rebuild(LedgerScope scope, Mode mode, OpeningSnapshot opening) {
        if (scope == null || scope.providerAccountId() != null || mode == null)
            throw invalid("Rebuild requires an explicit full organization/environment/currency scope and mode");
        if ((mode == Mode.EXTERNAL_OPENING_SNAPSHOT) != (opening != null))
            throw invalid("Opening modes cannot be mixed");
        Instant started = clock.instant();
        if (opening != null && opening.cutoff().isAfter(started))
            throw invalid("Opening cutoff cannot be in the future");
        String snapshot = jdbc.queryForObject("SELECT pg_current_snapshot()::text", String.class);
        var purposes = new LinkedHashMap<UUID, String>();
        jdbc.query(
                """
                SELECT id,account_purpose
                FROM platform.ledger_account
                WHERE organization_id=? AND environment_id=? AND currency=?
                ORDER BY id""",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> purposes.put(rs.getObject("id", UUID.class), rs.getString("account_purpose")),
                scope.organizationId(),
                scope.environmentId(),
                scope.currency());
        if (purposes.isEmpty()) throw invalid("No ledger accounts in rebuild scope");
        if (opening != null) {
            if (!opening.balances().keySet().equals(purposes.keySet()))
                throw invalid("Opening snapshot must cover exactly the selected account scope");
            Long count = jdbc.queryForObject(
                    """
                    SELECT count(*) FROM platform.financial_event WHERE organization_id=? AND environment_id=? AND currency=? AND event_type='L-OPENING'
                    """, Long.class, scope.organizationId(), scope.environmentId(), scope.currency());
            if (count != null && count != 0)
                throw invalid("External opening cannot be combined with an opening journal");
        }
        var rebuilt = new HashMap<UUID, BigInteger>();
        var counts = new HashMap<UUID, Long>();
        purposes.keySet()
                .forEach(id -> rebuilt.put(
                        id,
                        opening == null ? BigInteger.ZERO : opening.balances().get(id)));
        // Fetch individual immutable entries; no SQL projection accumulator or posting helper is reused.
        String after = opening == null ? "" : " AND j.created_at > ?";
        var arguments = new ArrayList<Object>(List.of(scope.organizationId(), scope.environmentId(), scope.currency()));
        if (opening != null) arguments.add(Timestamp.from(opening.cutoff()));
        jdbc.query(
                """
                SELECT e.ledger_account_id,e.side,e.amount_minor,j.created_at
                FROM platform.journal_entry e JOIN platform.journal j ON j.id=e.journal_id
                WHERE e.organization_id=? AND e.environment_id=? AND e.currency=? AND j.sealed_at IS NOT NULL
                """ + after,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    UUID account = rs.getObject("ledger_account_id", UUID.class);
                    BigInteger amount = BigInteger.valueOf(rs.getLong("amount_minor"));
                    if ("CREDIT".equals(rs.getString("side"))) amount = amount.negate();
                    rebuilt.put(account, rebuilt.get(account).add(amount));
                    counts.merge(account, 1L, Long::sum);
                    if (opening != null
                            && !rs.getTimestamp("created_at").toInstant().isAfter(opening.cutoff()))
                        throw invalid("Entry at or before opening cutoff entered the scan");
                },
                arguments.toArray());
        var projected = new HashMap<UUID, BigInteger>();
        jdbc.query(
                """
                SELECT ledger_account_id,balance_minor
                FROM platform.balance_projection
                WHERE organization_id=? AND environment_id=? AND currency=?""",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> projected.put(
                        rs.getObject("ledger_account_id", UUID.class), BigInteger.valueOf(rs.getLong("balance_minor"))),
                scope.organizationId(),
                scope.environmentId(),
                scope.currency());
        var accounts = new ArrayList<AccountBalance>();
        purposes.forEach((id, purpose) -> {
            var actual = projected.getOrDefault(id, BigInteger.ZERO);
            accounts.add(new AccountBalance(
                    id,
                    purpose,
                    rebuilt.get(id),
                    actual,
                    actual.subtract(rebuilt.get(id)),
                    counts.getOrDefault(id, 0L),
                    projected.containsKey(id)));
        });
        return new Report(
                UUID.randomUUID(),
                "PR03-1",
                "LOCAL_MODEL",
                scope,
                mode,
                started,
                clock.instant(),
                snapshot,
                opening == null ? null : opening.cutoff(),
                opening == null ? null : opening.source(),
                opening == null ? null : opening.digest(),
                opening != null,
                accounts);
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }
}
