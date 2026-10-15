package com.paymentsledger.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentsledger.bank.domain.BankStatement;
import com.paymentsledger.bank.domain.BankStatementLine;
import com.paymentsledger.bank.domain.BankStatementParser;
import com.paymentsledger.bank.repository.BankStatementRepository;
import com.paymentsledger.provider.ProviderScope;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports simulated bank statements. Stable source identities and a canonical digest make whole-file reimport
 * idempotent: the same evidence returns the existing statement, changed evidence under the same identity is rejected.
 */
@Service
public class BankStatementImporter {
    private final BankStatementRepository statements;
    private final TransactionTemplate tx;

    public BankStatementImporter(BankStatementRepository statements, PlatformTransactionManager transactions) {
        this.statements = statements;
        this.tx = new TransactionTemplate(transactions);
    }

    public UUID importStatement(ProviderScope scope, JsonNode input) {
        BankStatement statement = BankStatementParser.parse(input);
        if (scope == null || scope.livemode() || !statements.isActiveTestScope(scope, statement.currency()))
            throw new IllegalArgumentException("Trusted enabled test scope required");
        try {
            return tx.execute(status -> {
                UUID id = UUID.randomUUID();
                int inserted = statements.insertIfAbsent(id, scope, statement);
                var stored = statements.find(scope, statement);
                if (!statement.digest().equals(stored.digest()))
                    throw new IllegalArgumentException("Fixture identity has different immutable evidence");
                if (inserted == 0) return stored.id();
                for (BankStatementLine line : statement.lines()) statements.insertLine(id, scope, statement, line);
                return id;
            });
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalArgumentException("Bank source line already exists in another fixture", duplicate);
        }
    }
}
