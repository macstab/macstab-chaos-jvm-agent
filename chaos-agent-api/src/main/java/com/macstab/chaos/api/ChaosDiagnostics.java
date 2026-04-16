package com.macstab.chaos.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only runtime view of all registered chaos scenarios and agent health.
 *
 * <p>Obtained via {@link ChaosControlPlane#diagnostics()}. The same instance is also exposed over
 * JMX at {@code com.macstab.chaos:type=ChaosDiagnostics} and drives JFR periodic snapshot events
 * (registered by the bootstrap module when JFR is available).
 *
 * <p>All methods return immutable point-in-time views. Counters continue to increment after the
 * snapshot is taken.
 *
 * <p><b>Primary operations:</b>
 *
 * <ul>
 *   <li>{@link #snapshot()} — full picture: all scenarios, failures, runtime capabilities
 *   <li>{@link #scenario(String)} — single-scenario lookup by ID
 *   <li>{@link #debugDump()} — human-readable text suitable for logging or test failure messages
 * </ul>
 */
public interface ChaosDiagnostics {

  /**
   * Returns an immutable snapshot of the full agent state at this instant.
   *
   * <p>The snapshot includes:
   *
   * <ul>
   *   <li>one {@link ScenarioReport} per registered scenario with current counters and state
   *   <li>all {@link ActivationFailure} records accumulated since agent startup
   *   <li>runtime capability details: JDK version, virtual thread support, JFR availability
   * </ul>
   */
  Snapshot snapshot();

  /**
   * Returns the current report for the scenario with the given ID, or empty if no scenario with
   * that ID is registered.
   *
   * @param scenarioId the scenario ID as provided to {@link ChaosScenario.Builder#build()}
   */
  Optional<ScenarioReport> scenario(String scenarioId);

  /**
   * Returns a multi-line human-readable diagnostics dump. Includes all scenario states, counters,
   * failure records, and runtime capability details.
   *
   * <p>Use in test failure messages or agent startup logging ({@link
   * ChaosPlan.Observability#debugDumpOnStart()}).
   */
  String debugDump();

  /** Lifecycle state of a registered chaos scenario. */
  enum ScenarioState {

    /**
     * The scenario was registered but has not been started yet. Matches no operations. Occurs when
     * using {@link ActivationPolicy.StartMode#MANUAL} before {@link ChaosActivationHandle#start()}
     * is called.
     */
    REGISTERED,

    /**
     * The scenario is running and will evaluate its selector against matching operations. Effects
     * fire when selector matches and {@link ActivationPolicy} constraints pass.
     */
    ACTIVE,

    /**
     * The scenario is temporarily inactive. Occurs when {@link ActivationPolicy#activeFor()} has
     * elapsed, {@link ActivationPolicy#maxApplications()} is exhausted, or a manual stop was
     * called. No effects fire while inactive.
     */
    INACTIVE,

    /**
     * The scenario was explicitly stopped via {@link ChaosActivationHandle#stop()} or {@link
     * ChaosActivationHandle#close()}. Terminal state — cannot be restarted.
     */
    STOPPED,

    /**
     * The scenario failed to activate due to a configuration or runtime error. See {@link
     * ActivationFailure} records in the {@link Snapshot} for details.
     */
    FAILED,
  }

  /** Categories of scenario activation failure, used for programmatic error handling. */
  enum FailureCategory {

    /**
     * The scenario configuration violates an API contract (invalid selector ↔ effect pairing,
     * illegal parameter values, etc.). See {@link ChaosValidationException}.
     */
    INVALID_CONFIGURATION,

    /**
     * The scenario requires a runtime feature not available on the current JVM (e.g., virtual
     * threads on JDK 17). See {@link ChaosUnsupportedFeatureException}.
     */
    UNSUPPORTED_RUNTIME,

    /**
     * The ByteBuddy instrumentation required by this selector failed to install. The target class
     * may be sealed, boot-loaded, or otherwise uninstrumentable.
     */
    INSTRUMENTATION_FAILURE,

    /**
     * A scenario with the same activation key is already registered. Duplicate scenario IDs within
     * the same scope are not permitted.
     */
    ACTIVATION_CONFLICT,

    /**
     * An unexpected internal error occurred during activation. File a bug report with the
     * diagnostics dump.
     */
    INTERNAL_ERROR,
  }

  /**
   * Immutable point-in-time snapshot of the full agent state.
   *
   * @param capturedAt when this snapshot was taken
   * @param scenarios per-scenario reports ordered by registration time
   * @param failures all activation failures accumulated since agent startup
   * @param runtimeDetails capability map: {@code jdkFeatureVersion}, {@code
   *     virtualThreadsSupported}, {@code jfrSupported}, {@code currentSessionId}
   */
  record Snapshot(
      Instant capturedAt,
      List<ScenarioReport> scenarios,
      List<ActivationFailure> failures,
      Map<String, String> runtimeDetails) {}

  /**
   * Per-scenario state and counters at snapshot time.
   *
   * @param id scenario ID
   * @param description human-readable description from {@link ChaosScenario#description()}
   * @param scopeKey internal scope key: {@code "jvm"} for JVM-scoped, {@code "session:<id>"} for
   *     session-scoped
   * @param scope the scenario scope
   * @param state current lifecycle state
   * @param matchedCount number of operations that satisfied the selector since the scenario was
   *     started; includes matches skipped by {@link ActivationPolicy} constraints
   * @param appliedCount number of times the effect was actually applied; always {@code <=
   *     matchedCount}
   * @param reason human-readable explanation of the current state (e.g., {@code "max applications
   *     reached"})
   */
  record ScenarioReport(
      String id,
      String description,
      String scopeKey,
      ChaosScenario.ScenarioScope scope,
      ScenarioState state,
      long matchedCount,
      long appliedCount,
      String reason) {}

  /**
   * A record of a failed scenario activation attempt.
   *
   * @param scenarioId the ID of the scenario that failed to activate
   * @param category why the activation failed
   * @param message human-readable detail; suitable for logging or test failure messages
   */
  record ActivationFailure(String scenarioId, FailureCategory category, String message) {}
}
