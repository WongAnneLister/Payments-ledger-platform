package com.paymentsledger.pr19;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pr19ProcessHarnessTest {
    @TempDir
    Path directory;

    @Test
    void barrierReleasesIndependentJvmAndRetainsProcessIdentity() throws Exception {
        try (var child = Pr19ProcessHarness.start(Probe.class.getName(), directory, "released", Map.of())) {
            var ready = child.awaitMarker("ready");
            assertThat(ready.path("javaPid").asLong())
                    .isNotEqualTo(ProcessHandle.current().pid());
            child.release("ready");
            assertThat(child.awaitResult().path("status").asText()).isEqualTo("PASS");
        }
    }

    @Test
    void forcibleKillEndsBlockedChildAndRecordsNonzeroExit() throws Exception {
        try (var child = Pr19ProcessHarness.start(Probe.class.getName(), directory, "killed", Map.of())) {
            child.awaitMarker("ready");
            child.killForcibly();
            assertThat(child.evidence()).containsEntry("forceKilled", true);
            assertThat(child.evidence().get("exitCode")).isNotEqualTo(0);
        }
    }

    public static class Probe {
        public static void main(String[] args) {
            Pr19ProcessHarness.checkpoint("ready", Map.of("kind", "unit-probe"));
            Pr19ProcessHarness.mark("result", Map.of("status", "PASS"));
        }
    }
}
