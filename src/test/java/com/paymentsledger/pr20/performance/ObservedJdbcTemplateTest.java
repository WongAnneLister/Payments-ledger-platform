package com.paymentsledger.pr20.performance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

class ObservedJdbcTemplateTest {
    @TempDir
    Path directory;

    @Test
    void originalDatabaseFailureAndSqlAreRetainedBeforeRethrowing() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT broken_observation"))
                .thenThrow(new SQLException("controlled query deadline", "57014"));
        var path = directory.resolve("queries.ndjson");
        var jdbc = new ObservedJdbcTemplate(source, path, "platform");
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT broken_observation", Long.class))
                .isInstanceOf(DataAccessException.class);
        var evidence = Pr20PerformanceExperiment.JSON.readTree(Files.readString(path));
        assertThat(evidence.path("sql").asText()).isEqualTo("SELECT broken_observation");
        assertThat(evidence.path("database").asText()).isEqualTo("platform");
        assertThat(evidence.path("status").asText()).isEqualTo("FAIL");
        assertThat(evidence.path("failure").asText()).contains("57014", "controlled query deadline", "SQLException");
        assertThat(evidence.path("elapsedMillis").asDouble()).isGreaterThanOrEqualTo(0);
        verify(connection).close();
    }

    @Test
    void singletonResultCardinalityFailureIsRecordedAsFailure() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT absent_singleton")).thenReturn(rows);
        when(rows.next()).thenReturn(false);
        var path = directory.resolve("cardinality.ndjson");
        var jdbc = new ObservedJdbcTemplate(source, path, "platform");
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT absent_singleton", Long.class))
                .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        var records = Files.readAllLines(path);
        assertThat(records).hasSize(1);
        var evidence = Pr20PerformanceExperiment.JSON.readTree(records.getFirst());
        assertThat(evidence.path("status").asText()).isEqualTo("FAIL");
        assertThat(evidence.path("failure").asText()).contains("EmptyResultDataAccessException");
    }

    @Test
    void failedDiagnosticAppendDoesNotReplaceOriginalDatabaseFailure() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT original_failure"))
                .thenThrow(new SQLException("original timeout", "57014"));
        var evidenceFailure = new java.util.concurrent.atomic.AtomicReference<java.io.IOException>();
        var jdbc = new ObservedJdbcTemplate(
                source, directory.resolve("missing-parent/queries.ndjson"), "platform", evidenceFailure::set);
        var failure = catchThrowable(() -> jdbc.queryForObject("SELECT original_failure", Long.class));
        assertThat(failure).isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(SQLException.class);
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).isInstanceOf(java.io.IOException.class);
        assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo("57014");
        assertThat(evidenceFailure.get()).isSameAs(failure.getSuppressed()[0]);
    }
}
