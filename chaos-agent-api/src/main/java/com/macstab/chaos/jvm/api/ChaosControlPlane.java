package com.macstab.chaos.jvm.api;

import org.jspecify.annotations.Nullable;

/**
 * Primary entry point to the chaos agent runtime.
 *
 * <p>A single instance is installed per JVM when the agent attaches, accessible via {@code
 * ChaosPlatform.current()} (or {@code ChaosPlatform.installed()}, empty while no agent is
 * installed) or installed in test code via {@code ChaosPlatform.installLocally()}.
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
   * <p>The exceptions have no common root besides {@link RuntimeException}: catch the ones below,
   * or {@link RuntimeException}. Every failure is recorded once in the diagnostics and published as
   * a {@code FAILED} event before it is thrown.
   *
   * @param scenario the scenario to activate; must have {@link ChaosScenario.ScenarioScope#JVM}
   *     scope
   * @return a handle for lifecycle control; close it to stop the scenario
   * @throws ChaosValidationException if the selector ↔ effect combination is invalid
   * @throws ChaosUnsupportedFeatureException if the scenario requires a JVM feature not available
   *     at runtime
   * @throws ChaosActivationConflictException if a scenario with the same ID is still registered or
   *     active (a {@link ChaosActivationException})
   * @throws ChaosActivationException if the scenario is {@code SESSION}-scoped
   * @throws ChaosInstrumentationException if the instrumentation the scenario needs cannot be woven
   *     (a {@link ChaosActivationException})
   * @throws IllegalStateException if the control plane is closed
   */
  ChaosActivationHandle activate(ChaosScenario scenario);

  /**
   * Activates all scenarios in a {@link ChaosPlan} at JVM scope.
   *
   * <p>All scenarios activate atomically: either all succeed or the plan activation fails. The
   * returned handle controls all scenarios in the plan as a group. Every scenario is validated and
   * every hook the plan needs is woven before any scenario is registered.
   *
   * <p>The exceptions have no common root besides {@link RuntimeException}: catch the ones below,
   * or {@link RuntimeException}. Every failure is recorded once in the diagnostics and published as
   * a {@code FAILED} event before it is thrown.
   *
   * @param plan all scenarios must have {@link ChaosScenario.ScenarioScope#JVM} scope
   * @return a composite handle that stops all plan scenarios when closed
   * @throws ChaosValidationException if a scenario fails validation
   * @throws ChaosUnsupportedFeatureException if a scenario requires more than the runtime provides
   * @throws ChaosActivationConflictException if a scenario's ID is still registered or active
   * @throws ChaosActivationException if a scenario in the plan is not JVM-scoped
   * @throws ChaosInstrumentationException if the instrumentation the plan needs cannot be woven
   * @throws IllegalStateException if the control plane is closed
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
   * com.macstab.chaos.jvm:type=ChaosDiagnostics}.
   *
   * @return the diagnostics view for this control plane
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
   * Removes a listener registered with {@link #addEventListener}; a listener registered more than
   * once is removed once. Removing an {@link ChaosEventListener#async asynchronous} listener stops
   * its delivery thread; events still queued for it are discarded.
   *
   * @param listener the listener to remove, as it was registered
   * @return {@code true} if it was registered
   */
  boolean removeEventListener(ChaosEventListener listener);

  /**
   * Stops and removes every scenario, those of open sessions included, cleans up stressor resources
   * and closes the listeners that are {@link AutoCloseable}.
   *
   * <p>After this call the control plane is inert; all selector evaluations return no-match. An
   * open session stays open but activates nothing more (its {@code activate} fails with {@link
   * IllegalStateException}); its own {@link ChaosSession#close()} still ends its binding of the
   * thread that opened it. Calling {@code close()} more than once is safe.
   */
  @Override
  void close();

  /**
   * Binds the calling thread to the open session with {@code sessionId}, as {@link
   * ChaosSession#bind()} would: how a server joins the session a request came from ({@link
   * ChaosSession#HEADER}). The id decides for the binding's lifetime: an unknown or closed id runs
   * the thread outside any session, even one it inherited (a pooled server thread started while a
   * test's session was bound).
   *
   * @param sessionId the session id, e.g. the value of the {@link ChaosSession#HEADER} header;
   *     {@code null} (no header) also runs the thread outside any session
   * @return the binding to close when the work is done
   */
  ChaosSession.ScopeBinding bindSession(@Nullable String sessionId);
}
