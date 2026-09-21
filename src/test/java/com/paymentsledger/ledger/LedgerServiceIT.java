package com.paymentsledger.ledger;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("api")
class LedgerServiceIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pr03_isolated_services").withInitScript("db/pr02-runtime-role.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", () -> "payments_app");
        r.add("spring.datasource.password", () -> "pr02_test_only");
        r.add("spring.flyway.url", DB::getJdbcUrl);
        r.add("spring.flyway.user", DB::getUsername);
        r.add("spring.flyway.password", DB::getPassword);
    }
    @Autowired LedgerPostingService posting;
    @Autowired JournalQueryService journals;
    @Autowired ReversalService reversals;
    @Autowired BalanceRebuildService rebuild;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate runtime;
    final ObjectMapper mapper = new ObjectMapper();
    JdbcTemplate admin;
    final LedgerScope scope = new LedgerScope(id(1), id(11), "USD", id(21));
    static UUID id(int n) { return UUID.fromString("20000000-0000-0000-0000-" + "%012d".formatted(n)); }

    @BeforeEach void seed() {
        admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        admin.execute("TRUNCATE platform.organization, platform.currency_config CASCADE");
        admin.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2),('JPY',0)");
        for (int org : new int[]{1, 2}) {
            admin.update("INSERT INTO platform.organization(id,code) VALUES (?,?)", id(org), "ledger-org-" + org);
            admin.update("INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES (?,?,'fixture',false)", id(10+org), id(org));
            admin.update("INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES (?,?,?,'FAKE',?,false)", id(20+org), id(org), id(10+org), "ledger-provider-"+org);
            admin.update("INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES (?,?,'USD'),(?,?,'JPY')", id(org), id(10+org), id(org), id(10+org));
        }
        account(101, "PSP_FUNDS", "PROVIDER", id(21), "USD", 1);
        account(102, "PSP_FEE_EXPENSE", "PROVIDER", id(21), "USD", 1);
        account(103, "SALES_REVENUE", "INTERNAL", null, "USD", 1);
        account(104, "SALES_RETURNS", "INTERNAL", null, "USD", 1);
        account(105, "OPENING_EQUITY", "INTERNAL", null, "USD", 1);
        account(201, "PSP_FUNDS", "PROVIDER", id(22), "USD", 2);
        account(202, "SALES_REVENUE", "INTERNAL", null, "USD", 2);
        account(301, "PSP_FUNDS", "PROVIDER", id(21), "JPY", 1);
    }
    void account(int n, String purpose, String domain, UUID provider, String currency, int org) {
        admin.update("""
                INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,source_domain,provider_account_id,balance_domain)
                VALUES (?,?,?,?,?,?,?,?)
                """, id(n), id(org), id(10+org), currency, purpose, domain, provider, provider == null ? null : "PAYMENTS");
    }
    PostingCommand charge(String key) {
        return new PostingCommand(key,"L-CHARGE","PROVIDER","fixture_charge",
                mapper.createObjectNode().put("amount",10000).put("fee",300).put("net",9700),
                List.of(new PostingLine(id(101),Side.DEBIT,9700),new PostingLine(id(102),Side.DEBIT,300),new PostingLine(id(103),Side.CREDIT,10000)),null);
    }
    long count(String table) { return admin.queryForObject("SELECT count(*) FROM platform." + table,Long.class); }
    long balance(int account) {
        var rows = admin.queryForList("SELECT balance_minor FROM platform.balance_projection WHERE ledger_account_id=?",Long.class,id(account));
        return rows.isEmpty() ? 0 : rows.getFirst();
    }
    @Test void postsHandCalculatedChargeAndReplayOnlyOnce() {
        UUID journal = posting.post(scope,charge("fixture:charge"));
        assertThat(posting.post(scope,charge("fixture:charge"))).isEqualTo(journal);
        var result = journals.get(scope,journal);
        assertThat(result.entries()).hasSize(3);
        assertThat(result.sealedAt()).isNotNull();
        assertThat(count("financial_event")).isEqualTo(1);
        assertThat(count("journal")).isEqualTo(1);
        assertThat(balance(101)).isEqualTo(9700);
        assertThat(balance(102)).isEqualTo(300);
        assertThat(balance(103)).isEqualTo(-10000);
        assertThat(rebuild.rebuild(scope.moneyScope(),BalanceRebuildService.Mode.OPENING_JOURNAL,null).matches()).isTrue();
    }
    @Test void sameKeyChangedFactsOrScopeIsConflictNotDuplicate() {
        var command = charge("fixture:charge"); posting.post(scope,command);
        var changed = new PostingCommand(command.eventKey(),command.eventType(),command.sourceDomain(),command.sourceRef(),
                mapper.createObjectNode().put("fee",301),command.entries(),null);
        assertThatThrownBy(() -> posting.post(scope,changed)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException)e).errorCode()).isEqualTo(ErrorCode.EVIDENCE_CONFLICT);
        var otherCurrency = new LedgerScope(id(1),id(11),"JPY",id(21));
        assertThatThrownBy(() -> posting.post(otherCurrency,command)).isInstanceOf(ApiException.class);
        assertThat(count("journal")).isEqualTo(1);
        assertThat(balance(101)).isEqualTo(9700);
    }
    @Test void crossOrganizationCurrencyAndProviderAreRejectedAtomically() {
        for(int account : new int[]{201,301,999}) {
            var command = new PostingCommand("fixture:invalid:"+account,"L-TEST","PROVIDER","fixture",
                    mapper.createObjectNode(),List.of(new PostingLine(id(account),Side.DEBIT,100),new PostingLine(id(103),Side.CREDIT,100)),null);
            assertThatThrownBy(() -> posting.post(scope,command)).isInstanceOf(ApiException.class);
        }
        assertThat(count("financial_event")).isZero(); assertThat(count("journal")).isZero();
        assertThat(balance(101)).isZero();
    }
    @Test void joinsOuterTransactionAndRollsBackAllEffects() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            posting.post(scope,charge("fixture:rollback"));
            throw new IllegalStateException("simulated downstream failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("financial_event")).isZero(); assertThat(count("journal_entry")).isZero();
        assertThat(balance(101)).isZero();
    }
    @Test void concurrentReplayReturnsOneJournalAcrossDatabaseConnections() throws Exception {
        try(var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
            List<Future<UUID>> futures = new ArrayList<>();
            for(int i=0;i<8;i++) futures.add(executor.submit(() -> {
                ready.countDown(); if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
                return posting.post(scope,charge("fixture:concurrent"));
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            Set<UUID> results = new HashSet<>();
            for(var future:futures) results.add(future.get(20,TimeUnit.SECONDS));
            assertThat(results).hasSize(1);
        }
        assertThat(count("journal")).isEqualTo(1); assertThat(balance(101)).isEqualTo(9700);
    }
    @Test void reversalIsAppendOnlyIdempotentAndCannotBeRepeatedWithAnotherKey() {
        UUID original = posting.post(scope,charge("fixture:original"));
        UUID reverse = reversals.reverse(scope,original,"fixture:reverse","fixture correction");
        assertThat(reversals.reverse(scope,original,"fixture:reverse","fixture correction")).isEqualTo(reverse);
        assertThat(journals.get(scope,reverse).reversalOf()).isEqualTo(original);
        assertThat(journals.get(scope,original).entries()).isEqualTo(charge("fixture:original").entries());
        assertThatThrownBy(() -> reversals.reverse(scope,original,"fixture:reverse-again","fixture correction"))
                .isInstanceOf(ApiException.class);
        assertThat(count("journal")).isEqualTo(2); assertThat(balance(101)).isZero();
    }
    @Test void negativePspBalanceIsAllowedAndProjectionOverflowRollsBack() {
        var withdrawal = new PostingCommand("fixture:withdraw","L-REFUND","PROVIDER","fixture_refund",mapper.createObjectNode(),
                List.of(new PostingLine(id(104),Side.DEBIT,1),new PostingLine(id(101),Side.CREDIT,1)),null);
        posting.post(scope,withdrawal); assertThat(balance(101)).isEqualTo(-1);
        var large = new PostingCommand("fixture:large","L-TEST","PROVIDER","fixture",mapper.createObjectNode(),
                List.of(new PostingLine(id(104),Side.DEBIT,Long.MAX_VALUE),new PostingLine(id(101),Side.CREDIT,Long.MAX_VALUE)),null);
        assertThatThrownBy(() -> posting.post(scope,large)).isInstanceOf(ApiException.class);
        assertThat(count("journal")).isEqualTo(1); assertThat(balance(101)).isEqualTo(-1);
    }
    @Test void scopedJournalLookupDoesNotRevealForeignEvidence() {
        UUID journal=posting.post(scope,charge("fixture:read"));
        assertThatThrownBy(() -> journals.get(new LedgerScope(id(2),id(12),"USD",id(22)),journal))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException)e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }
    @Test void independentRebuildDetectsProjectionDriftWithoutRepairingHistory() {
        posting.post(scope,charge("fixture:drift"));
        admin.update("UPDATE platform.balance_projection SET balance_minor=balance_minor+1 WHERE ledger_account_id=?",id(101));
        var report=rebuild.rebuild(scope.moneyScope(),BalanceRebuildService.Mode.OPENING_JOURNAL,null);
        assertThat(report.matches()).isFalse();
        assertThat(report.accounts().stream().filter(a -> a.accountId().equals(id(101))).findFirst().orElseThrow().residual()).isEqualTo(BigInteger.ONE);
        assertThat(balance(101)).isEqualTo(9701); assertThat(count("journal")).isEqualTo(1);
    }
    @Test void openingJournalIsCountedOnceAndSnapshotModeCannotMixIt() {
        posting.post(scope,new PostingCommand("opening:fixture:cutoff","L-OPENING","INTERNAL","verified_fixture",mapper.createObjectNode(),
                List.of(new PostingLine(id(101),Side.DEBIT,100),new PostingLine(id(105),Side.CREDIT,100)),null));
        var report=rebuild.rebuild(scope.moneyScope(),BalanceRebuildService.Mode.OPENING_JOURNAL,null);
        assertThat(report.matches()).isTrue();
        Map<UUID,BigInteger> opening = new HashMap<>();
        for(int n:new int[]{101,102,103,104,105}) opening.put(id(n),BigInteger.valueOf(balance(n)));
        var snapshot=new BalanceRebuildService.OpeningSnapshot("fixture","a".repeat(64),Instant.EPOCH,opening);
        assertThatThrownBy(() -> rebuild.rebuild(scope.moneyScope(),BalanceRebuildService.Mode.EXTERNAL_OPENING_SNAPSHOT,snapshot))
                .isInstanceOf(ApiException.class).hasMessage("External opening cannot be combined with an opening journal");
    }
    @Test void externalOpeningRequiresExplicitEvidenceAndOnlyScansAfterCutoff() {
        posting.post(scope,charge("fixture:before-cutoff"));
        var cutoff=Instant.now();
        Map<UUID,BigInteger> opening = new HashMap<>();
        for(int n:new int[]{101,102,103,104,105}) opening.put(id(n),BigInteger.valueOf(balance(n)));
        var snapshot=new BalanceRebuildService.OpeningSnapshot("independent_local_fixture","b".repeat(64),cutoff,opening);
        // This next entry is strictly later than cutoff because the previous transaction has already committed.
        posting.post(scope,charge("fixture:after-cutoff"));
        var result=rebuild.rebuild(scope.moneyScope(),BalanceRebuildService.Mode.EXTERNAL_OPENING_SNAPSHOT,snapshot);
        assertThat(result.matches()).isTrue(); assertThat(result.mode()).isEqualTo(BalanceRebuildService.Mode.EXTERNAL_OPENING_SNAPSHOT);
        assertThat(result.openingCutoff()).isEqualTo(cutoff);
        assertThat(result.snapshotDigest()).isEqualTo("b".repeat(64));
        assertThatThrownBy(() -> rebuild.rebuild(scope.moneyScope(),BalanceRebuildService.Mode.EXTERNAL_OPENING_SNAPSHOT,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> rebuild.rebuild(scope.moneyScope(),BalanceRebuildService.Mode.OPENING_JOURNAL,snapshot)).isInstanceOf(ApiException.class);
    }
}
