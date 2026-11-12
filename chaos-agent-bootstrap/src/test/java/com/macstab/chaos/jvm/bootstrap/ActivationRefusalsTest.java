package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The validator's refusals reached through the public entry points, not only by direct validator
 * calls: {@code activate(scenario)} and a JSON plan read by {@link ChaosPlanMapper}. Each is
 * refused as an invalid configuration, recorded with one FAILED event, and leaves nothing
 * registered; a plan with one refused scenario starts none.
 */
@DisplayName("Validator refusals through activation and plans")
class ActivationRefusalsTest {

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final List<ChaosEvent> failed = new CopyOnWriteArrayList<>();

  @AfterEach
  void close() {
    runtime.close();
  }

  private static ChaosScenario scenario(
      final String id, final ChaosSelector selector, final ChaosEffect effect) {
    return ChaosScenario.builder(id)
        .selector(selector)
        .effect(effect)
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  private static ChaosSelector executor() {
    return ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT));
  }

  private static ChaosSelector stress(final ChaosSelector.StressTarget target) {
    return ChaosSelector.stress(target);
  }

  static Stream<Arguments> refusals() {
    return Stream.of(
        Arguments.of(
            "string intern count above the cap",
            scenario(
                "intern-count",
                stress(ChaosSelector.StressTarget.STRING_INTERN_PRESSURE),
                ChaosEffect.stringInternPressure(1_000_001, 8)),
            "stringInternPressure internCount"),
        Arguments.of(
            "string intern count above what its length makes distinct",
            scenario(
                "intern-distinct",
                stress(ChaosSelector.StressTarget.STRING_INTERN_PRESSURE),
                ChaosEffect.stringInternPressure(70_000, 2)),
            "distinct strings of 2 bytes"),
        Arguments.of(
            "a stressor interval below 1 ms",
            scenario(
                "heartbeat",
                stress(ChaosSelector.StressTarget.KEEPALIVE),
                ChaosEffect.keepAlive("refused-heartbeat", true, Duration.ofNanos(500_000))),
            "is below 1 ms"),
        Arguments.of(
            "a stressor effect without a StressSelector",
            scenario("heap-on-executor", executor(), ChaosEffect.heapPressure(1_024, 1_024)),
            "is a stressor effect and requires StressSelector"),
        Arguments.of(
            "an exceptional completion without an AsyncSelector",
            scenario(
                "completion",
                executor(),
                ChaosEffect.exceptionalCompletion(ChaosEffect.FailureKind.ILLEGAL_STATE, "failed")),
            "ExceptionalCompletionEffect is only valid with AsyncSelector"),
        Arguments.of(
            "an exception injection without a MethodSelector",
            scenario(
                "inject-executor",
                executor(),
                new ChaosEffect.ExceptionInjectionEffect(
                    "java.lang.IllegalStateException", "x", true)),
            "ExceptionInjectionEffect requires MethodSelector"),
        Arguments.of(
            "an exception injection on METHOD_EXIT",
            scenario(
                "inject-exit",
                method(OperationType.METHOD_EXIT),
                new ChaosEffect.ExceptionInjectionEffect(
                    "java.lang.IllegalStateException", "x", true)),
            "found METHOD_EXIT"),
        Arguments.of(
            "a return-value corruption without a MethodSelector",
            scenario(
                "corrupt-executor",
                executor(),
                ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.NULL)),
            "ReturnValueCorruptionEffect requires MethodSelector"),
        Arguments.of(
            "a return-value corruption on METHOD_ENTER",
            scenario(
                "corrupt-enter",
                method(OperationType.METHOD_ENTER),
                ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.NULL)),
            "found METHOD_ENTER"),
        Arguments.of(
            "a clock skew without a JvmRuntimeSelector",
            scenario(
                "skew-executor",
                executor(),
                ChaosEffect.skewClock(Duration.ofSeconds(1), ChaosEffect.ClockSkewMode.FIXED)),
            "ClockSkewEffect requires JvmRuntimeSelector"),
        Arguments.of(
            "a clock skew on an operation that is no clock read",
            scenario(
                "skew-gc",
                ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_GC_REQUEST)),
                ChaosEffect.skewClock(Duration.ofSeconds(1), ChaosEffect.ClockSkewMode.FIXED)),
            "ClockSkewEffect only applies to clock reads"),
        Arguments.of(
            "a delay on a clock read",
            scenario(
                "delay-clock",
                ChaosSelector.jvmRuntime(Set.of(OperationType.INSTANT_NOW)),
                ChaosEffect.delay(Duration.ofMillis(1))),
            "only support ClockSkewEffect"),
        Arguments.of(
            "a spurious wakeup without a NioSelector",
            scenario("wakeup-executor", executor(), ChaosEffect.spuriousWakeup()),
            "SpuriousWakeupEffect requires NioSelector"),
        Arguments.of(
            "a spurious wakeup beside another NIO operation",
            scenario(
                "wakeup-read",
                ChaosSelector.nio(
                    Set.of(OperationType.NIO_SELECTOR_SELECT, OperationType.NIO_CHANNEL_READ)),
                ChaosEffect.spuriousWakeup()),
            "as its only operation"));
  }

  private static ChaosSelector method(final OperationType operation) {
    return ChaosSelector.method(
        Set.of(operation), NamePattern.exact("com.example.Service"), NamePattern.any());
  }

  private void recordFailures() {
    runtime.addEventListener(
        event -> {
          if (event.type() == ChaosEvent.Type.FAILED) {
            failed.add(event);
          }
        });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refusals")
  @DisplayName("activate(scenario) refuses it as INVALID_CONFIGURATION and registers nothing")
  void refusedByActivate(final String rule, final ChaosScenario scenario, final String fragment) {
    recordFailures();

    assertThatThrownBy(() -> runtime.activate(scenario))
        .isInstanceOf(ChaosValidationException.class)
        .hasMessageContaining(fragment);

    assertThat(runtime.diagnostics().scenario(scenario.id())).isEmpty();
    assertThat(failed)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.scenarioId()).isEqualTo(scenario.id());
              assertThat(event.attributes())
                  .containsEntry(
                      "category", ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION.name());
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refusals")
  @DisplayName("a JSON plan with the scenario beside a valid one starts neither")
  void refusedInAPlan(final String rule, final ChaosScenario scenario, final String fragment) {
    final ChaosPlan plan =
        ChaosPlanMapper.read(
            ChaosPlanMapper.write(
                new ChaosPlan(
                    new ChaosPlan.Metadata("refused", ""),
                    null,
                    List.of(
                        scenario("valid", executor(), ChaosEffect.delay(Duration.ofMillis(1))),
                        scenario))));

    assertThatThrownBy(() -> runtime.activate(plan))
        .isInstanceOf(ChaosValidationException.class)
        .hasMessageContaining(fragment);

    assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
  }

  @Test
  @DisplayName("a stress scenario activated in a session is refused: stressors are JVM-scoped")
  void stressScenarioInASession() {
    recordFailures();
    final ChaosSession session = runtime.openSession("stress");
    try {
      assertThatThrownBy(
              () ->
                  session.activate(
                      ChaosScenario.builder("session-heap")
                          .scope(ChaosScenario.ScenarioScope.SESSION)
                          .selector(stress(ChaosSelector.StressTarget.HEAP))
                          .effect(ChaosEffect.heapPressure(1_024, 1_024))
                          .build()))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessage("stress scenarios must be JVM scoped");

      assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
      assertThat(failed)
          .singleElement()
          .extracting(ChaosEvent::scenarioId)
          .isEqualTo("session-heap");
    } finally {
      session.close();
    }
  }
}
