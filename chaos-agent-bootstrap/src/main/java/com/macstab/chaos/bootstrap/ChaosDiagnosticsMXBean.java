package com.macstab.chaos.bootstrap;

/**
 * MXBean interface for the chaos agent diagnostics, registered with the platform MBean server under
 * the object name {@code com.macstab.chaos:type=ChaosDiagnostics}.
 *
 * <p>This interface follows the JMX MXBean convention ({@code javax.management.MXBean}) and exposes
 * one operation that operators can invoke from any JMX client (JConsole, JMC, the {@code jmxterm}
 * CLI, or management tooling) to retrieve a human-readable diagnostics snapshot without attaching a
 * debugger.
 *
 * <p>The MBean is registered automatically by {@link ChaosAgentBootstrap#initialize} during agent
 * startup. If registration fails (e.g., because another MBean with the same name already exists),
 * the failure is logged to {@code System.err} and the agent continues without JMX support.
 *
 * <p><b>Available operations:</b>
 *
 * <table border="1">
 *   <caption>JMX operations exposed by this MXBean</caption>
 *   <tr><th>Operation</th><th>Return type</th><th>Description</th></tr>
 *   <tr><td>{@link #debugDump()}</td><td>{@code String}</td>
 *       <td>Returns a multi-line human-readable snapshot of all registered chaos scenarios,
 *           their current state, applied-count, and any stressor-specific metrics.</td></tr>
 * </table>
 */
public interface ChaosDiagnosticsMXBean {

  /**
   * Returns a multi-line human-readable diagnostics dump of the current chaos runtime state.
   *
   * <p>The dump includes, for each registered scenario: its ID, scope, current {@link
   * com.macstab.chaos.api.ChaosDiagnostics.ScenarioState}, applied-effect count, and any
   * stressor-specific resource metrics. The exact format is not stable across agent versions and is
   * intended for human consumption only.
   *
   * @return a non-null, non-empty diagnostics string; formatting may include newlines
   */
  String debugDump();
}
