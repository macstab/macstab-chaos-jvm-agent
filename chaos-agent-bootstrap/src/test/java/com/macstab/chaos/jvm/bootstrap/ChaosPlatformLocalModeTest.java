package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosPlatform local mode")
class ChaosPlatformLocalModeTest {

  private static final long DELAY_MS = 60L;
  private static final long DELAY_MIN_MS = (long) (DELAY_MS * 0.8);

  @Test
  @DisplayName("session-scoped executor delay is applied when installed locally")
  void localInstallAppliesSessionScopedExecutorDelay() throws Exception {
    final ChaosControlPlane controlPlane = ChaosPlatform.installLocally();
    try (final var session = controlPlane.openSession("local-mode")) {
      session.activate(
          ChaosScenario.builder("local-delay")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(60)))
              .activationPolicy(ActivationPolicy.always())
              .build());

      final ThreadPoolExecutor executor =
          new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
      try {
        final long start = System.nanoTime();
        executor.execute(() -> {});
        final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsed)
            .as("execute() must be delayed by session-scoped chaos")
            .isGreaterThanOrEqualTo(DELAY_MIN_MS);
      } finally {
        executor.shutdownNow();
      }
    }
  }
}
