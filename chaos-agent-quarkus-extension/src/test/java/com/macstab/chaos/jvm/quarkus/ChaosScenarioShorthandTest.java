package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.core.ChaosRuntime;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every pair of a {@code @ChaosScenario} selector and effect shorthand either builds a scenario the
 * runtime accepts, or is refused by {@link ChaosQuarkusExtension#toScenario} with an {@link
 * IllegalArgumentException} that names the reason: no shorthand builds a plan that only fails when
 * the test class activates it.
 */
@SuppressWarnings("removal")
@DisplayName("@ChaosScenario shorthand pairs")
class ChaosScenarioShorthandTest {

  private static final String[] SELECTORS = {"executor", "jvmRuntime", "httpClient", "jdbc"};

  private static final String[] EFFECTS = {"delay:PT0.001S", "suppress", "freeze"};

  /** The pairs {@code toScenario} refuses, with a part of its message. */
  private static final Map<String, String> REFUSED =
      Map.of(
          "executor+freeze", "requires selector=jvmRuntime",
          "jvmRuntime+delay:PT0.001S", "only supports effect=freeze",
          "jvmRuntime+suppress", "only supports effect=freeze",
          "httpClient+suppress", "suppress is not supported for httpClient; use delay",
          "httpClient+freeze", "requires selector=jvmRuntime",
          "jdbc+suppress", "suppress is not supported for jdbc; use delay",
          "jdbc+freeze", "requires selector=jvmRuntime");

  static Stream<Arguments> pairs() {
    return Stream.of(SELECTORS)
        .flatMap(selector -> Stream.of(EFFECTS).map(effect -> Arguments.of(selector, effect)));
  }

  @ParameterizedTest(name = "{0} + {1}")
  @MethodSource("pairs")
  @DisplayName("is accepted by the runtime or refused by toScenario")
  void acceptedOrRefusedUpFront(final String selector, final String effect) {
    final ChaosScenario annotation = annotation(selector, effect);
    final String refusal = REFUSED.get(selector + "+" + effect);
    if (refusal != null) {
      assertThatThrownBy(() -> ChaosQuarkusExtension.toScenario(annotation))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(refusal);
      return;
    }
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      assertThatCode(() -> runtime.activate(ChaosQuarkusExtension.toScenario(annotation)).close())
          .doesNotThrowAnyException();
    }
  }

  @org.junit.jupiter.api.Test
  @DisplayName("freeze covers every wall-clock read the clockJump preset covers, CLOCK_READ too")
  void freezeCoversEveryWallClockRead() {
    final com.macstab.chaos.jvm.api.ChaosSelector expected =
        com.macstab.chaos.jvm.api.ChaosPresets.clockJump(java.time.Duration.ofSeconds(1))
            .build()
            .selector();

    org.assertj.core.api.Assertions.assertThat(
            ChaosQuarkusExtension.toScenario(annotation("jvmRuntime", "freeze")).selector())
        .isEqualTo(expected);
  }

  private static ChaosScenario annotation(final String selector, final String effect) {
    return (ChaosScenario)
        Proxy.newProxyInstance(
            ChaosScenario.class.getClassLoader(),
            new Class<?>[] {ChaosScenario.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "id" -> "shorthand";
                  case "selector" -> selector;
                  case "effect" -> effect;
                  case "scope" -> "JVM";
                  case "annotationType" -> ChaosScenario.class;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }
}
