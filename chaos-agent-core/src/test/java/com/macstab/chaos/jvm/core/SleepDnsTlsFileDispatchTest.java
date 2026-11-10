package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests of the runtime dispatch methods {@code beforeThreadSleep}, {@code beforeDnsResolve}, {@code
 * beforeSslHandshake}, and {@code beforeFileIo}.
 *
 * <p>Each test drives the dispatcher directly through {@link ChaosRuntime}, verifying suppress,
 * delay, passthrough, and selector isolation behaviour without requiring a JVM agent.
 */
@DisplayName("Thread sleep, DNS, TLS and file I/O dispatch")
class SleepDnsTlsFileDispatchTest {

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
      try (ChaosRuntime runtime = new ChaosRuntime()) {
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
    }

    @Test
    @DisplayName("no active scenario — beforeThreadSleep returns false")
    void noScenarioReturnsFalse() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        assertThat(runtime.beforeThreadSleep(500L)).isFalse();
      }
    }

    @Test
    @DisplayName("after handle.stop() — no longer suppressed")
    void afterStopNoSuppression() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
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
    }

    @Test
    @DisplayName("delay scenario blocks for at least 100 ms")
    void delayBlocksForConfiguredDuration() throws Throwable {
      final long delayMs = 100L;
      try (ChaosRuntime runtime = new ChaosRuntime()) {
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
              .isGreaterThanOrEqualTo(delayMs);
          assertThat(applied(runtime)).as("the scenario applied").isPositive();
        } finally {
          handle.stop();
        }
      }
    }

    @Test
    @DisplayName("THREAD_SLEEP scenario does NOT suppress DNS_RESOLVE call")
    void sleepScenarioDoesNotAffectDns() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            activate(
                runtime,
                "sleep-isolation",
                ChaosSelector.thread(
                    Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
                ChaosEffect.suppress());
        try {
          assertThatCode(() -> runtime.beforeDnsResolve("db.internal")).doesNotThrowAnyException();
          assertThat(handle.matchedCount()).as("the DNS call is not matched").isZero();
          assertThat(runtime.beforeThreadSleep(1000L)).as("its own operation is").isTrue();
          assertThat(handle.matchedCount()).isEqualTo(1L);
        } finally {
          handle.stop();
        }
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
      try (ChaosRuntime runtime = new ChaosRuntime()) {
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
              .isGreaterThanOrEqualTo(delayMs);
          assertThat(applied(runtime)).as("the scenario applied").isPositive();
        } finally {
          handle.stop();
        }
      }
    }

    @Test
    @DisplayName("hostname pattern mismatch — no delay applied")
    void hostnameMismatchNoDelay() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            activate(
                runtime,
                "dns-prefix-delay",
                new ChaosSelector.DnsSelector(
                    Set.of(OperationType.DNS_RESOLVE), NamePattern.prefix("internal.")),
                ChaosEffect.delay(Duration.ofMillis(200)));
        try {
          runtime.beforeDnsResolve("external.example.com");
          assertThat(handle.matchedCount())
              .as("a host outside the pattern is not matched")
              .isZero();
        } finally {
          handle.stop();
        }
      }
    }

    @Test
    @DisplayName("no active scenario — beforeDnsResolve does not throw")
    void noScenarioDoesNotThrow() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        assertThatCode(() -> runtime.beforeDnsResolve("anything.com")).doesNotThrowAnyException();
      }
    }

    @Test
    @DisplayName("null hostname (getLocalHost) is accepted with any-pattern selector")
    void nullHostnameAcceptedWithAnyPattern() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
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
    }

    @Test
    @DisplayName(
        "the empty name (getByName(\"\"), loopback) is no lookup: a reject or rewrite skips it")
    void emptyNameIsNotALookup() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle reject =
            activate(
                runtime,
                "dns-reject-all",
                ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE)),
                ChaosEffect.reject("chaos dns"));
        final String afterReject = runtime.beforeDnsResolve("");
        reject.stop();
        final ChaosActivationHandle rewrite =
            activate(
                runtime,
                "dns-rewrite-all",
                ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE)),
                ChaosEffect.dnsRewrite("does-not-exist.invalid"));
        final String afterRewrite = runtime.beforeDnsResolve("");
        rewrite.stop();

        assertThat(afterReject).isEmpty();
        assertThat(afterRewrite).isEmpty();
        assertThat(reject.matchedCount()).isZero();
        assertThat(rewrite.matchedCount()).isZero();
      }
    }

    @Test
    @DisplayName("DNS_RESOLVE scenario does NOT suppress FILE_IO_READ")
    void dnsScenarioDoesNotAffectFileIo() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            activate(
                runtime,
                "dns-isolation",
                ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE)),
                ChaosEffect.delay(Duration.ofMillis(200)));
        try {
          runtime.beforeFileIo("FILE_IO_READ", new Object(), null, InvocationContext.UNKNOWN);
          assertThat(handle.matchedCount()).as("a DNS scenario never sees a file read").isZero();
        } finally {
          handle.stop();
        }
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
      try (ChaosRuntime runtime = new ChaosRuntime()) {
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
              .isGreaterThanOrEqualTo(delayMs);
          assertThat(applied(runtime)).as("the scenario applied").isPositive();
        } finally {
          handle.stop();
        }
      }
    }

    @Test
    @DisplayName("no active scenario — beforeSslHandshake does not throw")
    void noScenarioDoesNotThrow() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        assertThatCode(() -> runtime.beforeSslHandshake(new Object())).doesNotThrowAnyException();
      }
    }

    @Test
    @DisplayName("null socket argument is tolerated")
    void nullSocketIsTolerated() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
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
    }

    @Test
    @DisplayName("after handle.stop() — no longer delayed")
    void afterStopNoDelay() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            activate(
                runtime,
                "ssl-delay-stop",
                ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE)),
                ChaosEffect.delay(Duration.ofMillis(200)));
        handle.stop();
        runtime.beforeSslHandshake(new Object());
        assertThat(handle.appliedCount()).as("a stopped scenario applies nothing").isZero();
      }
    }
  }

  // ── File I/O ─────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("beforeFileIo")
  class BeforeFileIo {

    /** An application subclass of a java.io stream. */
    static final class CountingInput extends java.io.InputStream {
      @Override
      public int read() {
        return -1;
      }
    }

    @Test
    @DisplayName("an open of a java.io stream subclass fails with FileNotFoundException")
    void openOfASubclassFailsAsItsJdkSuperclass() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        activate(
            runtime,
            "open",
            ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_OPEN)),
            ChaosEffect.reject("gone"));
        final java.io.File missing = java.io.File.createTempFile("open", ".txt");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () ->
                    runtime.beforeFileIo(
                        "FILE_IO_OPEN", new CountingInput(), missing.getPath(), -1))
            .isExactlyInstanceOf(java.io.FileNotFoundException.class);
        assertThat(FileDispatch.hookedClassName(new CountingInput()))
            .isEqualTo("java.io.InputStream");
        assertThat(FileDispatch.hookedClassName("not a stream")).isEqualTo("java.lang.String");
        missing.delete();
      }
    }

    @Test
    @DisplayName("FILE_IO_READ delay blocks for at least 100 ms")
    void readDelayBlocksForConfiguredDuration() throws Throwable {
      final long delayMs = 100L;
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            activate(
                runtime,
                "file-read-delay",
                ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)),
                ChaosEffect.delay(Duration.ofMillis(delayMs)));
        try {
          final long start = System.nanoTime();
          runtime.beforeFileIo("FILE_IO_READ", new Object(), null, InvocationContext.UNKNOWN);
          final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
          assertThat(elapsedMs)
              .as("delay effect must pause for at least %dms (got %dms)", delayMs, elapsedMs)
              .isGreaterThanOrEqualTo(delayMs);
          assertThat(applied(runtime)).as("the scenario applied").isPositive();
        } finally {
          handle.stop();
        }
      }
    }

    @Test
    @DisplayName("FILE_IO_WRITE delay blocks for at least 100 ms")
    void writeDelayBlocksForConfiguredDuration() throws Throwable {
      final long delayMs = 100L;
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            activate(
                runtime,
                "file-write-delay",
                ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_WRITE)),
                ChaosEffect.delay(Duration.ofMillis(delayMs)));
        try {
          final long start = System.nanoTime();
          runtime.beforeFileIo("FILE_IO_WRITE", new Object(), null, InvocationContext.UNKNOWN);
          final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
          assertThat(elapsedMs)
              .as("delay effect must pause for at least %dms (got %dms)", delayMs, elapsedMs)
              .isGreaterThanOrEqualTo(delayMs);
          assertThat(applied(runtime)).as("the scenario applied").isPositive();
        } finally {
          handle.stop();
        }
      }
    }

    @Test
    @DisplayName("FILE_IO_READ-only scenario does NOT delay FILE_IO_WRITE")
    void readOnlyScenarioDoesNotAffectWrite() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            activate(
                runtime,
                "file-read-isolation",
                ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)),
                ChaosEffect.delay(Duration.ofMillis(200)));
        try {
          runtime.beforeFileIo("FILE_IO_WRITE", new Object(), null, InvocationContext.UNKNOWN);
          assertThat(handle.matchedCount()).as("a read scenario never sees a write").isZero();
        } finally {
          handle.stop();
        }
      }
    }

    @Test
    @DisplayName("no active scenario — beforeFileIo does not throw")
    void noScenarioDoesNotThrow() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        assertThatCode(
                () ->
                    runtime.beforeFileIo(
                        "FILE_IO_READ", new Object(), null, InvocationContext.UNKNOWN))
            .doesNotThrowAnyException();
      }
    }

    @Test
    @DisplayName("unknown operation tag throws IllegalArgumentException")
    void unknownOperationTagThrows() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () ->
                    runtime.beforeFileIo(
                        "UNKNOWN_TAG", new Object(), null, InvocationContext.UNKNOWN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("UNKNOWN_TAG");
      }
    }

    @Test
    @DisplayName("null stream argument is tolerated")
    void nullStreamIsTolerated() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        assertThatCode(
                () -> runtime.beforeFileIo("FILE_IO_READ", null, null, InvocationContext.UNKNOWN))
            .doesNotThrowAnyException();
      }
    }
  }

  // ── Cross-feature isolation ──────────────────────────────────────────────────

  @Nested
  @DisplayName("Cross-feature isolation")
  class CrossFeatureIsolation {

    @Test
    @DisplayName("all four dispatch methods pass through when no scenario is active")
    void allMethodsPassthroughWithNoScenario() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        assertThatCode(
                () -> {
                  assertThat(runtime.beforeThreadSleep(100L)).isFalse();
                  runtime.beforeDnsResolve("example.com");
                  runtime.beforeSslHandshake(new Object());
                  runtime.beforeFileIo(
                      "FILE_IO_READ", new Object(), null, InvocationContext.UNKNOWN);
                  runtime.beforeFileIo(
                      "FILE_IO_WRITE", new Object(), null, InvocationContext.UNKNOWN);
                })
            .doesNotThrowAnyException();
      }
    }

    @Test
    @DisplayName("FILE_IO scenario does NOT affect THREAD_SLEEP")
    void fileIoScenarioDoesNotAffectThreadSleep() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        // File I/O only supports delay/reject; SUPPRESS on it is rejected at activation.
        final ChaosActivationHandle handle =
            activate(
                runtime,
                "file-no-sleep",
                ChaosSelector.fileIo(
                    Set.of(OperationType.FILE_IO_READ, OperationType.FILE_IO_WRITE)),
                ChaosEffect.reject("file chaos"));
        try {
          assertThat(runtime.beforeThreadSleep(100L)).isFalse();
        } finally {
          handle.stop();
        }
      }
    }
  }

  /** How often the runtime's scenarios applied, all together. */
  private static long applied(final ChaosRuntime runtime) {
    return runtime.diagnostics().snapshot().scenarios().stream()
        .mapToLong(com.macstab.chaos.jvm.api.ChaosDiagnostics.ScenarioReport::appliedCount)
        .sum();
  }
}
