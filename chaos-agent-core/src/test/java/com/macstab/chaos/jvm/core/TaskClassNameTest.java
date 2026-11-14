package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Task class name of lambda tasks")
class TaskClassNameTest {

  @Test
  @DisplayName("a lambda task is reported under its defining class on the running JDK")
  void lambdaSuffixIsStripped() {
    final Runnable lambda = () -> {};

    assertThat(lambda.getClass().getName()).contains("$$Lambda");
    assertThat(DispatchEngine.taskClassName(lambda)).isEqualTo(TaskClassNameTest.class.getName());
  }

  @Test
  @DisplayName("an EXACT task-class pattern on the defining class matches a lambda task")
  void exactPatternMatchesLambda() {
    final ChaosRuntime runtime = new ChaosRuntime();
    try {
      runtime.activate(
          ChaosScenario.builder("lambda-task")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  new ChaosSelector.ExecutorSelector(
                      Set.of(OperationType.EXECUTOR_SUBMIT),
                      NamePattern.any(),
                      NamePattern.exact(TaskClassNameTest.class.getName()),
                      null))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build());

      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

      assertThat(runtime.diagnostics().scenario("lambda-task").orElseThrow().matchedCount())
          .isEqualTo(1L);
    } finally {
      runtime.close();
    }
  }
}
