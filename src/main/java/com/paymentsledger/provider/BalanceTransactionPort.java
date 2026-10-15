package com.paymentsledger.provider;

import com.paymentsledger.provider.ProviderModels.*;

/** Provider facts and evidence; callers invoke this port outside local database transactions. */
public interface BalanceTransactionPort {
    ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery query);

    ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope scope, String balanceTransactionId);
}
