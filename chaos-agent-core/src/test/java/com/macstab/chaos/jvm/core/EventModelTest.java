package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
        .isInstanceOf(com.macstab.chaos.jvm.api.ChaosValidationException.class)
        .hasMessageContaining("ConnectionResetEffect applies only to");

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

  @Nested
  @DisplayName("lifecycle events per transition")
  class Transitions {

    @Test
    @DisplayName("stop() and then close() publish one STOPPED")
    void stopThenCloseStopsOnce() {
      runtime.addEventListener(recorder);
      final ChaosActivationHandle handle =
          runtime.activate(scenario("s", ActivationPolicy.always()));

      handle.stop();
      handle.stop();
      handle.close();

      assertThat(typesOf("s")).filteredOn(ChaosEvent.Type.STOPPED::equals).hasSize(1);
    }

    @Test
    @DisplayName("a spent maxApplications publishes one INACTIVE with its reason, after APPLIED")
    void spentBudgetPublishesInactive() {
      runtime.addEventListener(recorder);
      final ChaosActivationHandle handle =
          runtime.activate(
              scenario("once", ActivationPolicy.builder().maxApplications(1L).build()));

      submit();
      submit();
      submit();
      handle.close();

      assertThat(events)
          .filteredOn(event -> event.type() == ChaosEvent.Type.INACTIVE)
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.attributes())
                      .containsEntry("reason", "max applications reached"));
      assertThat(typesOf("once"))
          .containsSubsequence(
              ChaosEvent.Type.APPLIED, ChaosEvent.Type.INACTIVE, ChaosEvent.Type.STOPPED)
          .filteredOn(ChaosEvent.Type.STOPPED::equals)
          .hasSize(1);
    }

    @Test
    @DisplayName("a call that finds the budget spent before the commit reported it ends it, once")
    void spentBudgetSeenByAnotherCall() throws Exception {
      final CountDownLatch applying = new CountDownLatch(1);
      final CountDownLatch proceed = new CountDownLatch(1);
      final AtomicBoolean first = new AtomicBoolean(true);
      runtime.addEventListener(
          event -> {
            events.add(event);
            if (event.type() == ChaosEvent.Type.APPLIED && first.getAndSet(false)) {
              applying.countDown();
              try {
                proceed.await(5, TimeUnit.SECONDS);
              } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            }
          });
      final ChaosActivationHandle handle =
          runtime.activate(
              scenario("once", ActivationPolicy.builder().maxApplications(1L).build()));
      // The committing call blocks while APPLIED is delivered, before it reports the end.
      final Thread committing = Thread.ofPlatform().start(EventModelTest.this::submit);
      try {
        assertThat(applying.await(5, TimeUnit.SECONDS)).isTrue();

        submit();
      } finally {
        proceed.countDown();
        committing.join(5_000);
      }

      assertThat(handle.appliedCount()).isEqualTo(1L);
      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
      assertThat(events)
          .filteredOn(event -> event.type() == ChaosEvent.Type.INACTIVE)
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.attributes())
                      .containsEntry("reason", "max applications reached"));
    }

    @Test
    @DisplayName("an expiry publishes INACTIVE with reason expired")
    void expiryPublishesInactive() {
      final ChaosRuntimeActivationPolicyTest.MutableClock clock =
          new ChaosRuntimeActivationPolicyTest.MutableClock();
      final ChaosRuntime timed = new ChaosRuntime(clock, ChaosMetricsSink.NOOP);
      try {
        timed.addEventListener(recorder);
        timed.activate(
            scenario("timed", ActivationPolicy.builder().activeFor(Duration.ofHours(1)).build()));
        clock.advance(Duration.ofHours(2));

        timed.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
        timed.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

        assertThat(events)
            .filteredOn(event -> event.type() == ChaosEvent.Type.INACTIVE)
            .singleElement()
            .satisfies(event -> assertThat(event.attributes()).containsEntry("reason", "expired"));
      } finally {
        timed.close();
      }
    }

    @Test
    @DisplayName("a call made while STARTED is being delivered is neither matched nor applied")
    void noCallAppliesBeforeStartedIsDelivered() throws Exception {
      final CountDownLatch delivering = new CountDownLatch(1);
      final CountDownLatch proceed = new CountDownLatch(1);
      runtime.addEventListener(blockOnStarted(delivering, proceed));
      final ChaosActivationHandle handle =
          runtime.activate(scenario("s", ActivationPolicy.manual()));
      final Thread starter = Thread.ofPlatform().start(handle::start);
      try {
        assertThat(delivering.await(5, TimeUnit.SECONDS)).isTrue();

        submit();

        assertThat(handle.matchedCount()).as("matched while STARTED is delivered").isZero();
        assertThat(handle.appliedCount()).as("applied while STARTED is delivered").isZero();
      } finally {
        proceed.countDown();
        starter.join(5_000);
      }
      submit();
      assertThat(handle.appliedCount()).as("applied once STARTED was delivered").isEqualTo(1L);
      assertThat(typesOf("s"))
          .containsSubsequence(ChaosEvent.Type.STARTED, ChaosEvent.Type.APPLIED);
    }

    @Test
    @DisplayName(
        "a stop() while STARTED is being delivered leaves no later call matched or applied")
    void stopDuringStartedDeliveryIsFinal() throws Exception {
      final CountDownLatch delivering = new CountDownLatch(1);
      final CountDownLatch proceed = new CountDownLatch(1);
      runtime.addEventListener(blockOnStarted(delivering, proceed));
      final ChaosActivationHandle handle =
          runtime.activate(scenario("s", ActivationPolicy.manual()));
      final Thread starter = Thread.ofPlatform().start(handle::start);
      try {
        assertThat(delivering.await(5, TimeUnit.SECONDS)).isTrue();
        handle.stop();
      } finally {
        proceed.countDown();
        starter.join(5_000);
      }

      submit();

      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
      assertThat(handle.matchedCount()).isZero();
      assertThat(handle.appliedCount()).isZero();
    }

    @Test
    @DisplayName("a stop racing an expiry publishes STOPPED once and INACTIVE at most once")
    void stopRacingExpiry() throws Exception {
      final ChaosRuntimeActivationPolicyTest.MutableClock clock =
          new ChaosRuntimeActivationPolicyTest.MutableClock();
      final ChaosRuntime timed = new ChaosRuntime(clock, ChaosMetricsSink.NOOP);
      try {
        timed.addEventListener(recorder);
        for (int round = 0; round < 200; round++) {
          final String id = "race-" + round;
          final ChaosActivationHandle handle =
              timed.activate(
                  scenario(id, ActivationPolicy.builder().activeFor(Duration.ofHours(1)).build()));
          clock.advance(Duration.ofHours(2));
          final CyclicBarrier together = new CyclicBarrier(2);
          final Thread expiring =
              Thread.ofPlatform()
                  .start(
                      () -> {
                        awaitQuietly(together);
                        timed.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
                      });
          awaitQuietly(together);
          handle.stop();
          expiring.join(5_000);

          final List<ChaosEvent.Type> types = typesOf(id);
          assertThat(types).as(id).filteredOn(ChaosEvent.Type.STOPPED::equals).hasSize(1);
          assertThat(types)
              .as(id)
              .filteredOn(ChaosEvent.Type.INACTIVE::equals)
              .hasSizeLessThanOrEqualTo(1);
          handle.close();
        }
      } finally {
        timed.close();
      }
    }

    private ChaosEventListener blockOnStarted(
        final CountDownLatch delivering, final CountDownLatch proceed) {
      return event -> {
        events.add(event);
        if (event.type() == ChaosEvent.Type.STARTED) {
          delivering.countDown();
          try {
            proceed.await(5, TimeUnit.SECONDS);
          } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      };
    }

    private List<ChaosEvent.Type> typesOf(final String id) {
      return events.stream()
          .filter(event -> id.equals(event.scenarioId()))
          .map(ChaosEvent::type)
          .toList();
    }
  }

  private static void awaitQuietly(final CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (final Exception failure) {
      throw new IllegalStateException(failure);
    }
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

  @Test
  @DisplayName("a FAILED event of an exception without a message says (no message), not null")
  void failedWithoutMessage() {
    runtime.addEventListener(recorder);
    runtime.setHookDemand(
        operations -> {
          throw new com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException("unused") {
            private static final long serialVersionUID = 1L;

            @Override
            public String getMessage() {
              return null;
            }
          };
        });
    final ChaosScenario scenario =
        ChaosScenario.builder("silent")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .build();

    assertThatThrownBy(() -> runtime.activate(scenario))
        .isInstanceOf(com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException.class);

    assertThat(events)
        .filteredOn(event -> event.type() == ChaosEvent.Type.FAILED)
        .singleElement()
        .satisfies(event -> assertThat(event.message()).isEqualTo("(no message)"));
    assertThat(runtime.diagnostics().snapshot().failures())
        .singleElement()
        .satisfies(failure -> assertThat(failure.message()).isEqualTo("(no message)"));
  }
}
