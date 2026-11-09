package com.macstab.chaos.jvm.bootstrap;

/**
 * MXBean interface for the chaos agent diagnostics, registered with the platform MBean server under
 * the object name {@code com.macstab.chaos.jvm:type=ChaosDiagnostics}.
 *
 * <p>This interface follows the JMX MXBean convention ({@code javax.management.MXBean}) and exposes
 * operations that operators can invoke from any JMX client (JConsole, JMC, the {@code jmxterm} CLI,
 * or management tooling) to inspect the agent without attaching a debugger and, when enabled, to
 * activate and stop plans.
 *
 * <p>The MBean is registered automatically during agent startup ({@code premain} defers it until
 * the application has initialised {@code java.util.logging}). If registration fails (e.g., because
 * another MBean with the same name already exists), the failure is logged to {@code System.err} and
 * the agent continues without JMX support.
 *
 * <p><b>Available operations:</b>
 *
 * <table border="1">
 *   <caption>JMX operations exposed by this MXBean</caption>
 *   <tr><th>Operation</th><th>Return type</th><th>Description</th></tr>
 *   <tr><td>{@link #debugDump()}</td><td>{@code String}</td>
 *       <td>Returns a multi-line human-readable snapshot of the runtime details, all registered
 *           chaos scenarios with their state and counts, and the recent activation failures.</td></tr>
 *   <tr><td>{@link #listScenarios()}</td><td>{@code String[]}</td>
 *       <td>One line per registered scenario.</td></tr>
 *   <tr><td>{@link #isControlEnabled()} (attribute {@code ControlEnabled})</td>
 *       <td>{@code boolean}</td><td>Whether the control operations below are allowed.</td></tr>
 *   <tr><td>{@link #activatePlan(String)}</td><td>{@code String}</td>
 *       <td>Activates a plan given as JSON; control operation.</td></tr>
 *   <tr><td>{@link #stopScenario(String)}</td><td>{@code boolean}</td>
 *       <td>Stops a plan activated through {@code activatePlan}; control operation.</td></tr>
 *   <tr><td>{@link #stopAll()}</td><td>{@code int}</td>
 *       <td>Stops every plan activated through {@code activatePlan}; control operation.</td></tr>
 * </table>
 */
public interface ChaosDiagnosticsMXBean {

  /**
   * Returns a multi-line human-readable diagnostics dump of the current chaos runtime state.
   *
   * <p>The dump includes the runtime details; for each registered scenario its ID, scope, current
   * {@link com.macstab.chaos.jvm.api.ChaosDiagnostics.ScenarioState}, matched and applied counts
   * and reason; and the recent activation failures. Scenarios of a plan with {@code
   * jmxEnabled=false} are left out. The exact format is not stable across agent versions and is
   * intended for human consumption only.
   *
   * @return a non-null, non-empty diagnostics string; formatting may include newlines
   */
  String debugDump();

  /**
   * Lists every registered scenario as {@code <id> state=<state> matched=<n> applied=<n>}.
   *
   * @return one line per scenario
   */
  String[] listScenarios();

  /**
   * Whether the control operations ({@link #activatePlan}, {@link #stopScenario}, {@link #stopAll})
   * are enabled: agent argument {@code jmxControl=true}, system property {@code
   * macstab.chaos.jmx.control=true} or environment variable {@code MACSTAB_CHAOS_JMX_CONTROL=true}.
   * Off by default — anyone who can reach the MBean server could otherwise inject faults into the
   * JVM.
   *
   * @return {@code true} when control operations are allowed
   */
  boolean isControlEnabled();

  /**
   * Activates a plan given as JSON (the startup-config format).
   *
   * @param planJson the plan
   * @return the ids of the scenarios activated, comma-separated
   * @throws IllegalStateException if control operations are disabled
   * @throws IllegalArgumentException if the plan is invalid
   */
  String activatePlan(String planJson);

  /**
   * Stops a scenario that was activated through {@link #activatePlan}, together with the rest of
   * its plan: a plan is activated and stopped as a unit.
   *
   * @param scenarioId the scenario id
   * @return {@code true} if a plan holding that scenario was stopped
   * @throws IllegalStateException if control operations are disabled
   */
  boolean stopScenario(String scenarioId);

  /**
   * Stops every plan activated through {@link #activatePlan}.
   *
   * @return how many plans were stopped
   * @throws IllegalStateException if control operations are disabled
   */
  int stopAll();
}
