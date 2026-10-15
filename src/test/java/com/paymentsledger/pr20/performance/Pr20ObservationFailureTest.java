package com.paymentsledger.pr20.performance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.testcontainers.containers.PostgreSQLContainer;

class Pr20ObservationFailureTest {
    @TempDir
    Path directory;

    Pr20PerformanceExperiment experiment(String name) throws IOException {
        return new Pr20PerformanceExperiment(
                new Pr20PerformanceExperiment.Options(8, 0, 1, 2, 4, 0, 200020, directory.resolve(name)));
    }

    @Test
    void ordinaryObservationFailureRetainsFailureAndCompleteArrivalBudgetWithoutFabricatedCounts() throws Exception {
        var experiment = experiment("retained");
        var failed = experiment.observed("controlled-timeout", () -> {
            throw new DataAccessResourceFailureException("controlled observation timeout");
        });
        assertThat(failed)
                .containsEntry("observationComplete", false)
                .containsEntry("instrumentationError", "DataAccessResourceFailureException");
        assertThat(failed).doesNotContainKeys("businessCompleted", "totalQueue", "kafkaLag");
        assertThat(experiment.abortReason.get()).isNull();
        assertThat(experiment.instrumentationErrors).hasSize(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var raw = new StringWriter();
                var writer = new BufferedWriter(raw)) {
            var phase = experiment.phase("measurement", 1, executor, writer, null);
            assertThat(phase.scheduled.get()).isEqualTo(6);
            assertThat(phase.attempted.get() + phase.drops.get()).isEqualTo(6);
            assertThat(phase.actualSeconds).isGreaterThanOrEqualTo(1);
            assertThat(phase.windowEnd)
                    .containsEntry("observationComplete", false)
                    .doesNotContainKeys("businessCompleted", "totalQueue");
            writer.flush();
            assertThat(raw.toString().lines().count()).isEqualTo(6);
        }
        assertThat(PerformancePolicy.verdict(true, 0, 0, 0, 0, 0, !experiment.instrumentationErrors.isEmpty()))
                .isEqualTo("FAIL");
        assertThat(Files.readString(experiment.output.resolve("observation-failures.ndjson")))
                .contains("controlled observation timeout");
    }

    @Test
    void safetyAbortStillStopsArrivalsImmediately() throws Exception {
        var experiment = experiment("safety");
        experiment.abortReason.set("DISK_RESERVE_BELOW_2_GIB");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var writer = new BufferedWriter(new StringWriter())) {
            assertThatThrownBy(() -> experiment.phase("measurement", 1, executor, writer, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("DISK_RESERVE_BELOW_2_GIB");
            assertThat(experiment.activePhase.scheduled.get()).isZero();
        }
    }

    @Test
    void unavailableDiskProtectionIsAnImmediateSafetyAbort() throws Exception {
        var experiment = experiment("disk-unavailable");
        experiment.ownedDatabase = mock(PostgreSQLContainer.class);
        when(experiment.ownedDatabase.execInContainer("df", "-Pk", "/var/lib/postgresql/data"))
                .thenThrow(new IllegalStateException("controlled disk inspection unavailable"));
        assertThatThrownBy(() -> experiment.diskGuard(-1)).hasMessage("controlled disk inspection unavailable");
        assertThat(experiment.abortReason.get()).isEqualTo("DISK_GUARD_UNAVAILABLE");
    }

    @Test
    void rawRequestEvidenceFailureStopsFurtherLoad() throws Exception {
        var experiment = experiment("raw-unavailable");
        var writer = new BufferedWriter(new StringWriter()) {
            @Override
            public void write(String text) throws IOException {
                throw new IOException("controlled raw evidence unavailable");
            }
        };
        experiment.writeRaw(writer, "measurement", "query", 1, 0, 0, 0, 0, 0, 0, 0, "ERROR", "");
        assertThat(experiment.abortReason.get()).isEqualTo("RAW_CSV_WRITE_FAILED");
    }
}
