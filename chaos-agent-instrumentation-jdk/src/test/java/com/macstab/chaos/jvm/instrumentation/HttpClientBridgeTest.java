package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.net.URI;
import java.net.http.HttpRequest;
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

  @Nested
  @DisplayName("beforeHttpSend passthrough")
  class HttpSendPassthrough {

    @Test
    @DisplayName("passes through when no bridge is installed (fallback)")
    void passesWithoutBridge() {
      assertThatCode(() -> BootstrapDispatcher.beforeHttpSend("https://api.example.com/"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes through when no scenario matches")
    void passesWithoutScenarios() {
      assertThatCode(() -> bridge.beforeHttpSend("https://api.example.com/"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accepts null URL without throwing")
    void nullUrlIsSafe() {
      assertThatCode(() -> bridge.beforeHttpSend(null)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("beforeHttpSendAsync passthrough")
  class HttpSendAsyncPassthrough {

    @Test
    @DisplayName("passes through when no bridge is installed (fallback)")
    void passesWithoutBridge() {
      assertThatCode(() -> BootstrapDispatcher.beforeHttpSendAsync("https://api.example.com/"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes through when no scenario matches")
    void passesWithoutScenarios() {
      assertThatCode(() -> bridge.beforeHttpSendAsync("https://api.example.com/"))
          .doesNotThrowAnyException();
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
