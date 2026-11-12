package com.example.chaostarget;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * A JDBC driver for the JDBC probes, outside the agent's packages (which the agent never weaves).
 * Its connection validates the way pgjdbc does, with a statement. It extends H2's classes, which
 * only the probe JVMs have, so it is named like a probe main and kept out of test discovery.
 */
public final class JdbcDriverProbeMain {

  private JdbcDriverProbeMain() {}

  /** An H2 connection whose {@code isValid} runs a statement, the way pgjdbc validates. */
  public static final class StatementValidatedConnection extends org.h2.jdbc.JdbcConnection {

    public StatementValidatedConnection(final String url) throws SQLException {
      super(url, new Properties(), "sa", "", false);
    }

    @Override
    public boolean isValid(final int timeout) {
      try (Statement statement = createStatement()) {
        statement.execute("SELECT 1");
        return true;
      } catch (final SQLException failed) {
        return false;
      }
    }
  }

  /** A data source that hands out {@link StatementValidatedConnection}s. */
  public static final class StatementValidatedDataSource implements javax.sql.DataSource {

    private final String url;

    public StatementValidatedDataSource(final String url) {
      this.url = url;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return new StatementValidatedConnection(url);
    }

    @Override
    public Connection getConnection(final String user, final String password) throws SQLException {
      return getConnection();
    }

    @Override
    public java.io.@org.jspecify.annotations.Nullable PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(final java.io.PrintWriter out) {}

    @Override
    public void setLoginTimeout(final int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public java.util.logging.Logger getParentLogger() {
      return java.util.logging.Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(final Class<T> type) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(final Class<?> type) {
      return false;
    }
  }
}
