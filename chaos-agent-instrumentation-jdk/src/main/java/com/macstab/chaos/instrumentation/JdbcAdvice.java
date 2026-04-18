package com.macstab.chaos.instrumentation;

import com.macstab.chaos.api.ChaosJdbcSuppressException;
import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} classes for JDBC and connection-pool interception points.
 *
 * <p>Targets:
 *
 * <ul>
 *   <li>HikariCP ({@code com.zaxxer.hikari.pool.HikariPool}) — {@code getConnection(long)}
 *   <li>c3p0 ({@code com.mchange.v2.c3p0.impl.C3P0PooledConnectionPool}) — {@code
 *       checkoutPooledConnection()}
 *   <li>{@link java.sql.Statement} — {@code execute(String)}, {@code executeQuery(String)}, {@code
 *       executeUpdate(String)}
 *   <li>{@link java.sql.Connection} — {@code prepareStatement(String)}, {@code commit()}, {@code
 *       rollback()}
 * </ul>
 *
 * <p>All advice classes route through {@link BootstrapDispatcher} so they require no compile-time
 * dependency on HikariCP or c3p0. When a scenario suppresses the call, the advice throws {@link
 * ChaosJdbcSuppressException} so the caller observes a terminal failure.
 */
final class JdbcAdvice {
  private JdbcAdvice() {}

  /**
   * Intercepts {@code com.zaxxer.hikari.pool.HikariPool.getConnection(long)}. Uses {@code
   * Advice.This(typing = Advice.AssignReturned.Typing.DYNAMIC, optional = true)}-style Object
   * receiver so the advice compiles without HikariCP on the classpath.
   */
  static final class HikariGetConnectionAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object pool) throws Throwable {
      final String poolName = JdbcTargetExtractor.fromHikariPool(pool);
      if (BootstrapDispatcher.beforeJdbcConnectionAcquire(poolName)) {
        throw new ChaosJdbcSuppressException(
            "JDBC connection acquire suppressed by chaos agent: " + poolName);
      }
    }
  }

  /**
   * Intercepts {@code
   * com.mchange.v2.c3p0.impl.C3P0PooledConnectionPool.checkoutPooledConnection()}.
   */
  static final class C3p0CheckoutAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object pool) throws Throwable {
      final String poolName = pool == null ? null : pool.getClass().getName();
      if (BootstrapDispatcher.beforeJdbcConnectionAcquire(poolName)) {
        throw new ChaosJdbcSuppressException(
            "JDBC connection acquire suppressed by chaos agent: " + poolName);
      }
    }
  }

  /**
   * Intercepts {@code java.sql.Statement.execute(String)}, {@code
   * java.sql.Statement.executeQuery(String)}, and {@code java.sql.Statement.executeUpdate(String)}.
   */
  static final class StatementExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final String sql) throws Throwable {
      if (BootstrapDispatcher.beforeJdbcStatementExecute(sql)) {
        throw new ChaosJdbcSuppressException(
            "JDBC statement execute suppressed by chaos agent: " + sql);
      }
    }
  }

  /** Intercepts {@code java.sql.Connection.prepareStatement(String)}. */
  static final class PrepareStatementAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final String sql) throws Throwable {
      if (BootstrapDispatcher.beforeJdbcPreparedStatement(sql)) {
        throw new ChaosJdbcSuppressException(
            "JDBC prepareStatement suppressed by chaos agent: " + sql);
      }
    }
  }

  /** Intercepts {@code java.sql.Connection.commit()}. */
  static final class CommitAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      if (BootstrapDispatcher.beforeJdbcTransactionCommit()) {
        throw new ChaosJdbcSuppressException("JDBC commit suppressed by chaos agent");
      }
    }
  }

  /** Intercepts {@code java.sql.Connection.rollback()}. */
  static final class RollbackAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      if (BootstrapDispatcher.beforeJdbcTransactionRollback()) {
        throw new ChaosJdbcSuppressException("JDBC rollback suppressed by chaos agent");
      }
    }
  }
}
