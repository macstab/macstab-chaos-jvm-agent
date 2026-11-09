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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Package-private JMX adapter that bridges the {@link ChaosDiagnosticsMXBean} interface to the
 * internal {@link ChaosDiagnostics} API.
 *
 * <p>Instances are constructed by {@link ChaosAgentBootstrap} and registered with the platform
 * MBean server. This class is not part of the public API and must not be instantiated or referenced
 * directly by callers outside this package.
 *
 * <p>The adapter is intentionally thin: it delegates every operation to the {@link
 * ChaosDiagnostics} object; its one piece of state is the handles of the plans activated through
 * it, so they can be stopped through it.
 */
final class ChaosDiagnosticsMBean implements ChaosDiagnosticsMXBean {
  private static final Logger LOGGER = Logger.getLogger(ChaosDiagnosticsMBean.class.getName());

  private final ChaosDiagnostics diagnostics;
  private final ChaosControlPlane controlPlane;
  private final boolean controlEnabled;
  private final java.util.function.Predicate<ChaosDiagnostics.ScenarioReport> visible;
  private final java.util.function.Function<
          java.util.function.Predicate<ChaosDiagnostics.ScenarioReport>, String>
      dump;

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
    this.visible = report -> runtime.jmxVisible(report.scopeKey(), report.id());
    this.dump = runtime::debugDump;
  }

  @Override
  public String[] listScenarios() {
    return diagnostics.snapshot().scenarios().stream()
        .filter(visible)
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
    pruneFinished();
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
  @SuppressWarnings("ReferenceEquality") // one handle per activation: identity names the plan
  public boolean stopScenario(final String scenarioId) {
    requireControl();
    pruneFinished();
    final ChaosActivationHandle handle = activated.remove(scenarioId);
    if (handle == null) {
      return false;
    }
    // A scenario stops with the plan it was activated in.
    activated.values().removeIf(other -> other == handle);
    handle.close();
    return true;
  }

  @Override
  public int stopAll() {
    requireControl();
    pruneFinished();
    int stopped = 0;
    for (final String id : java.util.List.copyOf(activated.keySet())) {
      if (stopScenario(id)) {
        stopped++;
      }
    }
    return stopped;
  }

  /**
   * Forgets the plans that finished on their own (spent or expired) and closes them, so their
   * controllers leave the registry; they are not counted as stopped.
   */
  @SuppressWarnings("ReferenceEquality") // one handle per activation: identity names the plan
  private void pruneFinished() {
    for (final ChaosActivationHandle handle : java.util.List.copyOf(activated.values())) {
      if (ChaosManagement.finished(handle)
          && activated.values().removeIf(other -> other == handle)) {
        try {
          handle.close();
        } catch (final RuntimeException failure) {
          LOGGER.log(Level.WARNING, failure, () -> "[chaos-agent] could not close a finished plan");
        }
      }
    }
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
}
