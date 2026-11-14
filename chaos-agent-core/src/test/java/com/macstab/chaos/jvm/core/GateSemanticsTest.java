package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.lang.Thread.State;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Gate semantics")
@SuppressWarnings("removal") // ChaosMetricsSink, deprecated, until it is removed
class GateSemanticsTest {

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
  @DisplayName("release() lets current waiters through and the gate holds the next call again")
  void releaseReArmsTheGate() throws Exception {
    // Arrange
    final ChaosActivationHandle handle = runtime.activate(gate("gate"));
    final CountDownLatch firstPassed = new CountDownLatch(1);
    Thread.ofPlatform().start(() -> submitThen(firstPassed::countDown));
    assertThat(firstPassed.await(200, TimeUnit.MILLISECONDS)).as("held at the gate").isFalse();

    // Act
    handle.release();

    // Assert
    assertThat(firstPassed.await(5, TimeUnit.SECONDS)).as("released").isTrue();
    final CountDownLatch secondPassed = new CountDownLatch(1);
    Thread.ofPlatform().start(() -> submitThen(secondPassed::countDown));
    assertThat(secondPassed.await(300, TimeUnit.MILLISECONDS))
        .as("the gate closed again after the release")
        .isFalse();
    handle.release();
    assertThat(secondPassed.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("release() on a scenario without a gate is a no-op without a RELEASED event")
  void releaseWithoutGateIsNoOp() {
    final List<ChaosEvent> events = new CopyOnWriteArrayList<>();
    runtime.addEventListener(events::add);
    final ChaosActivationHandle handle =
        runtime.activate(
            ChaosScenario.builder("delay")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .activationPolicy(ActivationPolicy.always())
                .build());

    handle.release();

    assertThat(events).extracting(ChaosEvent::type).doesNotContain(ChaosEvent.Type.RELEASED);
  }

  @Test
  @DisplayName("an interrupted gate wait returns with the interrupt flag set and no exception")
  void interruptEndsTheWaitAndKeepsTheFlag() throws Exception {
    // Arrange
    runtime.activate(gate("gate"));
    final AtomicReference<Throwable> thrown = new AtomicReference<>();
    final AtomicBoolean interruptedAfter = new AtomicBoolean();
    final Thread waiter =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
                  } catch (final Throwable throwable) {
                    thrown.set(throwable);
                  }
                  interruptedAfter.set(Thread.currentThread().isInterrupted());
                });
    await().atMost(Duration.ofSeconds(5)).until(() -> waiter.getState() == Thread.State.WAITING);

    // Act
    waiter.interrupt();
    waiter.join(5_000L);

    // Assert
    assertThat(waiter.isAlive()).isFalse();
    assertThat(thrown.get()).isNull();
    assertThat(interruptedAfter.get()).as("interrupt flag restored").isTrue();
  }

  @Nested
  @DisplayName("a gate with maxApplications")
  class GateWithMaxApplications {

    @Test
    @DisplayName("holds the last admitted caller until release(), and the next call passes")
    void lastAdmittedCallerIsHeld() throws Exception {
      // Arrange
      final ChaosActivationHandle handle =
          runtime.activate(gate("capped", ActivationPolicy.count(1)));
      final CountDownLatch passed = new CountDownLatch(1);

      // Act
      final Thread worker = Thread.ofPlatform().start(() -> submitThen(passed::countDown));

      // Assert: the budget is spent, yet its one caller stays at the gate
      await().atMost(Duration.ofSeconds(5)).until(() -> worker.getState() == State.WAITING);
      await()
          .during(Duration.ofMillis(300))
          .atMost(Duration.ofSeconds(5))
          .until(() -> worker.getState() == State.WAITING);
      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
      handle.release();
      assertThat(passed.await(5, TimeUnit.SECONDS)).as("released").isTrue();
      final CountDownLatch nextPassed = new CountDownLatch(1);
      Thread.ofPlatform().start(() -> submitThen(nextPassed::countDown));
      assertThat(nextPassed.await(5, TimeUnit.SECONDS)).as("the next call is not held").isTrue();
    }

    @Test
    @DisplayName("an expired gate releases its waiter, and a later release() does not re-close it")
    void expiryReleasesAndReleaseDoesNotReclose() throws Exception {
      // Arrange
      final ScenarioController controller =
          new ScenarioController(
              gate(
                  "expiring", ActivationPolicy.builder().activeFor(Duration.ofMillis(200)).build()),
              "jvm",
              null,
              Clock.systemUTC(),
              new ObservabilityBus(ChaosMetricsSink.NOOP),
              java.util.Optional::empty);
      controller.start();
      final CountDownLatch passed = new CountDownLatch(1);
      Thread.ofPlatform()
          .start(
              () -> {
                try {
                  controller.gate().await(null);
                } catch (final InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
                passed.countDown();
              });

      // Act: the first time check after activeFor marks the scenario expired
      await().atMost(Duration.ofSeconds(5)).until(() -> !controller.withinWindow());
      assertThat(passed.await(5, TimeUnit.SECONDS)).as("expiry released the waiter").isTrue();
      assertThat(controller.state()).isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
      controller.release();

      // Assert: a caller admitted just before expiry reaches an open gate
      final long start = System.nanoTime();
      controller.gate().await(Duration.ofSeconds(5));
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
      controller.stop();
    }
  }

  private void submitThen(final Runnable afterSubmit) {
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    afterSubmit.run();
  }

  private static ChaosScenario gate(final String id) {
    return gate(id, ActivationPolicy.always());
  }

  private static ChaosScenario gate(final String id, final ActivationPolicy policy) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.gate(null))
        .activationPolicy(policy)
        .build();
  }
}
