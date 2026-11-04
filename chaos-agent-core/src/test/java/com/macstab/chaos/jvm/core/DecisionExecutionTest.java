package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a decision is carried out once the scenarios have been matched: delays and interrupts,
 * injected exceptions, the seed of a corrupting read, which effects do not apply to a call, and the
 * DNS, file, process and class-loading entry points that hand a decision back to their advice.
 */
@DisplayName("Decision execution")
class DecisionExecutionTest {

  private static final Duration DELAY = Duration.ofMillis(60);

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private void activate(final String id, final ChaosSelector selector, final ChaosEffect effect) {
    activate(id, selector, effect, ActivationPolicy.always());
  }

  private void activate(
      final String id,
      final ChaosSelector selector,
      final ChaosEffect effect,
      final ActivationPolicy policy) {
    runtime.activate(
        ChaosScenario.builder(id)
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(selector)
            .effect(effect)
            .activationPolicy(policy)
            .build());
  }

  private static Duration timed(final ThrowingRunnable action) throws Throwable {
    final long start = System.nanoTime();
    action.run();
    return Duration.ofNanos(System.nanoTime() - start);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Throwable;
  }

  @Nested
  @DisplayName("delays")
  class Delays {

    @Test
    @DisplayName("a delay cut short by an interrupt returns with the interrupt flag restored")
    void interruptedDelayRestoresTheFlag() throws Throwable {
      activate(
          "long-delay",
          ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT)),
          ChaosEffect.delay(Duration.ofMinutes(5)));

      Thread.currentThread().interrupt();
      try {
        runtime.beforeQueueOperation("QUEUE_PUT", this);
      } finally {
        assertThat(Thread.interrupted()).as("the interrupt flag survived the delay").isTrue();
      }
    }
  }

  @Nested
  @DisplayName("injected exceptions")
  class InjectedExceptions {

    private static final String SERVICE = "com.example.PaymentService";

    private void inject(final String exceptionClass, final String message) {
      activate(
          "inject",
          ChaosSelector.method(
              Set.of(OperationType.METHOD_ENTER),
              NamePattern.exact(SERVICE),
              NamePattern.exact("charge")),
          ChaosEffect.injectException(exceptionClass, message));
    }

    @Test
    @DisplayName("a class that is not a Throwable fails with IllegalArgumentException naming it")
    void notAThrowable() {
      inject("java.lang.String", "boom");

      assertThatThrownBy(() -> runtime.beforeMethodEnter(SERVICE, "charge"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("does not extend Throwable")
          .hasMessageContaining("java.lang.String");
    }

    @Test
    @DisplayName("a missing class fails with a RuntimeException that keeps the cause")
    void missingClass() {
      inject("java.lang.NoSuchChaosException", "boom");

      assertThatThrownBy(() -> runtime.beforeMethodEnter(SERVICE, "charge"))
          .isExactlyInstanceOf(RuntimeException.class)
          .hasMessageContaining("failed to instantiate java.lang.NoSuchChaosException")
          .hasCauseInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("a private (String) constructor is used, with the configured message")
    void privateMessageConstructor() {
      inject(PrivateMessageFault.class.getName(), "card declined");

      assertThatThrownBy(() -> runtime.beforeMethodEnter(SERVICE, "charge"))
          .isInstanceOf(PrivateMessageFault.class)
          .hasMessage("card declined");
    }

    @Test
    @DisplayName("without a (String) constructor the message survives as the cause")
    void messageAsCause() {
      inject(NoMessageFault.class.getName(), "card declined");

      assertThatThrownBy(() -> runtime.beforeMethodEnter(SERVICE, "charge"))
          .isInstanceOf(NoMessageFault.class)
          .cause()
          .hasMessage("card declined");
    }
  }

  /** An injectable fault whose only constructor is a private (String) one. */
  static final class PrivateMessageFault extends RuntimeException {
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("UnusedMethod") // constructed reflectively by the injection
    private PrivateMessageFault(final String message) {
      super(message);
    }
  }

  /** An injectable fault with only a private no-arg constructor. */
  static final class NoMessageFault extends RuntimeException {
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("UnusedMethod") // constructed reflectively by the injection
    private NoMessageFault() {
      super();
    }
  }

  @Nested
  @DisplayName("corrupting reads")
  class CorruptingReads {

    private void corruptSocketReads(final ChaosRuntime target) {
      target.activate(
          ChaosScenario.builder("corrupt")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_READ)))
              .effect(ChaosEffect.corruptRead())
              .activationPolicy(ActivationPolicy.builder().randomSeed(42L).build())
              .build());
    }

    @Test
    @DisplayName("a fixed random seed makes the read's corruption seed reproducible")
    void seedIsReproducible() throws Throwable {
      try (ChaosRuntime other = new ChaosRuntime()) {
        corruptSocketReads(runtime);
        corruptSocketReads(other);

        runtime.beforeSocketRead(this, 8, null, 0, 0);
        final long seed = runtime.corruptionSeed();
        other.beforeSocketRead(this, 8, null, 0, 0);

        assertThat(other.corruptionSeed()).isEqualTo(seed);
        assertThat(seed).isNotZero();
      }
    }

    @Test
    @DisplayName("without a corrupting read the seed is a fresh random one each time")
    void unsetSeedIsRandom() {
      final long first = runtime.corruptionSeed();
      final long second = runtime.corruptionSeed();

      assertThat(first == 0L && second == 0L).isFalse();
      assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("a transferTo moves bytes inside the kernel: there is nothing to corrupt")
    void transferToIsNotCorrupted() throws Throwable {
      activate(
          "corrupt",
          ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)),
          ChaosEffect.corruptRead());

      assertThat(runtime.beforeFileIo(ChaosDispatcher.TRANSFER_TO, this, "/data/x", 100))
          .isEqualTo(100);
      assertThat(runtime.diagnostics().scenario("corrupt").orElseThrow().appliedCount()).isZero();
      assertThat(runtime.beforeFileIo("FILE_IO_READ", this, "/data/x", 100)).isEqualTo(-101);
    }
  }

  @Nested
  @DisplayName("torn file writes")
  class TornFileWrites {

    @Test
    @DisplayName("a file stream write cannot be torn; a FileChannel write can")
    void onlyChannelsTear(@TempDir final Path directory) throws Throwable {
      activate(
          "torn",
          ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_WRITE)),
          ChaosEffect.tornWrite());

      assertThat(runtime.beforeFileIo("FILE_IO_WRITE", this, "/data/x", 10)).isEqualTo(10);
      try (FileChannel channel =
          FileChannel.open(
              Files.createFile(directory.resolve("torn.bin")), StandardOpenOption.WRITE)) {
        for (int write = 0; write < 20; write++) {
          assertThat(runtime.beforeFileIo("FILE_IO_WRITE", channel, "/data/x", 10)).isBetween(1, 9);
        }
      }
    }
  }

  @Nested
  @DisplayName("DNS resolution")
  class DnsResolution {

    @Test
    @DisplayName("without a scenario the name itself is resolved")
    void idle() throws Throwable {
      assertThat(runtime.beforeDnsResolve("db.internal")).isEqualTo("db.internal");
    }

    @Test
    @DisplayName("a delay alone resolves the name itself")
    void delayKeepsTheName() throws Throwable {
      activate(
          "delay",
          ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE)),
          ChaosEffect.delay(Duration.ofMillis(1)));

      assertThat(runtime.beforeDnsResolve("db.internal")).isEqualTo("db.internal");
    }

    @Test
    @DisplayName("a rewrite resolves another name, after a composed delay")
    void rewriteAfterDelay() throws Throwable {
      final ChaosSelector selector =
          ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE), NamePattern.exact("db.internal"));
      activate("delay", selector, ChaosEffect.delay(DELAY));
      activate("rewrite", selector, ChaosEffect.dnsRewrite("db-replica.internal"));

      final String[] resolved = new String[1];
      final Duration elapsed = timed(() -> resolved[0] = runtime.beforeDnsResolve("db.internal"));

      assertThat(resolved[0]).isEqualTo("db-replica.internal");
      assertThat(elapsed).isGreaterThanOrEqualTo(DELAY);
      assertThat(runtime.beforeDnsResolve("cache.internal")).isEqualTo("cache.internal");
    }

    @Test
    @DisplayName("a rejected resolution fails with UnknownHostException")
    void reject() {
      activate(
          "nxdomain",
          ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE)),
          ChaosEffect.reject("nxdomain"));

      assertThatThrownBy(() -> runtime.beforeDnsResolve("db.internal"))
          .isInstanceOf(UnknownHostException.class)
          .hasMessage("nxdomain");
    }
  }

  @Nested
  @DisplayName("processes")
  class Processes {

    @Test
    @DisplayName("a rejected start fails with IOException; another program starts")
    void rejectedStart() throws Throwable {
      runtime.beforeProcessStart("git");
      activate(
          "no-git",
          ChaosSelector.process(NamePattern.exact("git")),
          ChaosEffect.reject("cannot run git"));

      assertThatThrownBy(() -> runtime.beforeProcessStart("git"))
          .isInstanceOf(IOException.class)
          .hasMessage("cannot run git");
      assertThatCode(() -> runtime.beforeProcessStart("ls")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a rejected wait fails with InterruptedException; a delay alone lets it wait")
    void rejectedAndDelayedWaits() throws Throwable {
      activate(
          "delay",
          new ChaosSelector.ProcessSelector(Set.of(OperationType.PROCESS_WAIT), NamePattern.any()),
          ChaosEffect.delay(Duration.ofMillis(1)));
      assertThat(runtime.beforeProcessWait(this, 0L)).isFalse();

      activate(
          "reject",
          new ChaosSelector.ProcessSelector(Set.of(OperationType.PROCESS_WAIT), NamePattern.any()),
          ChaosEffect.reject("no wait"));
      assertThatThrownBy(() -> runtime.beforeProcessWait(this, 0L))
          .isInstanceOf(InterruptedException.class)
          .hasMessage("no wait");
    }

    @Test
    @DisplayName("a wait timeout adds a composed delay to the timeout it waits out")
    void delayBeforeTimeout() throws Throwable {
      final ChaosSelector selector =
          new ChaosSelector.ProcessSelector(Set.of(OperationType.PROCESS_WAIT), NamePattern.any());
      activate("delay", selector, ChaosEffect.delay(DELAY));
      activate("timeout", selector, ChaosEffect.timeout());

      final boolean[] timedOut = new boolean[1];
      final Duration elapsed = timed(() -> timedOut[0] = runtime.beforeProcessWait(this, 40L));

      assertThat(timedOut[0]).isTrue();
      assertThat(elapsed).isGreaterThanOrEqualTo(DELAY.plusMillis(40));
    }

    @Test
    @DisplayName("a wait on something that is not a Process never matches a command pattern")
    void unknownProgram() throws Throwable {
      activate(
          "any-program",
          new ChaosSelector.ProcessSelector(
              Set.of(OperationType.PROCESS_WAIT), NamePattern.glob("*")),
          ChaosEffect.reject("no wait"));

      assertThat(runtime.beforeProcessWait(this, 0L)).isFalse();
    }
  }

  @Nested
  @DisplayName("class and resource loading")
  class ClassLoading {

    @Test
    @DisplayName(
        "a rejected class load fails with ClassNotFoundException; the bootstrap loader too")
    void rejectedClassLoad() throws Throwable {
      runtime.beforeClassLoad(getClass().getClassLoader(), "com.example.Plugin");
      activate(
          "plugins",
          ChaosSelector.classLoading(
              Set.of(OperationType.CLASS_LOAD), NamePattern.exact("com.example.Plugin")),
          ChaosEffect.reject("no plugin"));

      assertThatThrownBy(
              () -> runtime.beforeClassLoad(getClass().getClassLoader(), "com.example.Plugin"))
          .isInstanceOf(ClassNotFoundException.class)
          .hasMessage("no plugin");
      assertThatThrownBy(() -> runtime.beforeClassLoad(null, "com.example.Plugin"))
          .isInstanceOf(ClassNotFoundException.class);
      assertThatCode(() -> runtime.beforeClassLoad(null, "com.example.Other"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a bootstrap-loader pattern matches loads by the bootstrap loader only")
    void bootstrapLoader() throws Throwable {
      activate(
          "bootstrap",
          new ChaosSelector.ClassLoadingSelector(
              Set.of(OperationType.CLASS_LOAD), NamePattern.any(), NamePattern.exact("bootstrap")),
          ChaosEffect.reject("no bootstrap"));

      assertThatThrownBy(() -> runtime.beforeClassLoad(null, "com.example.Plugin"))
          .isInstanceOf(ClassNotFoundException.class);
      assertThatCode(
              () -> runtime.beforeClassLoad(getClass().getClassLoader(), "com.example.Plugin"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a missing resource is reported after a composed delay")
    void delayBeforeMissingResource() throws Throwable {
      final ChaosSelector selector =
          ChaosSelector.classLoading(Set.of(OperationType.RESOURCE_LOAD), NamePattern.any());
      activate("delay", selector, ChaosEffect.delay(DELAY));
      activate("suppress", selector, ChaosEffect.suppress());

      final Duration elapsed =
          timed(
              () ->
                  assertThat(
                          runtime.afterResourceLookup(
                              null, "app.properties", URI.create("file:/tmp/x").toURL()))
                      .isNull());

      assertThat(elapsed).isGreaterThanOrEqualTo(DELAY);
    }
  }
}
