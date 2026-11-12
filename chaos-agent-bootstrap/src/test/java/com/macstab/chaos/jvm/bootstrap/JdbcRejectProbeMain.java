package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Probe main for a JDBC reject of prepare, commit and rollback on a real H2 connection, plain and
 * through HikariCP. Runs only in child JVMs whose classpath carries H2 and HikariCP ({@code
 * chaos.test.jdbcClasspath}).
 */
public final class JdbcRejectProbeMain {

  private JdbcRejectProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "rejects" -> rejects();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  private static void rejects() throws Exception {
    try (Connection plain = DriverManager.getConnection("jdbc:h2:mem:plain;DB_CLOSE_DELAY=-1")) {
      calls("plain", plain);
    }
    final HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:pooled;DB_CLOSE_DELAY=-1");
    config.setMaximumPoolSize(1);
    try (HikariDataSource pool = new HikariDataSource(config)) {
      final DataSource source = pool;
      try (Connection pooled = source.getConnection()) {
        calls("pooled", pooled);
      }
      System.out.println(
          "pooled.borrowAfter=" + RealCallRows.outcome(() -> source.getConnection().close()));
    }
  }

  private static void calls(final String prefix, final Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS t (v INT)");
    }
    connection.setAutoCommit(false);
    row(
        prefix + ".prepare",
        OperationType.JDBC_PREPARED_STATEMENT,
        () -> connection.prepareStatement("INSERT INTO t VALUES (1)").close());
    try (Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO t VALUES (2)");
    }
    row(prefix + ".commit", OperationType.JDBC_TRANSACTION_COMMIT, connection::commit);
    row(prefix + ".rollback", OperationType.JDBC_TRANSACTION_ROLLBACK, connection::rollback);
    try (Statement statement = connection.createStatement();
        java.sql.ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM t")) {
      rows.next();
      System.out.println(prefix + ".rowsAfterRollback=" + rows.getInt(1));
    }
  }

  private static void row(
      final String row, final OperationType operation, final RealCallRows.Call call) {
    final @Nullable Throwable failure =
        RealCallRows.during(row, ChaosSelector.jdbc(operation), ChaosEffect.reject("chaos"), call);
    System.out.println(row + ".type=" + (failure == null ? "none" : failure.getClass().getName()));
    if (failure != null) {
      System.out.println(row + ".message=" + failure.getMessage());
    }
  }
}
