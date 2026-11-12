package com.macstab.chaos.jvm.testkit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opentest4j.AssertionFailedError;

@ExtendWith(ChaosAgentExtension.class)
@DisplayName("ChaosAssertions")
class ChaosAssertionsTest {

  @Test
  @DisplayName("counts applications and matches of a handle")
  void handleCounts(final ChaosSession session) throws Exception {
    final ChaosActivationHandle handle = session.activate(submitDelay("ca-counts"));
    try (ChaosSession.ScopeBinding ignored = session.bind()) {
      submitTwice();
    }

    ChaosAssertions.assertThat(handle).applied().exactly(2);
    ChaosAssertions.assertThat(handle).applied().atLeast(1);
    ChaosAssertions.assertThat(handle).matched().atMost(2);
    ChaosAssertions.assertThat(handle).hasState(ChaosDiagnostics.ScenarioState.ACTIVE);
    assertThatThrownBy(() -> ChaosAssertions.assertThat(handle).applied().never())
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("ca-counts")
        .hasMessageContaining("but was 2");
    assertThatThrownBy(() -> ChaosAssertions.assertThat(handle).applied().atLeast(3))
        .isInstanceOf(AssertionFailedError.class);
  }

  @Test
  @DisplayName("within waits for applications that happen later")
  void withinWaits(final ChaosSession session) throws Exception {
    final ChaosActivationHandle handle = session.activate(submitDelay("ca-within"));
    final Thread late =
        new Thread(
            () -> {
              try (ChaosSession.ScopeBinding ignored = session.bind()) {
                Thread.sleep(100L);
                submitTwice();
              } catch (final Exception e) {
                throw new IllegalStateException(e);
              }
            });
    late.start();

    ChaosAssertions.assertThat(handle).applied().within(Duration.ofSeconds(5)).atLeast(2);
    late.join();
    assertThatThrownBy(
            () ->
                ChaosAssertions.assertThat(handle)
                    .applied()
                    .within(Duration.ofMillis(50))
                    .atLeast(10))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("after waiting");
  }

  @Test
  @DisplayName("checks diagnostics by scenario id and failures")
  void diagnostics(final ChaosControlPlane controlPlane, final ChaosSession session) {
    session.activate(submitDelay("ca-diag"));

    assertThatCode(
            () ->
                ChaosAssertions.assertThat(controlPlane.diagnostics())
                    .hasNoFailuresFor("ca-diag")
                    .scenario("ca-diag")
                    .applied()
                    .never())
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> ChaosAssertions.assertThat(controlPlane.diagnostics()).scenario("ca-unknown"))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("ca-unknown");
  }

  @Test
  @DisplayName("the waiting of an assertion is not seen by a THREAD_SLEEP scenario")
  void waitingIsUnobserved(final ChaosControlPlane controlPlane, final ChaosSession session)
      throws Exception {
    final String pollerName = "ca-unobserved-poller-" + System.nanoTime();
    final ChaosActivationHandle sleeps =
        controlPlane.activate(
            ChaosScenario.builder("ca-unobserved")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(
                    new ChaosSelector.ThreadSelector(
                        Set.of(OperationType.THREAD_SLEEP),
                        null,
                        com.macstab.chaos.jvm.api.NamePattern.exact(pollerName),
                        null))
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .activationPolicy(ActivationPolicy.always())
                .build());
    final java.util.concurrent.atomic.AtomicReference<Throwable> outcome =
        new java.util.concurrent.atomic.AtomicReference<>();
    // A session scenario no thread binds: it never applies, so the poller waits the full 300 ms.
    final ChaosActivationHandle never = session.activate(submitDelay("ca-never"));

    final Thread poller =
        new Thread(
            () ->
                outcome.set(
                    org.assertj.core.api.Assertions.catchThrowable(
                        () ->
                            ChaosAssertions.assertThat(never)
                                .applied()
                                .within(Duration.ofMillis(300))
                                .atLeast(1))),
            pollerName);
    poller.start();
    poller.join(TimeUnit.SECONDS.toMillis(10));

    org.assertj.core.api.Assertions.assertThat(outcome.get())
        .isInstanceOf(AssertionFailedError.class);
    org.assertj.core.api.Assertions.assertThat(sleeps.matchedCount())
        .as("the poller's own sleeps")
        .isZero();
  }

  private static void submitTwice() throws Exception {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  private static ChaosScenario submitDelay(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.SESSION)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}
