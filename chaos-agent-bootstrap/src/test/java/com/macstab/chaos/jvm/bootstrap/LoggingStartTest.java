package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.LogManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LoggingStart")
class LoggingStartTest {

  @Test
  @DisplayName("the deferred work runs once the application has initialised JUL")
  void runsWhenInitialised() throws Exception {
    final AtomicBoolean initialised = new AtomicBoolean();
    final CountDownLatch ran = new CountDownLatch(1);

    final Thread waiter =
        LoggingStart.runAfter(initialised::get, Duration.ofMinutes(1), ran::countDown);
    assertThat(waiter.isDaemon()).isTrue();
    assertThat(ran.await(100, TimeUnit.MILLISECONDS)).as("not before").isFalse();

    initialised.set(true);

    assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
    waiter.join(10_000);
  }

  @Test
  @DisplayName("the deferred work runs after the limit when JUL is never initialised")
  void runsAfterTheLimit() throws Exception {
    final AtomicLong ranAfter = new AtomicLong(-1);
    final long start = System.nanoTime();

    final Thread waiter =
        LoggingStart.runAfter(
            () -> false, Duration.ofMillis(200), () -> ranAfter.set(System.nanoTime() - start));
    waiter.join(10_000);

    assertThat(ranAfter.get()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(200));
  }

  @Test
  @DisplayName("the deferred work runs when the waiting thread is interrupted")
  void runsWhenInterrupted() throws Exception {
    final CountDownLatch ran = new CountDownLatch(1);

    final Thread waiter = LoggingStart.runAfter(() -> false, Duration.ofMinutes(1), ran::countDown);
    waiter.interrupt();

    assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName(
      "the waiting thread keeps no context class loader and no inheritable thread-local of the"
          + " thread that starts it")
  void waiterKeepsNothingOfItsStarter() throws Exception {
    final InheritableThreadLocal<String> request = new InheritableThreadLocal<>();
    final ClassLoader starterLoader = new java.net.URLClassLoader(new java.net.URL[0]);
    final java.util.concurrent.atomic.AtomicReference<Object> seen =
        new java.util.concurrent.atomic.AtomicReference<>();
    final Thread self = Thread.currentThread();
    final ClassLoader previous = self.getContextClassLoader();
    self.setContextClassLoader(starterLoader);
    request.set("request-context");
    final Thread waiter;
    try {
      waiter =
          LoggingStart.runAfter(
              () -> true,
              Duration.ofMinutes(1),
              () ->
                  seen.set(
                      java.util.Arrays.asList(
                          Thread.currentThread().getContextClassLoader(), request.get())));
    } finally {
      request.remove();
      self.setContextClassLoader(previous);
    }
    waiter.join(10_000);

    assertThat(seen.get()).isEqualTo(java.util.Arrays.asList(null, null));
  }

  @Test
  @DisplayName("the held lines' shutdown hook keeps no context class loader of its maker")
  void logReleaseHookKeepsNoLoader() {
    final ClassLoader starterLoader = new java.net.URLClassLoader(new java.net.URL[0]);
    final Thread self = Thread.currentThread();
    final ClassLoader previous = self.getContextClassLoader();
    self.setContextClassLoader(starterLoader);
    try {
      final Thread hook = ChaosAgentBootstrap.logReleaseHook();

      assertThat(hook.getContextClassLoader()).isNull();
      assertThat(hook.getName()).isEqualTo("macstab-chaos-log-release");
    } finally {
      self.setContextClassLoader(previous);
    }
  }

  @Test
  @DisplayName("without the jdk.internal.misc open the state is unreadable and the limit decides")
  void unreadableStateWaitsForTheLimit() {
    // The agent opens the package to itself (LogManagerIntegrationTest reads the state for real);
    // this test JVM does not.
    assumeFalse(
        Object.class.getModule().isOpen("jdk.internal.misc", LoggingStart.class.getModule()));
    LogManager.getLogManager();

    assertThat(LoggingStart.logManagerInitialised().getAsBoolean()).isFalse();
  }
}
