package com.paymentsledger.pr19;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Per-variant evidence from actual assertions. No production behavior is changed. */
public final class FaultEvidence {
    private FaultEvidence() {}

    @FunctionalInterface
    public interface Checked {
        void accept(Map<String, Object> evidence) throws Exception;
    }

    private static String sourceCommit() throws Exception {
        Process process = new ProcessBuilder("git", "rev-parse", "HEAD").start();
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Git provenance unavailable");
        }
        String commit =
                new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0 || !commit.matches("[a-f0-9]{40,64}"))
            throw new IllegalStateException("Git provenance unavailable");
        return commit;
    }

    public static void run(
            String id, String variant, long seed, String injection, String recovery, String limitations, Checked body)
            throws Exception {
        if (id == null || !id.matches("F-0[1-8]") || variant == null || !variant.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
            throw new IllegalArgumentException("Invalid fault identity");
        var record = new LinkedHashMap<String, Object>();
        record.put("title", id + " " + variant + ": " + injection);
        record.put("id", id);
        record.put("variant", variant);
        record.put("testId", id + "/" + variant);
        record.put("seed", seed);
        record.put("sourceCommit", sourceCommit());
        record.put("sourceProvenanceReport", "run-summary.json");
        record.put("replayCommand", "python3 scripts/ci/run_pr19.py --fault " + id);
        record.put(
                "environment",
                Map.of(
                        "javaVersion",
                        System.getProperty("java.version"),
                        "vm",
                        System.getProperty("java.vm.name"),
                        "os",
                        System.getProperty("os.name"),
                        "osVersion",
                        System.getProperty("os.version"),
                        "architecture",
                        System.getProperty("os.arch"),
                        "cpuCount",
                        Runtime.getRuntime().availableProcessors()));
        record.put(
                "expected",
                switch (id) {
                    case "F-01" ->
                        "Known operations recover through L1/L2; trusted orders can bind through L3; no-order observations remain unallocated without fulfillment.";
                    case "F-02" ->
                        "Same provider financial fact yields one canonical Journal and one independent merchant effect despite repeated delivery.";
                    case "F-03" ->
                        "Missing dependencies remain durable and recover; late old states do not erase capture or duplicate money.";
                    case "F-04" ->
                        "Invalid bytes/signature/secret/time are rejected without business effects; persistence failure is not acknowledged; valid original bytes remain receivable.";
                    case "F-05" ->
                        "Uncertain send remains UNKNOWN with refund hold and stable key; safe recovery does not duplicate independent funds; expired keys are not resent.";
                    case "F-06" ->
                        "Uncommitted work rolls back; committed obligations recover after actual process termination; stale tokens cannot overwrite newer control.";
                    case "F-07" ->
                        "Broker outage leaves durable backlog; re-publication and consumption converge with one independent merchant fulfillment.";
                    default ->
                        "Separate JVMs serialize at PostgreSQL controls, preserving refund/share/bank capacity and unique journal effects.";
                });
        record.put("injection", injection);
        record.put("recovery", recovery);
        record.put("limitations", limitations);
        record.put("providerEvidence", "FAKE_ONLY");
        record.put("bankEvidence", "SIMULATED_BANK_EVIDENCE");
        record.put("startedAtUtc", Instant.now().toString());
        record.put("status", "FAIL");
        long started = System.nanoTime();
        try {
            body.accept(record);
            record.put("status", "PASS");
        } catch (Exception | Error failure) {
            record.put("status", "FAIL");
            record.put(
                    "failure",
                    Map.of(
                            "type",
                            failure.getClass().getName(),
                            "message",
                            Objects.toString(failure.getMessage(), "")));
            throw failure;
        } finally {
            var volume = new LinkedHashMap<String, Integer>();
            Object finalEvidence = record.get("finalEvidence");
            if (finalEvidence instanceof Map<?, ?> snapshot)
                for (var entry : snapshot.entrySet()) {
                    if (entry.getValue() instanceof Collection<?> rows)
                        volume.put(entry.getKey().toString(), rows.size());
                    else if (entry.getValue() instanceof com.fasterxml.jackson.databind.JsonNode node && node.isArray())
                        volume.put(entry.getKey().toString(), node.size());
                }
            record.put("dataVolume", volume);
            record.put("completedAtUtc", Instant.now().toString());
            record.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
            Path directory = Path.of(System.getProperty("pr19.evidenceDir", "target/pr19"));
            Files.createDirectories(directory);
            Path file = directory.resolve(id + "-" + variant + ".json");
            new ObjectMapper()
                    .findAndRegisterModules()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(file.toFile(), record);
        }
    }
}
