package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.invoke.MethodHandle;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * <p>The advice is called directly rather than woven, so the test needs no agent attached: it
 * exercises the runtime and bridge code the woven advice reaches, with a {@link
 * JdkInstrumentationInstaller#buildMethodHandles()} table installed into {@link
 * BootstrapDispatcher}. The woven JDBC hooks themselves (weaving the JDBC hook group on demand, as
 * under {@code -javaagent:} or self-attach) are covered by the bootstrap module's child-JVM tests.
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

  private ChaosActivationHandle activate(
      final String id, final OperationType op, final ChaosEffect effect) {
    return runtime.activate(
        ChaosScenario.builder(id)
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.jdbc(op))
            .effect(effect)
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  private ChaosActivationHandle activateReject(final String id, final OperationType op) {
    return activate(id, op, ChaosEffect.reject("db down"));
  }

  @Nested
  @DisplayName("Suppress")
  class Suppress {

    @Test
    @DisplayName("a suppress on any JDBC operation is refused at activation: use reject")
    void suppressIsRefusedAtActivation() {
      for (final OperationType op :
          new OperationType[] {
            OperationType.JDBC_CONNECTION_ACQUIRE,
            OperationType.JDBC_STATEMENT_EXECUTE,
            OperationType.JDBC_PREPARED_STATEMENT,
            OperationType.JDBC_TRANSACTION_COMMIT,
            OperationType.JDBC_TRANSACTION_ROLLBACK
          }) {
        assertThatThrownBy(() -> activate("it-suppress-" + op, op, ChaosEffect.suppress()))
            .isInstanceOf(ChaosValidationException.class)
            .hasMessageContaining("use reject");
      }
    }
  }

  @Nested
  @DisplayName("Statement interception")
  class StatementInterception {

    @Test
    @DisplayName("REJECT on JDBC_STATEMENT_EXECUTE throws SQLException via StatementExecuteAdvice")
    void statementExecuteIsRejected() throws Exception {
      try (Connection connection = DriverManager.getConnection(H2_URL);
          Statement statement = connection.createStatement()) {
        final ChaosActivationHandle handle =
            activateReject("it-stmt-reject", OperationType.JDBC_STATEMENT_EXECUTE);
        try {
          assertThatThrownBy(() -> runStatementExecuteAdvice(statement, "SELECT 1"))
              .isInstanceOf(SQLException.class)
              .hasMessage("db down");
        } finally {
          handle.stop();
        }
      }
    }

    private void runStatementExecuteAdvice(final Statement statement, final String sql)
        throws Throwable {
      BootstrapDispatcher.beforeJdbcStatementExecute(sql);
      statement.execute(sql);
    }
  }

  @Nested
  @DisplayName("PrepareStatement interception")
  class PrepareStatementInterception {

    @Test
    @DisplayName("REJECT on JDBC_PREPARED_STATEMENT throws SQLException via PrepareStatementAdvice")
    void prepareStatementIsRejected() throws Exception {
      try (Connection connection = DriverManager.getConnection(H2_URL)) {
        final ChaosActivationHandle handle =
            activateReject("it-prepare-reject", OperationType.JDBC_PREPARED_STATEMENT);
        try {
          assertThatThrownBy(() -> runPrepareAdvice(connection, "SELECT ?"))
              .isInstanceOf(SQLException.class)
              .hasMessage("db down");
        } finally {
          handle.stop();
        }
      }
    }

    private void runPrepareAdvice(final Connection connection, final String sql) throws Throwable {
      BootstrapDispatcher.beforeJdbcPreparedStatement(sql);
      try (PreparedStatement ignored = connection.prepareStatement(sql)) {
        // passthrough
      }
    }
  }

  @Nested
  @DisplayName("Commit interception")
  class CommitInterception {

    @Test
    @DisplayName("REJECT on JDBC_TRANSACTION_COMMIT throws SQLException")
    void commitIsRejected() throws Exception {
      try (Connection connection = DriverManager.getConnection(H2_URL)) {
        connection.setAutoCommit(false);
        final ChaosActivationHandle handle =
            activateReject("it-commit-reject", OperationType.JDBC_TRANSACTION_COMMIT);
        try {
          assertThatThrownBy(() -> runCommitAdvice(connection))
              .isInstanceOf(SQLException.class)
              .hasMessage("db down");
        } finally {
          handle.stop();
        }
      }
    }

    private void runCommitAdvice(final Connection connection) throws Throwable {
      BootstrapDispatcher.beforeJdbcTransactionCommit();
      connection.commit();
    }
  }

  @Nested
  @DisplayName("Rollback interception")
  class RollbackInterception {

    @Test
    @DisplayName("REJECT on JDBC_TRANSACTION_ROLLBACK throws SQLException")
    void rollbackIsRejected() throws Exception {
      try (Connection connection = DriverManager.getConnection(H2_URL)) {
        connection.setAutoCommit(false);
        final ChaosActivationHandle handle =
            activateReject("it-rollback-reject", OperationType.JDBC_TRANSACTION_ROLLBACK);
        try {
          assertThatThrownBy(() -> runRollbackAdvice(connection))
              .isInstanceOf(SQLException.class)
              .hasMessage("db down");
        } finally {
          handle.stop();
        }
      }
    }

    private void runRollbackAdvice(final Connection connection) throws Throwable {
      BootstrapDispatcher.beforeJdbcTransactionRollback();
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
        BootstrapDispatcher.beforeJdbcStatementExecute("SELECT 1");
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
        BootstrapDispatcher.beforeJdbcPreparedStatement("SELECT ?");
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
        "REJECT on JDBC_CONNECTION_ACQUIRE throws SQLException via HikariGetConnectionAdvice")
    void hikariGetConnectionIsRejected() throws Exception {
      final HikariConfig config = new HikariConfig();
      config.setJdbcUrl(H2_URL);
      config.setMaximumPoolSize(1);
      config.setPoolName("chaos-jdbc-it-pool");
      try (HikariDataSource dataSource = new HikariDataSource(config)) {
        final ChaosActivationHandle handle =
            activateReject("it-hikari-reject", OperationType.JDBC_CONNECTION_ACQUIRE);
        try {
          assertThatThrownBy(() -> runHikariAcquireAdvice(dataSource))
              .isInstanceOf(SQLException.class)
              .hasMessage("db down");
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
      BootstrapDispatcher.beforeJdbcConnectionAcquire(poolName);
      try (Connection ignored = dataSource.getConnection()) {
        // passthrough
      }
    }
  }
}
