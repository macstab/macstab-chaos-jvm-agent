package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.reflect.Method;
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

  @Nested
  @DisplayName("beforeJdbcConnectionAcquire passthrough")
  class ConnectionAcquirePassthrough {

    @Test
    @DisplayName("passes through when no bridge is installed (fallback)")
    void passesWithoutBridge() {
      assertThatCode(() -> BootstrapDispatcher.beforeJdbcConnectionAcquire("hikari-primary"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes through when no scenario matches")
    void passesWithoutScenarios() {
      assertThatCode(() -> bridge.beforeJdbcConnectionAcquire("hikari-primary"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accepts null pool name without throwing")
    void nullPoolNameIsSafe() {
      assertThatCode(() -> bridge.beforeJdbcConnectionAcquire(null)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("beforeJdbcStatementExecute passthrough")
  class StatementExecutePassthrough {

    @Test
    @DisplayName("passes through when no bridge is installed (fallback)")
    void passesWithoutBridge() {
      assertThatCode(() -> BootstrapDispatcher.beforeJdbcStatementExecute("SELECT 1"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes through when no scenario matches")
    void passesWithoutScenarios() {
      assertThatCode(() -> bridge.beforeJdbcStatementExecute("SELECT 1"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accepts null SQL without throwing (no NPE)")
    void nullSqlIsSafe() {
      assertThatCode(() -> bridge.beforeJdbcStatementExecute(null)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("beforeJdbcPreparedStatement passthrough")
  class PreparedStatementPassthrough {

    @Test
    @DisplayName("passes through when no bridge is installed (fallback)")
    void passesWithoutBridge() {
      assertThatCode(() -> BootstrapDispatcher.beforeJdbcPreparedStatement("SELECT * FROM t"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes through when no scenario matches")
    void passesWithoutScenarios() {
      assertThatCode(() -> bridge.beforeJdbcPreparedStatement("SELECT * FROM t"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accepts null SQL without throwing (no NPE)")
    void nullSqlIsSafe() {
      assertThatCode(() -> bridge.beforeJdbcPreparedStatement(null)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("beforeJdbcTransactionCommit passthrough")
  class TransactionCommitPassthrough {

    @Test
    @DisplayName("passes through when no bridge is installed (fallback)")
    void passesWithoutBridge() {
      assertThatCode(() -> BootstrapDispatcher.beforeJdbcTransactionCommit())
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes through when no scenario matches")
    void passesWithoutScenarios() {
      assertThatCode(() -> bridge.beforeJdbcTransactionCommit()).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("beforeJdbcTransactionRollback passthrough")
  class TransactionRollbackPassthrough {

    @Test
    @DisplayName("passes through when no bridge is installed (fallback)")
    void passesWithoutBridge() {
      assertThatCode(() -> BootstrapDispatcher.beforeJdbcTransactionRollback())
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes through when no scenario matches")
    void passesWithoutScenarios() {
      assertThatCode(() -> bridge.beforeJdbcTransactionRollback()).doesNotThrowAnyException();
    }
  }
}
