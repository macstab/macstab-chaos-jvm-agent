package com.macstab.chaos.bootstrap;

import com.macstab.chaos.api.ChaosDiagnostics;

/**
 * Package-private JMX adapter that bridges the {@link ChaosDiagnosticsMXBean} interface to the
 * internal {@link ChaosDiagnostics} API.
 *
 * <p>Instances are constructed by {@link ChaosAgentBootstrap} and registered with the platform
 * MBean server. This class is not part of the public API and must not be instantiated or referenced
 * directly by callers outside this package.
 *
 * <p>The adapter is intentionally thin: it delegates every operation to the {@link
 * ChaosDiagnostics} object and adds no state of its own.
 */
final class ChaosDiagnosticsMBean implements ChaosDiagnosticsMXBean {
  private final ChaosDiagnostics diagnostics;

  /**
   * Constructs the MBean adapter backed by the given diagnostics source.
   *
   * @param diagnostics the runtime diagnostics provider; must not be null
   */
  ChaosDiagnosticsMBean(final ChaosDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegates directly to {@link ChaosDiagnostics#debugDump()}.
   */
  @Override
  public String debugDump() {
    return diagnostics.debugDump();
  }
}
