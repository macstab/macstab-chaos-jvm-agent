package com.macstab.chaos.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.core.ChaosRuntime;
import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JdbcAdvice / JDBC bridge")
class JdbcAdviceTest {

  private ChaosBridge bridge;

  @BeforeEach
  void setUp() {
    bridge = new ChaosBridge(new ChaosRuntime());
  }

  @AfterEach
  void resetBridge() {
    BootstrapDispatcher.install(null, null);
  }

  @Nested
  @DisplayName("beforeJdbcConnectionAcquire passthrough")
  class ConnectionAcquirePassthrough {

    @Test
    @DisplayName("returns false when no bridge installed (fallback)")
    void returnsFalseWithoutBridge() throws Throwable {
      assertThat(BootstrapDispatcher.beforeJdbcConnectionAcquire("hikari-primary")).isFalse();
    }

    @Test
    @DisplayName("returns false when no scenarios match")
    void returnsFalseWithoutScenarios() throws Throwable {
      assertThat(bridge.beforeJdbcConnectionAcquire("hikari-primary")).isFalse();
    }

    @Test
    @DisplayName("accepts null pool name without throwing")
    void nullPoolNameIsSafe() {
      assertThatCode(() -> bridge.beforeJdbcConnectionAcquire(null)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("beforeJdbcStatementExecute passthrough")
  class StatementExecutePassthrough {

    @Test
    @DisplayName("returns false when no bridge installed (fallback)")
    void returnsFalseWithoutBridge() throws Throwable {
      assertThat(BootstrapDispatcher.beforeJdbcStatementExecute("SELECT 1")).isFalse();
    }

    @Test
    @DisplayName("returns false when no scenarios match")
    void returnsFalseWithoutScenarios() throws Throwable {
      assertThat(bridge.beforeJdbcStatementExecute("SELECT 1")).isFalse();
    }

    @Test
    @DisplayName("accepts null SQL without throwing (no NPE)")
    void nullSqlIsSafe() {
      assertThatCode(() -> bridge.beforeJdbcStatementExecute(null)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("beforeJdbcPreparedStatement passthrough")
  class PreparedStatementPassthrough {

    @Test
    @DisplayName("returns false when no bridge installed (fallback)")
    void returnsFalseWithoutBridge() throws Throwable {
      assertThat(BootstrapDispatcher.beforeJdbcPreparedStatement("SELECT * FROM t")).isFalse();
    }

    @Test
    @DisplayName("returns false when no scenarios match")
    void returnsFalseWithoutScenarios() throws Throwable {
      assertThat(bridge.beforeJdbcPreparedStatement("SELECT * FROM t")).isFalse();
    }

    @Test
    @DisplayName("accepts null SQL without throwing (no NPE)")
    void nullSqlIsSafe() {
      assertThatCode(() -> bridge.beforeJdbcPreparedStatement(null)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("beforeJdbcTransactionCommit passthrough")
  class TransactionCommitPassthrough {

    @Test
    @DisplayName("returns false when no bridge installed (fallback)")
    void returnsFalseWithoutBridge() throws Throwable {
      assertThat(BootstrapDispatcher.beforeJdbcTransactionCommit()).isFalse();
    }

    @Test
    @DisplayName("returns false when no scenarios match")
    void returnsFalseWithoutScenarios() throws Throwable {
      assertThat(bridge.beforeJdbcTransactionCommit()).isFalse();
    }
  }

  @Nested
  @DisplayName("beforeJdbcTransactionRollback passthrough")
  class TransactionRollbackPassthrough {

    @Test
    @DisplayName("returns false when no bridge installed (fallback)")
    void returnsFalseWithoutBridge() throws Throwable {
      assertThat(BootstrapDispatcher.beforeJdbcTransactionRollback()).isFalse();
    }

    @Test
    @DisplayName("returns false when no scenarios match")
    void returnsFalseWithoutScenarios() throws Throwable {
      assertThat(bridge.beforeJdbcTransactionRollback()).isFalse();
    }
  }
}
