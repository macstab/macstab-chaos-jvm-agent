package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosDiagnostics;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

final class ScenarioRegistry implements ChaosDiagnostics {
  private final ConcurrentHashMap<String, ScenarioController> controllers =
      new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<ActivationFailure> failures = new ConcurrentLinkedQueue<>();
  private final Supplier<Map<String, String>> runtimeDetailsSupplier;
  private final Clock clock;

  ScenarioRegistry(Clock clock, Supplier<Map<String, String>> runtimeDetailsSupplier) {
    this.clock = clock;
    this.runtimeDetailsSupplier = runtimeDetailsSupplier;
  }

  void register(ScenarioController controller) {
    if (controllers.putIfAbsent(controller.key(), controller) != null) {
      throw new IllegalStateException("scenario key already active: " + controller.key());
    }
  }

  void unregister(ScenarioController controller) {
    controllers.remove(controller.key(), controller);
  }

  void recordFailure(String scenarioId, FailureCategory category, String message) {
    failures.add(new ActivationFailure(scenarioId, category, message));
  }

  List<ScenarioContribution> match(InvocationContext context) {
    return controllers.values().stream()
        .map(controller -> controller.evaluate(context))
        .filter(java.util.Objects::nonNull)
        .sorted(
            Comparator.comparingInt(
                    (ScenarioContribution contribution) -> contribution.scenario().precedence())
                .reversed()
                .thenComparing(contribution -> contribution.scenario().id()))
        .toList();
  }

  List<ScenarioController> controllers() {
    return List.copyOf(controllers.values());
  }

  @Override
  public Snapshot snapshot() {
    List<ScenarioReport> reports =
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

  @Override
  public Optional<ScenarioReport> scenario(String scenarioId) {
    return controllers.values().stream()
        .map(ScenarioController::snapshot)
        .filter(report -> report.id().equals(scenarioId))
        .findFirst();
  }

  @Override
  public String debugDump() {
    Snapshot snapshot = snapshot();
    StringBuilder builder = new StringBuilder();
    builder.append("macstab-chaos diagnostics").append(System.lineSeparator());
    builder.append("capturedAt=").append(snapshot.capturedAt()).append(System.lineSeparator());
    snapshot
        .runtimeDetails()
        .forEach(
            (key, value) ->
                builder.append(key).append('=').append(value).append(System.lineSeparator()));
    for (ScenarioReport report : snapshot.scenarios()) {
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
