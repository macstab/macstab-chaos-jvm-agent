package com.macstab.chaos.jvm.spring.boot.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("ChaosSessionFilter")
class ChaosSessionFilterTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private String sessionSeenBy(final ChaosSessionFilter filter, final String header)
      throws Exception {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    if (header != null) {
      request.addHeader(ChaosSession.HEADER, header);
    }
    final AtomicReference<String> seen = new AtomicReference<>("unset");
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        new MockFilterChain() {
          @Override
          public void doFilter(
              final jakarta.servlet.ServletRequest req, final jakarta.servlet.ServletResponse res) {
            seen.set(runtime.currentSessionId());
          }
        });
    return seen.get();
  }

  @Test
  @DisplayName("binds the request to the open session the header names, for the request only")
  void joinsSession() throws Exception {
    final ChaosSessionFilter filter = new ChaosSessionFilter(() -> runtime);
    final String[] id = new String[1];
    final Thread opener =
        new Thread(
            () -> {
              final ChaosSession session = runtime.openSession("remote test");
              id[0] = session.id();
            });
    opener.start();
    opener.join();

    assertThat(sessionSeenBy(filter, id[0])).isEqualTo(id[0]);
    assertThat(runtime.currentSessionId()).as("unbound after the request").isNull();
  }

  @Test
  @DisplayName("passes requests without the header, with an unknown id or without the agent")
  void passesThrough() throws Exception {
    assertThat(sessionSeenBy(new ChaosSessionFilter(() -> runtime), null)).isNull();
    assertThat(sessionSeenBy(new ChaosSessionFilter(() -> runtime), "no-such-session")).isNull();
    assertThat(sessionSeenBy(new ChaosSessionFilter(() -> null), "anything")).isNull();
    assertThat(new ChaosSessionFilter(() -> runtime).getOrder()).isEqualTo(Integer.MIN_VALUE);
  }

  @Test
  @DisplayName("a blank header and a request that is not HTTP join no session")
  void blankHeaderAndNonHttpRequest() throws Exception {
    final ChaosSessionFilter filter = new ChaosSessionFilter(() -> runtime);
    final String[] id = new String[1];
    final Thread opener = new Thread(() -> id[0] = runtime.openSession("remote test").id());
    opener.start();
    opener.join();

    assertThat(sessionSeenBy(filter, "  ")).isNull();

    // The same header on a request seen only as a plain ServletRequest is not read.
    final MockHttpServletRequest http = new MockHttpServletRequest();
    http.addHeader(ChaosSession.HEADER, id[0]);
    final AtomicReference<String> seen = new AtomicReference<>("unset");
    filter.doFilter(
        new jakarta.servlet.ServletRequestWrapper(http),
        new MockHttpServletResponse(),
        (req, res) -> seen.set(runtime.currentSessionId()));
    assertThat(seen).hasValue(null);
  }

  @Test
  @DisplayName("a request without the header runs outside the session its thread inherited")
  void headerlessRequestMasksInheritedSession() throws Exception {
    final ChaosSessionFilter filter = new ChaosSessionFilter(() -> runtime);
    try (ChaosSession inherited = runtime.openSession("inherited")) {
      assertThat(sessionSeenBy(filter, null)).isNull();
      assertThat(runtime.currentSessionId()).isEqualTo(inherited.id());
    }
  }
}
