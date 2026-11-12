package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("HTTP bridge (BootstrapDispatcher)")
class HttpClientBridgeTest {

  private ChaosRuntime runtime;
  private ChaosBridge bridge;

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
    bridge = new ChaosBridge(runtime);
  }

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  /** Activates a reject of {@code operation} for URLs matching {@code url}. */
  private ChaosActivationHandle rejectOn(final OperationType operation, final NamePattern url) {
    return runtime.activate(
        ChaosScenario.builder("http-" + operation)
            .selector(ChaosSelector.httpClient(Set.of(operation), url))
            .effect(ChaosEffect.reject("http refused"))
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  @Nested
  @DisplayName("the HTTP entries")
  class HttpSendEntries {

    @Test
    @DisplayName("without a bridge return at once and leave no scope entered")
    void withoutBridgeNothingHappens() throws Throwable {
      BootstrapDispatcher.beforeHttpSend("https://api.example.com/");
      BootstrapDispatcher.beforeHttpSendAsync("https://api.example.com/");

      assertThat(BootstrapDispatcher.inScope(BootstrapDispatcher.SCOPE_HTTP)).isFalse();
      assertThat(BootstrapDispatcher.idle("HTTP_CLIENT_SEND")).isTrue();
    }

    @Test
    @DisplayName("a send reaches the runtime as HTTP_CLIENT_SEND, matched by its URL")
    void sendReachesTheRuntime() {
      final ChaosActivationHandle handle =
          rejectOn(OperationType.HTTP_CLIENT_SEND, NamePattern.prefix("https://api.example.com"));

      assertThatCode(() -> bridge.beforeHttpSend("https://other.example.com/"))
          .doesNotThrowAnyException();
      assertThatCode(() -> bridge.beforeHttpSend(null)).doesNotThrowAnyException();
      assertThatCode(() -> bridge.beforeHttpSendAsync("https://api.example.com/"))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> bridge.beforeHttpSend("https://api.example.com/"))
          .isInstanceOf(java.io.IOException.class)
          .hasMessageContaining("http refused");
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("an asynchronous send reaches the runtime as HTTP_CLIENT_SEND_ASYNC")
    void sendAsyncReachesTheRuntime() {
      final ChaosActivationHandle handle =
          rejectOn(OperationType.HTTP_CLIENT_SEND_ASYNC, NamePattern.any());

      assertThatCode(() -> bridge.beforeHttpSend("https://api.example.com/"))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> bridge.beforeHttpSendAsync("https://api.example.com/"))
          .isInstanceOf(java.io.IOException.class);
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("JDK HttpClient send advice outside a session")
  class SendOutsideSession {

    private static final int CALLS = 10_000;

    @AfterEach
    void uninstall() {
      BootstrapDispatcher.install(null, null);
    }

    @Test
    @DisplayName(
        "a send and an async send on a thread without a session allocate nothing while another"
            + " thread is in a session: the session is asked before the headers are read")
    void sendWithoutSessionDoesNotAllocate() throws Throwable {
      BootstrapDispatcher.install(bridge, JdkInstrumentationInstaller.buildMethodHandles());
      final CountDownLatch bound = new CountDownLatch(1);
      final CountDownLatch done = new CountDownLatch(1);
      final Thread sessionThread =
          new Thread(
              () -> {
                try (ChaosSession session = runtime.openSession("other-thread");
                    ChaosSession.ScopeBinding binding = session.bind()) {
                  bound.countDown();
                  done.await();
                } catch (final InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              },
              "session-thread");
      sessionThread.start();
      bound.await();
      final HttpRequest request = HttpRequest.newBuilder(URI.create("http://orders/v1")).build();
      try {
        // The first round warms up the method-handle adaptation, which allocates once.
        sendAndSendAsync(request);

        final long allocated = sendAndSendAsync(request);

        // The header lookup builds a stream and an Optional per call, i.e. well over 1 MB over
        // 10,000 sends and async sends; the bound leaves room for the measurement's own noise.
        assertThat(allocated).as("bytes allocated by sends outside a session").isLessThan(CALLS);
      } finally {
        done.countDown();
        sessionThread.join();
      }
    }

    private long sendAndSendAsync(final HttpRequest request) throws Throwable {
      final com.sun.management.ThreadMXBean threads =
          (com.sun.management.ThreadMXBean)
              java.lang.management.ManagementFactory.getThreadMXBean();
      final long threadId = Thread.currentThread().threadId();
      final long before = threads.getThreadAllocatedBytes(threadId);
      for (int i = 0; i < CALLS; i++) {
        HttpClientAdvice.JavaHttpClientSendAdvice.enter(request);
        HttpClientAdvice.JavaHttpClientSendAdvice.exit();
        final Throwable rejected = HttpClientAdvice.JavaHttpClientSendAsyncAdvice.enter(request);
        HttpClientAdvice.JavaHttpClientSendAsyncAdvice.exit(rejected, null);
      }
      return threads.getThreadAllocatedBytes(threadId) - before;
    }
  }
}
