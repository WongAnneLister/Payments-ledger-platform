package com.paymentsledger.financialingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.provider.ProviderModels.BalanceTransactionFact;
import com.paymentsledger.provider.ProviderScope;
import com.paymentsledger.ledger.*;
import java.util.*;

/** Signed provider minor units are translated into positive, balanced lines; no account provisioning. */
public final class BTJournalMapper {
    public PostingCommand map(ProviderScope scope,BalanceTransactionFact fact,FinancialEventNormalizer.Kind kind,Map<String,UUID> accounts) {
        var lines=new ArrayList<PostingLine>();
        add(lines,accounts,"PSP_FUNDS",fact.netMinor());
        switch(kind) {
            case CHARGE -> {add(lines,accounts,"PSP_FEE_EXPENSE",fact.feeMinor());add(lines,accounts,"SALES_REVENUE",Math.negateExact(fact.amountMinor()));}
            case REFUND -> {add(lines,accounts,"PSP_FEE_EXPENSE",fact.feeMinor());add(lines,accounts,"SALES_RETURNS",Math.negateExact(fact.amountMinor()));}
            case UNKNOWN -> add(lines,accounts,"SUSPENSE",Math.negateExact(fact.netMinor()));
            default -> throw new IllegalArgumentException("Evidence is not eligible for posting");
        }
        if(lines.size()<2)throw new IllegalArgumentException("Zero movement has no journal");
        var facts=FinancialFacts.immutable(new ObjectMapper().findAndRegisterModules(),fact);
        return new PostingCommand("bt:"+scope.providerAccountId()+":"+scope.livemode()+":"+fact.btId(),"L-"+kind.name(),"PROVIDER",fact.btId(),facts,lines,null);
    }
    private static void add(List<PostingLine> lines,Map<String,UUID> accounts,String purpose,long signedDebit) {
        if(signedDebit==0)return;
        UUID account=accounts.get(purpose);
        if(account==null)throw new IllegalArgumentException("Missing configured account: "+purpose);
        lines.add(new PostingLine(account,signedDebit>0?Side.DEBIT:Side.CREDIT,signedDebit>0?signedDebit:Math.negateExact(signedDebit)));
    }
}
