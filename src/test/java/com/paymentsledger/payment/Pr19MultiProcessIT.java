package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** F-08. Every race launches >=2 JVMs, with separately wired production services/connections. */
class Pr19MultiProcessIT extends Pr19ProcessFixture {
    @Test void sameKeyPayment() throws Exception {fault("F-08","same-key-payment",190801,"two JVM transactions share API payment key","durable original intent and provider operation",e->{
        e.put("operationKeys",List.of("pr19-same-key","pr19-order"));
        var results=race(e,List.of("create-payment","create-payment"),List.of(Map.of("PR19_KEY","pr19-same-key"),Map.of("PR19_KEY","pr19-same-key")));
        assertThat(results.get(0).path("value").path("paymentId")).isEqualTo(results.get(1).path("value").path("paymentId"));
        assertThat(results.get(0).path("value").path("operationId")).isEqualTo(results.get(1).path("value").path("operationId"));
        assertThat(count("payment")).isEqualTo(1);assertThat(count("business_order")).isEqualTo(1);assertThat(count("api_idempotency_record")).isEqualTo(1);assertThat(count("provider_operation")).isEqualTo(1);
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);assertThat(fake.counters(scope).paymentEffectCount()).isZero();
    });}
    @Test void doubleRefund() throws Exception {fault("F-08","double-refund",190802,"two JVMs reserve 600 against captured 1000","stable refund control row serialization",e->{
        thousandCapture(true);e.put("operationKeys",List.of("refund-a","refund-b","capture"));
        var r=race(e,List.of("reserve-refund","reserve-refund"),List.of(refund("a",600),refund("b",600)));
        assertThat(r.stream().filter(x->x.path("outcome").asText().equals("ACCEPTED")).count()).isEqualTo(1);
        assertThat(r.stream().filter(x->x.path("errorCode").asText().equals("REFUND_LIMIT_EXCEEDED")).count()).isEqualTo(1);
        assertThat(count("refund")).isEqualTo(1);assertThat(held()).isEqualTo(600);assertThat(balance("PARTNER_SHARE_EXPENSE")).isEqualTo(80);
        assertThat(fake.counters(scope).refundEffectCount()).isZero();
    });}
    @Test void refundShare() throws Exception {fault("F-08","refund-share",190803,"refund reservation and share catch-up enter in separate JVMs","payment then allocation locks",e->{
        thousandCapture(true);e.put("operationKeys",List.of("refund-share","share-catchup","capture"));
        var r=race(e,List.of("reserve-refund","share"),List.of(refund("share",400),Map.of()));
        assertThat(r).allSatisfy(x->assertThat(x.path("outcome").asText()).isEqualTo("ACCEPTED"));
        assertThat(held()).isEqualTo(400);assertThat(balance("PARTNER_SHARE_EXPENSE")).isEqualTo(120);assertThat(count("refund")).isEqualTo(1);
    });}
    @Test void refundDispute() throws Exception {fault("F-08","refund-dispute",190804,"refund and dispute warning enter in separate JVMs","admissible serialization, explicit external conflict when reservation wins",e->{
        thousandCapture(true);e.put("operationKeys",List.of("refund-dispute","du_pr19-race","capture"));
        var r=race(e,List.of("reserve-refund","dispute"),List.of(refund("dispute",400),Map.of()));
        boolean reserved=r.getFirst().path("outcome").asText().equals("ACCEPTED");
        if(!reserved)assertThat(r.getFirst().path("errorCode").asText()).isIn("REFUND_BLOCKED","EVIDENCE_CONFLICT");
        assertThat(r.get(1).path("outcome").asText()).isEqualTo("ACCEPTED");assertThat(held()).isEqualTo(reserved?400:0);assertThat(disputes.blocks(paymentId)).isTrue();assertThat(balance("PARTNER_SHARE_EXPENSE")).isZero();
        if(reserved)assertThat(jdbc.queryForObject("SELECT control_state FROM platform.refund_control WHERE payment_id=?",String.class,paymentId)).isEqualTo("EXTERNAL_CONFLICT");
        e.put("refundWonRace",reserved);
    });}
    @Test void duplicateBankAllocation() throws Exception {fault("F-08","duplicate-bank-allocation",190805,"two JVMs match same payout/line/principal identity","replay same match without another journal",e->{
        var payout=payout("payout_duplicate");post(bt("payout_duplicate-out","payout",-5700,0,"payout_duplicate","payout"));var line=bankLine("duplicate-line",5700,101,"payout_duplicate");
        e.put("operationKeys",List.of("payout_duplicate",line.toString()));var args=bank(payout,line);long before=count("journal");
        var r=race(e,List.of("bank-match","bank-match"),List.of(args,args));
        assertThat(r.getFirst().path("value").path("matchId")).isEqualTo(r.get(1).path("value").path("matchId"));assertThat(count("payout_bank_match")).isEqualTo(1);assertThat(count("journal")).isEqualTo(before+1);assertThat(balance("BANK_CASH")).isEqualTo(3930);
        assertThat(jdbc.queryForObject("SELECT allocated_minor FROM platform.bank_statement_line WHERE id=?",Long.class,line)).isEqualTo(4000);
    });}
    @Test void sharedBankLine() throws Exception {fault("F-08","shared-bank-line",190806,"different payouts compete for one bank line from two JVMs","capacity reject rolls back all losing effects",e->{
        var a=payout("payout_a");var b=payout("payout_b");post(bt("payout_a-out","payout",-5700,0,"payout_a","payout"));post(bt("payout_b-out","payout",-5700,0,"payout_b","payout"));var line=bankLine("shared-line",5700,101,"payout_a","payout_b");
        e.put("operationKeys",List.of("payout_a","payout_b",line.toString()));long before=count("journal");var r=race(e,List.of("bank-match","bank-match"),List.of(bank(a,line),bank(b,line)));
        assertThat(r.stream().filter(x->x.path("outcome").asText().equals("ACCEPTED")).count()).isEqualTo(1);assertThat(r.stream().filter(x->x.path("errorCode").asText().equals("REJECTED_CAPACITY")).count()).isEqualTo(1);
        assertThat(count("payout_bank_match")).isEqualTo(1);assertThat(count("journal")).isEqualTo(before+1);assertThat(balance("BANK_CASH")).isEqualTo(3930);assertThat(balance("BANK_FEE_EXPENSE")).isEqualTo(70);assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).isEqualTo(7400);
        assertThat(jdbc.queryForObject("SELECT allocated_minor FROM platform.bank_statement_line WHERE id=?",Long.class,line)).isEqualTo(4000);
    });}
    @Test void sameBtMultipleEntrypoints() throws Exception {fault("F-08","same-bt-multi-entry",190807,"BACKFILL and OVERLAP JVMs import/replay same immutable BT","unique canonical BT and journal identity",e->{
        thousandCapture(false);fake.controls().putBalanceTransaction(scope,bt("capture","charge",1000,0,chargeId,"charge"),from,null);
        e.put("operationKeys",List.of("capture","BACKFILL","OVERLAP"));var r=race(e,List.of("import-bt","import-bt"),List.of(Map.of("PR19_IMPORT_PURPOSE","BACKFILL"),Map.of("PR19_IMPORT_PURPOSE","OVERLAP")));
        assertThat(r).allSatisfy(x->assertThat(x.path("outcome").asText()).isEqualTo("ACCEPTED"));assertThat(count("balance_transaction")).isEqualTo(1);assertThat(count("import_page")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.financial_event WHERE source_ref='capture' AND event_type='L-CHARGE'",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT state FROM platform.balance_transaction WHERE bt_id='capture'",String.class)).isEqualTo("POSTED");assertThat(balance("SALES_REVENUE")).isEqualTo(-1000);assertThat(balance("PARTNER_SHARE_EXPENSE")).isEqualTo(200);
    });}
    Map<String,String> refund(String key,long amount){return Map.of("PR19_KEY",key,"PR19_AMOUNT",Long.toString(amount));}
    Map<String,String> bank(UUID payout,UUID line){return Map.of("PR19_PAYOUT_ID",payout.toString(),"PR19_BANK_LINE_ID",line.toString(),"PR19_AMOUNT","4000");}
}
