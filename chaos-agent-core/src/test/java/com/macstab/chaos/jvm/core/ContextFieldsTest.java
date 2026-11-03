package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Selector fields that the dispatcher used to leave empty, so non-ANY filters never matched. */
@DisplayName("Invocation context fields")
class ContextFieldsTest {

  private ChaosRuntime runtime;

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
  }

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Nested
  @DisplayName("THREAD_SLEEP")
  class ThreadSleep {

    @Test
    @DisplayName("a thread-name pattern matches the sleeping thread")
    void threadNamePatternMatches() throws Throwable {
      activateSleepScenario(
          new ChaosSelector.ThreadSelector(
              Set.of(OperationType.THREAD_SLEEP),
              ChaosSelector.ThreadKind.ANY,
              NamePattern.exact(Thread.currentThread().getName()),
              null));

      runtime.beforeThreadSleep(1L);

      assertThat(matched()).isEqualTo(1L);
    }

    @Test
    @DisplayName("kind=PLATFORM does not match a sleep on a virtual thread")
    void platformKindDoesNotMatchVirtualThread() throws Exception {
      activateSleepScenario(
          new ChaosSelector.ThreadSelector(
              Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.PLATFORM, null, null));
      final AtomicReference<Throwable> failure = new AtomicReference<>();

      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  runtime.beforeThreadSleep(1L);
                } catch (final Throwable throwable) {
                  failure.set(throwable);
                }
              })
          .join();

      assertThat(failure.get()).isNull();
      assertThat(matched()).isZero();
    }

    private void activateSleepScenario(final ChaosSelector selector) {
      runtime.activate(
          ChaosScenario.builder("sleep")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(selector)
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build());
    }

    private long matched() {
      return runtime.diagnostics().scenario("sleep").orElseThrow().matchedCount();
    }
  }

  @Nested
  @DisplayName("socket host")
  class SocketHost {

    @Test
    @DisplayName("the host name of an address is used when known, without a lookup")
    void hostNameWhenKnown() throws Exception {
      assertThat(
              NetworkDispatch.remoteHost(
                  InetAddress.getByAddress("db.internal", new byte[] {10, 0, 0, 5})))
          .isEqualTo("db.internal");
    }

    @Test
    @DisplayName("the IP literal is used when no host name is known")
    void ipLiteralOtherwise() throws Exception {
      assertThat(NetworkDispatch.remoteHost(InetAddress.getByAddress(new byte[] {10, 0, 0, 5})))
          .isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("a host filter on SOCKET_ACCEPT is rejected because the peer is unknown")
    void hostFilterOnAcceptIsRejected() {
      assertThatThrownBy(
              () ->
                  runtime.activate(
                      ChaosScenario.builder("accept")
                          .scope(ChaosScenario.ScenarioScope.JVM)
                          .selector(
                              new ChaosSelector.NetworkSelector(
                                  Set.of(OperationType.SOCKET_ACCEPT),
                                  NamePattern.exact("10.0.0.5")))
                          .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                          .activationPolicy(ActivationPolicy.always())
                          .build()))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("SOCKET_ACCEPT");
    }

    @Test
    @DisplayName("a CIDR or port filter on SOCKET_ACCEPT is rejected for the same reason")
    void peerFiltersOnAcceptAreRejected() {
      for (final ChaosSelector selector :
          new ChaosSelector[] {
            new ChaosSelector.NetworkSelector(
                Set.of(OperationType.SOCKET_ACCEPT), null, "10.0.0.0/8", null, null, null),
            new ChaosSelector.NetworkSelector(
                Set.of(OperationType.SOCKET_ACCEPT), null, null, 8080, null, null)
          }) {
        assertThatThrownBy(
                () ->
                    runtime.activate(
                        ChaosScenario.builder("accept-peer")
                            .scope(ChaosScenario.ScenarioScope.JVM)
                            .selector(selector)
                            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                            .activationPolicy(ActivationPolicy.always())
                            .build()))
            .isInstanceOf(ChaosValidationException.class)
            .hasMessageContaining("SOCKET_ACCEPT");
      }
    }
  }
}
