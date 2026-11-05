package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Stressor scenarios and their activation policy")
class StressorActiveForTest {

  private ChaosRuntime runtime;

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
  }

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("a stressor with activeFor ends its load and turns INACTIVE when the window elapses")
  void stressorExpires() {
    // Arrange
    final String threadName = "stressor-active-for-" + System.nanoTime();
    final ChaosActivationHandle handle =
        runtime.activate(
            keepAlive("expiring", threadName, policyActiveFor(Duration.ofMillis(300))));
    assertThat(threadAlive(threadName)).as("keep-alive thread runs while active").isTrue();

    // Act / Assert
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
              assertThat(threadAlive(threadName)).as("stressor closed on expiry").isFalse();
            });
    assertThat(runtime.diagnostics().scenario("expiring").orElseThrow().reason())
        .isEqualTo("expired");
  }

  @Test
  @DisplayName("stopping a stressor before its window ends stops it at once")
  void stopBeforeExpiry() {
    final String threadName = "stressor-stop-" + System.nanoTime();
    final ChaosActivationHandle handle =
        runtime.activate(keepAlive("stopped", threadName, policyActiveFor(Duration.ofHours(1))));

    handle.stop();

    await().atMost(Duration.ofSeconds(5)).until(() -> !threadAlive(threadName));
    assertThat(threadAlive("macstab-chaos-expiry-stopped")).as("expiry thread ended").isFalse();
  }

  @Test
  @DisplayName("a stop racing the start (from a STARTED listener) leaves no expiry thread behind")
  void stopDuringStartLeavesNoExpiryThread() {
    final ActivationPolicy manual =
        new ActivationPolicy(
            ActivationPolicy.StartMode.MANUAL,
            1.0d,
            0L,
            null,
            Duration.ofHours(1),
            null,
            0L,
            false);
    final ChaosActivationHandle handle =
        runtime.activate(keepAlive("raced", "stressor-raced-" + System.nanoTime(), manual));
    // The listener runs on the starting thread once start() has left the controller's monitor,
    // the window in which a stop() used to find no expiry thread to cancel.
    runtime.addEventListener(
        event -> {
          if (event.type() == ChaosEvent.Type.STARTED && event.scenarioId().equals("raced")) {
            handle.stop();
          }
        });

    handle.start();

    await().atMost(Duration.ofSeconds(5)).until(() -> !threadAlive("macstab-chaos-expiry-raced"));
  }

  @Test
  @DisplayName("per-call policy fields are rejected because a stressor is never evaluated per call")
  void perCallPolicyIsRejected() {
    final ActivationPolicy capped =
        new ActivationPolicy(
            ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0L, 5L, null, null, 0L, false);

    assertThatThrownBy(() -> runtime.activate(keepAlive("capped", "unused", capped)))
        .isInstanceOf(ChaosValidationException.class)
        .hasMessageContaining("maxApplications");
  }

  @Test
  @DisplayName("closing a stressor's handle ends it, as stopping does")
  void closeEndsTheStressor() {
    final String threadName = "stressor-close-" + System.nanoTime();
    final ChaosActivationHandle handle =
        runtime.activate(keepAlive("closed", threadName, policyActiveFor(Duration.ofHours(1))));
    assertThat(threadAlive(threadName)).isTrue();

    handle.close();

    await().atMost(Duration.ofSeconds(5)).until(() -> !threadAlive(threadName));
  }

  @Test
  @DisplayName("a rate limit, a warm-up and a probability below 1.0 are rejected for a stressor")
  void otherPerCallFieldsAreRejected() {
    for (final ActivationPolicy policy :
        java.util.List.of(
            ActivationPolicy.builder().rateLimit(5, Duration.ofSeconds(1)).build(),
            ActivationPolicy.builder().activateAfterMatches(2).build(),
            ActivationPolicy.builder().probability(0.5).build())) {
      assertThatThrownBy(() -> runtime.activate(keepAlive("per-call", "unused", policy)))
          .as("%s", policy)
          .isInstanceOf(ChaosValidationException.class);
    }
  }

  private static ActivationPolicy policyActiveFor(final Duration activeFor) {
    return new ActivationPolicy(
        ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0L, null, activeFor, null, 0L, false);
  }

  private static ChaosScenario keepAlive(
      final String id, final String threadName, final ActivationPolicy policy) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.stress(ChaosSelector.StressTarget.KEEPALIVE))
        .effect(ChaosEffect.keepAlive(threadName, true, Duration.ofMillis(50)))
        .activationPolicy(policy)
        .build();
  }

  private static boolean threadAlive(final String name) {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(thread -> name.equals(thread.getName()) && thread.isAlive());
  }
}
