package com.paymentsledger.ledger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class LedgerDatabaseIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static final UUID ORG=id(1), ENV=id(2), PROVIDER=id(3), PSP=id(10), REVENUE=id(11), BANK=id(12);
    static UUID id(int n) { return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(n)); }
    static Connection owner() throws SQLException { return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); }
    static Connection app() throws SQLException { return DriverManager.getConnection(PG.getJdbcUrl(), "payments_app", "ledger-test"); }
    static void sql(Connection c,String s) throws SQLException { try(var st=c.createStatement()){st.execute(s);} }
    static long count(String table) throws SQLException { try(var c=owner();var st=c.createStatement();var r=st.executeQuery("SELECT count(*) FROM platform."+table)){r.next();return r.getLong(1);} }
    @BeforeAll static void migrate() throws Exception {
        try(var c=owner()){sql(c,"CREATE ROLE payments_app LOGIN PASSWORD 'ledger-test'");}
        Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()).load().migrate();
    }
    @BeforeEach void seed() throws Exception {
        // Destructive reset is confined to this newly created Testcontainers database.
        try(var c=owner()) {
            sql(c,"TRUNCATE platform.organization,platform.currency_config CASCADE");
            sql(c,"INSERT INTO platform.organization(id,code) VALUES ('"+ORG+"','ledger-test')");
            sql(c,"INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES ('"+ENV+"','"+ORG+"','test',false)");
            sql(c,"INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES ('"+PROVIDER+"','"+ORG+"','"+ENV+"','FAKE','ledger-test',false)");
            sql(c,"INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2),('JPY',0)");
            sql(c,"INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES ('"+ORG+"','"+ENV+"','USD'),('"+ORG+"','"+ENV+"','JPY')");
            account(c,PSP,"PSP_FUNDS","PROVIDER",PROVIDER,"PAYMENTS");
            account(c,REVENUE,"SALES_REVENUE","INTERNAL",null,null);
            account(c,BANK,"BANK_CASH","SIMULATED_BANK",null,null);
        }
    }
    static void account(Connection c,UUID id,String purpose,String source,UUID provider,String domain) throws SQLException {
        try(var p=c.prepareStatement("INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,source_domain,provider_account_id,balance_domain) VALUES (?,?,?,'USD',?,?,?,?)")){
            p.setObject(1,id);p.setObject(2,ORG);p.setObject(3,ENV);p.setString(4,purpose);p.setString(5,source);p.setObject(6,provider);p.setString(7,domain);p.executeUpdate();
        }
    }
    static String entries(UUID debit,UUID credit,long amount) {return "[{\"accountId\":\""+debit+"\",\"side\":\"DEBIT\",\"amount\":"+amount+"},{\"accountId\":\""+credit+"\",\"side\":\"CREDIT\",\"amount\":"+amount+"}]";}
    static UUID post(Connection c,String key,String facts,String entries,UUID reversal) throws SQLException {
        try(var p=c.prepareStatement("SELECT platform.post_ledger(?,?,'USD',?,?,'L-CHARGE','PROVIDER','evidence',?::jsonb,?::jsonb,?)")){
            p.setObject(1,ORG);p.setObject(2,ENV);p.setObject(3,PROVIDER);p.setString(4,key);p.setString(5,facts);p.setString(6,entries);p.setObject(7,reversal);
            try(var r=p.executeQuery()){r.next();return r.getObject(1,UUID.class);}
        }
    }
    static void state(String expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(SQLException.class).satisfies(e->assertThat(((SQLException)e).getSQLState()).isEqualTo(expected));
    }
    static long balance(UUID account) throws SQLException {try(var c=app();var p=c.prepareStatement("SELECT balance_minor FROM platform.balance_projection WHERE ledger_account_id=?")){p.setObject(1,account);try(var r=p.executeQuery()){return r.next()?r.getLong(1):0;}}}
    @Test void postsAndReplaysCanonicalEntriesWithoutChangingProjection() throws Exception {
        try(var c=app()) {
            assertThat(c.getMetaData().getUserName()).isEqualTo("payments_app");
            UUID journal=post(c,"one","{\"fee\":3}",entries(PSP,REVENUE,100),null);
            assertThat(post(c,"one","{\"fee\":3}","[{\"accountId\":\""+REVENUE+"\",\"side\":\"CREDIT\",\"amount\":100},{\"accountId\":\""+PSP+"\",\"side\":\"DEBIT\",\"amount\":100}]",null)).isEqualTo(journal);
            state("P0003",()->post(c,"one","{\"fee\":4}",entries(PSP,REVENUE,100),null));
            state("P0003",()->post(c,"one","{\"fee\":3}",entries(PSP,REVENUE,101),null));
        }
        assertThat(count("journal")).isEqualTo(1);assertThat(balance(PSP)).isEqualTo(100);assertThat(balance(REVENUE)).isEqualTo(-100);
    }
    @Test void permitsNegativePspAndMixedCompatibleSourceDomains() throws Exception {
        try(var c=app()){post(c,"bank","{}",entries(BANK,PSP,20),null);}
        assertThat(balance(PSP)).isEqualTo(-20);assertThat(balance(BANK)).isEqualTo(20);
    }
    @Test void rejectsInvalidInputsWithoutPartialWrites() throws Exception {
        try(var c=app()){
            for(String lines:new String[]{"[]", "[{\"accountId\":\""+PSP+"\",\"side\":\"DEBIT\",\"amount\":1}]", entries(PSP,REVENUE,0),entries(PSP,REVENUE,-1), entries(PSP,REVENUE,2).replace("CREDIT","DEBIT"), entries(PSP,REVENUE,1).replace("\"amount\":1","\"amount\":1.5"), entries(PSP,REVENUE,Long.MAX_VALUE).replace("]",","+entries(PSP,REVENUE,1).substring(1))})
                state("23514",()->post(c,"bad","{}",lines,null));
            state("P0002",()->post(c,"missing","{}",entries(id(99),REVENUE,10),null));
        }
        assertThat(count("financial_event")).isZero();assertThat(count("journal")).isZero();assertThat(count("balance_projection")).isZero();
    }
    @Test void projectionOverflowRollsBackEveryNewObject() throws Exception {
        try(var c=app()){
            post(c,"max","{}",entries(PSP,REVENUE,Long.MAX_VALUE),null);
            state("23514",()->post(c,"overflow","{}",entries(PSP,REVENUE,1),null));
        }
        assertThat(count("financial_event")).isEqualTo(1);assertThat(balance(PSP)).isEqualTo(Long.MAX_VALUE);
    }
    @Test void runtimeCannotMutateOrAppendToSealedJournal() throws Exception {
        try(var c=app()){
            UUID journal=post(c,"sealed","{}",entries(PSP,REVENUE,10),null);
            for(String table:new String[]{"financial_event","journal","journal_entry","balance_projection","ledger_account"}){
                state("42501",()->sql(c,"DELETE FROM platform."+table));
                state("42501",()->sql(c,"TRUNCATE platform."+table));
            }
            state("42501",()->sql(c,"UPDATE platform.journal SET sealed_at=NULL"));
            state("42501",()->sql(c,"UPDATE platform.journal_entry SET amount_minor=11"));
            state("42501",()->sql(c,"UPDATE platform.financial_event SET facts='{}'"));
            state("42501",()->sql(c,"UPDATE platform.balance_projection SET balance_minor=0"));
            state("23514",()->entry(c,journal,PSP,"DEBIT",1,3));
            state("23514",()->sql(c,"INSERT INTO platform.journal_entry(journal_id,ledger_account_id,organization_id,environment_id,currency,side,amount_minor,line_no) VALUES ('"+journal+"','"+PSP+"','"+ORG+"','"+ENV+"','USD','DEBIT',1,3),('"+journal+"','"+REVENUE+"','"+ORG+"','"+ENV+"','USD','CREDIT',1,4)"));
        }
        assertThat(count("journal_entry")).isEqualTo(2);
    }
    static UUID event(Connection c,String key) throws SQLException { UUID id=UUID.randomUUID();sql(c,"INSERT INTO platform.financial_event(id,organization_id,environment_id,currency,provider_account_id,event_key,event_type,source_domain,source_ref,facts) VALUES ('"+id+"','"+ORG+"','"+ENV+"','USD','"+PROVIDER+"','"+key+"','L-TEST','PROVIDER','test','{}')");return id;}
    static UUID journal(Connection c,UUID event) throws SQLException {UUID id=UUID.randomUUID();sql(c,"INSERT INTO platform.journal(id,organization_id,environment_id,currency,financial_event_id) VALUES ('"+id+"','"+ORG+"','"+ENV+"','USD','"+event+"')");return id;}
    static void entry(Connection c,UUID journal,UUID account,String side,long amount,int line) throws SQLException {sql(c,"INSERT INTO platform.journal_entry(journal_id,ledger_account_id,organization_id,environment_id,currency,side,amount_minor,line_no) VALUES ('"+journal+"','"+account+"','"+ORG+"','"+ENV+"','USD','"+side+"',"+amount+","+line+")");}
    @Test void deferredConstraintsRejectOrphanEventsAndUnsealedEmptySingleUnbalancedAndOverflowJournals() throws Exception {
        for(int n=0;n<6;n++)try(var c=app()) {
            c.setAutoCommit(false); UUID event=event(c,"direct");
            if(n>0){UUID j=journal(c,event);if(n>1)entry(c,j,PSP,"DEBIT",Long.MAX_VALUE,1);if(n>2)entry(c,j,REVENUE,"CREDIT",n==3?1:Long.MAX_VALUE,2);if(n==5){entry(c,j,PSP,"DEBIT",1,3);entry(c,j,REVENUE,"CREDIT",1,4);}}
            state("23514",c::commit); c.rollback();
        }
        assertThat(count("financial_event")).isZero();assertThat(count("journal")).isZero();
    }
    @Test void cannotForgeConstructionTransactionOrSealEvenUsingCustomSettings() throws Exception {
        try(var c=app()){
            c.setAutoCommit(false);UUID e=event(c,"forge");
            sql(c,"SET LOCAL platform.posting='on'");
            state("23514",()->sql(c,"INSERT INTO platform.journal(organization_id,environment_id,currency,financial_event_id,sealed_at) VALUES ('"+ORG+"','"+ENV+"','USD','"+e+"',now())"));c.rollback();
            UUID eventId=event(c,"forge-tx");
            state("23514",()->sql(c,"INSERT INTO platform.journal(organization_id,environment_id,currency,financial_event_id,construction_txid) VALUES ('"+ORG+"','"+ENV+"','USD','"+eventId+"',1)"));c.rollback();
        }
    }
    @Test void reversalMustExactlyInvertAndOnlyOnce() throws Exception {
        try(var c=app()){
            UUID original=post(c,"original","{}",entries(PSP,REVENUE,10),null);
            state("23514",()->post(c,"tamper","{}",entries(REVENUE,PSP,9),original));
            UUID reversal=post(c,"reverse","{}",entries(REVENUE,PSP,10),original);
            assertThat(post(c,"reverse","{}",entries(REVENUE,PSP,10),original)).isEqualTo(reversal);
            state("P0003",()->post(c,"second-reverse","{}",entries(REVENUE,PSP,10),original));
            state("P0002",()->post(c,"absent","{}",entries(REVENUE,PSP,10),id(99)));
        }
        assertThat(balance(PSP)).isZero();assertThat(count("journal")).isEqualTo(2);
    }
    @Test void enforcesPurposeProviderScopeAndNullableUniqueness() throws Exception {
        try(var c=owner()){
            state("23505",()->account(c,id(20),"SALES_REVENUE","INTERNAL",null,null));
            state("23514",()->account(c,id(21),"PSP_FUNDS","INTERNAL",null,null));
            state("23514",()->account(c,id(22),"BANK_CASH","PROVIDER",PROVIDER,"PAYMENTS"));
            state("23514",()->account(c,id(23),"SUSPENSE","PROVIDER",PROVIDER,"ISSUING"));
            state("23503",()->account(c,id(24),"SUSPENSE","PROVIDER",id(999),"PAYMENTS"));
        }
    }
    @Test void rejectsCrossTenantEnvironmentCurrencyAndProviderEntries() throws Exception {
        UUID otherOrg=id(100),otherEnv=id(101),siblingEnv=id(102),otherProvider=id(103);
        try(var c=owner()) {
            sql(c,"INSERT INTO platform.organization(id,code) VALUES ('"+otherOrg+"','other')");
            sql(c,"INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES ('"+otherEnv+"','"+otherOrg+"','test',false),('"+siblingEnv+"','"+ORG+"','sibling',false)");
            sql(c,"INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES ('"+otherOrg+"','"+otherEnv+"','USD'),('"+ORG+"','"+siblingEnv+"','USD')");
            sql(c,"INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES ('"+otherProvider+"','"+ORG+"','"+ENV+"','FAKE','other-provider',false)");
            account(c,id(104),"PSP_FUNDS","PROVIDER",otherProvider,"PAYMENTS");
            sql(c,"INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,source_domain) VALUES ('"+id(105)+"','"+otherOrg+"','"+otherEnv+"','USD','SALES_REVENUE','INTERNAL'),('"+id(106)+"','"+ORG+"','"+siblingEnv+"','USD','SALES_REVENUE','INTERNAL'),('"+id(107)+"','"+ORG+"','"+ENV+"','JPY','SALES_REVENUE','INTERNAL')");
        }
        try(var c=app()) {
            for(int foreign:new int[]{105,106,107})state("P0002",()->post(c,"foreign","{}",entries(PSP,id(foreign),1),null));
            state("23514",()->post(c,"provider","{}",entries(id(104),REVENUE,1),null));
            for(int foreign:new int[]{105,106,107}) {
                c.setAutoCommit(false); UUID j=journal(c,event(c,"direct-fk"));
                state("23503",()->entry(c,j,id(foreign),"CREDIT",1,1));c.rollback();
            }
            c.setAutoCommit(false);UUID j=journal(c,event(c,"direct-provider"));
            state("23514",()->entry(c,j,id(104),"CREDIT",1,1));c.rollback();
        }
        assertThat(count("financial_event")).isZero();assertThat(count("balance_projection")).isZero();
    }
    @Test void definerHasFixedPathAndPublicCannotExecuteIt() throws Exception {
        try(var c=owner();var st=c.createStatement();var r=st.executeQuery("SELECT prosecdef,proconfig::text,has_function_privilege('public',oid,'EXECUTE') FROM pg_proc WHERE oid='platform.post_ledger(uuid,uuid,text,uuid,text,text,text,text,jsonb,jsonb,uuid)'::regprocedure")) {
            r.next();assertThat(r.getBoolean(1)).isTrue();assertThat(r.getString(2)).contains("search_path=pg_catalog, platform, pg_temp");assertThat(r.getBoolean(3)).isFalse();
        }
        try(var c=app()) {
            state("42501",()->sql(c,"INSERT INTO platform.balance_projection(ledger_account_id,organization_id,environment_id,currency) VALUES ('"+PSP+"','"+ORG+"','"+ENV+"','USD')"));
            state("42501",()->account(c,id(200),"OPENING_EQUITY","INTERNAL",null,null));
            state("42501",()->sql(c,"SELECT platform.check_journal_final()"));
        }
    }
    @Test void rejectsLiveScopeEvenWhenConfiguredAfterMigration() throws Exception {
        try(var c=owner()) {
            sql(c,"INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES ('"+id(300)+"','"+ORG+"','live',true)");
            sql(c,"INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES ('"+id(301)+"','"+ORG+"','"+id(300)+"','STRIPE','live-config',true)");
            sql(c,"INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES ('"+ORG+"','"+id(300)+"','USD')");
            sql(c,"INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,source_domain) VALUES ('"+id(302)+"','"+ORG+"','"+id(300)+"','USD','SALES_REVENUE','INTERNAL'),('"+id(303)+"','"+ORG+"','"+id(300)+"','USD','OPENING_EQUITY','INTERNAL')");
        }
        try(var c=app()) {
            state("23514",()->sql(c,"SELECT platform.post_ledger('"+ORG+"','"+id(300)+"','USD','"+id(301)+"','live','L-TEST','INTERNAL','test','{}','"+entries(id(302),id(303),1)+"')"));
        }
        assertThat(count("financial_event")).isZero();assertThat(count("journal")).isZero();
    }
    @Test void migrationUpgradesV2DatabaseWithExistingConfiguration() throws Exception {
        try(var c=owner()){sql(c,"CREATE DATABASE ledger_upgrade");}
        String url=PG.getJdbcUrl().replace("/"+PG.getDatabaseName(),"/ledger_upgrade");
        Flyway.configure().dataSource(url,PG.getUsername(),PG.getPassword()).target("2").load().migrate();
        try(var c=DriverManager.getConnection(url,PG.getUsername(),PG.getPassword())){sql(c,"INSERT INTO platform.organization(code) VALUES ('survives')");}
        assertThat(Flyway.configure().dataSource(url,PG.getUsername(),PG.getPassword()).load().migrate().migrationsExecuted).isEqualTo(13); // V3 ledger through V15 reconciliation recovery
        try(var c=DriverManager.getConnection(url,PG.getUsername(),PG.getPassword());var s=c.createStatement();var r=s.executeQuery("SELECT code FROM platform.organization")){r.next();assertThat(r.getString(1)).isEqualTo("survives");}
    }
}
