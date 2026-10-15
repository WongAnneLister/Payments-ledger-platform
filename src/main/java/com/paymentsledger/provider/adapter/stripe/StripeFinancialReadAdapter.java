package com.paymentsledger.provider.adapter.stripe;

import static com.paymentsledger.provider.adapter.stripe.StripeGateway.*;

import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.stripe.model.*;
import com.stripe.param.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Reads provider facts only; never allocates payouts or infers historic balances. */
public final class StripeFinancialReadAdapter
        implements BalanceTransactionPort, DisputePort, PayoutPort, BalancePort, ReportPort {
    private final StripeGateway gateway;

    public StripeFinancialReadAdapter(StripeGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway);
    }

    @Override
    public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery query) {
        return gateway.call(query.scope(), null, c -> {
            if (query.payoutId() != null) {
                var payout = c.fetch(() -> c.client()
                        .v1()
                        .payouts()
                        .retrieve(
                                required(query.payoutId()),
                                PayoutRetrieveParams.builder().build(),
                                c.options()));
                var fact = payoutFact(payout);
                if (!query.payoutId().equals(fact.payoutId())) throw mismatch();
                if (!fact.automatic()) throw unavailable("manual_payout_membership_unsupported");
            }
            var params = BalanceTransactionListParams.builder()
                    .setCreated(BalanceTransactionListParams.Created.builder()
                            .setGte(epoch(query.createdGte()))
                            .setLt(epoch(query.createdLt()))
                            .build())
                    .setStartingAfter(query.startingAfterCursor())
                    .setLimit(limit(query.limit()))
                    .setPayout(query.payoutId())
                    .build();
            var response = c.fetch(() -> c.client().v1().balanceTransactions().list(params, c.options()));
            var page = page(
                    response,
                    StripeFinancialReadAdapter::balanceTransactionFact,
                    BalanceTransactionFact::btId,
                    query.startingAfterCursor());
            return new BalanceTransactionPage(page.items(), page.hasMore(), page.nextCursor());
        });
    }

    @Override
    public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope scope, String id) {
        return gateway.call(scope, null, c -> {
            var fact = balanceTransactionFact(c.fetch(() -> c.client()
                    .v1()
                    .balanceTransactions()
                    .retrieve(
                            required(id),
                            BalanceTransactionRetrieveParams.builder().build(),
                            c.options())));
            if (!id.equals(fact.btId())) throw mismatch();
            return fact;
        });
    }

    @Override
    public ProviderCallResult<Page<DisputeFact>> listDisputes(PageQuery query) {
        return gateway.call(query.scope(), null, c -> {
            var params = DisputeListParams.builder()
                    .setCreated(DisputeListParams.Created.builder()
                            .setGte(epoch(query.createdGte()))
                            .setLt(epoch(query.createdLt()))
                            .build())
                    .setStartingAfter(query.startingAfterCursor())
                    .setLimit(limit(query.limit()))
                    .build();
            return page(
                    c.fetch(() -> c.client().v1().disputes().list(params, c.options())),
                    StripeFinancialReadAdapter::disputeFact,
                    DisputeFact::disputeId,
                    query.startingAfterCursor());
        });
    }

    @Override
    public ProviderCallResult<DisputeFact> getDispute(ProviderScope scope, String id) {
        return gateway.call(scope, null, c -> {
            var fact = disputeFact(c.fetch(() -> c.client()
                    .v1()
                    .disputes()
                    .retrieve(required(id), DisputeRetrieveParams.builder().build(), c.options())));
            if (!id.equals(fact.disputeId())) throw mismatch();
            return fact;
        });
    }

    @Override
    public ProviderCallResult<Page<PayoutFact>> listPayouts(PageQuery query) {
        return gateway.call(query.scope(), null, c -> {
            var params = PayoutListParams.builder()
                    .setCreated(PayoutListParams.Created.builder()
                            .setGte(epoch(query.createdGte()))
                            .setLt(epoch(query.createdLt()))
                            .build())
                    .setStartingAfter(query.startingAfterCursor())
                    .setLimit(limit(query.limit()))
                    .build();
            return page(
                    c.fetch(() -> c.client().v1().payouts().list(params, c.options())),
                    StripeFinancialReadAdapter::payoutFact,
                    PayoutFact::payoutId,
                    query.startingAfterCursor());
        });
    }

    @Override
    public ProviderCallResult<PayoutFact> getPayout(ProviderScope scope, String id) {
        return gateway.call(scope, null, c -> {
            var fact = payoutFact(c.fetch(() -> c.client()
                    .v1()
                    .payouts()
                    .retrieve(required(id), PayoutRetrieveParams.builder().build(), c.options())));
            if (!id.equals(fact.payoutId())) throw mismatch();
            return fact;
        });
    }

    @Override
    public ProviderCallResult<BalanceSnapshot> getBalance(ProviderScope scope, String currency, String domain) {
        if (!"PAYMENTS".equals(domain)) return gateway.unavailable(scope, "balance_domain_contract_unverified");
        return gateway.call(scope, null, c -> {
            var balance = c.fetch(() -> c.client()
                    .v1()
                    .balance()
                    .retrieve(BalanceRetrieveParams.builder().build(), c.options()));
            testMode(balance.getLivemode());
            if (!"balance".equals(balance.getObject())) throw mismatch();
            long available = currencyAmount(
                    balance.getAvailable(), currency, Balance.Available::getCurrency, Balance.Available::getAmount);
            long pending = currencyAmount(
                    balance.getPending(), currency, Balance.Pending::getCurrency, Balance.Pending::getAmount);
            return new BalanceSnapshot(
                    currency.toLowerCase(Locale.ROOT),
                    "PAYMENTS",
                    available,
                    pending,
                    c.clock().instant());
        });
    }

    @Override
    public ProviderCallResult<ReportFact> requestReport(ReportRequest request) {
        return gateway.unavailable(request.scope(), "historical_report_contract_unverified");
    }

    @Override
    public ProviderCallResult<ReportFact> fetchReport(ProviderScope scope, String id) {
        return gateway.unavailable(scope, "historical_report_contract_unverified");
    }

    private static BalanceTransactionFact balanceTransactionFact(BalanceTransaction value) {
        if (!"balance_transaction".equals(value.getObject()) || value.getFeeDetails() == null) throw mismatch();
        String domain = required(value.getBalanceType()).toUpperCase(Locale.ROOT);
        if (!Set.of("PAYMENTS", "ISSUING", "REFUND_AND_DISPUTE_PREFUNDING", "RISK_RESERVED")
                .contains(domain)) throw mismatch();
        var fees = value.getFeeDetails().stream()
                .map(f -> new FeeDetail(
                        number(f.getAmount()), required(f.getCurrency()), required(f.getType()), f.getDescription()))
                .toList();
        var fact = new BalanceTransactionFact(
                required(value.getId()),
                required(value.getType()),
                number(value.getAmount()),
                number(value.getFee()),
                number(value.getNet()),
                required(value.getCurrency()),
                domain,
                value.getSource(),
                sourceType(value),
                required(value.getStatus()),
                instant(value.getCreated()),
                instant(value.getAvailableOn()),
                fees,
                null);
        return new BalanceTransactionFact(
                fact.btId(),
                fact.type(),
                fact.amountMinor(),
                fact.feeMinor(),
                fact.netMinor(),
                fact.currency(),
                fact.balanceDomain(),
                fact.sourceObjectId(),
                fact.sourceObjectType(),
                fact.status(),
                fact.createdAt(),
                fact.availableOn(),
                fact.feeDetails(),
                digest(fact));
    }

    private static DisputeFact disputeFact(Dispute value) {
        testMode(value.getLivemode());
        if (!"dispute".equals(value.getObject()) || value.getBalanceTransactions() == null) throw mismatch();
        if (value.getChargeObject() != null) testMode(value.getChargeObject().getLivemode());
        if (value.getPaymentIntentObject() != null)
            testMode(value.getPaymentIntentObject().getLivemode());
        var btIds = value.getBalanceTransactions().stream()
                .map(bt -> balanceTransactionFact(bt).btId())
                .toList();
        Long due = value.getEvidenceDetails() == null
                ? null
                : value.getEvidenceDetails().getDueBy();
        return new DisputeFact(
                required(value.getId()),
                required(value.getCharge()),
                required(value.getStatus()),
                required(value.getReason()),
                number(value.getAmount()),
                required(value.getCurrency()),
                due == null ? null : instant(due),
                btIds);
    }

    private static PayoutFact payoutFact(Payout value) {
        testMode(value.getLivemode());
        if (!"payout".equals(value.getObject()) || value.getAutomatic() == null) throw mismatch();
        return new PayoutFact(
                required(value.getId()),
                required(value.getStatus()),
                number(value.getAmount()),
                required(value.getCurrency()),
                value.getBalanceTransaction(),
                value.getFailureBalanceTransaction(),
                value.getAutomatic(),
                instant(value.getArrivalDate()),
                value.getFailureCode());
    }

    private static <S extends StripeObject & HasId, T> Page<T> page(
            StripeCollection<S> response, Function<S, T> map, Function<T, String> id, String requestedCursor) {
        if (response.getData() == null || response.getHasMore() == null) throw mismatch();
        var items = response.getData().stream().map(map).toList();
        var ids = new HashSet<String>();
        for (T item : items) {
            String itemId = required(id.apply(item));
            if (!ids.add(itemId) || itemId.equals(requestedCursor)) throw mismatch();
        }
        boolean more = response.getHasMore();
        if (more && items.isEmpty()) throw mismatch();
        return new Page<>(items, more, more ? required(id.apply(items.getLast())) : null);
    }

    private static <T> long currencyAmount(
            List<T> balances, String currency, Function<T, String> code, Function<T, Long> amount) {
        if (balances == null) throw mismatch();
        required(currency);
        Long found = null;
        for (T entry : balances) {
            String entryCurrency = required(code.apply(entry));
            long entryAmount = number(amount.apply(entry));
            if (entryCurrency.equalsIgnoreCase(currency)) {
                if (found != null) throw mismatch();
                found = entryAmount;
            }
        }
        if (found == null) throw unavailable("balance_currency_unavailable");
        return found;
    }

    private static String sourceType(BalanceTransaction value) {
        var expanded = value.getSourceObject();
        if (expanded instanceof StripeObject object) {
            // Stripe's polymorphic source includes models both with and without a livemode field.
            // The pinned SDK getter identifies which sources require an explicit test-mode value.
            try {
                testMode((Boolean) object.getClass().getMethod("getLivemode").invoke(object));
            } catch (NoSuchMethodException ignored) {
                /* This SDK model has no livemode property. */
            } catch (ReflectiveOperationException error) {
                throw mismatch();
            }
            try {
                return required(
                        (String) object.getClass().getMethod("getObject").invoke(object));
            } catch (NoSuchMethodException ignored) {
                return null;
            } catch (ReflectiveOperationException error) {
                throw mismatch();
            }
        }
        String source = value.getSource();
        if (source == null) return null;
        if (source.startsWith("ch_")) return "charge";
        if (source.startsWith("re_")) return "refund";
        if ((source.startsWith("dp_") || source.startsWith("du_"))) return "dispute";
        if (source.startsWith("po_")) return "payout";
        return null;
    }

    private static long number(Long value) {
        if (value == null) throw mismatch();
        return value;
    }

    private static long limit(int requested) {
        return Math.min(requested, 100);
    }
    // Stripe timestamps are whole seconds: ceiling preserves both >= and < membership.
    private static long epoch(Instant value) {
        return value.getNano() == 0 ? value.getEpochSecond() : Math.addExact(value.getEpochSecond(), 1);
    }
}
