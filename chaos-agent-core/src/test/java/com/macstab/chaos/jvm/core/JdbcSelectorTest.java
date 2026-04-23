package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JdbcSelector")
class JdbcSelectorTest {

  private final FeatureSet featureSet = new FeatureSet();

  @Nested
  @DisplayName("Factory")
  class Factory {

    @Test
    @DisplayName("jdbc() accepts all 5 JDBC operation types")
    void jdbcFactoryAcceptsAllJdbcOps() {
      ChaosSelector.JdbcSelector selector = ChaosSelector.jdbc();
      assertThat(selector.operations())
          .containsExactlyInAnyOrder(
              OperationType.JDBC_CONNECTION_ACQUIRE,
              OperationType.JDBC_STATEMENT_EXECUTE,
              OperationType.JDBC_PREPARED_STATEMENT,
              OperationType.JDBC_TRANSACTION_COMMIT,
              OperationType.JDBC_TRANSACTION_ROLLBACK);
    }

    @Test
    @DisplayName("jdbc(JDBC_CONNECTION_ACQUIRE) rejects HTTP_CLIENT_SEND")
    void jdbcWithConnectionAcquireRejectsHttpClientSend() {
      ChaosSelector selector = ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE);
      InvocationContext context =
          new InvocationContext(
              OperationType.HTTP_CLIENT_SEND, "jdbc.pool", null, "pool-a", false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("jdbc(JDBC_CONNECTION_ACQUIRE) matches JDBC_CONNECTION_ACQUIRE context")
    void jdbcMatchesConnectionAcquire() {
      ChaosSelector selector = ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE);
      InvocationContext context =
          new InvocationContext(
              OperationType.JDBC_CONNECTION_ACQUIRE,
              "jdbc.pool",
              null,
              "pool-a",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("jdbc() vararg with no operations throws IllegalArgumentException")
    void emptyOperationsThrows() {
      assertThatThrownBy(() -> ChaosSelector.jdbc(new OperationType[0]))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null operations set in record constructor throws IllegalArgumentException")
    void nullOperationsSetThrows() {
      assertThatThrownBy(() -> new ChaosSelector.JdbcSelector(Set.of(), NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Target pattern matching")
  class TargetPatternMatching {

    @Test
    @DisplayName("glob pool pattern matches pool name")
    void globTargetPatternMatches() {
      ChaosSelector selector =
          new ChaosSelector.JdbcSelector(
              Set.of(OperationType.JDBC_CONNECTION_ACQUIRE), NamePattern.glob("hikari-*"));
      InvocationContext context =
          new InvocationContext(
              OperationType.JDBC_CONNECTION_ACQUIRE,
              "jdbc.pool",
              null,
              "hikari-primary",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("glob pool pattern rejects non-matching pool name")
    void globTargetPatternRejects() {
      ChaosSelector selector =
          new ChaosSelector.JdbcSelector(
              Set.of(OperationType.JDBC_CONNECTION_ACQUIRE), NamePattern.glob("hikari-*"));
      InvocationContext context =
          new InvocationContext(
              OperationType.JDBC_CONNECTION_ACQUIRE,
              "jdbc.pool",
              null,
              "c3p0-primary",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("null targetPattern factory defaults to any()")
    void nullTargetPatternMatchesAny() {
      ChaosSelector.JdbcSelector selector =
          new ChaosSelector.JdbcSelector(Set.of(OperationType.JDBC_CONNECTION_ACQUIRE), null);
      assertThat(selector.targetPattern().mode()).isEqualTo(NamePattern.MatchMode.ANY);
    }
  }

  @Nested
  @DisplayName("CompatibilityValidator")
  class Validator {

    @Test
    @DisplayName("all 5 JDBC ops on JdbcSelector are valid")
    void allJdbcOpsAreValid() {
      ChaosScenario scenario =
          ChaosScenario.builder("jdbc-ok")
              .selector(
                  new ChaosSelector.JdbcSelector(
                      Set.of(
                          OperationType.JDBC_CONNECTION_ACQUIRE,
                          OperationType.JDBC_STATEMENT_EXECUTE,
                          OperationType.JDBC_PREPARED_STATEMENT,
                          OperationType.JDBC_TRANSACTION_COMMIT,
                          OperationType.JDBC_TRANSACTION_ROLLBACK),
                      NamePattern.any()))
              .effect(ChaosEffect.delay(Duration.ofMillis(10)))
              .activationPolicy(ActivationPolicy.always())
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("non-JDBC operation in JdbcSelector is rejected by the selector constructor")
    void nonJdbcOperationThrows() {
      // Record-level enforcement: the invalid op cannot reach CompatibilityValidator.
      assertThatThrownBy(
              () ->
                  new ChaosSelector.JdbcSelector(
                      Set.of(OperationType.EXECUTOR_SUBMIT), NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("JdbcSelector")
          .hasMessageContaining("EXECUTOR_SUBMIT");
    }

    @Test
    @DisplayName("JDBC_CONNECTION_ACQUIRE in NetworkSelector is rejected at construction")
    void jdbcOpWithWrongSelectorThrows() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.NetworkSelector(
                      Set.of(OperationType.JDBC_CONNECTION_ACQUIRE), NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("NetworkSelector")
          .hasMessageContaining("JDBC_CONNECTION_ACQUIRE");
    }

    @Test
    @DisplayName("JDBC_STATEMENT_EXECUTE in ExecutorSelector is rejected at construction")
    void jdbcStatementExecuteInExecutorSelectorThrows() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.ExecutorSelector(
                      Set.of(OperationType.JDBC_STATEMENT_EXECUTE),
                      NamePattern.any(),
                      NamePattern.any(),
                      null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ExecutorSelector")
          .hasMessageContaining("JDBC_STATEMENT_EXECUTE");
    }
  }
}
