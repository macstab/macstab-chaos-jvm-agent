package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Probe main for the JDBC traffic of a connection pool and a driver: the validation, set-up and
 * reset statements they issue themselves are not the application's JDBC calls. Runs only in child
 * JVMs whose classpath carries H2 and HikariCP ({@code chaos.test.jdbcClasspath}).
 */
public final class JdbcInternalProbeMain {

  /** Longer than HikariCP's 500 ms window in which a borrowed connection is not validated. */
  private static final long PAST_ALIVE_BYPASS_MILLIS = 700L;

  private static final AtomicInteger DATABASES = new AtomicInteger();

  private JdbcInternalProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "driver-validation" -> driverValidation();
      case "pool-queries" -> poolQueries();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /**
   * A driver whose {@code isValid} runs a statement, as pgjdbc's does, behind HikariCP's JDBC4
   * validation, under a {@code JDBC_STATEMENT_EXECUTE} reject. Prints {@code isValid} (a direct
   * call), {@code borrowMillis} (a borrow that validates), {@code app.type} (the application's
   * statement) and {@code applied}.
   */
  private static void driverValidation() throws Exception {
    final String url = url();
    try (ChaosActivationHandle reject = executeReject()) {
      try (Connection direct =
          new com.example.chaostarget.JdbcDriverProbeMain.StatementValidatedConnection(url)) {
        System.out.println("isValid=" + direct.isValid(1));
      }
      final HikariConfig config = config();
      config.setDataSource(
          new com.example.chaostarget.JdbcDriverProbeMain.StatementValidatedDataSource(url));
      try (HikariDataSource pool = new HikariDataSource(config)) {
        borrowValidatedAndQuery(pool);
      }
      System.out.println("applied=" + reject.appliedCount());
    }
  }

  /**
   * HikariCP's {@code connectionTestQuery} and {@code connectionInitSql} under a {@code
   * JDBC_STATEMENT_EXECUTE} reject, then its rollback of a dirty connection it takes back under a
   * {@code JDBC_TRANSACTION_ROLLBACK} reject. Prints {@code init} (the pool's start), {@code
   * borrowMillis}, {@code app.type}, {@code applied}, {@code rollback.close} (the application's
   * close of a dirty connection), {@code rollback.applied}, then {@code rollback.app} and {@code
   * rollback.appApplied} (the application's own rollback under the same scenario).
   */
  private static void poolQueries() throws Exception {
    final HikariConfig config = config();
    config.setJdbcUrl(url());
    config.setConnectionTestQuery("SELECT 1");
    config.setConnectionInitSql("SET @PROBE = 1");
    try (ChaosActivationHandle reject = executeReject()) {
      final HikariDataSource pool;
      try {
        pool = new HikariDataSource(config);
      } catch (final RuntimeException failed) {
        System.out.println("init=" + failed.getClass().getName());
        System.out.println("applied=" + reject.appliedCount());
        return;
      }
      System.out.println("init=ok");
      try (pool) {
        borrowValidatedAndQuery(pool);
        System.out.println("applied=" + reject.appliedCount());
        reject.close();
        try (ChaosActivationHandle rollback =
            RealCallRows.activate(
                "pool-rollback",
                ChaosSelector.jdbc(OperationType.JDBC_TRANSACTION_ROLLBACK),
                ChaosEffect.reject("chaos rollback"))) {
          final Connection dirty = pool.getConnection();
          dirty.setAutoCommit(false);
          try (Statement statement = dirty.createStatement()) {
            statement.execute("SELECT 1");
          }
          // The pool rolls the open transaction back when it takes the connection back.
          System.out.println("rollback.close=" + RealCallRows.outcome(dirty::close));
          System.out.println("rollback.applied=" + rollback.appliedCount());
          // The application's own rollback, through the same pool and scenario, is a target.
          try (Connection app = pool.getConnection()) {
            app.setAutoCommit(false);
            System.out.println("rollback.app=" + RealCallRows.outcome(app::rollback));
            System.out.println("rollback.appApplied=" + rollback.appliedCount());
          }
        }
      }
    }
  }

  /**
   * Borrows once, returns the connection, waits until the pool validates it on the next borrow,
   * borrows again and runs the application's statement.
   */
  private static void borrowValidatedAndQuery(final HikariDataSource pool) throws Exception {
    pool.getConnection().close();
    Thread.sleep(PAST_ALIVE_BYPASS_MILLIS);
    final long start = System.nanoTime();
    try (Connection connection = pool.getConnection()) {
      System.out.println("borrowMillis=" + (System.nanoTime() - start) / 1_000_000L);
      Throwable failure = null;
      try (Statement statement = connection.createStatement()) {
        statement.executeQuery("SELECT 1").close();
      } catch (final SQLException rejected) {
        failure = rejected;
      }
      RealCallRows.print("app", failure);
    }
  }

  private static ChaosActivationHandle executeReject() {
    return RealCallRows.activate(
        "pool-execute",
        ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE),
        ChaosEffect.reject("chaos statement"));
  }

  private static HikariConfig config() {
    final HikariConfig config = new HikariConfig();
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(2_000L);
    config.setUsername("sa");
    config.setPassword("");
    return config;
  }

  private static String url() {
    return "jdbc:h2:mem:internal" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
  }
}
