package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosSession;
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
}
