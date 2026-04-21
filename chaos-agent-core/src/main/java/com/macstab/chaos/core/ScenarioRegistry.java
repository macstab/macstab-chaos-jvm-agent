package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosDiagnostics;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Thread-safe registry of all active {@link ScenarioController}s.
 *
 * <p>Scenarios are registered by {@code ChaosRuntime} (keyed by their string ID), evaluated in
 * parallel on the hot path via {@link #match(InvocationContext)}, and unregistered when the
 * corresponding {@link com.macstab.chaos.api.ChaosActivationHandle} is destroyed.
 *
 * <h2>Match ordering</h2>
 *
 * <p>{@link #match(InvocationContext)} returns contributions sorted by descending precedence and,
 * within the same precedence, by ascending scenario ID. This ordering is stable and deterministic,
 * so tests can rely on it to predict which effect wins when multiple scenarios match the same
 * invocation.
 *
 * <h2>Failure tracking</h2>
 *
 * <p>When a scenario's activation fails (e.g. due to a compatibility error), the failure is
 * recorded in an internal {@link java.util.concurrent.ConcurrentLinkedQueue} and surfaced via the
 * {@link ChaosDiagnostics} interface.
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods are thread-safe. The internal map is a {@link
 * java.util.concurrent.ConcurrentHashMap}; the failure queue is a {@link
 * java.util.concurrent.ConcurrentLinkedQueue}.
 */
final class ScenarioRegistry implements ChaosDiagnostics {
  private static final Comparator<ScenarioContribution> CONTRIBUTION_ORDER =
      Comparator.comparingInt((ScenarioContribution c) -> c.scenario().precedence())
          .reversed()
          .thenComparing(c -> c.scenario().id());

  private final ConcurrentHashMap<String, ScenarioController> controllers =
      new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<ActivationFailure> failures = new ConcurrentLinkedQueue<>();
  private final Supplier<Map<String, String>> runtimeDetailsSupplier;
  private final Clock clock;

  /**
   * Creates a new registry.
   *
   * @param clock clock used to timestamp {@link ChaosDiagnostics.Snapshot} instances; must not be
   *     {@code null}
   * @param runtimeDetailsSupplier supplier of key/value runtime metadata included in each snapshot
   *     (e.g. JVM version, agent version); must not be {@code null}
   */
  ScenarioRegistry(final Clock clock, final Supplier<Map<String, String>> runtimeDetailsSupplier) {
    this.clock = clock;
    this.runtimeDetailsSupplier = runtimeDetailsSupplier;
  }

  /**
   * Adds {@code controller} to the registry, keyed by {@link ScenarioController#key()}.
   *
   * @param controller the controller to register; must not be {@code null}
   * @throws IllegalStateException if a controller with the same key is already registered
   */
  void register(final ScenarioController controller) {
    if (controllers.putIfAbsent(controller.key(), controller) != null) {
      throw new IllegalStateException("scenario key already active: " + controller.key());
    }
  }

  /**
   * Removes {@code controller} from the registry.
   *
   * <p>The removal is conditional: {@code controller} is only removed if the current mapping for
   * its key is this exact instance (identity equality via {@link ConcurrentHashMap#remove(Object,
   * Object)}). A no-op if the controller is not currently registered.
   *
   * @param controller the controller to deregister; must not be {@code null}
   */
  void unregister(final ScenarioController controller) {
    controllers.remove(controller.key(), controller);
  }

  /**
   * Records an activation failure so that it is visible in subsequent {@link #snapshot()} calls.
   *
   * @param scenarioId the ID of the scenario whose activation failed
   * @param category the category of the failure (e.g. compatibility error, resource exhaustion)
   * @param message a human-readable description of what went wrong
   */
  void recordFailure(
      final String scenarioId, final FailureCategory category, final String message) {
    failures.add(new ActivationFailure(scenarioId, category, message));
  }

  /**
   * Evaluates all registered controllers against {@code context} and returns the matching
   * contributions in deterministic order.
   *
   * <p>Each registered {@link ScenarioController} is asked to evaluate the context. Controllers
   * that return {@code null} (no match) are excluded. Survivors are sorted by:
   *
   * <ol>
   *   <li>Descending {@link com.macstab.chaos.api.ChaosScenario#precedence()} — higher precedence
   *       wins.
   *   <li>Ascending {@link com.macstab.chaos.api.ChaosScenario#id()} — tie-breaks deterministically
   *       within the same precedence level.
   * </ol>
   *
   * <p>This method is on the hot path and executes on the application thread that triggered the
   * instrumentation point. Implementations should remain allocation-light; the returned list is an
   * unmodifiable view.
   *
   * @param context the invocation context captured at the instrumentation point; must not be {@code
   *     null}
   * @return an unmodifiable, ordered list of contributions from matching scenarios; empty if no
   *     scenario matches
   */
  List<ScenarioContribution> match(final InvocationContext context) {
    if (controllers.isEmpty()) {
      return List.of();
    }
    ArrayList<ScenarioContribution> results = null;
    for (final ScenarioController controller : controllers.values()) {
      final ScenarioContribution contribution = controller.evaluate(context);
      if (contribution == null) {
        continue;
      }
      if (results == null) {
        results = new ArrayList<>(4);
      }
      results.add(contribution);
    }
    if (results == null) {
      return List.of();
    }
    if (results.size() > 1) {
      results.sort(CONTRIBUTION_ORDER);
    }
    return Collections.unmodifiableList(results);
  }

  /**
   * Returns an unmodifiable snapshot of all currently registered controllers.
   *
   * <p>The returned list reflects the registry state at the moment of the call; subsequent
   * registrations or unregistrations are not reflected.
   *
   * @return an unmodifiable list of active {@link ScenarioController}s; never {@code null}
   */
  List<ScenarioController> controllers() {
    return List.copyOf(controllers.values());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Captures the current state of every registered controller as a {@link ScenarioReport}, sorts
   * them by ID then scope key for a stable ordering, and bundles them together with the accumulated
   * activation failures and the runtime details from the supplier provided at construction time.
   * The {@link Snapshot#capturedAt()} timestamp is derived from the {@link Clock} supplied at
   * construction time.
   */
  @Override
  public Snapshot snapshot() {
    final List<ScenarioReport> reports =
        controllers.values().stream()
            .map(ScenarioController::snapshot)
            .sorted(
                Comparator.comparing(ScenarioReport::id).thenComparing(ScenarioReport::scopeKey))
            .toList();
    return new Snapshot(
        Instant.ofEpochMilli(clock.millis()),
        reports,
        List.copyOf(failures),
        runtimeDetailsSupplier.get());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Scans all currently registered controllers for one whose snapshot ID equals {@code
   * scenarioId}. Returns the first match, or {@link Optional#empty()} if none is found. Note that a
   * single logical scenario may be active in multiple scopes (e.g. per-session), each backed by its
   * own controller; this method returns the first one encountered.
   *
   * @param scenarioId the scenario ID to look up; must not be {@code null}
   * @return an {@link Optional} containing the matching report, or empty if no active controller
   *     has that ID
   */
  @Override
  public Optional<ScenarioReport> scenario(final String scenarioId) {
    return controllers.values().stream()
        .map(ScenarioController::snapshot)
        .filter(report -> report.id().equals(scenarioId))
        .findFirst();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Produces a plain-text multi-line dump suitable for log files or console output. The format
   * is intentionally human-readable and not guaranteed to be stable across releases. Each line is
   * terminated with {@link System#lineSeparator()}.
   *
   * <p>Output structure:
   *
   * <pre>
   * macstab-chaos diagnostics
   * capturedAt=&lt;ISO instant&gt;
   * &lt;runtimeDetail key&gt;=&lt;value&gt;
   * ...
   * &lt;scenarioId&gt; state=&lt;state&gt; scope=&lt;scopeKey&gt; matched=&lt;n&gt; applied=&lt;n&gt; reason=&lt;reason&gt;
   * ...
   * </pre>
   *
   * @return a non-{@code null} human-readable diagnostic string
   */
  @Override
  public String debugDump() {
    final Snapshot snapshot = snapshot();
    final StringBuilder builder = new StringBuilder();
    builder.append("macstab-chaos diagnostics").append(System.lineSeparator());
    builder.append("capturedAt=").append(snapshot.capturedAt()).append(System.lineSeparator());
    snapshot
        .runtimeDetails()
        .forEach(
            (key, value) ->
                builder.append(key).append('=').append(value).append(System.lineSeparator()));
    for (final ScenarioReport report : snapshot.scenarios()) {
      builder
          .append(report.id())
          .append(" state=")
          .append(report.state())
          .append(" scope=")
          .append(report.scopeKey())
          .append(" matched=")
          .append(report.matchedCount())
          .append(" applied=")
          .append(report.appliedCount())
          .append(" reason=")
          .append(report.reason())
          .append(System.lineSeparator());
    }
    return builder.toString();
  }
}
