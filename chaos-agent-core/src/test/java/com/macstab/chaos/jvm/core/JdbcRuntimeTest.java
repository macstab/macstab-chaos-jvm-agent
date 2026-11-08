package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JDBC runtime dispatch")
class JdbcRuntimeTest {

  private static ChaosActivationHandle activate(
      final ChaosRuntime runtime,
      final String id,
      final ChaosSelector selector,
      final ChaosEffect effect) {
    return runtime.activate(
        ChaosScenario.builder(id)
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(selector)
            .effect(effect)
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  private static ChaosSelector selectStatements(final String sqlGlob) {
    return new ChaosSelector.JdbcSelector(
        Set.of(OperationType.JDBC_STATEMENT_EXECUTE), NamePattern.glob(sqlGlob));
  }

  @Nested
  @DisplayName("RejectEffect: the driver's SQLException")
  class RejectSemantics {

    @Test
    @DisplayName("beforeJdbcConnectionAcquire throws SQLException when a reject is active")
    void connectionAcquireRejected() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "jdbc-conn-reject",
              ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE),
              ChaosEffect.reject("db down"));
      try {
        assertThatThrownBy(() -> runtime.beforeJdbcConnectionAcquire("hikari-primary"))
            .isInstanceOf(SQLException.class)
            .hasMessage("db down");
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("beforeJdbcStatementExecute rejects SQL matching the target pattern only")
    void statementExecuteRejectedOnMatchOnly() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime, "jdbc-select-reject", selectStatements("SELECT*"), ChaosEffect.reject("x"));
      try {
        assertThatThrownBy(() -> runtime.beforeJdbcStatementExecute("SELECT 1"))
            .isInstanceOf(SQLException.class);
        assertThatCode(() -> runtime.beforeJdbcStatementExecute("INSERT INTO foo VALUES (1)"))
            .doesNotThrowAnyException();
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("prepare, commit and rollback throw SQLException when a reject is active")
    void prepareCommitRollbackRejected() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "jdbc-tx-reject",
              new ChaosSelector.JdbcSelector(
                  Set.of(
                      OperationType.JDBC_PREPARED_STATEMENT,
                      OperationType.JDBC_TRANSACTION_COMMIT,
                      OperationType.JDBC_TRANSACTION_ROLLBACK),
                  NamePattern.any()),
              ChaosEffect.reject("db down"));
      try {
        assertThatThrownBy(() -> runtime.beforeJdbcPreparedStatement("SELECT ?"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(runtime::beforeJdbcTransactionCommit).isInstanceOf(SQLException.class);
        assertThatThrownBy(runtime::beforeJdbcTransactionRollback).isInstanceOf(SQLException.class);
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("a suppress on JDBC is refused at activation: no driver skips a call silently")
    void suppressIsRefused() {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThatThrownBy(
              () ->
                  activate(
                      runtime,
                      "jdbc-suppress",
                      ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE),
                      ChaosEffect.suppress()))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("use reject");
    }
  }

  @Nested
  @DisplayName("DelayEffect timing semantics")
  class DelaySemantics {

    private static final long DELAY_MS = 50L;

    @Test
    @DisplayName("beforeJdbcConnectionAcquire with DELAY 50ms blocks for at least 50ms")
    void connectionAcquireBlocksForDelay() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "jdbc-conn-delay",
              ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      try {
        final long start = System.nanoTime();
        runtime.beforeJdbcConnectionAcquire("hikari-primary");
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs)
            .as(
                "delay effect must pause the call for at least %dms (got %dms)",
                DELAY_MS, elapsedMs)
            .isGreaterThanOrEqualTo((long) (DELAY_MS * 0.8));
      } finally {
        handle.stop();
      }
    }
  }

  @Nested
  @DisplayName("Rate limiting semantics")
  class RateLimiting {

    @Test
    @DisplayName("maxApplications(3) rejects exactly 3 times, then passthrough")
    void maxApplicationsCapsRejectionCount() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final long cap = 3L;
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("jdbc-stmt-capped")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE))
                  .effect(ChaosEffect.reject("db down"))
                  .activationPolicy(
                      new ActivationPolicy(
                          ActivationPolicy.StartMode.AUTOMATIC,
                          1.0d,
                          0,
                          cap,
                          null,
                          null,
                          0L,
                          false))
                  .build());
      try {
        int rejected = 0;
        int passthrough = 0;
        for (int i = 0; i < 8; i++) {
          try {
            runtime.beforeJdbcStatementExecute("SELECT " + i);
            passthrough++;
          } catch (final Throwable failure) {
            assertThat(failure).isInstanceOf(SQLException.class);
            rejected++;
          }
        }
        assertThat(rejected)
            .as("maxApplications=%d must cap rejection count", cap)
            .isEqualTo((int) cap);
        assertThat(passthrough).isEqualTo(8 - (int) cap);
      } finally {
        handle.stop();
      }
    }
  }

  @Nested
  @DisplayName("Selector isolation")
  class SelectorIsolation {

    @Test
    @DisplayName("a JDBC_CONNECTION_ACQUIRE scenario does NOT reach the other JDBC operations")
    void connectionAcquireDoesNotAffectStatementExecute() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "jdbc-conn-only",
              ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE),
              ChaosEffect.reject("db down"));
      try {
        assertThatCode(
                () -> {
                  runtime.beforeJdbcStatementExecute("SELECT 1");
                  runtime.beforeJdbcPreparedStatement("SELECT ?");
                  runtime.beforeJdbcTransactionCommit();
                  runtime.beforeJdbcTransactionRollback();
                })
            .doesNotThrowAnyException();
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("no active scenario — every JDBC dispatch method passes through")
    void noActiveScenarioPassthrough() {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThatCode(
              () -> {
                runtime.beforeJdbcConnectionAcquire("hikari-primary");
                runtime.beforeJdbcStatementExecute("SELECT 1");
                runtime.beforeJdbcPreparedStatement("SELECT ?");
                runtime.beforeJdbcTransactionCommit();
                runtime.beforeJdbcTransactionRollback();
              })
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("after handle.stop() — no rejection on subsequent calls")
    void afterStopNoRejection() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "jdbc-conn-stop",
              ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE),
              ChaosEffect.reject("db down"));
      assertThatThrownBy(() -> runtime.beforeJdbcConnectionAcquire("hikari-primary"))
          .isInstanceOf(SQLException.class);
      handle.stop();
      assertThatCode(() -> runtime.beforeJdbcConnectionAcquire("hikari-primary"))
          .doesNotThrowAnyException();
    }
  }
}
