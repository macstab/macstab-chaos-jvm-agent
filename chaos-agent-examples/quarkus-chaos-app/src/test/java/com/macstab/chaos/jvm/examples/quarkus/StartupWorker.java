package com.macstab.chaos.jvm.examples.quarkus;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A long-lived thread a bean starts while the application starts (an event loop, a consumer, a
 * worker loop): it records the session it runs in and waits until the application stops. Only an
 * application whose test profile sets {@value #ENABLED} starts it.
 */
@ApplicationScoped
public class StartupWorker {

  /** The property a test profile sets to make the application start the worker. */
  public static final String ENABLED = "macstab.chaos.test.startup-worker";

  private final CompletableFuture<Optional<String>> sessionOfTheWorker = new CompletableFuture<>();

  @Inject ChaosControlPlane controlPlane;

  @ConfigProperty(name = ENABLED, defaultValue = "false")
  boolean enabled;

  private volatile Thread thread;

  /** Creates the bean; called by CDI. */
  public StartupWorker() {}

  void start(@Observes final StartupEvent event) {
    if (!enabled) {
      return;
    }
    final Thread worker =
        new Thread(
            () -> {
              sessionOfTheWorker.complete(currentSessionId(controlPlane));
              try {
                Thread.sleep(Long.MAX_VALUE);
              } catch (final InterruptedException stopped) {
                Thread.currentThread().interrupt();
              }
            },
            "qk-startup-worker");
    worker.setDaemon(true);
    worker.start();
    thread = worker;
  }

  void stop(@Observes final ShutdownEvent event) {
    final Thread worker = thread;
    if (worker != null) {
      worker.interrupt();
    }
  }

  /**
   * Returns the session the worker thread runs in.
   *
   * @return the worker thread's session id, empty outside any session
   * @throws Exception if the worker did not record its session in time
   */
  public Optional<String> sessionOfTheWorker() throws Exception {
    return sessionOfTheWorker.get(10, TimeUnit.SECONDS);
  }

  /**
   * Returns the session the calling thread runs in, as the diagnostics report it.
   *
   * @param controlPlane the control plane whose diagnostics are read
   * @return the calling thread's session id, empty outside any session
   */
  public static Optional<String> currentSessionId(final ChaosControlPlane controlPlane) {
    return Optional.ofNullable(
        controlPlane.diagnostics().snapshot().runtimeDetails().get("currentSessionId"));
  }
}
