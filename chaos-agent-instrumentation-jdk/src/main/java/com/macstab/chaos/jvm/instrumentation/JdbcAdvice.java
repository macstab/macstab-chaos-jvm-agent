package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.OperationTags;
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
 *   <li>{@link java.sql.Statement} — {@code execute}, {@code executeQuery}, {@code executeUpdate},
 *       and {@code executeLargeUpdate} (every overload whose first argument is the SQL string)
 *   <li>{@link java.sql.Connection} — {@code prepareStatement} and {@code prepareCall} (every
 *       overload whose first argument is the SQL string), {@code commit()}, {@code rollback()}
 * </ul>
 *
 * <p>All advice classes route through {@link BootstrapDispatcher} so they require no compile-time
 * dependency on HikariCP or c3p0. A rejected call fails with the {@link java.sql.SQLException} the
 * dispatcher throws. The advice holds no string concatenation ({@code invokedynamic}): it is
 * inlined into drivers and pools compiled for Java 6.
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
      if (BootstrapDispatcher.idle(OperationTags.JDBC_CONNECTION_ACQUIRE)) {
        return;
      }
      final String poolName;
      BootstrapDispatcher.beginInternalCall();
      try {
        poolName = JdbcTargetExtractor.fromHikariPool(pool);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      BootstrapDispatcher.beforeJdbcConnectionAcquire(poolName);
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
      BootstrapDispatcher.beforeJdbcConnectionAcquire(poolName);
    }
  }

  /**
   * Intercepts every {@code java.sql.Statement} execute variant whose first argument is the SQL
   * string: {@code execute}, {@code executeQuery}, {@code executeUpdate}, {@code
   * executeLargeUpdate} — including the generated-keys overloads {@code execute(String, int)},
   * {@code execute(String, int[])}, {@code execute(String, String[])} and their update equivalents.
   */
  static final class StatementExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final String sql) throws Throwable {
      // Entered also inside the driver's or pool's own work, so the exit advice stays balanced.
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_JDBC_EXECUTE)
          || BootstrapDispatcher.inScope(BootstrapDispatcher.SCOPE_JDBC_INTERNAL)) {
        return;
      }
      try {
        BootstrapDispatcher.beforeJdbcStatementExecute(sql);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JDBC_EXECUTE);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JDBC_EXECUTE);
    }
  }

  /**
   * Intercepts the no-argument {@code PreparedStatement.execute()}, {@code executeQuery()}, {@code
   * executeUpdate()} and {@code executeLargeUpdate()} — the execution path of JPA, Hibernate, jOOQ
   * and Spring {@code JdbcTemplate}. The SQL text was bound at prepare time and is not available
   * here, so these calls report a {@code null} target: they match JDBC_STATEMENT_EXECUTE scenarios
   * whose target pattern is ANY. The SQL is visible to JDBC_PREPARED_STATEMENT scenarios at prepare
   * time. Also intercepts {@code executeBatch()} / {@code executeLargeBatch()} of every {@code
   * Statement}: one evaluation per batch, likewise without SQL text.
   */
  static final class PreparedStatementExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      // Entered also inside the driver's or pool's own work, so the exit advice stays balanced.
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_JDBC_EXECUTE)
          || BootstrapDispatcher.inScope(BootstrapDispatcher.SCOPE_JDBC_INTERNAL)) {
        return;
      }
      try {
        BootstrapDispatcher.beforeJdbcStatementExecute(null);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JDBC_EXECUTE);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JDBC_EXECUTE);
    }
  }

  /**
   * Intercepts every {@code java.sql.Connection.prepareStatement} / {@code prepareCall} overload
   * whose first argument is the SQL string, including the generated-keys and column-index/name
   * variants such as {@code prepareStatement(String, int)}, {@code prepareStatement(String,
   * int[])}, {@code prepareStatement(String, String[])}, and {@code prepareStatement(String, int,
   * int)}.
   */
  static final class PrepareStatementAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final String sql) throws Throwable {
      // Entered also inside the driver's or pool's own work, so the exit advice stays balanced.
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_JDBC_PREPARE)
          || BootstrapDispatcher.inScope(BootstrapDispatcher.SCOPE_JDBC_INTERNAL)) {
        return;
      }
      try {
        BootstrapDispatcher.beforeJdbcPreparedStatement(sql);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JDBC_PREPARE);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JDBC_PREPARE);
    }
  }

  /**
   * The JDBC work of a driver or a pool itself, scope-only ({@link
   * BootstrapDispatcher#SCOPE_JDBC_INTERNAL}): {@code Connection.isValid(int)} (pgjdbc runs a
   * statement there), and HikariCP's {@code PoolBase.isConnectionDead}, {@code newPoolEntry} (its
   * {@code connectionInitSql} and driver checks), {@code resetConnectionState} and {@code
   * ProxyConnection.close} (the rollback of a connection taken back dirty). The statements,
   * prepares, commits and rollbacks inside it are not the application's JDBC calls.
   */
  static final class JdbcInternal {
    @Advice.OnMethodEnter
    static void enter() {
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_JDBC_INTERNAL);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JDBC_INTERNAL);
    }
  }

  /** Intercepts {@code java.sql.Connection.commit()}. */
  static final class CommitAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      // Entered also inside the driver's or pool's own work, so the exit advice stays balanced.
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_JDBC_COMMIT)
          || BootstrapDispatcher.inScope(BootstrapDispatcher.SCOPE_JDBC_INTERNAL)) {
        return;
      }
      try {
        BootstrapDispatcher.beforeJdbcTransactionCommit();
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JDBC_COMMIT);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JDBC_COMMIT);
    }
  }

  /** Intercepts {@code java.sql.Connection.rollback()}. */
  static final class RollbackAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      // Entered also inside the driver's or pool's own work, so the exit advice stays balanced.
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_JDBC_ROLLBACK)
          || BootstrapDispatcher.inScope(BootstrapDispatcher.SCOPE_JDBC_INTERNAL)) {
        return;
      }
      try {
        BootstrapDispatcher.beforeJdbcTransactionRollback();
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JDBC_ROLLBACK);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JDBC_ROLLBACK);
    }
  }
}
