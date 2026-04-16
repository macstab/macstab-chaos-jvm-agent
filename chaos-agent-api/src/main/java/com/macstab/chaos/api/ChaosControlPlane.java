package com.macstab.chaos.api;

/**
 * Primary entry point to the chaos agent runtime.
 *
 * <p>A single instance is installed per JVM when the agent attaches, accessible via {@code
 * ChaosAgentBootstrap.current()} or injected in test code via {@code
 * ChaosAgentBootstrap.installForLocalTests()}.
 *
 * <p>Core operations:
 *
 * <ul>
 *   <li>{@link #activate(ChaosScenario)} / {@link #activate(ChaosPlan)} — inject chaos
 *   <li>{@link #openSession(String)} — create an isolated session scope for a single test
 *   <li>{@link #diagnostics()} — inspect scenario states and counters
 *   <li>{@link #addEventListener(ChaosEventListener)} — observe chaos events in real time
 *   <li>{@link #close()} — stop all active scenarios and clean up resources
 * </ul>
 *
 * <p><b>Thread safety:</b> all methods are thread-safe.
 */
public interface ChaosControlPlane extends AutoCloseable {

  /**
   * Activates a single chaos scenario at JVM scope.
   *
   * <p>The scenario is validated against selector/effect compatibility and registered immediately.
   * Scenarios with {@link ActivationPolicy.StartMode#AUTOMATIC} begin intercepting operations
   * instantly. Manual-start scenarios require a subsequent {@link ChaosActivationHandle#start()}
   * call.
   *
   * @param scenario the scenario to activate; must have {@link ChaosScenario.ScenarioScope#JVM}
   *     scope
   * @return a handle for lifecycle control; close it to stop the scenario
   * @throws ChaosValidationException if the selector ↔ effect combination is invalid
   * @throws ChaosUnsupportedFeatureException if the scenario requires a JVM feature not available
   *     at runtime
   * @throws ChaosActivationException if a scenario with the same ID is already active
   */
  ChaosActivationHandle activate(ChaosScenario scenario);

  /**
   * Activates all scenarios in a {@link ChaosPlan} at JVM scope.
   *
   * <p>All scenarios activate atomically: either all succeed or the plan activation fails. The
   * returned handle controls all scenarios in the plan as a group.
   *
   * @param plan all scenarios must have {@link ChaosScenario.ScenarioScope#JVM} scope
   * @return a composite handle that stops all plan scenarios when closed
   * @throws ChaosActivationException if any scenario in the plan is not JVM-scoped, or if any
   *     scenario fails validation
   */
  ChaosActivationHandle activate(ChaosPlan plan);

  /**
   * Opens a named session scope for per-test chaos isolation.
   *
   * <p>Scenarios activated on the returned session affect only threads explicitly bound to it via
   * {@link ChaosSession#bind()}. Multiple sessions may be active simultaneously without interfering
   * with each other.
   *
   * <p>Always close the session when the test completes to stop all session scenarios and release
   * the session scope:
   *
   * <pre>{@code
   * try (ChaosSession session = controlPlane.openSession("my-test")) {
   *     session.activate(scenario);
   *     // ...
   * }
   * }</pre>
   *
   * @param displayName human-readable name for this session; appears in diagnostics and logs
   * @return a new session; caller is responsible for closing it
   */
  ChaosSession openSession(String displayName);

  /**
   * Returns the read-only diagnostics view for this control plane instance.
   *
   * <p>Use to inspect scenario states, application counters, failure records, and runtime
   * capabilities. The same view is exposed over JMX at {@code
   * com.macstab.chaos:type=ChaosDiagnostics}.
   */
  ChaosDiagnostics diagnostics();

  /**
   * Registers a listener that receives all chaos lifecycle and application events.
   *
   * <p>Listeners are invoked synchronously on the thread that triggers the event. Implementations
   * must be non-blocking and must not throw.
   *
   * <p>Common use cases:
   *
   * <ul>
   *   <li>JFR integration — emit JFR events correlated with chaos activity (built in, installed
   *       automatically when {@code jdk.jfr} is available)
   *   <li>Test assertions — verify that specific scenarios fired during a test run
   *   <li>Custom metrics — bridge chaos events to Micrometer, Prometheus, or similar
   * </ul>
   *
   * @param listener a non-null, non-blocking listener; must not throw from {@code onEvent}
   */
  void addEventListener(ChaosEventListener listener);

  /**
   * Stops all active scenarios, closes all open sessions, and cleans up stressor resources.
   *
   * <p>After this call the control plane is inert; all selector evaluations return no-match.
   * Calling {@code close()} more than once is safe.
   */
  @Override
  void close();
}
