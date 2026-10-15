package com.paymentsledger.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.bank.domain.BankLine;
import com.paymentsledger.bank.domain.BankMatchRules;
import com.paymentsledger.bank.domain.BankMatchRules.ReturnOverlap;
import com.paymentsledger.bank.domain.BankMatchRules.Settlement;
import com.paymentsledger.bank.domain.PayoutPosition;
import com.paymentsledger.bank.repository.BankMatchRepository;
import com.paymentsledger.ledger.LedgerPostingService;
import com.paymentsledger.ledger.LedgerScope;
import com.paymentsledger.ledger.PostingCommand;
import com.paymentsledger.ledger.PostingLine;
import com.paymentsledger.ledger.Side;
import com.paymentsledger.provider.ProviderScope;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Allocates simulated bank statement lines to payouts and posts the resulting bank cash, fee and clawback entries.
 *
 * <p>Lock order within the transaction: payout (60), bank line (65), payout ledger accounts. Money history is
 * append-only; a match is identified by (payout, line, kind) so retries return the original match.
 */
@Service
public class BankMatcher {
    public record MatchResult(String state, UUID matchId) {}

    /** PostgreSQL deadlock and serialization failures: the whole match is retried. */
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40P01", "40001");

    private static final int MAX_RETRIES = 2;

    private final BankMatchRepository matches;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final LedgerPostingService ledger;

    public BankMatcher(
            BankMatchRepository matches,
            PlatformTransactionManager transactions,
            ObjectMapper json,
            LedgerPostingService ledger) {
        this.matches = matches;
        this.tx = new TransactionTemplate(transactions);
        this.json = json.copy().findAndRegisterModules();
        this.ledger = ledger;
    }

    public MatchResult match(ProviderScope scope, UUID payoutId, UUID lineId, long principal) {
        if (principal <= 0) throw new IllegalArgumentException("Positive principal allocation required");
        // Inside a caller's transaction, a retry cannot restart it; let the caller decide.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            return apply(scope, payoutId, lineId, principal);
        for (int attempt = 0; ; attempt++)
            try {
                return tx.execute(status -> apply(scope, payoutId, lineId, principal));
            } catch (RuntimeException failure) {
                if (!isRetryable(failure) || attempt >= MAX_RETRIES) throw failure;
            }
    }

    private MatchResult apply(ProviderScope scope, UUID payoutId, UUID lineId, long principal) {
        PayoutPosition payout = requirePayout(scope, payoutId, true);
        BankLine line = matches.lockLine(lineId).orElseThrow(BankMatchRules::lineScopeMismatch);
        BankMatchRules.requireLineForPayout(scope, payout, line);

        String kind = BankMatchRules.matchKind(line);
        var prior = matches.findMatch(payoutId, lineId, kind);
        if (prior.isPresent()) {
            BankMatchRules.requireSameRetry(prior.get().principalMinor(), principal);
            return new MatchResult(payout.matchState().name(), prior.get().id());
        }

        return switch (BankMatchRules.allocate(payoutId, payout, line, principal)) {
            case ReturnOverlap overlap -> {
                matches.recordReturnOverlap(
                        payoutId,
                        json.createObjectNode()
                                .put("lineId", lineId.toString())
                                .put("principalMinor", principal)
                                .toString());
                yield new MatchResult("CONFLICT", null);
            }
            case Settlement settlement -> settle(scope, payoutId, payout, line, principal, settlement);
        };
    }

    private MatchResult settle(
            ProviderScope scope,
            UUID payoutId,
            PayoutPosition payout,
            BankLine line,
            long principal,
            Settlement settlement) {
        matches.lockPayoutLedgerAccounts(payoutId);
        Map<String, UUID> accounts = matches.ledgerAccounts(scope, payout.currency());
        var facts = json.createObjectNode()
                .put("evidenceType", "SIMULATED_BANK_EVIDENCE")
                .put("payoutId", payoutId.toString())
                .put("lineId", line.id().toString())
                .put("principalMinor", principal)
                .put("feeMinor", settlement.feeMinor());
        UUID journal = ledger.post(
                new LedgerScope(
                        scope.organizationId(), scope.environmentId(), payout.currency(), scope.providerAccountId()),
                new PostingCommand(
                        settlement.eventKey(),
                        settlement.postingType(),
                        "SIMULATED_BANK",
                        line.id().toString(),
                        facts,
                        postingLines(settlement.postings(), accounts),
                        null));
        UUID match = UUID.randomUUID();
        matches.insertMatch(
                match,
                payoutId,
                line.id(),
                scope,
                payout.currency(),
                settlement.matchKind(),
                principal,
                settlement.feeMinor(),
                journal);
        matches.updatePayoutPosition(
                payoutId,
                settlement.transitMinor(),
                settlement.bankConfirmedMinor(),
                settlement.returnedMinor(),
                settlement.suspenseMinor(),
                settlement.state());
        matches.updateLineAllocation(line.id(), settlement.lineAllocatedMinor(), settlement.lineFeeAllocatedMinor());
        return new MatchResult(settlement.state().name(), match);
    }

    /** External completeness is separate from the commit-time allocation invariant. Reconciliation consumes it. */
    public String evidenceState(ProviderScope scope, UUID payoutId) {
        PayoutPosition payout = requirePayout(scope, payoutId, false);
        var plan = BankMatchRules.evidencePlan(payout);
        var state = switch (plan.check()) {
            case NONE -> plan.state();
            case SETTLEMENT_COVERAGE -> BankMatchRules.settlementEvidence(matches.everyReceiptCovered(payout));
            case SHORT_RECEIPT ->
                BankMatchRules.shortReceiptEvidence(
                        matches.countCompleteMatchedReceipts(scope, payout),
                        matches.countUnallocatedReceipts(scope, payout),
                        payout.transitMinor());
        };
        return state.name();
    }

    private PayoutPosition requirePayout(ProviderScope scope, UUID payoutId, boolean lock) {
        if (scope == null || scope.livemode()) throw new IllegalArgumentException("Test provider scope required");
        return (lock ? matches.lockPayout(scope, payoutId) : matches.findPayout(scope, payoutId))
                .orElseThrow(() -> new IllegalArgumentException("Payout outside scope"));
    }

    /** Zero amounts are omitted; a nonzero amount requires the account to exist. */
    private static List<PostingLine> postingLines(List<BankMatchRules.Posting> postings, Map<String, UUID> accounts) {
        var lines = new ArrayList<PostingLine>();
        for (var posting : postings)
            if (posting.amountMinor() != 0)
                lines.add(new PostingLine(
                        Objects.requireNonNull(accounts.get(posting.purpose())),
                        posting.amountMinor() > 0 ? Side.DEBIT : Side.CREDIT,
                        Math.abs(posting.amountMinor())));
        return lines;
    }

    private static boolean isRetryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && RETRYABLE_SQL_STATES.contains(sql.getSQLState())) return true;
        return false;
    }
}
