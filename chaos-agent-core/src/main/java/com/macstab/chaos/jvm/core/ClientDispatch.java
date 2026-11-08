package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import java.net.InetSocketAddress;
import org.jspecify.annotations.Nullable;

/**
 * Dispatch of client-library calls: HTTP clients, JDBC and the messaging, RPC, cache and cloud
 * clients.
 *
 * <p>One domain of the dispatch the instrumentation layer calls through the bootstrap bridge; the
 * evaluation it shares with the other domains is {@link DispatchEngine}. Not part of the stable API
 * (see {@link ChaosDispatcher}).
 */
@Internal
public final class ClientDispatch {

  private final DispatchEngine engine;

  ClientDispatch(final DispatchEngine engine) {
    this.engine = engine;
  }

  /**
   * Called before a call through a client library (Kafka, gRPC, Redis, R2DBC, AWS SDK, Netty). A
   * reject throws the library's own exception type (see {@code FailureFactory.clientReject}).
   *
   * @param operation the {@link OperationType} name of the call
   * @param client the client object the call is made on
   * @param target what the selector's main pattern matches: topic(s), method, command, AWS
   *     operation, or {@code null}
   * @param detail a second attribute: the AWS service, or {@code null}
   * @param remoteAddress the peer {@link InetSocketAddress} of a Netty channel, or {@code null}
   * @throws Throwable if a scenario delays and then rejects the call
   */
  public void beforeClientCall(
      final String operation,
      final Object client,
      final @Nullable String target,
      final @Nullable String detail,
      final @Nullable Object remoteAddress)
      throws Throwable {
    final OperationType operationType = OperationType.valueOf(operation);
    if (engine.idle(operationType)) {
      return;
    }
    final InetSocketAddress peer =
        remoteAddress instanceof InetSocketAddress address ? address : null;
    final RuntimeDecision decision =
        engine.evaluate(
            new InvocationContext(
                operationType,
                client == null ? operation : client.getClass().getName(),
                detail,
                target,
                false,
                null,
                null,
                engine.scopeContext.currentSessionId(),
                InvocationContext.UNKNOWN,
                null,
                peer == null ? null : peer.getAddress(),
                peer == null ? InvocationContext.UNKNOWN : peer.getPort(),
                null,
                InvocationContext.UNKNOWN));
    engine.applyDelayAndGate(decision);
    final TerminalAction terminal = decision.terminalAction();
    if (terminal instanceof TerminalAction.Throw thrown) {
      // Only a reject throws on a client operation (exception injection is for method selectors),
      // so the failure is rebuilt as the library's own exception.
      throw FailureFactory.clientReject(operationType, client, thrown.failure().getMessage());
    }
  }

  /**
   * Called before an HTTP client send. A reject throws the client's own {@link java.io.IOException}
   * (ADR 0005: there is no suppress, which no real client does).
   *
   * @param url the target URL of the HTTP request
   * @param opType the specific {@link OperationType} for this send
   * @throws Throwable if an active scenario rejects the send
   */
  public void beforeHttpSend(final @Nullable String url, final OperationType opType)
      throws Throwable {
    if (engine.idle(opType)) {
      return;
    }
    apply(
        engine.evaluate(
            new InvocationContext(
                opType,
                "http.client",
                null,
                url,
                false,
                null,
                null,
                engine.scopeContext.currentSessionId())));
  }

  /**
   * Called before acquiring a JDBC connection from a pool.
   *
   * @param poolName the name of the JDBC connection pool
   * @throws Throwable an {@link java.sql.SQLException} if an active scenario rejects the acquire
   */
  public void beforeJdbcConnectionAcquire(final @Nullable String poolName) throws Throwable {
    evaluateJdbc(OperationType.JDBC_CONNECTION_ACQUIRE, "jdbc.pool", poolName);
  }

  /**
   * Called before executing a JDBC {@link java.sql.Statement}.
   *
   * @param sql the SQL statement being executed
   * @throws Throwable an {@link java.sql.SQLException} if an active scenario rejects the call
   */
  public void beforeJdbcStatementExecute(final @Nullable String sql) throws Throwable {
    evaluateJdbc(OperationType.JDBC_STATEMENT_EXECUTE, "java.sql.Statement", snippet(sql));
  }

  /**
   * Called before preparing a JDBC {@link java.sql.PreparedStatement}.
   *
   * @param sql the SQL being prepared
   * @throws Throwable an {@link java.sql.SQLException} if an active scenario rejects the call
   */
  public void beforeJdbcPreparedStatement(final @Nullable String sql) throws Throwable {
    evaluateJdbc(OperationType.JDBC_PREPARED_STATEMENT, "java.sql.Connection", snippet(sql));
  }

  /**
   * Called before a JDBC transaction commit.
   *
   * @throws Throwable an {@link java.sql.SQLException} if an active scenario rejects the commit
   */
  public void beforeJdbcTransactionCommit() throws Throwable {
    evaluateJdbc(OperationType.JDBC_TRANSACTION_COMMIT, "java.sql.Connection", null);
  }

  /**
   * Called before a JDBC transaction rollback.
   *
   * @throws Throwable an {@link java.sql.SQLException} if an active scenario rejects the rollback
   */
  public void beforeJdbcTransactionRollback() throws Throwable {
    evaluateJdbc(OperationType.JDBC_TRANSACTION_ROLLBACK, "java.sql.Connection", null);
  }

  private void evaluateJdbc(
      final OperationType opType, final String targetClassName, final @Nullable String targetName)
      throws Throwable {
    if (engine.idle(opType)) {
      return;
    }
    apply(
        engine.evaluate(
            new InvocationContext(
                opType,
                targetClassName,
                null,
                targetName,
                false,
                null,
                null,
                engine.scopeContext.currentSessionId())));
  }

  /** Applies the delay and gate of an HTTP or JDBC decision, then throws its rejection. */
  private void apply(final RuntimeDecision decision) throws Throwable {
    engine.applyDelayAndGate(decision);
    if (decision.terminalAction() instanceof TerminalAction.Throw thrown) {
      throw thrown.failure();
    }
  }

  private static @Nullable String snippet(final @Nullable String sql) {
    if (sql == null) {
      return null;
    }
    return sql.length() <= 200 ? sql : sql.substring(0, 200);
  }
}
