package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
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

  @Nested
  @DisplayName("SuppressEffect terminal semantics")
  class SuppressSemantics {

    @Test
    @DisplayName("beforeJdbcConnectionAcquire suppresses when SUPPRESS scenario is active")
    void connectionAcquireSuppressed() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "jdbc-conn-suppress",
              ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE),
              ChaosEffect.suppress());
      try {
        assertThat(runtime.beforeJdbcConnectionAcquire("hikari-primary")).isTrue();
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("beforeJdbcStatementExecute suppresses SQL matching the target pattern")
    void statementExecuteSuppressedOnMatch() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("jdbc-select-suppress")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(
                      new ChaosSelector.JdbcSelector(
                          Set.of(OperationType.JDBC_STATEMENT_EXECUTE),
                          NamePattern.glob("SELECT*")))
                  .effect(ChaosEffect.suppress())
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        assertThat(runtime.beforeJdbcStatementExecute("SELECT 1")).isTrue();
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("beforeJdbcStatementExecute does NOT suppress when SQL pattern does not match")
    void statementExecuteNotSuppressedOnMismatch() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("jdbc-select-only")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(
                      new ChaosSelector.JdbcSelector(
                          Set.of(OperationType.JDBC_STATEMENT_EXECUTE),
                          NamePattern.glob("SELECT*")))
                  .effect(ChaosEffect.suppress())
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        assertThat(runtime.beforeJdbcStatementExecute("INSERT INTO foo VALUES (1)")).isFalse();
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("beforeJdbcTransactionCommit suppresses when SUPPRESS scenario is active")
    void commitSuppressed() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "jdbc-commit-suppress",
              ChaosSelector.jdbc(OperationType.JDBC_TRANSACTION_COMMIT),
              ChaosEffect.suppress());
      try {
        assertThat(runtime.beforeJdbcTransactionCommit()).isTrue();
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("beforeJdbcTransactionRollback suppresses when SUPPRESS scenario is active")
    void rollbackSuppressed() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "jdbc-rollback-suppress",
              ChaosSelector.jdbc(OperationType.JDBC_TRANSACTION_ROLLBACK),
              ChaosEffect.suppress());
      try {
        assertThat(runtime.beforeJdbcTransactionRollback()).isTrue();
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("beforeJdbcPreparedStatement suppresses when SUPPRESS scenario is active")
    void preparedStatementSuppressed() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "jdbc-prepare-suppress",
              ChaosSelector.jdbc(OperationType.JDBC_PREPARED_STATEMENT),
              ChaosEffect.suppress());
      try {
        assertThat(runtime.beforeJdbcPreparedStatement("SELECT ?")).isTrue();
      } finally {
        handle.stop();
      }
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
        assertThat(runtime.beforeJdbcConnectionAcquire("hikari-primary")).isFalse();
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
    @DisplayName("maxApplications(3) suppresses exactly 3 times, then passthrough")
    void maxApplicationsCapsSuppressionCount() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final long cap = 3L;
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("jdbc-stmt-capped")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE))
                  .effect(ChaosEffect.suppress())
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
        int suppressed = 0;
        int passthrough = 0;
        for (int i = 0; i < 8; i++) {
          if (runtime.beforeJdbcStatementExecute("SELECT " + i)) {
            suppressed++;
          } else {
            passthrough++;
          }
        }
        assertThat(suppressed)
            .as("maxApplications=%d must cap suppression count", cap)
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
    @DisplayName("JDBC_CONNECTION_ACQUIRE scenario does NOT suppress JDBC_STATEMENT_EXECUTE")
    void connectionAcquireDoesNotAffectStatementExecute() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "jdbc-conn-only",
              ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE),
              ChaosEffect.suppress());
      try {
        assertThat(runtime.beforeJdbcStatementExecute("SELECT 1")).isFalse();
        assertThat(runtime.beforeJdbcPreparedStatement("SELECT ?")).isFalse();
        assertThat(runtime.beforeJdbcTransactionCommit()).isFalse();
        assertThat(runtime.beforeJdbcTransactionRollback()).isFalse();
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("SQL pattern \"SELECT*\" does NOT match \"INSERT INTO orders\"")
    void selectPatternDoesNotMatchInsert() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("jdbc-select-pattern")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(
                      new ChaosSelector.JdbcSelector(
                          Set.of(OperationType.JDBC_STATEMENT_EXECUTE),
                          NamePattern.glob("SELECT*")))
                  .effect(ChaosEffect.suppress())
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        assertThat(runtime.beforeJdbcStatementExecute("INSERT INTO orders (id) VALUES (1)"))
            .isFalse();
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("no active scenario — all JDBC dispatch methods return false / passthrough")
    void noActiveScenarioPassthrough() {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThatCode(
              () -> {
                assertThat(runtime.beforeJdbcConnectionAcquire("hikari-primary")).isFalse();
                assertThat(runtime.beforeJdbcStatementExecute("SELECT 1")).isFalse();
                assertThat(runtime.beforeJdbcPreparedStatement("SELECT ?")).isFalse();
                assertThat(runtime.beforeJdbcTransactionCommit()).isFalse();
                assertThat(runtime.beforeJdbcTransactionRollback()).isFalse();
              })
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("after handle.stop() — no suppression observed on subsequent calls")
    void afterStopNoSuppression() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "jdbc-conn-stop",
              ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE),
              ChaosEffect.suppress());
      assertThat(runtime.beforeJdbcConnectionAcquire("hikari-primary")).isTrue();
      handle.stop();
      assertThat(runtime.beforeJdbcConnectionAcquire("hikari-primary")).isFalse();
    }
  }
}
