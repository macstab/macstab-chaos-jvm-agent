package io.macstab.chaos.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.macstab.chaos.api.ActivationPolicy;
import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosScenario;
import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChaosPlatformLocalModeTest {
  @Test
  void localInstallAppliesSessionScopedExecutorDelay() throws Exception {
    var controlPlane = ChaosPlatform.installLocally();
    try (var session = controlPlane.openSession("local-mode")) {
      session.activate(
          ChaosScenario.builder("local-delay")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(60)))
              .activationPolicy(ActivationPolicy.always())
              .build());

      ThreadPoolExecutor executor =
          new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
      try {
        long start = System.nanoTime();
        executor.execute(() -> {});
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsed >= 45, "expected execute() to be delayed by session-scoped chaos");
      } finally {
        executor.shutdownNow();
      }
    }
  }
}
