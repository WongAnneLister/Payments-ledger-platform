package com.paymentsledger.pr19;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FaultEvidenceTest {
    @TempDir
    Path directory;

    @AfterEach
    void clear() {
        System.clearProperty("pr19.evidenceDir");
    }

    @Test
    void successPersistsActualObservationsAndIdentity() throws Exception {
        System.setProperty("pr19.evidenceDir", directory.toString());
        FaultEvidence.run("F-01", "known-l1", 19, "drop", "query", "fake", e -> e.put("actual", 7));
        var n = new ObjectMapper()
                .readTree(directory.resolve("F-01-known-l1.json").toFile());
        assertThat(n.path("status").asText()).isEqualTo("PASS");
        assertThat(n.path("actual").asInt()).isEqualTo(7);
        assertThat(n.path("elapsedMillis").asLong()).isNotNegative();
        assertThat(n.path("id").asText()).isEqualTo("F-01");
    }

    @Test
    void failurePersistsIntermediateEvidenceAndRethrows() throws Exception {
        System.setProperty("pr19.evidenceDir", directory.toString());
        assertThatThrownBy(() -> FaultEvidence.run("F-06", "before-commit", 19, "kill", "reclaim", "fake", e -> {
                    e.put("intermediateEvidence", Map.of("journal", 0));
                    throw new AssertionError("deliberate sensitivity");
                }))
                .isInstanceOf(AssertionError.class)
                .hasMessage("deliberate sensitivity");
        var n = new ObjectMapper()
                .readTree(directory.resolve("F-06-before-commit.json").toFile());
        assertThat(n.path("status").asText()).isEqualTo("FAIL");
        assertThat(n.path("failure").path("type").asText()).isEqualTo("java.lang.AssertionError");
        assertThat(n.path("intermediateEvidence").path("journal").asInt()).isZero();
    }

    @Test
    void identityCannotEscapeEvidenceDirectory() {
        System.setProperty("pr19.evidenceDir", directory.toString());
        assertThatThrownBy(() -> FaultEvidence.run("F-01", "../escape", 19, "x", "x", "x", e -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
