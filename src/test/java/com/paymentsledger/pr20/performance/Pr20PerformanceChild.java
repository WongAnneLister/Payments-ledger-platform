package com.paymentsledger.pr20.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.PaymentsLedgerApplication;
import java.lang.management.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** An actual production application JVM, with the isolated durable simulator bound to production Provider ports. */
public final class Pr20PerformanceChild {
    @Configuration(proxyBeanMethods = false)
    @Profile("pr20-performance")
    public static class SimulatorBinding {
        @Bean
        @Primary
        @Profile("worker")
        com.paymentsledger.notification.MerchantTransport localMerchant(Environment environment) {
            return new com.paymentsledger.notification.HttpMerchantTransport(
                    com.paymentsledger.notification.NotificationTargetPolicy.localFixture(
                            java.net.URI.create(environment.getRequiredProperty("PR20_MERCHANT_URI"))),
                    ref -> environment.getRequiredProperty("PR20_MERCHANT_SECRET"),
                    java.time.Clock.systemUTC());
        }

        @Bean(destroyMethod = "close")
        SqlPerformanceProvider performanceProvider(Environment environment) {
            return new SqlPerformanceProvider(
                    environment.getRequiredProperty("PR20_PROVIDER_URL"),
                    environment.getRequiredProperty("PR20_PROVIDER_USER"),
                    environment.getRequiredProperty("PR20_PROVIDER_PASSWORD"));
        }
    }

    public static void main(String[] args) throws Exception {
        var application =
                new SpringApplicationBuilder(PaymentsLedgerApplication.class, SimulatorBinding.class).run(args);
        boolean l2Checked = false;
        if ("worker".equals(System.getenv("PR20_ROLE"))) {
            application
                    .getBean(com.paymentsledger.payment.OperationRecoveryCoordinator.class)
                    .scanL2(100);
            l2Checked = true;
        }
        Path control = Path.of(System.getenv("PR20_CONTROL"));
        Files.createDirectories(control);
        ObjectMapper json = new ObjectMapper();
        json.writeValue(
                control.resolve("ready.json").toFile(),
                Map.of(
                        "pid",
                        ProcessHandle.current().pid(),
                        "at",
                        Instant.now().toString(),
                        "role",
                        System.getenv("PR20_ROLE"),
                        "java",
                        System.getProperty("java.version"),
                        "activeProfiles",
                        List.of(application.getEnvironment().getActiveProfiles()),
                        "preloadL2ScanExecuted",
                        l2Checked,
                        "databaseRole",
                        application
                                .getBean(org.springframework.jdbc.core.JdbcTemplate.class)
                                .queryForObject("SELECT current_user", String.class)));
        try (var output = Files.newBufferedWriter(control.resolve("jvm.ndjson"))) {
            while (application.isActive()) {
                var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                var os = ManagementFactory.getOperatingSystemMXBean();
                var sample = new LinkedHashMap<String, Object>();
                sample.put("at", Instant.now().toString());
                sample.put("pid", ProcessHandle.current().pid());
                sample.put("heapUsed", memory.getUsed());
                sample.put("heapCommitted", memory.getCommitted());
                sample.put("heapMax", memory.getMax());
                sample.put("threadCount", ManagementFactory.getThreadMXBean().getThreadCount());
                sample.put("systemLoadAverage", os.getSystemLoadAverage());
                if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
                    sample.put("processCpuLoad", extended.getProcessCpuLoad());
                    sample.put("processCpuNanos", extended.getProcessCpuTime());
                    sample.put("systemCpuLoad", extended.getCpuLoad());
                    sample.put("freePhysicalMemoryBytes", extended.getFreeMemorySize());
                }
                long gcCount = 0, gcMillis = 0;
                for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                    gcCount += Math.max(0, gc.getCollectionCount());
                    gcMillis += Math.max(0, gc.getCollectionTime());
                }
                sample.put("gcCount", gcCount);
                sample.put("gcMillis", gcMillis);
                output.write(json.writeValueAsString(sample));
                output.newLine();
                output.flush();
                Thread.sleep(5000);
            }
        }
    }
}
