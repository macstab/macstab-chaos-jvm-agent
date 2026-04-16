package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.NamePattern;
import com.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link ChaosRuntime#beforeThreadLocalGet} and {@link
 * ChaosRuntime#beforeThreadLocalSet}.
 */
@DisplayName("ThreadLocal chaos - runtime integration")
class ThreadLocalChaosIntegrationTest {

  /**
   * Named subclass so {@code
   * NamePattern.prefix("com.macstab.chaos.core.ThreadLocalChaosIntegrationTest")} matches this
   * class but not plain {@code java.lang.ThreadLocal}.
   */
  static final class TestThreadLocal extends ThreadLocal<String> {}

  private static final NamePattern TEST_TL_PATTERN =
      NamePattern.prefix("com.macstab.chaos.core.ThreadLocalChaosIntegrationTest");

  // ---------------------------------------------------------------------------
  // GET suppression
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("GET suppression")
  class GetSuppression {

    @Test
    @DisplayName(
        "beforeThreadLocalGet returns true when SuppressEffect active on matching ThreadLocal")
    void suppressReturnsTrueForTestThreadLocal() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("tl-get-suppress")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.threadLocal(
                      Set.of(OperationType.THREAD_LOCAL_GET), TEST_TL_PATTERN))
              .effect(ChaosEffect.suppress())
              .activationPolicy(ActivationPolicy.always())
              .build());
      assertThat(runtime.beforeThreadLocalGet(new TestThreadLocal())).isTrue();
    }

    @Test
    @DisplayName("narrow NamePattern does not suppress unrelated ThreadLocals")
    void narrowPatternDoesNotSuppressOtherThreadLocals() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("tl-get-suppress-narrow")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.threadLocal(
                      Set.of(OperationType.THREAD_LOCAL_GET), TEST_TL_PATTERN))
              .effect(ChaosEffect.suppress())
              .activationPolicy(ActivationPolicy.always())
              .build());
      // java.lang.ThreadLocal does not match the test-class prefix
      assertThat(runtime.beforeThreadLocalGet(new ThreadLocal<String>())).isFalse();
    }
  }

  // ---------------------------------------------------------------------------
  // SET suppression
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("SET suppression")
  class SetSuppression {

    @Test
    @DisplayName(
        "beforeThreadLocalSet returns true when SuppressEffect active on matching ThreadLocal")
    void suppressReturnsTrueForTestThreadLocal() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("tl-set-suppress")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.threadLocal(
                      Set.of(OperationType.THREAD_LOCAL_SET), TEST_TL_PATTERN))
              .effect(ChaosEffect.suppress())
              .activationPolicy(ActivationPolicy.always())
              .build());
      assertThat(runtime.beforeThreadLocalSet(new TestThreadLocal(), "hello")).isTrue();
    }

    @Test
    @DisplayName("narrow NamePattern does not suppress unrelated ThreadLocals")
    void narrowPatternDoesNotSuppressOtherThreadLocals() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("tl-set-suppress-narrow")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.threadLocal(
                      Set.of(OperationType.THREAD_LOCAL_SET), TEST_TL_PATTERN))
              .effect(ChaosEffect.suppress())
              .activationPolicy(ActivationPolicy.always())
              .build());
      // java.lang.ThreadLocal does not match the test-class prefix
      assertThat(runtime.beforeThreadLocalSet(new ThreadLocal<String>(), "hello")).isFalse();
    }
  }

  // ---------------------------------------------------------------------------
  // GET delay
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("GET delay")
  class GetDelay {

    @Test
    @DisplayName("DelayEffect slows beforeThreadLocalGet")
    void delaySlowsGet() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("tl-get-delay")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.threadLocal(
                      Set.of(OperationType.THREAD_LOCAL_GET), TEST_TL_PATTERN))
              .effect(ChaosEffect.delay(Duration.ofMillis(50)))
              .activationPolicy(ActivationPolicy.always())
              .build());
      final long start = System.nanoTime();
      final boolean result = runtime.beforeThreadLocalGet(new TestThreadLocal());
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(result).isFalse();
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }
  }

  // ---------------------------------------------------------------------------
  // Dispatcher depth not affected
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Dispatcher depth not affected by narrow pattern")
  class DispatcherDepthNotAffected {

    @Test
    @DisplayName("plain java.lang.ThreadLocal not suppressed by test-class prefix pattern")
    void plainThreadLocalNotSuppressedByNarrowPattern() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("tl-depth-safe")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.threadLocal(
                      Set.of(OperationType.THREAD_LOCAL_GET), TEST_TL_PATTERN))
              .effect(ChaosEffect.suppress())
              .activationPolicy(ActivationPolicy.always())
              .build());
      // java.lang.ThreadLocal (as used by BootstrapDispatcher's depth counter) does not match
      final ThreadLocal<Integer> jdkTl = new ThreadLocal<>();
      assertThat(runtime.beforeThreadLocalGet(jdkTl)).isFalse();
    }
  }

  // ---------------------------------------------------------------------------
  // After stop
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("After stop")
  class AfterStop {

    @Test
    @DisplayName("beforeThreadLocalGet returns false after handle.stop()")
    void returnsFalseAfterHandleStop() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("tl-after-stop")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(
                      ChaosSelector.threadLocal(
                          Set.of(OperationType.THREAD_LOCAL_GET), TEST_TL_PATTERN))
                  .effect(ChaosEffect.suppress())
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final TestThreadLocal tl = new TestThreadLocal();
      assertThat(runtime.beforeThreadLocalGet(tl)).isTrue();
      handle.stop();
      assertThat(runtime.beforeThreadLocalGet(tl)).isFalse();
    }
  }
}
