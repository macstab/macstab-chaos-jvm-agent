package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Set;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JdbcAdvice / JDBC bridge")
class JdbcAdviceTest {

  private ChaosRuntime runtime;
  private ChaosBridge bridge;

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
    bridge = new ChaosBridge(runtime);
  }

  @AfterEach
  void resetBridge() {
    BootstrapDispatcher.install(null, null);
    runtime.close();
  }

  @Nested
  @DisplayName("woven into a Java 6 class file (version 50)")
  class Java6ClassFile {

    @Test
    @DisplayName(
        "every JDBC advice weaves and runs: it holds no invokedynamic, which version 50 forbids")
    void everyAdviceWeavesIntoVersion50() throws Exception {
      // Arrange: a version-50 class with one method per JDBC hook, as old drivers and pools are.
      final Class<?> type =
          new ByteBuddy(ClassFileVersion.JAVA_V6)
              .subclass(Object.class)
              .name("com.macstab.chaos.jvm.instrumentation.Version50Jdbc")
              .defineMethod("execute", boolean.class, Visibility.PUBLIC)
              .withParameters(String.class)
              .intercept(FixedValue.value(true))
              .defineMethod("executeQuery", Object.class, Visibility.PUBLIC)
              .intercept(FixedValue.nullValue())
              .defineMethod("prepareStatement", Object.class, Visibility.PUBLIC)
              .withParameters(String.class)
              .intercept(FixedValue.nullValue())
              .defineMethod("commit", void.class, Visibility.PUBLIC)
              .intercept(StubMethod.INSTANCE)
              .defineMethod("rollback", void.class, Visibility.PUBLIC)
              .intercept(StubMethod.INSTANCE)
              .defineMethod("getConnection", Object.class, Visibility.PUBLIC)
              .withParameters(long.class)
              .intercept(FixedValue.nullValue())
              .defineMethod("checkoutPooledConnection", Object.class, Visibility.PUBLIC)
              .intercept(FixedValue.nullValue())
              .visit(
                  Advice.to(JdbcAdvice.StatementExecuteAdvice.class)
                      .on(ElementMatchers.named("execute")))
              .visit(
                  Advice.to(JdbcAdvice.PreparedStatementExecuteAdvice.class)
                      .on(ElementMatchers.named("executeQuery")))
              .visit(
                  Advice.to(JdbcAdvice.PrepareStatementAdvice.class)
                      .on(ElementMatchers.named("prepareStatement")))
              .visit(Advice.to(JdbcAdvice.CommitAdvice.class).on(ElementMatchers.named("commit")))
              .visit(
                  Advice.to(JdbcAdvice.RollbackAdvice.class).on(ElementMatchers.named("rollback")))
              .visit(
                  Advice.to(JdbcAdvice.HikariGetConnectionAdvice.class)
                      .on(ElementMatchers.named("getConnection")))
              .visit(
                  Advice.to(JdbcAdvice.C3p0CheckoutAdvice.class)
                      .on(ElementMatchers.named("checkoutPooledConnection")))
              .make()
              .load(JdbcAdviceTest.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
              .getLoaded();
      final Object instance = type.getConstructor().newInstance();

      // Act + Assert: each woven method runs to its own result (no delegate is installed).
      assertThat(type.getMethod("execute", String.class).invoke(instance, "SELECT 1"))
          .isEqualTo(true);
      for (final Method method : type.getDeclaredMethods()) {
        if (method.getParameterCount() == 0) {
          assertThatCode(() -> method.invoke(instance)).doesNotThrowAnyException();
        }
      }
      assertThatCode(() -> type.getMethod("prepareStatement", String.class).invoke(instance, "x"))
          .doesNotThrowAnyException();
      assertThatCode(() -> type.getMethod("getConnection", long.class).invoke(instance, 1L))
          .doesNotThrowAnyException();
    }
  }

  /** Activates a reject of {@code operation} whose target matches {@code target}. */
  private ChaosActivationHandle rejectOn(final OperationType operation, final NamePattern target) {
    return runtime.activate(
        ChaosScenario.builder("jdbc-" + operation)
            .selector(new ChaosSelector.JdbcSelector(Set.of(operation), target))
            .effect(ChaosEffect.reject("jdbc refused"))
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  @Nested
  @DisplayName("without a bridge")
  class WithoutBridge {

    @Test
    @DisplayName("every JDBC entry returns at once and leaves no scope entered")
    void everyEntryReturnsAndLeavesNoScope() throws Throwable {
      BootstrapDispatcher.beforeJdbcConnectionAcquire("hikari-primary");
      BootstrapDispatcher.beforeJdbcStatementExecute("SELECT 1");
      BootstrapDispatcher.beforeJdbcPreparedStatement("SELECT * FROM t");
      BootstrapDispatcher.beforeJdbcTransactionCommit();
      BootstrapDispatcher.beforeJdbcTransactionRollback();

      for (final int scope :
          new int[] {
            BootstrapDispatcher.SCOPE_JDBC_EXECUTE,
            BootstrapDispatcher.SCOPE_JDBC_PREPARE,
            BootstrapDispatcher.SCOPE_JDBC_COMMIT,
            BootstrapDispatcher.SCOPE_JDBC_ROLLBACK
          }) {
        assertThat(BootstrapDispatcher.inScope(scope)).isFalse();
      }
      assertThat(BootstrapDispatcher.idle("JDBC_STATEMENT_EXECUTE")).isTrue();
    }
  }

  @Nested
  @DisplayName("the bridge reaches the runtime")
  class BridgeReachesTheRuntime {

    @Test
    @DisplayName("a connection acquire matches by pool name; a null pool name matches ANY only")
    void connectionAcquire() {
      final ChaosActivationHandle handle =
          rejectOn(OperationType.JDBC_CONNECTION_ACQUIRE, NamePattern.exact("hikari-primary"));

      assertThatCode(() -> bridge.beforeJdbcConnectionAcquire("hikari-replica"))
          .doesNotThrowAnyException();
      assertThatCode(() -> bridge.beforeJdbcConnectionAcquire(null)).doesNotThrowAnyException();
      assertThatThrownBy(() -> bridge.beforeJdbcConnectionAcquire("hikari-primary"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("jdbc refused");
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a statement execute matches by SQL; a null SQL (prepared) matches ANY")
    void statementExecute() {
      final ChaosActivationHandle bySql =
          rejectOn(OperationType.JDBC_STATEMENT_EXECUTE, NamePattern.prefix("UPDATE"));

      assertThatCode(() -> bridge.beforeJdbcStatementExecute("SELECT 1"))
          .doesNotThrowAnyException();
      assertThatCode(() -> bridge.beforeJdbcStatementExecute(null)).doesNotThrowAnyException();
      assertThatThrownBy(() -> bridge.beforeJdbcStatementExecute("UPDATE t SET a = 1"))
          .isInstanceOf(SQLException.class);
      assertThat(bySql.appliedCount()).isEqualTo(1L);
      bySql.close();

      final ChaosActivationHandle any =
          rejectOn(OperationType.JDBC_STATEMENT_EXECUTE, NamePattern.any());
      assertThatThrownBy(() -> bridge.beforeJdbcStatementExecute(null))
          .isInstanceOf(SQLException.class);
      assertThat(any.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a prepare matches by SQL; a null SQL is safe")
    void preparedStatement() {
      final ChaosActivationHandle handle =
          rejectOn(OperationType.JDBC_PREPARED_STATEMENT, NamePattern.prefix("SELECT"));

      assertThatCode(() -> bridge.beforeJdbcPreparedStatement("DELETE FROM t"))
          .doesNotThrowAnyException();
      assertThatCode(() -> bridge.beforeJdbcPreparedStatement(null)).doesNotThrowAnyException();
      assertThatThrownBy(() -> bridge.beforeJdbcPreparedStatement("SELECT * FROM t"))
          .isInstanceOf(SQLException.class);
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a commit is a JDBC_TRANSACTION_COMMIT, not a rollback")
    void commit() {
      final ChaosActivationHandle handle =
          rejectOn(OperationType.JDBC_TRANSACTION_COMMIT, NamePattern.any());

      assertThatCode(() -> bridge.beforeJdbcTransactionRollback()).doesNotThrowAnyException();
      assertThatThrownBy(() -> bridge.beforeJdbcTransactionCommit())
          .isInstanceOf(SQLException.class);
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a rollback is a JDBC_TRANSACTION_ROLLBACK, not a commit")
    void rollback() {
      final ChaosActivationHandle handle =
          rejectOn(OperationType.JDBC_TRANSACTION_ROLLBACK, NamePattern.any());

      assertThatCode(() -> bridge.beforeJdbcTransactionCommit()).doesNotThrowAnyException();
      assertThatThrownBy(() -> bridge.beforeJdbcTransactionRollback())
          .isInstanceOf(SQLException.class);
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }
  }
}
