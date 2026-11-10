package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosActivationConflictException;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

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
 * <p>Every selector matches a fixed set of {@link OperationType}s ({@link SelectorOperations#of};
 * empty for a stress selector, which is never evaluated per call). The registry keeps an immutable
 * index from operation type to the controllers whose selector names it, already in match order, and
 * replaces it whenever a controller is registered or unregistered. {@link
 * #match(InvocationContext)} therefore only evaluates scenarios that can match the intercepted
 * operation, instead of every registered scenario, and never sorts.
 */
final class ScenarioRegistry implements ChaosDiagnostics {
  /**
   * The order of the scopes of one id: by scope key, so the JVM scope ({@code "jvm"}) comes before
   * every session ({@code "session:<id>"}) and sessions by their id. It decides both the lookup by
   * bare id and equal-precedence ties, whatever thread asks.
   */
  private static final Comparator<ScenarioController> SCOPE_ORDER =
      Comparator.comparing(ScenarioController::scopeKey);

  private static final Comparator<ScenarioController> MATCH_ORDER =
      Comparator.comparingInt((ScenarioController c) -> c.scenario().precedence())
          .reversed()
          .thenComparing(c -> c.scenario().id())
          .thenComparing(SCOPE_ORDER);

  private static final ScenarioController[] NO_CONTROLLERS = new ScenarioController[0];

  private final ConcurrentHashMap<String, ScenarioController> controllers =
      new ConcurrentHashMap<>();
  private final Object indexLock = new Object();
  private volatile Map<OperationType, ScenarioController[]> index =
      new EnumMap<>(OperationType.class);

  /**
   * The ids of the sessions with at least one registered controller; replaced, never changed, by
   * {@link #rebuildIndex(boolean)}.
   */
  private volatile Set<String> sessionsWithScenarios = Set.of();

  private volatile Consumer<Set<ChaosSelector.MethodSelector>> methodSelectorListener =
      selectors -> {};

  /**
   * Serialises every hand-over of method selectors to the weaver; computing the set and handing it
   * over happen under it together, so the last hand-over always reflects the latest registry state.
   */
  private final Object weaveLock = new Object();

  /**
   * The method selectors last handed to the weaver; {@code null} after a weave failed, when the
   * weaver holds no method hooks and the next hand-over must weave whatever is registered. Guarded
   * by {@link #weaveLock}.
   */
  private @Nullable Set<ChaosSelector.MethodSelector> wovenMethodSelectors = Set.of();

  /** How many activation failures the diagnostics keep: the most recent ones. */
  static final int MAX_FAILURES = 256;

  /**
   * The {@value #MAX_FAILURES} most recent activation failures, oldest first; guarded by itself, as
   * is {@link #droppedFailures}, so the add and the trim are one step.
   */
  private final ArrayDeque<ActivationFailure> failures = new ArrayDeque<>();

  /**
   * The failures dropped from {@link #failures} to keep it bounded; guarded by {@link #failures}.
   */
  private long droppedFailures;

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
   * (expired or out of applications) — is replaced and returned so the caller can end and destroy
   * it. Publishes nothing, so a caller may hold a lock. A stopped scenario can never run again
   * ({@code start()} is a no-op after {@code stop()}), so its id is free for the next test of a
   * suite, a re-posted plan or a reloaded config. The check and the replacement are one atomic
   * step.
   *
   * @param controller the controller to register; must not be {@code null}
   * @return the finished controller that was replaced, or {@code null}
   * @throws ChaosActivationException if a controller with the same key is registered and still
   *     {@code REGISTERED} or {@code ACTIVE}
   */
  ScenarioController register(final ScenarioController controller) {
    // An expired controller under the key counts as finished and is replaced; the caller ends it
    // (its INACTIVE event) with no lock held. Nothing is published here: the caller holds its
    // lifecycle lock.
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
          throw new ChaosActivationConflictException("scenario key already active: " + key);
        });
    // Adds the new controller's method selector if it was not woven beforehand, and keeps the
    // pre-woven ones of a plan whose other scenarios are still to be registered.
    rebuildIndex(false);
    return replaced[0];
  }

  /**
   * Returns whether a controller registered under {@code key} is still {@code REGISTERED} or {@code
   * ACTIVE}, so registering another one under the key would fail.
   *
   * @param key the registry key ({@code scope::id})
   * @return {@code true} when the key is taken by a live controller
   */
  boolean isLive(final String key) {
    final ScenarioController existing = controllers.get(key);
    return existing != null && !isFinished(existing);
  }

  /** Side-effect free: called inside the map update. */
  private static boolean isFinished(final ScenarioController controller) {
    final ChaosDiagnostics.ScenarioState state = controller.rawState();
    return state == ChaosDiagnostics.ScenarioState.STOPPED
        || state == ChaosDiagnostics.ScenarioState.INACTIVE
        || controller.isExpired();
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
      rebuildIndex(true);
    }
  }

  /**
   * Returns whether at least one controller of the session {@code sessionId} is registered.
   *
   * @param sessionId the session id
   * @return {@code true} when the session has a registered scenario
   */
  boolean hasScenarios(final String sessionId) {
    return sessionsWithScenarios.contains(sessionId);
  }

  /**
   * Replaces the operation index and the set of sessions with scenarios with ones built from the
   * current controllers. Every change to {@link #controllers} is followed by a rebuild, and
   * rebuilds are serialised, so the last rebuild always sees every change made before it. Then the
   * method selectors are reconciled: trimmed to the registered ones after a removal ({@code trim}),
   * only added to after a registration.
   */
  private void rebuildIndex(final boolean trim) {
    synchronized (indexLock) {
      final EnumMap<OperationType, List<ScenarioController>> byOperation =
          new EnumMap<>(OperationType.class);
      final Set<String> sessions = new HashSet<>();
      for (final ScenarioController controller : controllers.values()) {
        final String sessionId = controller.sessionId();
        if (sessionId != null) {
          sessions.add(sessionId);
        }
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
      sessionsWithScenarios = Set.copyOf(sessions);
    }
    reconcileMethodSelectors(trim);
  }

  /**
   * Hands exactly the method selectors of the registered scenarios to the weaver when they differ
   * from the ones it has, dropping selectors woven beforehand for scenarios that were never
   * registered. Runs after a removal and after a failed registration, on the thread that made the
   * change, outside the index lock.
   */
  void reconcileMethodSelectors() {
    reconcileMethodSelectors(true);
  }

  /**
   * Hands the registered method selectors to the weaver: exactly them when {@code trim}, otherwise
   * added to the ones woven already (after a registration, which keeps the selectors woven
   * beforehand for the scenarios still to come).
   */
  private void reconcileMethodSelectors(final boolean trim) {
    synchronized (weaveLock) {
      final Set<ChaosSelector.MethodSelector> current = collectMethodSelectors();
      final Set<ChaosSelector.MethodSelector> next = trim ? current : union(current, Set.of());
      if (!next.equals(wovenMethodSelectors)) {
        try {
          methodSelectorListener.accept(next);
          wovenMethodSelectors = next;
        } catch (final RuntimeException failure) {
          // The weaver keeps no hooks after a failure (its listener reported it): unknown, so the
          // next reconcile weaves the registered selectors again instead of finding them equal.
          wovenMethodSelectors = null;
        }
      }
    }
  }

  /**
   * Weaves {@code added} before the scenarios with those method selectors are registered, so a
   * weaving failure leaves nothing registered. The weaver gets the selectors woven already, those
   * of the registered scenarios and {@code added}; nothing when that adds none. Pre-weaves add up
   * (a plan weaves all its selectors in one call, the plan-file reloader one per checked scenario)
   * until the next removal or failed registration trims them ({@link #reconcileMethodSelectors()}).
   * A failure of {@code weaver} propagates and leaves the woven set unknown: a weaver that fails
   * keeps no method hooks, so the next {@link #reconcileMethodSelectors()} weaves the registered
   * scenarios' selectors again.
   *
   * @param added the method selectors of the scenarios about to be registered
   * @param weaver weaves the given selectors
   */
  void weaveBeforeRegistering(
      final Set<ChaosSelector.MethodSelector> added,
      final Consumer<Set<ChaosSelector.MethodSelector>> weaver) {
    synchronized (weaveLock) {
      final Set<ChaosSelector.MethodSelector> next = union(collectMethodSelectors(), added);
      if (!next.equals(wovenMethodSelectors)) {
        try {
          weaver.accept(next);
        } catch (final RuntimeException failure) {
          wovenMethodSelectors = null;
          throw failure;
        }
        wovenMethodSelectors = next;
      }
    }
  }

  /** The woven method selectors with {@code registered} and {@code added}; under the weave lock. */
  private Set<ChaosSelector.MethodSelector> union(
      final Set<ChaosSelector.MethodSelector> registered,
      final Set<ChaosSelector.MethodSelector> added) {
    final Set<ChaosSelector.MethodSelector> woven = wovenMethodSelectors;
    final Set<ChaosSelector.MethodSelector> selectors =
        woven == null ? new HashSet<>() : new HashSet<>(woven);
    selectors.addAll(registered);
    selectors.addAll(added);
    return Set.copyOf(selectors);
  }

  private Set<ChaosSelector.MethodSelector> collectMethodSelectors() {
    final Set<ChaosSelector.MethodSelector> selectors = new HashSet<>();
    for (final ScenarioController controller : controllers.values()) {
      if (controller.scenario().selector() instanceof ChaosSelector.MethodSelector selector) {
        selectors.add(selector);
      }
    }
    return Set.copyOf(selectors);
  }

  /**
   * Sets the listener told about the registered method selectors whenever their set changes (a
   * scenario with a method selector was registered or removed). It runs on the thread that changed
   * the registry, under the registry's weave lock (never the index lock), so weaves are serialised.
   *
   * @param listener the listener; replaces the previous one
   */
  void onMethodSelectorsChanged(final Consumer<Set<ChaosSelector.MethodSelector>> listener) {
    methodSelectorListener = listener;
  }

  /**
   * Records an activation failure so that it is visible in subsequent {@link #snapshot()} calls.
   *
   * @param scenarioId the ID of the scenario whose activation failed
   * @param category the category of the failure (e.g. compatibility error, resource exhaustion)
   *     <p>Only the {@value #MAX_FAILURES} most recent failures are kept; an older one is dropped
   *     and counted ({@link #droppedActivationFailures()}).
   * @param message a human-readable description of what went wrong; an exception without a message
   *     is recorded as {@code "(no message)"}
   */
  void recordFailure(
      final String scenarioId, final FailureCategory category, final @Nullable String message) {
    final ActivationFailure failure =
        new ActivationFailure(
            scenarioId, category, java.util.Objects.requireNonNullElse(message, "(no message)"));
    synchronized (failures) {
      failures.addLast(failure);
      if (failures.size() > MAX_FAILURES) {
        failures.removeFirst();
        droppedFailures++;
      }
    }
  }

  /**
   * Returns how many activation failures were dropped to keep the history at {@value
   * #MAX_FAILURES}.
   *
   * @return the dropped count
   */
  long droppedActivationFailures() {
    synchronized (failures) {
      return droppedFailures;
    }
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
   * <p>When a controller throws, the contributions already collected release their reservations
   * before the failure propagates.
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
    try {
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
    } catch (final RuntimeException | Error failure) {
      // The caller never sees the contributions collected so far, so it cannot return them:
      // release their reservations here, or a failing later candidate would use up the budget of
      // every earlier one.
      if (results != null) {
        for (final ScenarioContribution contribution : results) {
          try {
            contribution.release();
          } catch (final RuntimeException | Error releaseFailure) {
            failure.addSuppressed(releaseFailure);
          }
        }
      }
      throw failure;
    }
    if (results == null) {
      return List.of();
    }
    return Collections.unmodifiableList(results);
  }

  /**
   * Returns the controllers whose selector names {@code operation}, in match order, straight from
   * the index: no copy is made, so callers must not modify the array.
   *
   * @param operation the intercepted operation
   * @return the indexed controllers; an empty array when none can match
   */
  ScenarioController[] candidates(final OperationType operation) {
    final ScenarioController[] candidates = index.get(operation);
    return candidates == null ? NO_CONTROLLERS : candidates;
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
   * activation failures (the {@value #MAX_FAILURES} most recent) and the runtime details from the
   * supplier provided at construction time. The {@link Snapshot#capturedAt()} timestamp is read,
   * unskewed, from the {@link Clock} supplied at construction time.
   */
  @Override
  public Snapshot snapshot() {
    final List<ScenarioReport> reports =
        controllers.values().stream()
            .map(ScenarioController::snapshot)
            .sorted(
                Comparator.comparing(ScenarioReport::id).thenComparing(ScenarioReport::scopeKey))
            .toList();
    final List<ActivationFailure> recentFailures;
    synchronized (failures) {
      recentFailures = List.copyOf(failures);
    }
    return new Snapshot(
        Instant.ofEpochMilli(DispatchDepth.unskewedInstant(clock).toEpochMilli()),
        reports,
        recentFailures,
        runtimeDetailsSupplier.get());
  }

  /**
   * {@inheritDoc}
   *
   * <p>An id may be registered in several scopes, each with its own controller. The JVM scope's
   * controller is returned when there is one, else the session with the smallest id: the same
   * answer on every thread. For one session's scenario, use its activation handle.
   *
   * @param scenarioId the scenario ID to look up; must not be {@code null}
   * @return an {@link Optional} containing the matching report, or empty if no active controller
   *     has that ID
   */
  @Override
  public Optional<ScenarioReport> scenario(final String scenarioId) {
    return controllers.values().stream()
        .filter(controller -> controller.scenario().id().equals(scenarioId))
        .min(SCOPE_ORDER)
        .map(ScenarioController::snapshot);
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
   * failure &lt;scenarioId&gt; category=&lt;category&gt; message=&lt;message&gt;
   * ...
   * </pre>
   *
   * @return a non-{@code null} human-readable diagnostic string
   */
  @Override
  public String debugDump() {
    return debugDump(report -> true);
  }

  /**
   * Returns the debug dump restricted to the scenarios {@code include} accepts.
   *
   * @param include whether a scenario is part of the dump
   * @return the dump
   */
  String debugDump(final java.util.function.Predicate<ScenarioReport> include) {
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
      if (!include.test(report)) {
        continue;
      }
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
    for (final ActivationFailure failure : snapshot.failures()) {
      builder
          .append("failure ")
          .append(failure.scenarioId())
          .append(" category=")
          .append(failure.category())
          .append(" message=")
          .append(failure.message())
          .append(System.lineSeparator());
    }
    return builder.toString();
  }
}
