package com.paymentsledger.financialingestion;

import java.sql.SQLException;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Retries a complete local transaction, never a provider call. */
final class FinancialTransactions {
    private final TransactionTemplate transaction;
    FinancialTransactions(PlatformTransactionManager manager){
        transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    <T> T run(Supplier<T> action){
        // A joined transaction can only be retried by its outer owner.
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())return action.get();
        for(int attempt=0;;attempt++)try{return transaction.execute(status->action.get());}
        catch(RuntimeException failure){
            boolean retry=false;
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql && ("40P01".equals(sql.getSQLState())||"40001".equals(sql.getSQLState())))retry=true;
            if(!retry||attempt>=2)throw failure;
        }
    }
}
