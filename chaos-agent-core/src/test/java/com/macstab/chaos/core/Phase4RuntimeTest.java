package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.NamePattern;
import com.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Phase 4 runtime dispatch methods: {@code beforeThreadSleep}, {@code
 * beforeDnsResolve}, {@code beforeSslHandshake}, and {@code beforeFileIo}.
 *
 * <p>Each test drives the dispatcher directly through {@link ChaosRuntime}, verifying suppress,
 * delay, passthrough, and selector isolation behaviour without requiring a JVM agent.
 */
@DisplayName("Phase 4 runtime dispatch")
class Phase4RuntimeTest {

  private static ChaosActivationHandle activate(
      final ChaosRuntime runtime,
      final String id,
      final ChaosSelector selector,
      final ChaosEffect effect) {
    return runtime.activate(
        ChaosScenario.builder(id)
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(selector)
            .effect(effect)
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  // ── Thread.sleep ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("beforeThreadSleep")
  class BeforeThreadSleep {

    @Test
    @DisplayName("suppress scenario causes beforeThreadSleep to return true")
    void suppressReturnsTrueOnMatch() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "sleep-suppress",
              ChaosSelector.thread(
                  Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
              ChaosEffect.suppress());
      try {
        assertThat(runtime.beforeThreadSleep(1000L)).isTrue();
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("no active scenario — beforeThreadSleep returns false")
    void noScenarioReturnsFalse() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThat(runtime.beforeThreadSleep(500L)).isFalse();
    }

    @Test
    @DisplayName("after handle.stop() — no longer suppressed")
    void afterStopNoSuppression() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "sleep-suppress-stop",
              ChaosSelector.thread(
                  Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
              ChaosEffect.suppress());
      assertThat(runtime.beforeThreadSleep(1000L)).isTrue();
      handle.stop();
      assertThat(runtime.beforeThreadSleep(1000L)).isFalse();
    }

    @Test
    @DisplayName("delay scenario blocks for at least 100 ms")
    void delayBlocksForConfiguredDuration() throws Throwable {
      final long delayMs = 100L;
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "sleep-delay",
              ChaosSelector.thread(
                  Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
              ChaosEffect.delay(Duration.ofMillis(delayMs)));
      try {
        final long start = System.nanoTime();
        assertThat(runtime.beforeThreadSleep(1L)).isFalse();
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs)
            .as("delay effect must pause for at least %dms (got %dms)", delayMs, elapsedMs)
            .isGreaterThanOrEqualTo((long) (delayMs * 0.8));
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("THREAD_SLEEP scenario does NOT suppress DNS_RESOLVE call")
    void sleepScenarioDoesNotAffectDns() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "sleep-isolation",
              ChaosSelector.thread(
                  Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
              ChaosEffect.suppress());
      try {
        assertThatCode(() -> runtime.beforeDnsResolve("db.internal")).doesNotThrowAnyException();
      } finally {
        handle.stop();
      }
    }
  }

  // ── DNS resolution ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("beforeDnsResolve")
  class BeforeDnsResolve {

    @Test
    @DisplayName("delay scenario blocks for at least 100 ms")
    void delayBlocksForConfiguredDuration() throws Throwable {
      final long delayMs = 100L;
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "dns-delay",
              ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE)),
              ChaosEffect.delay(Duration.ofMillis(delayMs)));
      try {
        final long start = System.nanoTime();
        runtime.beforeDnsResolve("api.example.com");
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs)
            .as("delay effect must pause for at least %dms (got %dms)", delayMs, elapsedMs)
            .isGreaterThanOrEqualTo((long) (delayMs * 0.8));
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("hostname pattern mismatch — no delay applied")
    void hostnameMismatchNoDelay() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "dns-prefix-delay",
              new ChaosSelector.DnsSelector(
                  Set.of(OperationType.DNS_RESOLVE), NamePattern.prefix("internal.")),
              ChaosEffect.delay(Duration.ofMillis(200)));
      try {
        final long start = System.nanoTime();
        runtime.beforeDnsResolve("external.example.com");
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs)
            .as("mismatch must not introduce delay (got %dms)", elapsedMs)
            .isLessThan(50L);
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("no active scenario — beforeDnsResolve does not throw")
    void noScenarioDoesNotThrow() {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThatCode(() -> runtime.beforeDnsResolve("anything.com")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null hostname (getLocalHost) is accepted with any-pattern selector")
    void nullHostnameAcceptedWithAnyPattern() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "dns-localhost",
              ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE)),
              ChaosEffect.delay(Duration.ofMillis(5)));
      try {
        assertThatCode(() -> runtime.beforeDnsResolve(null)).doesNotThrowAnyException();
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("DNS_RESOLVE scenario does NOT suppress FILE_IO_READ")
    void dnsScenarioDoesNotAffectFileIo() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "dns-isolation",
              ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE)),
              ChaosEffect.delay(Duration.ofMillis(200)));
      try {
        final long start = System.nanoTime();
        runtime.beforeFileIo("FILE_IO_READ", new Object());
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs)
            .as("DNS scenario must not delay FILE_IO_READ (got %dms)", elapsedMs)
            .isLessThan(50L);
      } finally {
        handle.stop();
      }
    }
  }

  // ── SSL/TLS handshake ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("beforeSslHandshake")
  class BeforeSslHandshake {

    @Test
    @DisplayName("delay scenario blocks for at least 100 ms")
    void delayBlocksForConfiguredDuration() throws Throwable {
      final long delayMs = 100L;
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "ssl-delay",
              ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE)),
              ChaosEffect.delay(Duration.ofMillis(delayMs)));
      try {
        final long start = System.nanoTime();
        runtime.beforeSslHandshake(new Object());
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs)
            .as("delay effect must pause for at least %dms (got %dms)", delayMs, elapsedMs)
            .isGreaterThanOrEqualTo((long) (delayMs * 0.8));
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("no active scenario — beforeSslHandshake does not throw")
    void noScenarioDoesNotThrow() {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThatCode(() -> runtime.beforeSslHandshake(new Object())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null socket argument is tolerated")
    void nullSocketIsTolerated() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "ssl-null-socket",
              ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE)),
              ChaosEffect.delay(Duration.ofMillis(5)));
      try {
        assertThatCode(() -> runtime.beforeSslHandshake(null)).doesNotThrowAnyException();
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("after handle.stop() — no longer delayed")
    void afterStopNoDelay() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "ssl-delay-stop",
              ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE)),
              ChaosEffect.delay(Duration.ofMillis(200)));
      handle.stop();
      final long start = System.nanoTime();
      runtime.beforeSslHandshake(new Object());
      final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      assertThat(elapsedMs)
          .as("stopped scenario must not delay (got %dms)", elapsedMs)
          .isLessThan(50L);
    }
  }

  // ── File I/O ─────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("beforeFileIo")
  class BeforeFileIo {

    @Test
    @DisplayName("FILE_IO_READ delay blocks for at least 100 ms")
    void readDelayBlocksForConfiguredDuration() throws Throwable {
      final long delayMs = 100L;
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "file-read-delay",
              ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)),
              ChaosEffect.delay(Duration.ofMillis(delayMs)));
      try {
        final long start = System.nanoTime();
        runtime.beforeFileIo("FILE_IO_READ", new Object());
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs)
            .as("delay effect must pause for at least %dms (got %dms)", delayMs, elapsedMs)
            .isGreaterThanOrEqualTo((long) (delayMs * 0.8));
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("FILE_IO_WRITE delay blocks for at least 100 ms")
    void writeDelayBlocksForConfiguredDuration() throws Throwable {
      final long delayMs = 100L;
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "file-write-delay",
              ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_WRITE)),
              ChaosEffect.delay(Duration.ofMillis(delayMs)));
      try {
        final long start = System.nanoTime();
        runtime.beforeFileIo("FILE_IO_WRITE", new Object());
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs)
            .as("delay effect must pause for at least %dms (got %dms)", delayMs, elapsedMs)
            .isGreaterThanOrEqualTo((long) (delayMs * 0.8));
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("FILE_IO_READ-only scenario does NOT delay FILE_IO_WRITE")
    void readOnlyScenarioDoesNotAffectWrite() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "file-read-isolation",
              ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)),
              ChaosEffect.delay(Duration.ofMillis(200)));
      try {
        final long start = System.nanoTime();
        runtime.beforeFileIo("FILE_IO_WRITE", new Object());
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs)
            .as("READ-only scenario must not delay WRITE (got %dms)", elapsedMs)
            .isLessThan(50L);
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("no active scenario — beforeFileIo does not throw")
    void noScenarioDoesNotThrow() {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThatCode(() -> runtime.beforeFileIo("FILE_IO_READ", new Object()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unknown operation tag throws IllegalArgumentException")
    void unknownOperationTagThrows() {
      final ChaosRuntime runtime = new ChaosRuntime();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> runtime.beforeFileIo("UNKNOWN_TAG", new Object()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("UNKNOWN_TAG");
    }

    @Test
    @DisplayName("null stream argument is tolerated")
    void nullStreamIsTolerated() {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThatCode(() -> runtime.beforeFileIo("FILE_IO_READ", null)).doesNotThrowAnyException();
    }
  }

  // ── Cross-feature isolation ──────────────────────────────────────────────────

  @Nested
  @DisplayName("Cross-feature isolation")
  class CrossFeatureIsolation {

    @Test
    @DisplayName("all four dispatch methods pass through when no scenario is active")
    void allMethodsPassthroughWithNoScenario() {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThatCode(
              () -> {
                assertThat(runtime.beforeThreadSleep(100L)).isFalse();
                runtime.beforeDnsResolve("example.com");
                runtime.beforeSslHandshake(new Object());
                runtime.beforeFileIo("FILE_IO_READ", new Object());
                runtime.beforeFileIo("FILE_IO_WRITE", new Object());
              })
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FILE_IO scenario does NOT suppress THREAD_SLEEP")
    void fileIoScenarioDoesNotAffectThreadSleep() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          activate(
              runtime,
              "file-no-sleep",
              ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ, OperationType.FILE_IO_WRITE)),
              ChaosEffect.suppress());
      try {
        assertThat(runtime.beforeThreadSleep(100L)).isFalse();
      } finally {
        handle.stop();
      }
    }
  }
}
