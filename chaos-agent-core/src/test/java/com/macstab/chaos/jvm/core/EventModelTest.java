package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Event model")
class EventModelTest {

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final List<ChaosEvent> events = new CopyOnWriteArrayList<>();
  private final ChaosEventListener recorder = events::add;

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("REGISTERED precedes STARTED")
  void registeredThenStarted() {
    runtime.addEventListener(recorder);
    runtime.activate(scenario("s", ActivationPolicy.always()));

    assertThat(events)
        .extracting(ChaosEvent::type)
        .containsSubsequence(ChaosEvent.Type.REGISTERED, ChaosEvent.Type.STARTED);
  }

  @Test
  @Tag("law-L21")
  @DisplayName("a failed activation publishes FAILED with its category")
  void failedActivation() {
    runtime.addEventListener(recorder);

    assertThatThrownBy(
            () ->
                runtime.activate(
                    ChaosScenario.builder("bad")
                        .scope(ChaosScenario.ScenarioScope.JVM)
                        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                        .effect(ChaosEffect.connectionReset())
                        .activationPolicy(ActivationPolicy.always())
                        .build()))
        .isInstanceOf(RuntimeException.class);

    assertThat(events)
        .filteredOn(event -> event.type() == ChaosEvent.Type.FAILED)
        .singleElement()
        .satisfies(
            event ->
                assertThat(event.attributes()).containsEntry("category", "INVALID_CONFIGURATION"));
  }

  @Test
  @DisplayName("SKIPPED names the policy check that kept the effect from applying")
  void skippedWithReason() {
    runtime.addEventListener(recorder);
    runtime.activate(scenario("warm", ActivationPolicy.builder().activateAfterMatches(1).build()));

    submit();
    submit();

    assertThat(events)
        .filteredOn(event -> event.type() == ChaosEvent.Type.SKIPPED)
        .singleElement()
        .satisfies(event -> assertThat(event.attributes()).containsEntry("reason", "warmUp"));
    assertThat(events).extracting(ChaosEvent::type).contains(ChaosEvent.Type.APPLIED);
  }

  @Test
  @DisplayName("a removed listener receives nothing more")
  void removal() {
    runtime.addEventListener(recorder);

    assertThat(runtime.removeEventListener(recorder)).isTrue();
    runtime.activate(scenario("s", ActivationPolicy.always()));

    assertThat(events).isEmpty();
    assertThat(runtime.removeEventListener(recorder)).isFalse();
  }

  @Test
  @DisplayName("an asynchronous listener receives events on an agent thread, in order")
  void asynchronousDelivery() throws Exception {
    final AtomicReference<Thread> deliveryThread = new AtomicReference<>();
    final CountDownLatch applied = new CountDownLatch(1);
    runtime.addEventListener(
        ChaosEventListener.async(
            event -> {
              deliveryThread.set(Thread.currentThread());
              events.add(event);
              if (event.type() == ChaosEvent.Type.APPLIED) {
                applied.countDown();
              }
            }));
    runtime.activate(scenario("s", ActivationPolicy.always()));

    submit();

    assertThat(applied.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(deliveryThread.get()).isNotSameAs(Thread.currentThread());
    assertThat(deliveryThread.get().getName()).isEqualTo("macstab-chaos-events");
    assertThat(events)
        .extracting(ChaosEvent::type)
        .containsSubsequence(
            ChaosEvent.Type.REGISTERED, ChaosEvent.Type.STARTED, ChaosEvent.Type.APPLIED);
    assertThat(runtime.diagnostics().snapshot().runtimeDetails()).containsKey("droppedEvents");
  }

  private void submit() {
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
  }

  private static ChaosScenario scenario(final String id, final ActivationPolicy policy) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(policy)
        .build();
  }

  @Test
  @DisplayName("a handle reports its scenario's matched and applied counts; a plan handle sums")
  void handleCounters() {
    final com.macstab.chaos.jvm.api.ChaosActivationHandle handle =
        runtime.activate(
            scenario("counted", ActivationPolicy.builder().activateAfterMatches(1).build()));

    submit();
    submit();
    submit();

    assertThat(handle.matchedCount()).isEqualTo(3L);
    assertThat(handle.appliedCount()).isEqualTo(2L);

    final com.macstab.chaos.jvm.api.ChaosActivationHandle plan =
        runtime.activate(
            new com.macstab.chaos.jvm.api.ChaosPlan(
                new com.macstab.chaos.jvm.api.ChaosPlan.Metadata("p", ""),
                null,
                List.of(
                    scenario("p1", ActivationPolicy.always()),
                    scenario("p2", ActivationPolicy.always()))));
    submit();
    assertThat(plan.appliedCount()).isEqualTo(2L);
  }
}
