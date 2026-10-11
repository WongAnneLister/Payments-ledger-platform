package com.paymentsledger.pr20.performance;

/** JDK-only executable tests so policy RED/GREEN does not need a Maven or Docker slot. */
public final class PerformancePolicyTest {
    public static void main(String[] args) {
        check(PerformancePolicy.percentile(new long[]{1,2,3,4,100},95)==100,"nearest-rank percentile");
        check(PerformancePolicy.percentile(new long[]{},99)==0,"empty percentile");
        check(!PerformancePolicy.fullBudget(999999,300,1800,100,200),"history is part of budget");
        check(PerformancePolicy.fullBudget(1000000,300,1800,100,200),"frozen full budget");
        check(PerformancePolicy.verdict(false,100,200,0,0,0,false).equals("SMOKE_PASS"),"smoke cannot claim full pass");
        check(PerformancePolicy.verdict(true,100,200,0,0,1,false).equals("FAIL"),"queue growth defeats latency pass");
        check(PerformancePolicy.verdict(true,100,200,0,0,0,true).equals("FAIL"),"correctness defeats latency pass");
        check(PerformancePolicy.verdict(true,100,200,0.001,0,0,false).equals("FAIL"),"strict error threshold");
        check(PerformancePolicy.verdict(true,100,200,0,1,0,false).equals("FAIL"),"dropped arrivals fail");
        check(PerformancePolicy.verdict(true,301,200,0,0,0,false).equals("FAIL"),"latency threshold");
        check(PerformancePolicy.scheduledNanos(10,100)==100000000L,"open-loop schedule");
        System.out.println("PASS 11 performance policy assertions");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
