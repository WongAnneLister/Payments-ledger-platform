package com.paymentsledger.pr18;

import java.time.Instant;

public record ReconciliationModel(String scope,Instant cutoff) {
    public record Evidence(String scope,Instant cutoff,long closing,boolean complete) {}
    public String compare(long expected,Evidence evidence) {
        if(evidence==null || !evidence.complete || !scope.equals(evidence.scope) || !cutoff.equals(evidence.cutoff))return "INCOMPLETE";
        return expected==evidence.closing?"PASS":"FAIL";
    }
}
