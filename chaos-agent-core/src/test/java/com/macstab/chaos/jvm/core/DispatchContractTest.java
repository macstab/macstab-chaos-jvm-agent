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
import java.net.InetAddress;
import java.net.URL;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What each dispatch entry point hands back to its advice, per effect: the contract the inlined
 * advice relies on. The child-JVM tests show the end-to-end result inside the JDK; these pin the
 * dispatch side of it, one entry point and one effect at a time.
 */
@DisplayName("Dispatch contracts")
class DispatchContractTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private void activate(final ChaosSelector selector, final ChaosEffect effect) {
    runtime.activate(
        ChaosScenario.builder("contract")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(selector)
            .effect(effect)
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  @Nested
  @DisplayName("CompletableFuture completion")
  class Completion {

    private final CompletableFuture<String> future = new CompletableFuture<>();

    @Test
    @DisplayName("without a scenario the real completion runs")
    void none() throws Throwable {
      assertThat(runtime.beforeCompletableFutureComplete("ASYNC_COMPLETE", future, "v")).isNull();
    }

    @Test
    @DisplayName("suppress skips the completion and reports it did not complete")
    void suppress() throws Throwable {
      activate(ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)), ChaosEffect.suppress());
      assertThat(runtime.beforeCompletableFutureComplete("ASYNC_COMPLETE", future, "v")).isFalse();
      assertThat(future).isNotDone();
    }

    @Test
    @DisplayName("exceptional completion completes the future with the failure instead")
    void exceptional() throws Throwable {
      activate(
          ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)),
          ChaosEffect.exceptionalCompletion(ChaosEffect.FailureKind.IO, "chaos io"));
      assertThat(runtime.beforeCompletableFutureComplete("ASYNC_COMPLETE", future, "v")).isTrue();
      assertThat(future).isCompletedExceptionally();
      assertThat(future.exceptionNow())
          .isInstanceOf(java.io.IOException.class)
          .hasMessage("chaos io");
    }
  }

  @Nested
  @DisplayName("executor callables")
  class Callables {

    private final Callable<String> task = () -> "result";

    @Test
    @DisplayName("without a scenario the task itself is submitted")
    void none() {
      assertThat(runtime.decorateExecutorCallable("EXECUTOR_SUBMIT", this, task)).isSameAs(task);
    }

    @Test
    @DisplayName("suppress submits a no-op in its place")
    void suppress() throws Exception {
      final AtomicInteger runs = new AtomicInteger();
      activate(
          ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)), ChaosEffect.suppress());
      final Callable<String> submitted =
          runtime.decorateExecutorCallable(
              "EXECUTOR_SUBMIT",
              this,
              () -> {
                runs.incrementAndGet();
                return "result";
              });
      assertThat(submitted.call()).isNull();
      assertThat(runs).hasValue(0);
    }

    @Test
    @DisplayName("reject throws RejectedExecutionException at submission")
    void reject() {
      activate(
          ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)),
          ChaosEffect.reject("full"));
      assertThatThrownBy(() -> runtime.decorateExecutorCallable("EXECUTOR_SUBMIT", this, task))
          .isInstanceOf(RejectedExecutionException.class)
          .hasMessageContaining("full");
    }
  }

  @Nested
  @DisplayName("shutdown hooks")
  class ShutdownHooks {

    private final AtomicInteger runs = new AtomicInteger();
    private final Thread hook = new Thread(runs::incrementAndGet, "user-hook");

    @Test
    @DisplayName("reject fails the registration; suppress is not offered for it")
    void reject() {
      activate(
          ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
          ChaosEffect.reject("no hooks"));
      // ApplicationShutdownHooks.add refuses a hook only while the JVM shuts down, with its text
      assertThatThrownBy(() -> runtime.decorateShutdownHook(hook))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Shutdown in progress");
      assertThatThrownBy(
              () ->
                  activate(
                      ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
                      ChaosEffect.suppress()))
          .isInstanceOf(com.macstab.chaos.jvm.api.ChaosValidationException.class);
    }

    @Test
    @DisplayName("a delayed hook is evaluated and not run; the JDK registers it as itself")
    void registeredAsItself() throws Throwable {
      activate(
          ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
          ChaosEffect.delay(Duration.ofMillis(1)));

      runtime.decorateShutdownHook(hook);
      assertThat(runs).as("registering does not run the hook").hasValue(0);
    }
  }

  @Nested
  @DisplayName("scheduled ticks")
  class ScheduledTicks {

    @Test
    @DisplayName("without a scenario the tick runs")
    void none() throws Throwable {
      assertThat(runtime.beforeScheduledTick(this, (Runnable) () -> {}, true)).isTrue();
    }

    @Test
    @DisplayName("suppress skips the tick")
    void suppress() throws Throwable {
      activate(
          ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_TICK)), ChaosEffect.suppress());
      assertThat(runtime.beforeScheduledTick(this, (Runnable) () -> {}, true)).isFalse();
    }

    @Test
    @DisplayName("reject fails the tick")
    void reject() {
      activate(
          ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_TICK)), ChaosEffect.reject("no"));
      assertThatThrownBy(() -> runtime.beforeScheduledTick(this, (Runnable) () -> {}, false))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("no");
    }
  }

  @Nested
  @DisplayName("process waits")
  class ProcessWaits {

    @Test
    @DisplayName("without a scenario the real wait runs")
    void none() throws Throwable {
      assertThat(runtime.beforeProcessWait(this, 5L)).isFalse();
    }

    @Test
    @DisplayName(
        "a wait is matched by the program its process was started as, a start without one not")
    void waitMatchesTheStartedProgram() throws Throwable {
      activate(
          new ChaosSelector.ProcessSelector(
              Set.of(OperationType.PROCESS_WAIT), NamePattern.exact("sh")),
          ChaosEffect.timeout());
      final Object started = new Object();
      final Object unnamed = new Object();
      runtime.afterProcessStart(started, "sh");
      runtime.afterProcessStart(unnamed, null);

      assertThat(runtime.beforeProcessWait(started, 1L)).as("started as sh").isTrue();
      assertThat(runtime.beforeProcessWait(unnamed, 1L)).as("no program recorded").isFalse();
      assertThat(runtime.beforeProcessWait(new Object(), 1L)).as("never recorded").isFalse();
    }

    @Test
    @DisplayName("timeout waits out the timeout and reports a child that has not exited")
    void timeout() throws Throwable {
      activate(
          new ChaosSelector.ProcessSelector(Set.of(OperationType.PROCESS_WAIT), NamePattern.any()),
          ChaosEffect.timeout());
      final long start = System.nanoTime();
      assertThat(runtime.beforeProcessWait(this, 50L)).isTrue();
      assertThat(Duration.ofNanos(System.nanoTime() - start))
          .isGreaterThanOrEqualTo(Duration.ofMillis(40));
    }
  }

  @Nested
  @DisplayName("resource lookups")
  class ResourceLookups {

    @Test
    @DisplayName("without a scenario the real resource is returned")
    void none() throws Throwable {
      final URL real = java.net.URI.create("file:/tmp/app.properties").toURL();
      assertThat(runtime.afterResourceLookup(null, "app.properties", real)).isSameAs(real);
    }

    @Test
    @DisplayName("suppress makes the resource missing")
    void suppress() throws Throwable {
      activate(
          ChaosSelector.classLoading(Set.of(OperationType.RESOURCE_LOAD), NamePattern.any()),
          ChaosEffect.suppress());
      assertThat(
              runtime.afterResourceLookup(
                  null, "app.properties", java.net.URI.create("file:/tmp/x").toURL()))
          .isNull();
    }
  }

  @Nested
  @DisplayName("HTTP sends")
  class HttpSends {

    @Test
    @DisplayName("reject fails the send with the client's IOException; a URL filter decides which")
    void rejectByUrl() throws Throwable {
      activate(
          ChaosSelector.httpClient(
              Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.glob("https://payments/*")),
          ChaosEffect.reject("payments down"));
      assertThatThrownBy(
              () ->
                  runtime.beforeHttpSend("https://payments/charge", OperationType.HTTP_CLIENT_SEND))
          .isInstanceOf(java.io.IOException.class);
      assertThatCode(
              () -> runtime.beforeHttpSend("https://catalog/items", OperationType.HTTP_CLIENT_SEND))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("DNS answers")
  class DnsAnswers {

    @Test
    @DisplayName("an override replaces the answer; for other hosts null keeps the real one")
    void override() throws Throwable {
      activate(
          ChaosSelector.dns(Set.of(OperationType.DNS_ANSWER), NamePattern.exact("db.internal")),
          ChaosEffect.dnsOverride("10.9.8.7"));
      final InetAddress[] real = {
        InetAddress.getByAddress("db.internal", new byte[] {10, 0, 0, 1})
      };

      assertThat(runtime.afterDnsAnswer("db.internal", real, null))
          .extracting(InetAddress::getHostAddress)
          .containsExactly("10.9.8.7");
      assertThat(runtime.afterDnsAnswer("cache.internal", real, null)).isNull();
    }
  }
}
