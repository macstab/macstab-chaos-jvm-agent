package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
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
 * corresponding {@link com.macstab.chaos.jvm.api.ChaosActivationHandle} is destroyed.
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
 *
 * <h2>Operation index</h2>
 *
 * <p>Every selector matches a fixed, non-empty set of {@link OperationType}s ({@link
 * SelectorOperations#of}). The registry keeps an immutable index from operation type to the
 * controllers whose selector names it, already in match order, and replaces it whenever a
 * controller is registered or unregistered. {@link #match(InvocationContext)} therefore only
 * evaluates scenarios that can match the intercepted operation, instead of every registered
 * scenario, and never sorts.
 */
final class ScenarioRegistry implements ChaosDiagnostics {
  private static final Comparator<ScenarioController> MATCH_ORDER =
      Comparator.comparingInt((ScenarioController c) -> c.scenario().precedence())
          .reversed()
          .thenComparing(c -> c.scenario().id());

  private static final ScenarioController[] NO_CONTROLLERS = new ScenarioController[0];

  private final ConcurrentHashMap<String, ScenarioController> controllers =
      new ConcurrentHashMap<>();
  private final Object indexLock = new Object();
  private volatile Map<OperationType, ScenarioController[]> index =
      new EnumMap<>(OperationType.class);
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
   * <p>A controller registered under the same key that has finished — {@link
   * ChaosDiagnostics.ScenarioState#STOPPED} or {@link ChaosDiagnostics.ScenarioState#INACTIVE}
   * (expired or out of applications) — is replaced and returned so the caller can destroy it. A
   * stopped scenario can never run again ({@code start()} is a no-op after {@code stop()}), so
   * keeping it registered only blocked re-activating the same id: the next test of a suite, a
   * re-posted plan or a reloaded config failed with "already active". The check and the replacement
   * are one atomic step.
   *
   * @param controller the controller to register; must not be {@code null}
   * @return the finished controller that was replaced, or {@code null}
   * @throws ChaosActivationException if a controller with the same key is registered and still
   *     {@code REGISTERED} or {@code ACTIVE}
   */
  ScenarioController register(final ScenarioController controller) {
    final ScenarioController[] replaced = new ScenarioController[1];
    controllers.compute(
        controller.key(),
        (key, existing) -> {
          if (existing == null) {
            return controller;
          }
          if (isFinished(existing)) {
            replaced[0] = existing;
            return controller;
          }
          throw new ChaosActivationException("scenario key already active: " + key);
        });
    rebuildIndex();
    return replaced[0];
  }

  private static boolean isFinished(final ScenarioController controller) {
    final ChaosDiagnostics.ScenarioState state = controller.state();
    return state == ChaosDiagnostics.ScenarioState.STOPPED
        || state == ChaosDiagnostics.ScenarioState.INACTIVE;
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
    if (controllers.remove(controller.key(), controller)) {
      rebuildIndex();
    }
  }

  /**
   * Replaces the operation index with one built from the current controllers. Every change to
   * {@link #controllers} is followed by a rebuild, and rebuilds are serialised, so the last rebuild
   * always sees every change made before it.
   */
  private void rebuildIndex() {
    synchronized (indexLock) {
      final EnumMap<OperationType, List<ScenarioController>> byOperation =
          new EnumMap<>(OperationType.class);
      for (final ScenarioController controller : controllers.values()) {
        for (final OperationType operation :
            SelectorOperations.of(controller.scenario().selector())) {
          byOperation.computeIfAbsent(operation, ignored -> new ArrayList<>()).add(controller);
        }
      }
      final EnumMap<OperationType, ScenarioController[]> next = new EnumMap<>(OperationType.class);
      byOperation.forEach(
          (operation, list) -> {
            list.sort(MATCH_ORDER);
            next.put(operation, list.toArray(NO_CONTROLLERS));
          });
      index = next;
    }
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
   * Evaluates the controllers that can match {@code context}'s operation and returns the matching
   * contributions in deterministic order.
   *
   * <p>Each {@link ScenarioController} indexed under the context's {@link OperationType} is asked
   * to evaluate the context. Controllers that return {@code null} (no match) are excluded. The
   * survivors keep the index order:
   *
   * <ol>
   *   <li>Descending {@link com.macstab.chaos.jvm.api.ChaosScenario#precedence()} — higher
   *       precedence wins.
   *   <li>Ascending {@link com.macstab.chaos.jvm.api.ChaosScenario#id()} — tie-breaks
   *       deterministically within the same precedence level.
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
    final ScenarioController[] candidates = index.get(context.operationType());
    if (candidates == null) {
      return List.of();
    }
    ArrayList<ScenarioContribution> results = null;
    for (final ScenarioController controller : candidates) {
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
