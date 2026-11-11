package com.macstab.chaos.jvm.micrometer;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs in a JVM started with {@code -javaagent:}: binds {@link ChaosMeterBinder} to the installed
 * agent as the documentation shows ({@code new ChaosMeterBinder(ChaosPlatform.current())}), makes
 * three {@code ThreadPoolExecutor} submissions the agent delays, and prints what the registry
 * holds.
 */
public final class AgentMeterProbeMain {

  static final String SCENARIO_ID = "agent-meter-delay";
  static final int CALLS = 3;

  private AgentMeterProbeMain() {}

  public static void main(final String[] args) throws Exception {
    final ChaosControlPlane controlPlane = ChaosPlatform.current();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new ChaosMeterBinder(controlPlane).bindTo(registry);
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try (ChaosActivationHandle handle =
        controlPlane.activate(
            ChaosScenario.builder(SCENARIO_ID)
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ofMillis(20)))
                .activationPolicy(ActivationPolicy.always())
                .build())) {
      final long start = System.nanoTime();
      for (int i = 0; i < CALLS; i++) {
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      }
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      System.out.println("handleApplied=" + handle.appliedCount());
      // The binder counts on an agent thread, the counter before the timer: wait until both have
      // seen every application.
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while ((applications(registry) < CALLS || delayTimerCount(registry) < CALLS)
          && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      System.out.println("applications=" + (long) applications(registry));
      System.out.println("delayTimerCount=" + delayTimerCount(registry));
      System.out.println("hooksWoven=" + (long) registry.get("chaos.hooks.woven").gauge().value());
    } finally {
      executor.shutdownNow();
    }
  }

  private static double applications(final SimpleMeterRegistry registry) {
    final Counter counter =
        registry
            .find("chaos.applications")
            .tag("scenario", SCENARIO_ID)
            .tag("operation", "executor_submit")
            .tag("effect", "delay")
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private static long delayTimerCount(final SimpleMeterRegistry registry) {
    final Timer timer = registry.find("chaos.injected.delay").tag("scenario", SCENARIO_ID).timer();
    return timer == null ? 0 : timer.count();
  }
}
