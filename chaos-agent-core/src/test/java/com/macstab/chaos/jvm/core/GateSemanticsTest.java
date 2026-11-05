package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Gate semantics")
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
  @Tag("law-L15")
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

  private void submitThen(final Runnable afterSubmit) {
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    afterSubmit.run();
  }

  private static ChaosScenario gate(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.gate(null))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}
