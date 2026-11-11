package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ChaosSession;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code bindSession}: how a server thread joins the session a request names. */
@DisplayName("Binding a session by id")
class SessionBindingTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("an open session's id binds it on top of what the thread had, until closed")
  void bindsOpenSession() throws Exception {
    final String[] id = new String[1];
    final Thread opener = new Thread(() -> id[0] = runtime.openSession("remote").id());
    opener.start();
    opener.join();

    try (ChaosSession inherited = runtime.openSession("inherited")) {
      try (ChaosSession.ScopeBinding joined = runtime.bindSession(id[0])) {
        assertThat(runtime.currentSessionId()).isEqualTo(id[0]);
      }
      assertThat(runtime.currentSessionId()).isEqualTo(inherited.id());
    }
  }

  @Test
  @DisplayName("an unknown or closed id masks the thread's own session while bound")
  void unknownIdMasks() {
    final ChaosSession closed = runtime.openSession("closed");
    final String closedId = closed.id();
    closed.close();

    try (ChaosSession inherited = runtime.openSession("inherited")) {
      try (ChaosSession.ScopeBinding none = runtime.bindSession("no-such-session")) {
        assertThat(runtime.currentSessionId()).isNull();
      }
      try (ChaosSession.ScopeBinding none = runtime.bindSession(closedId)) {
        assertThat(runtime.currentSessionId()).isNull();
      }
      assertThat(runtime.currentSessionId()).isEqualTo(inherited.id());
    }
  }

  @Test
  @DisplayName("a capture whose carrier was collected before it ran is dropped at the next capture")
  void collectedCarrierIsDropped() throws Exception {
    final SessionHandoff handoff = new SessionHandoff();
    final ScopeContext scopes = new ScopeContext();
    Object carrier = new Object();
    handoff.capture(carrier, "s1");
    carrier = null;
    final Object next = new Object();
    // The collector clears the carrier's key at once but the Reference Handler enqueues it later,
    // so one capture can run in between and still find nothing to drop: capture until one has.
    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(20))
        .until(
            () -> {
              System.gc();
              handoff.capture(next, "s2");
              return captured(handoff).size() == 1;
            });

    handoff.enter(next, scopes);
    assertThat(scopes.currentSessionId()).isEqualTo("s2");
    handoff.exit(next);

    assertThat(captured(handoff)).as("no capture is left of either carrier").isEmpty();
  }

  /** The hand-off's private table of captures, read reflectively: there is no accessor. */
  private static Map<?, ?> captured(final SessionHandoff handoff)
      throws ReflectiveOperationException {
    final Field field = SessionHandoff.class.getDeclaredField("captured");
    field.setAccessible(true);
    return (Map<?, ?>) field.get(handoff);
  }
}
