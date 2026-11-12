package com.macstab.chaos.jvm.micronaut;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A long-lived thread a bean starts while the application context starts (an event loop, a
 * consumer, a worker loop): it records the session it runs in and waits until the context stops.
 * Only a context with {@value #ENABLED} set creates it, since every test context of this package
 * scans the package's beans.
 */
final class ContextWorker {

  /** The property that makes a test context start the worker. */
  static final String ENABLED = "macstab.chaos.test.context-worker";

  final CompletableFuture<Optional<String>> sessionOfTheWorker = new CompletableFuture<>();
  private final Thread thread;

  private ContextWorker() {
    thread =
        new Thread(
            () -> {
              sessionOfTheWorker.complete(currentSessionId());
              try {
                Thread.sleep(Long.MAX_VALUE);
              } catch (final InterruptedException stopped) {
                Thread.currentThread().interrupt();
              }
            },
            "mn-context-worker");
    thread.setDaemon(true);
    thread.start();
  }

  void stop() {
    thread.interrupt();
  }

  /** The session the calling thread runs in, as the diagnostics report it. */
  static Optional<String> currentSessionId() {
    return Optional.ofNullable(
        ChaosPlatform.current().diagnostics().snapshot().runtimeDetails().get("currentSessionId"));
  }

  /** Creates the worker eagerly, while the context starts. */
  @Factory
  @Requires(property = ENABLED, value = "true")
  static class WorkerFactory {
    @Context
    @Bean(preDestroy = "stop")
    ContextWorker contextWorker() {
      return new ContextWorker();
    }
  }
}
