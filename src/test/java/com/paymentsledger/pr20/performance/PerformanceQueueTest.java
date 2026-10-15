package com.paymentsledger.pr20.performance;

import java.util.Map;

/** Backlog migration and broker-only work must not create a false completion result. */
public final class PerformanceQueueTest {
    public static void main(String[] args) {
        if (args.length == 0) {
            var before = Map.of("outbox", 100L, "recovery", 0L, "kafka", 0L);
            var after = Map.of("outbox", 0L, "recovery", 100L, "kafka", 0L);
            long growth = PerformancePolicy.positiveQueueGrowth(before, after);
            if (growth != 100)
                throw new AssertionError("One queue draining cannot cancel another queue growing: " + growth);
            if (!PerformancePolicy.verdict(true, 100, 200, 0, 0, growth, false).equals("FAIL"))
                throw new AssertionError("Rotating backlog must fail");
            if (PerformancePolicy.positiveQueueGrowth(Map.of("kafka", 10L), Map.of("kafka", 3L)) != 0)
                throw new AssertionError("Drain does not create growth");
            System.out.println("PASS rotating backlog regression");
        } else {
            if (PerformancePolicy.drained(0, 10, 0))
                throw new AssertionError("Fresh local zero cannot hide Kafka backlog");
            if (PerformancePolicy.drained(0, 0, 1))
                throw new AssertionError("In-flight request prevents drain completion");
            if (!PerformancePolicy.drained(0, 0, 0)) throw new AssertionError("Fresh empty state should finish drain");
            System.out.println("PASS broker-only and in-flight drain regression");
        }
    }
}
