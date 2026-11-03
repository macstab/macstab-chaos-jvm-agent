package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Method selector weaving demand")
class MethodSelectorDemandTest {

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final List<Set<ChaosSelector.MethodSelector>> requests = new CopyOnWriteArrayList<>();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Nested
  @DisplayName("weaving requests")
  class Requests {

    @Test
    @DisplayName("activation asks for every registered method selector, removal for the rest")
    void activationAndRemoval() {
      runtime.setHookDemand(recording(null));
      final ChaosSelector.MethodSelector first = selector("a.First");
      final ChaosSelector.MethodSelector second = selector("b.Second");

      final ChaosActivationHandle firstHandle = runtime.activate(scenario("first", first));
      runtime.activate(scenario("second", second));
      firstHandle.close();

      assertThat(requests.get(0)).containsExactly(first);
      assertThat(requests).contains(Set.of(first, second));
      assertThat(requests.get(requests.size() - 1)).containsExactly(second);
    }

    @Test
    @Tag("law-L21")
    @DisplayName("a weaving failure fails the activation as INSTRUMENTATION_FAILURE")
    void weavingFailure() {
      runtime.setHookDemand(recording(new IllegalStateException("cannot weave")));

      assertThatThrownBy(() -> runtime.activate(scenario("broken", selector("c.Third"))))
          .isInstanceOf(IllegalStateException.class);

      assertThat(runtime.diagnostics().scenario("broken")).isEmpty();
      assertThat(runtime.diagnostics().snapshot().failures())
          .extracting(ChaosDiagnostics.ActivationFailure::category)
          .containsExactly(ChaosDiagnostics.FailureCategory.INSTRUMENTATION_FAILURE);
    }
  }

  @Nested
  @DisplayName("woven method calls")
  class WovenCalls {

    @Test
    @DisplayName("the signature pattern picks one overload by descriptor")
    void signaturePattern() throws Throwable {
      runtime.activate(
          ChaosScenario.builder("one-overload")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  new ChaosSelector.MethodSelector(
                      Set.of(OperationType.METHOD_ENTER),
                      NamePattern.exact(String.class.getName()),
                      NamePattern.exact("valueOf"),
                      NamePattern.exact("(I)Ljava/lang/String;")))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build());

      runtime.beforeMethodEnter(String.class, "valueOf", "(I)Ljava/lang/String;");
      runtime.beforeMethodEnter(String.class, "valueOf", "(J)Ljava/lang/String;");
      runtime.beforeMethodEnter(String.class.getName(), "valueOf");

      assertThat(runtime.diagnostics().scenario("one-overload").orElseThrow().matchedCount())
          .as("only the int overload; the manual hook carries no descriptor")
          .isEqualTo(1L);
    }

    @Test
    @DisplayName("the return type for corruption is resolved from the descriptor")
    void returnTypeFromDescriptor() throws Throwable {
      runtime.activate(
          ChaosScenario.builder("zero")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  new ChaosSelector.MethodSelector(
                      Set.of(OperationType.METHOD_EXIT),
                      NamePattern.exact(Integer.class.getName()),
                      NamePattern.any(),
                      null))
              .effect(ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.ZERO))
              .activationPolicy(ActivationPolicy.always())
              .build());

      assertThat(runtime.afterMethodExit(Integer.class, "intValue", "()I", 42)).isEqualTo(0);
      assertThat(runtime.afterMethodExit(Integer.class, "longValue", "()J", 42L)).isEqualTo(0L);
    }
  }

  @Test
  @DisplayName("MethodDescriptors resolves primitives, arrays and classes")
  void descriptorResolution() {
    assertThat(MethodDescriptors.returnType(String.class, "()V")).isEqualTo(void.class);
    assertThat(MethodDescriptors.returnType(String.class, "(I)Z")).isEqualTo(boolean.class);
    assertThat(MethodDescriptors.returnType(String.class, "()[B")).isEqualTo(byte[].class);
    assertThat(MethodDescriptors.returnType(String.class, "()Ljava/util/List;"))
        .isEqualTo(List.class);
    assertThat(MethodDescriptors.returnType(String.class, "()Lno/such/Type;"))
        .isEqualTo(Object.class);
  }

  private HookDemand recording(final RuntimeException failure) {
    return new HookDemand() {
      @Override
      public void require(final Set<OperationType> operations) {}

      @Override
      public void methods(final Set<ChaosSelector.MethodSelector> selectors) {
        if (failure != null) {
          throw failure;
        }
        requests.add(selectors);
      }
    };
  }

  private static ChaosSelector.MethodSelector selector(final String className) {
    return new ChaosSelector.MethodSelector(
        Set.of(OperationType.METHOD_ENTER), NamePattern.exact(className), NamePattern.any(), null);
  }

  private static ChaosScenario scenario(final String id, final ChaosSelector selector) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(selector)
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}
