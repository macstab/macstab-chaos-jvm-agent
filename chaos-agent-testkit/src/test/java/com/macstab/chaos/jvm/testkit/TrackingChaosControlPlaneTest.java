package com.macstab.chaos.jvm.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decorator the extensions wrap the JVM-wide control plane in: it releases what a test
 * activated, and passes everything else through to the control plane it never closes.
 */
@DisplayName("TrackingChaosControlPlane")
class TrackingChaosControlPlaneTest {

  private final RecordingControlPlane delegate = new RecordingControlPlane();
  private final TrackingChaosControlPlane tracker = new TrackingChaosControlPlane(delegate);

  @Test
  @DisplayName("closes the handles of scenarios and plans in reverse activation order")
  void closesInReverseOrder() {
    final ChaosActivationHandle scenario = tracker.activate(scenario("tracked-scenario"));
    final ChaosActivationHandle plan =
        tracker.activate(
            new ChaosPlan(
                new ChaosPlan.Metadata("tracked-plan", ""), null, List.of(scenario("in-plan"))));

    tracker.stopTracked();

    assertThat(delegate.closed).containsExactly(plan.id(), scenario.id());
  }

  @Test
  @DisplayName("a handle failing to close neither stops the others nor reaches the caller")
  void failingHandleDoesNotBlockCleanup() {
    final ChaosActivationHandle first = tracker.activate(scenario("first"));
    delegate.failNextClose = true;
    tracker.activate(scenario("failing"));

    assertThatCode(tracker::stopTracked).doesNotThrowAnyException();
    assertThat(delegate.closed).containsExactly("failing", first.id());

    tracker.stopTracked();
    assertThat(delegate.closed).hasSize(2);
  }

  @Test
  @DisplayName("closes the sessions opened and removes the listeners added through it")
  void releasesSessionsAndListeners() {
    final ChaosEventListener listener = event -> {};
    final ChaosSession session = tracker.openSession("tracked-session");
    tracker.addEventListener(listener);

    tracker.stopTracked();

    assertThat(delegate.closedSessions).containsExactly(session.id());
    assertThat(delegate.listeners).isEmpty();
    tracker.stopTracked();
    assertThat(delegate.closedSessions).hasSize(1);
    assertThat(delegate.removedListeners).isEqualTo(1);

    tracker.addEventListener(listener);
    tracker.stopTracked();
    assertThat(delegate.listeners).isEmpty();
    assertThat(delegate.removedListeners).isEqualTo(2);
  }

  @Test
  @DisplayName("a listener the test removed itself is no longer tracked, without an error")
  void listenerRemovedByTheTest() {
    final ChaosEventListener listener = event -> {};
    tracker.addEventListener(listener);
    assertThat(tracker.removeEventListener(listener)).isTrue();

    assertThatCode(tracker::stopTracked).doesNotThrowAnyException();
    assertThat(delegate.removedListeners).isEqualTo(1);
  }

  @Test
  @DisplayName("without listener and session tracking, both stay with the delegate")
  void untrackedListenersAndSessions() {
    final TrackingChaosControlPlane contextPlane = new TrackingChaosControlPlane(delegate, false);
    final ChaosEventListener listener = event -> {};
    contextPlane.openSession("application-session");
    contextPlane.addEventListener(listener);
    final ChaosActivationHandle handle = contextPlane.activate(scenario("context-scenario"));

    contextPlane.stopTracked();

    assertThat(delegate.closed).containsExactly(handle.id());
    assertThat(delegate.closedSessions).isEmpty();
    assertThat(delegate.listeners).containsExactly(listener);
  }

  @Test
  @DisplayName("a null delegate is refused with a NullPointerException naming it")
  void nullDelegate() {
    assertThatThrownBy(() -> new TrackingChaosControlPlane(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("delegate");
  }

  @Test
  @DisplayName("listeners and session bindings go to the delegate, which close leaves open")
  void passesThroughAndNeverClosesTheDelegate() {
    final ChaosEventListener listener = event -> {};

    tracker.addEventListener(listener);
    assertThat(tracker.removeEventListener(listener)).isTrue();
    assertThat(tracker.removeEventListener(listener)).isFalse();
    assertThat(tracker.bindSession("session-7")).isSameAs(delegate.binding);
    assertThat(delegate.boundSessionIds).containsExactly("session-7");
    tracker.close();

    assertThat(delegate.closedItself).isFalse();
  }

  private static ChaosScenario scenario(final String id) {
    return ChaosScenario.builder(id)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .build();
  }

  /** A control plane that hands out handles recording their close, and records every call. */
  private static final class RecordingControlPlane implements ChaosControlPlane {
    private final List<String> closed = new ArrayList<>();
    private final List<ChaosEventListener> listeners = new ArrayList<>();
    private final List<String> boundSessionIds = new ArrayList<>();
    private final List<String> closedSessions = new ArrayList<>();
    private int removedListeners;
    private final ChaosSession.ScopeBinding binding = () -> {};
    private boolean failNextClose;
    private boolean closedItself;

    @Override
    public ChaosActivationHandle activate(final ChaosScenario scenario) {
      return handle(scenario.id());
    }

    @Override
    public ChaosActivationHandle activate(final ChaosPlan plan) {
      return handle(plan.metadata().name());
    }

    private ChaosActivationHandle handle(final String id) {
      final boolean failing = failNextClose;
      failNextClose = false;
      return new ChaosActivationHandle() {
        @Override
        public String id() {
          return id;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {
          closed.add(id);
          if (failing) {
            throw new IllegalStateException("cannot stop " + id);
          }
        }

        @Override
        public void release() {}

        @Override
        public ChaosDiagnostics.ScenarioState state() {
          return ChaosDiagnostics.ScenarioState.ACTIVE;
        }

        @Override
        public long matchedCount() {
          return 0L;
        }

        @Override
        public long appliedCount() {
          return 0L;
        }
      };
    }

    @Override
    public ChaosSession openSession(final String displayName) {
      return new ChaosSession() {
        @Override
        public String id() {
          return "id-" + displayName;
        }

        @Override
        public String displayName() {
          return displayName;
        }

        @Override
        public ChaosActivationHandle activate(final ChaosScenario scenario) {
          throw new UnsupportedOperationException("not used");
        }

        @Override
        public ChaosActivationHandle activate(final ChaosPlan plan) {
          throw new UnsupportedOperationException("not used");
        }

        @Override
        public ChaosSession.ScopeBinding bind() {
          throw new UnsupportedOperationException("not used");
        }

        @Override
        public Runnable wrap(final Runnable runnable) {
          return runnable;
        }

        @Override
        public <T> Callable<T> wrap(final Callable<T> callable) {
          return callable;
        }

        @Override
        public void close() {
          closedSessions.add(id());
        }
      };
    }

    @Override
    public ChaosDiagnostics diagnostics() {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public void addEventListener(final ChaosEventListener listener) {
      listeners.add(listener);
    }

    @Override
    public boolean removeEventListener(final ChaosEventListener listener) {
      final boolean removed = listeners.remove(listener);
      if (removed) {
        removedListeners++;
      }
      return removed;
    }

    @Override
    public ChaosSession.ScopeBinding bindSession(final String sessionId) {
      boundSessionIds.add(sessionId);
      return binding;
    }

    @Override
    public void close() {
      closedItself = true;
    }
  }
}
