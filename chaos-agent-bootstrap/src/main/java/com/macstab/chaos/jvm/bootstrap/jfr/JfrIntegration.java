package com.macstab.chaos.jvm.bootstrap.jfr;

import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.core.ChaosRuntime;

/**
 * Entry point for the optional JFR integration layer.
 *
 * <p>Calling {@link #installIfAvailable(ChaosRuntime)} is safe on any JVM. When JFR is not
 * accessible (stripped JRE, disabled module), the method returns immediately without registering
 * anything. When JFR is available, a {@link JfrChaosEventSink} is instantiated and registered as a
 * lifecycle {@link ChaosEventListener} ({@link ChaosRuntime#addLifecycleListener}): it wires chaos
 * events to Flight Recorder, and, since it never reads {@code SKIPPED}, a skipped call builds no
 * event for it. It listens whether or not a recording runs, so the snapshot's total counts every
 * application since the start; {@link ChaosRuntime#close()} closes it.
 *
 * <p>This class deliberately avoids loading {@code jdk.jfr.*} classes at its own load time — those
 * are only referenced inside {@code installJfr}, which is only called after the probe succeeds.
 */
@Internal
public final class JfrIntegration {
  private JfrIntegration() {}

  /**
   * Probes JFR availability and, if present, installs a {@link JfrChaosEventSink} into {@code
   * runtime}. The sink registers a periodic stressor snapshot hook and bridges lifecycle/applied
   * events to JFR.
   *
   * <p>This method is idempotent across multiple calls on the same runtime only in the sense that
   * each call will install an additional sink. Callers (e.g., {@code ChaosAgentBootstrap}) must
   * ensure it is called exactly once per runtime lifetime.
   *
   * @param runtime the runtime into which the JFR sink is installed
   */
  public static void installIfAvailable(final ChaosRuntime runtime) {
    if (!JfrAvailability.probe()) {
      return;
    }
    installJfr(runtime);
  }

  /**
   * Separated from {@link #installIfAvailable} so that {@code jdk.jfr.*} references in this method
   * body are only class-loaded after the probe confirms JFR is accessible. The JVM loads classes
   * lazily — this method body is never executed (and therefore never triggers JFR class loading)
   * unless the probe returned {@code true}.
   */
  private static void installJfr(final ChaosRuntime runtime) {
    final JfrChaosEventSink sink = new JfrChaosEventSink(runtime.diagnostics());
    runtime.addLifecycleListener(sink);
  }
}
