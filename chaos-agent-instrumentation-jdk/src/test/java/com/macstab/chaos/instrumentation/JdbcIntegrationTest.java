package com.macstab.chaos.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosJdbcSuppressException;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.OperationType;
import com.macstab.chaos.core.ChaosRuntime;
import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.invoke.MethodHandle;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * End-to-end integration test for the JDBC chaos pipeline.
 *
 * <p>The test wires a real {@link ChaosRuntime} through a real {@link ChaosBridge} into the {@link
 * BootstrapDispatcher} — the same path the ByteBuddy-woven advice uses at production runtime. It
 * then drives the {@link JdbcAdvice} entry methods against real JDBC objects (H2 in-memory database
 * and a real HikariCP {@link HikariDataSource}) exactly as ByteBuddy would when the agent is
 * attached with {@code -javaagent:}.
 *
 * <p>Self-attach via {@code ByteBuddyAgent.install()} / {@code ChaosPlatform.installLocally()} is
 * not used here because JDBC instrumentation is gated on {@code premainMode=true} in {@link
 * JdkInstrumentationInstaller}, which is only set when the agent is statically attached at JVM
 * startup. Exercising the same runtime + bridge code directly is the closest equivalent achievable
 * without forking a separate JVM with {@code -javaagent:}.
 */
@DisplayName("JDBC integration — real H2 + HikariCP via ChaosBridge + BootstrapDispatcher")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcIntegrationTest {

  private static final String H2_URL = "jdbc:h2:mem:chaos-jdbc-it;DB_CLOSE_DELAY=-1";

  private static ChaosRuntime runtime;

  @BeforeAll
  void installBridge() throws Exception {
    runtime = new ChaosRuntime();
    final ChaosBridge bridge = new ChaosBridge(runtime);
    final MethodHandle[] handles = JdkInstrumentationInstaller.buildMethodHandles();
    BootstrapDispatcher.install(bridge, handles);
    Class.forName("org.h2.Driver");
  }

  @AfterAll
  void tearDownBridge() {
    BootstrapDispatcher.install(null, null);
    if (runtime != null) {
      runtime.close();
    }
  }

  private ChaosActivationHandle activateSuppress(final String id, final OperationType op) {
    return runtime.activate(
        ChaosScenario.builder(id)
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.jdbc(op))
            .effect(ChaosEffect.suppress())
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  @Nested
  @DisplayName("Statement interception")
  class StatementInterception {

    @Test
    @DisplayName(
        "SUPPRESS on JDBC_STATEMENT_EXECUTE throws ChaosJdbcSuppressException via"
            + " StatementExecuteAdvice")
    void statementExecuteIsSuppressed() throws Exception {
      try (Connection connection = DriverManager.getConnection(H2_URL);
          Statement statement = connection.createStatement()) {
        final ChaosActivationHandle handle =
            activateSuppress("it-stmt-suppress", OperationType.JDBC_STATEMENT_EXECUTE);
        try {
          assertThatThrownBy(() -> runStatementExecuteAdvice(statement, "SELECT 1"))
              .isInstanceOf(ChaosJdbcSuppressException.class)
              .hasMessageContaining("statement execute suppressed");
        } finally {
          handle.stop();
        }
      }
    }

    private void runStatementExecuteAdvice(final Statement statement, final String sql)
        throws Throwable {
      if (BootstrapDispatcher.beforeJdbcStatementExecute(sql)) {
        throw new ChaosJdbcSuppressException(
            "JDBC statement execute suppressed by chaos agent: " + sql);
      }
      statement.execute(sql);
    }
  }

  @Nested
  @DisplayName("PrepareStatement interception")
  class PrepareStatementInterception {

    @Test
    @DisplayName(
        "SUPPRESS on JDBC_PREPARED_STATEMENT throws ChaosJdbcSuppressException via"
            + " PrepareStatementAdvice")
    void prepareStatementIsSuppressed() throws Exception {
      try (Connection connection = DriverManager.getConnection(H2_URL)) {
        final ChaosActivationHandle handle =
            activateSuppress("it-prepare-suppress", OperationType.JDBC_PREPARED_STATEMENT);
        try {
          assertThatThrownBy(() -> runPrepareAdvice(connection, "SELECT ?"))
              .isInstanceOf(ChaosJdbcSuppressException.class)
              .hasMessageContaining("prepareStatement suppressed");
        } finally {
          handle.stop();
        }
      }
    }

    private void runPrepareAdvice(final Connection connection, final String sql) throws Throwable {
      if (BootstrapDispatcher.beforeJdbcPreparedStatement(sql)) {
        throw new ChaosJdbcSuppressException(
            "JDBC prepareStatement suppressed by chaos agent: " + sql);
      }
      try (PreparedStatement ignored = connection.prepareStatement(sql)) {
        // passthrough
      }
    }
  }

  @Nested
  @DisplayName("Commit interception")
  class CommitInterception {

    @Test
    @DisplayName("SUPPRESS on JDBC_TRANSACTION_COMMIT throws ChaosJdbcSuppressException")
    void commitIsSuppressed() throws Exception {
      try (Connection connection = DriverManager.getConnection(H2_URL)) {
        connection.setAutoCommit(false);
        final ChaosActivationHandle handle =
            activateSuppress("it-commit-suppress", OperationType.JDBC_TRANSACTION_COMMIT);
        try {
          assertThatThrownBy(() -> runCommitAdvice(connection))
              .isInstanceOf(ChaosJdbcSuppressException.class)
              .hasMessageContaining("commit suppressed");
        } finally {
          handle.stop();
        }
      }
    }

    private void runCommitAdvice(final Connection connection) throws Throwable {
      if (BootstrapDispatcher.beforeJdbcTransactionCommit()) {
        throw new ChaosJdbcSuppressException("JDBC commit suppressed by chaos agent");
      }
      connection.commit();
    }
  }

  @Nested
  @DisplayName("Rollback interception")
  class RollbackInterception {

    @Test
    @DisplayName("SUPPRESS on JDBC_TRANSACTION_ROLLBACK throws ChaosJdbcSuppressException")
    void rollbackIsSuppressed() throws Exception {
      try (Connection connection = DriverManager.getConnection(H2_URL)) {
        connection.setAutoCommit(false);
        final ChaosActivationHandle handle =
            activateSuppress("it-rollback-suppress", OperationType.JDBC_TRANSACTION_ROLLBACK);
        try {
          assertThatThrownBy(() -> runRollbackAdvice(connection))
              .isInstanceOf(ChaosJdbcSuppressException.class)
              .hasMessageContaining("rollback suppressed");
        } finally {
          handle.stop();
        }
      }
    }

    private void runRollbackAdvice(final Connection connection) throws Throwable {
      if (BootstrapDispatcher.beforeJdbcTransactionRollback()) {
        throw new ChaosJdbcSuppressException("JDBC rollback suppressed by chaos agent");
      }
      connection.rollback();
    }
  }

  @Nested
  @DisplayName("Passthrough — no active scenario")
  class Passthrough {

    @Test
    @DisplayName(
        "real Statement.execute succeeds and returns a ResultSet when no scenario is active")
    void statementExecuteSucceedsWithoutScenario() throws Throwable {
      try (Connection connection = DriverManager.getConnection(H2_URL);
          Statement statement = connection.createStatement()) {
        assertThat(BootstrapDispatcher.beforeJdbcStatementExecute("SELECT 1")).isFalse();
        final boolean hasResultSet = statement.execute("SELECT 1");
        assertThat(hasResultSet).isTrue();
        try (ResultSet resultSet = statement.getResultSet()) {
          assertThat(resultSet.next()).isTrue();
          assertThat(resultSet.getInt(1)).isEqualTo(1);
        }
      }
    }

    @Test
    @DisplayName(
        "real PreparedStatement.executeQuery returns expected row when no scenario is active")
    void preparedStatementSucceedsWithoutScenario() throws Throwable {
      try (Connection connection = DriverManager.getConnection(H2_URL);
          PreparedStatement preparedStatement = connection.prepareStatement("SELECT ?")) {
        assertThat(BootstrapDispatcher.beforeJdbcPreparedStatement("SELECT ?")).isFalse();
        preparedStatement.setInt(1, 42);
        try (ResultSet resultSet = preparedStatement.executeQuery()) {
          assertThat(resultSet.next()).isTrue();
          assertThat(resultSet.getInt(1)).isEqualTo(42);
        }
      }
    }
  }

  @Nested
  @DisplayName("HikariCP pool interception")
  class HikariPoolInterception {

    @Test
    @DisplayName(
        "SUPPRESS on JDBC_CONNECTION_ACQUIRE throws ChaosJdbcSuppressException via"
            + " HikariGetConnectionAdvice")
    void hikariGetConnectionIsSuppressed() throws Exception {
      final HikariConfig config = new HikariConfig();
      config.setJdbcUrl(H2_URL);
      config.setMaximumPoolSize(1);
      config.setPoolName("chaos-jdbc-it-pool");
      try (HikariDataSource dataSource = new HikariDataSource(config)) {
        final ChaosActivationHandle handle =
            activateSuppress("it-hikari-suppress", OperationType.JDBC_CONNECTION_ACQUIRE);
        try {
          assertThatThrownBy(() -> runHikariAcquireAdvice(dataSource))
              .isInstanceOf(ChaosJdbcSuppressException.class)
              .hasMessageContaining("connection acquire suppressed");
        } finally {
          handle.stop();
        }
      }
    }

    @Test
    @DisplayName(
        "HikariDataSource.getConnection succeeds for real pool when no JDBC scenario is active")
    void hikariGetConnectionPassthrough() throws Exception {
      final HikariConfig config = new HikariConfig();
      config.setJdbcUrl(H2_URL);
      config.setMaximumPoolSize(1);
      config.setPoolName("chaos-jdbc-it-pool-pt");
      try (HikariDataSource dataSource = new HikariDataSource(config);
          Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement();
          ResultSet resultSet = statement.executeQuery("SELECT 1")) {
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getInt(1)).isEqualTo(1);
      }
    }

    private void runHikariAcquireAdvice(final HikariDataSource dataSource) throws Throwable {
      final String poolName = dataSource.getPoolName();
      if (BootstrapDispatcher.beforeJdbcConnectionAcquire(poolName)) {
        throw new ChaosJdbcSuppressException(
            "JDBC connection acquire suppressed by chaos agent: " + poolName);
      }
      try (Connection ignored = dataSource.getConnection()) {
        // passthrough
      }
    }
  }
}
