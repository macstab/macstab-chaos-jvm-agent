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
      final String target,
      final String detail,
      final Object remoteAddress)
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
    if (terminal != null && terminal.kind() == TerminalKind.THROW) {
      // Only a reject throws on a client operation (exception injection is for method selectors),
      // so the failure is rebuilt as the library's own exception.
      throw FailureFactory.clientReject(operationType, client, terminal.failure().getMessage());
    }
  }

  /**
   * Called before an HTTP client send; returns {@code true} to suppress the call.
   *
   * @param url the target URL of the HTTP request
   * @param opType the specific {@link OperationType} for this send
   * @return {@code true} to suppress the call; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeHttpSend(final String url, final OperationType opType) throws Throwable {
    final RuntimeDecision decision =
        (engine.idle(opType)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    opType,
                    "http.client",
                    null,
                    url,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId())));
    engine.applyDelayAndGate(decision);
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.failure();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    return false;
  }

  /**
   * Called before acquiring a JDBC connection from a pool; returns {@code true} to suppress the
   * acquire.
   *
   * @param poolName the name of the JDBC connection pool
   * @return {@code true} to suppress the acquire; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeJdbcConnectionAcquire(final String poolName) throws Throwable {
    return evaluateJdbc(OperationType.JDBC_CONNECTION_ACQUIRE, "jdbc.pool", poolName);
  }

  /**
   * Called before executing a JDBC {@link java.sql.Statement}; returns {@code true} to suppress the
   * call.
   *
   * @param sql the SQL statement being executed
   * @return {@code true} to suppress the call; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeJdbcStatementExecute(final String sql) throws Throwable {
    return evaluateJdbc(OperationType.JDBC_STATEMENT_EXECUTE, "java.sql.Statement", snippet(sql));
  }

  /**
   * Called before preparing a JDBC {@link java.sql.PreparedStatement}; returns {@code true} to
   * suppress the call.
   *
   * @param sql the SQL being prepared
   * @return {@code true} to suppress the call; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeJdbcPreparedStatement(final String sql) throws Throwable {
    return evaluateJdbc(OperationType.JDBC_PREPARED_STATEMENT, "java.sql.Connection", snippet(sql));
  }

  /**
   * Called before a JDBC transaction commit; returns {@code true} to suppress the commit.
   *
   * @return {@code true} to suppress the commit; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeJdbcTransactionCommit() throws Throwable {
    return evaluateJdbc(OperationType.JDBC_TRANSACTION_COMMIT, "java.sql.Connection", null);
  }

  /**
   * Called before a JDBC transaction rollback; returns {@code true} to suppress the rollback.
   *
   * @return {@code true} to suppress the rollback; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeJdbcTransactionRollback() throws Throwable {
    return evaluateJdbc(OperationType.JDBC_TRANSACTION_ROLLBACK, "java.sql.Connection", null);
  }

  private boolean evaluateJdbc(
      final OperationType opType, final String targetClassName, final @Nullable String targetName)
      throws Throwable {
    final RuntimeDecision decision =
        (engine.idle(opType)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    opType,
                    targetClassName,
                    null,
                    targetName,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId())));
    engine.applyDelayAndGate(decision);
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.failure();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    return false;
  }

  private static @Nullable String snippet(final @Nullable String sql) {
    if (sql == null) {
      return null;
    }
    return sql.length() <= 200 ? sql : sql.substring(0, 200);
  }
}
