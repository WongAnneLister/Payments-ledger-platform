package com.paymentsledger.pr20.performance;
import org.junit.jupiter.api.Test;
final class PerformancePolicyJUnitTest {
    @Test void frozenPolicyRejectsInvalidEvidence(){PerformancePolicyTest.main(new String[0]);}
    @Test void fixtureCredentialUsesProductionGrammar(){PerformanceFixtureTokenTest.main(new String[0]);}
    @Test void rotatingQueueCannotCancelGrowth(){PerformanceQueueTest.main(new String[0]);}
    @Test void drainIncludesFreshBrokerAndInflightState(){PerformanceQueueTest.main(new String[]{"drain"});}
    @Test void argumentsEnforceEvidenceIsolation(){
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->Pr20PerformanceExperiment.Options.parse(new String[]{"--output","/tmp/not-owned-evidence"}));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->Pr20PerformanceExperiment.Options.parse(new String[]{"--write-rps","0"}));
        org.junit.jupiter.api.Assertions.assertFalse(Pr20PerformanceExperiment.Options.parse(new String[]{"--history","100","--warmup-seconds","1","--measure-seconds","2","--write-rps","2","--query-rps","4"}).full());
    }
}
