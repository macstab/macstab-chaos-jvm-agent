package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A stressor thread that something else interrupts (a {@code ThreadGroup.interrupt()}, a
 * container's thread cleanup, a test's leak detector) stops producing load. The scenario then ends
 * {@code INACTIVE} with the reason {@code "stressor interrupted: <type>"} instead of reporting a
 * load that no longer exists (L17).
 */
@DisplayName("A stressor thread interrupted by someone else")
class StressorInterruptTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void closeRuntime() {
    runtime.close();
  }

  @Test
  @DisplayName("monitor contention ends INACTIVE")
  void monitorContention() {
    assertInterruptEnds(
        ChaosSelector.StressTarget.MONITOR_CONTENTION,
        ChaosEffect.monitorContention(Duration.ofMillis(5), 4),
        ActivationPolicy.always(),
        "chaos-contention-0",
        "MonitorContention");
  }

  @Test
  @DisplayName("a deadlock ends INACTIVE")
  void deadlock() {
    assertInterruptEnds(
        ChaosSelector.StressTarget.DEADLOCK,
        new ChaosEffect.DeadlockEffect(2, Duration.ZERO),
        ActivationPolicy.withDestructiveEffects(),
        "chaos-deadlock-0",
        "Deadlock");
  }

  @Test
  @DisplayName("a thread leak ends INACTIVE")
  void threadLeak() {
    assertInterruptEnds(
        ChaosSelector.StressTarget.THREAD_LEAK,
        ChaosEffect.threadLeak(2, "interrupted-leak-", true),
        ActivationPolicy.withDestructiveEffects(),
        "interrupted-leak-0",
        "ThreadLeak");
  }

  @Test
  @DisplayName("a keep-alive thread ends INACTIVE")
  void keepAlive() {
    assertInterruptEnds(
        ChaosSelector.StressTarget.KEEPALIVE,
        ChaosEffect.keepAlive("interrupted-keepalive", true, Duration.ofSeconds(10)),
        ActivationPolicy.always(),
        "interrupted-keepalive",
        "KeepAlive");
  }

  @Test
  @DisplayName("GC pressure ends INACTIVE")
  void gcPressure() {
    assertInterruptEnds(
        ChaosSelector.StressTarget.GC_PRESSURE,
        ChaosEffect.gcPressure(1_000_000L, Duration.ofMinutes(5)),
        ActivationPolicy.always(),
        "chaos-gc-pressure",
        "GcPressure");
  }

  @Test
  @DisplayName("a safepoint storm ends INACTIVE")
  void safepointStorm() {
    assertInterruptEnds(
        ChaosSelector.StressTarget.SAFEPOINT_STORM,
        ChaosEffect.safepointStorm(Duration.ofSeconds(10)),
        ActivationPolicy.always(),
        "macstab-chaos-safepoint-storm",
        "SafepointStorm");
  }

  @Test
  @DisplayName("a reference-queue flood ends INACTIVE")
  void referenceQueueFlood() {
    assertInterruptEnds(
        ChaosSelector.StressTarget.REFERENCE_QUEUE_FLOOD,
        ChaosEffect.referenceQueueFlood(100, Duration.ofSeconds(10)),
        ActivationPolicy.always(),
        "macstab-chaos-refqueue-flood",
        "ReferenceQueueFlood");
  }

  @Test
  @DisplayName("a carrier-pinning thread reports its end")
  void carrierPinning() throws Exception {
    final List<String> ends = new CopyOnWriteArrayList<>();
    final VirtualThreadCarrierPinningStressor stressor =
        new VirtualThreadCarrierPinningStressor(
            new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofMillis(5)), ends::add);
    try {
      final java.lang.reflect.Field threads =
          VirtualThreadCarrierPinningStressor.class.getDeclaredField("pinningThreads");
      threads.setAccessible(true);
      @SuppressWarnings("unchecked")
      final List<Thread> pinning = (List<Thread>) threads.get(stressor);
      pinning.get(0).interrupt();

      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  assertThat(ends)
                      .containsExactly("stressor interrupted: VirtualThreadCarrierPinning"));
    } finally {
      stressor.close();
    }
    assertThat(ends).as("its own stop is no end").hasSize(1);
  }

  /**
   * Activates a stressor, interrupts its thread {@code threadName} from this thread, and asserts
   * the scenario ends INACTIVE with the reason naming the stressor.
   */
  private void assertInterruptEnds(
      final ChaosSelector.StressTarget target,
      final ChaosEffect effect,
      final ActivationPolicy policy,
      final String threadName,
      final String type) {
    final ChaosActivationHandle handle =
        runtime.activate(
            ChaosScenario.builder("interrupted")
                .selector(ChaosSelector.stress(target))
                .effect(effect)
                .activationPolicy(policy)
                .build());
    try {
      final Thread thread =
          await()
              .atMost(Duration.ofSeconds(5))
              .until(() -> thread(threadName), found -> found != null && found.isAlive());

      thread.interrupt();

      await()
          .atMost(Duration.ofSeconds(5))
          .until(handle::state, state -> state == ChaosDiagnostics.ScenarioState.INACTIVE);
      assertThat(runtime.diagnostics().scenario("interrupted").orElseThrow().reason())
          .isEqualTo("stressor interrupted: " + type);
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> assertThat(thread(threadName)).as("its threads are stopped").isNull());
    } finally {
      handle.close();
    }
  }

  private static Thread thread(final String name) {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(candidate -> candidate.getName().equals(name) && candidate.isAlive())
        .findFirst()
        .orElse(null);
  }
}
