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
  private final java.util.function.Predicate<String> visible;
  private final java.util.function.Function<java.util.function.Predicate<String>, String> dump;

  /** Handles of scenarios activated through JMX, by scenario id. */
  private final Map<String, ChaosActivationHandle> activated = new ConcurrentHashMap<>();

  /**
   * Constructs the MBean for the agent's runtime: scenarios whose plan set {@code jmxEnabled=false}
   * are left out of {@link #listScenarios()} and {@link #debugDump()}.
   *
   * @param runtime the runtime
   * @param controlEnabled whether the control operations are allowed
   */
  ChaosDiagnosticsMBean(
      final com.macstab.chaos.jvm.core.ChaosRuntime runtime, final boolean controlEnabled) {
    this.diagnostics = runtime.diagnostics();
    this.controlPlane = runtime;
    this.controlEnabled = controlEnabled;
    this.visible = runtime::jmxVisible;
    this.dump = runtime::debugDump;
  }

  @Override
  public String[] listScenarios() {
    return diagnostics.snapshot().scenarios().stream()
        .filter(report -> visible.test(report.id()))
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
    final ChaosControlPlane control = requireControl();
    final ChaosPlan plan;
    try {
      plan = ChaosPlanMapper.read(planJson);
    } catch (final ConfigLoadException invalid) {
      throw new IllegalArgumentException(invalid.getMessage());
    }
    // As one plan: validated whole before anything starts, with its observability settings, and
    // rolled back as a unit. Scenario by scenario, a failing third scenario would leave the first
    // two active and a session-scoped one would run JVM-wide.
    final ChaosActivationHandle planHandle;
    try {
      planHandle = control.activate(plan);
    } catch (final RuntimeException rejected) {
      throw new IllegalArgumentException(rejected.getMessage());
    }
    final java.util.List<String> ids = new java.util.ArrayList<>();
    for (final ChaosScenario scenario : plan.scenarios()) {
      activated.put(scenario.id(), planHandle);
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
    // A scenario stops with the plan it was activated in.
    activated.values().removeIf(other -> isSameHandle(other, handle));
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

  /** Returns the control plane, or fails when the control operations are disabled. */
  private ChaosControlPlane requireControl() {
    if (!controlEnabled) {
      throw new IllegalStateException(
          "chaos JMX control operations are disabled; enable them with the agent argument"
              + " jmxControl=true or -Dmacstab.chaos.jmx.control=true");
    }
    return controlPlane;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Leaves out scenarios whose plan set {@code jmxEnabled=false}.
   */
  @Override
  public String debugDump() {
    return dump.apply(visible);
  }

  /** Handles are compared by identity: one handle per activation. */
  @SuppressWarnings("ReferenceEquality")
  private static boolean isSameHandle(final Object a, final Object b) {
    return a == b;
  }
}
