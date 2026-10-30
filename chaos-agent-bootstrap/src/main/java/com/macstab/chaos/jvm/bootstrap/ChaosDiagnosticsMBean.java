package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import com.macstab.chaos.jvm.startup.ConfigLoadException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
  private final ChaosControlPlane controlPlane;
  private final boolean controlEnabled;

  /** Handles of scenarios activated through JMX, by scenario id. */
  private final Map<String, ChaosActivationHandle> activated = new ConcurrentHashMap<>();

  /**
   * Constructs the MBean adapter backed by the given diagnostics source, without control.
   *
   * @param diagnostics the runtime diagnostics provider; must not be null
   */
  ChaosDiagnosticsMBean(final ChaosDiagnostics diagnostics) {
    this(diagnostics, null, false);
  }

  /**
   * Constructs the MBean adapter.
   *
   * @param diagnostics the runtime diagnostics provider
   * @param controlPlane the control plane the control operations act on
   * @param controlEnabled whether the control operations are allowed
   */
  ChaosDiagnosticsMBean(
      final ChaosDiagnostics diagnostics,
      final ChaosControlPlane controlPlane,
      final boolean controlEnabled) {
    this.diagnostics = diagnostics;
    this.controlPlane = controlPlane;
    this.controlEnabled = controlEnabled && controlPlane != null;
  }

  @Override
  public String[] listScenarios() {
    return diagnostics.snapshot().scenarios().stream()
        .map(
            report ->
                report.id()
                    + " state="
                    + report.state()
                    + " matched="
                    + report.matchedCount()
                    + " applied="
                    + report.appliedCount())
        .toArray(String[]::new);
  }

  @Override
  public boolean isControlEnabled() {
    return controlEnabled;
  }

  @Override
  public String activatePlan(final String planJson) {
    requireControl();
    final ChaosPlan plan;
    try {
      plan = ChaosPlanMapper.read(planJson);
    } catch (final ConfigLoadException invalid) {
      throw new IllegalArgumentException(invalid.getMessage());
    }
    final java.util.List<String> ids = new java.util.ArrayList<>();
    for (final ChaosScenario scenario : plan.scenarios()) {
      activated.put(scenario.id(), controlPlane.activate(scenario));
      ids.add(scenario.id());
    }
    return String.join(",", ids);
  }

  @Override
  public boolean stopScenario(final String scenarioId) {
    requireControl();
    final ChaosActivationHandle handle = activated.remove(scenarioId);
    if (handle == null) {
      return false;
    }
    handle.close();
    return true;
  }

  @Override
  public int stopAll() {
    requireControl();
    int stopped = 0;
    for (final String id : java.util.List.copyOf(activated.keySet())) {
      if (stopScenario(id)) {
        stopped++;
      }
    }
    return stopped;
  }

  private void requireControl() {
    if (!controlEnabled) {
      throw new IllegalStateException(
          "chaos JMX control operations are disabled; enable them with the agent argument"
              + " jmxControl=true or -Dmacstab.chaos.jmx.control=true");
    }
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
