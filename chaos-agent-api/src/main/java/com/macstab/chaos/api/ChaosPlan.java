package com.macstab.chaos.api;

import java.util.List;

/**
 * An ordered, named collection of chaos scenarios that activate as a unit.
 *
 * <p>Plans are the primary configuration vehicle for agent-startup scenarios: they are loaded from
 * YAML or JSON files referenced via {@code -javaagent} arguments or environment variables. All
 * scenarios in a plan activate atomically; activating a plan returns a single {@link
 * ChaosActivationHandle} that controls all constituent scenarios.
 *
 * <p>Plans can also be activated programmatically:
 *
 * <pre>{@code
 * ChaosPlan plan = new ChaosPlan(
 *     new ChaosPlan.Metadata("integration-suite", "Full chaos suite for integration tests"),
 *     new ChaosPlan.Observability(true, true, false),
 *     List.of(scenario1, scenario2, scenario3));
 *
 * ChaosActivationHandle handle = controlPlane.activate(plan);
 * }</pre>
 *
 * <p>All scenarios in a plan must have {@link ChaosScenario.ScenarioScope#JVM JVM} scope when
 * activated at the JVM level. For session-scoped plans use {@link
 * ChaosSession#activate(ChaosPlan)}.
 *
 * @param metadata plan identity; defaults to {@code Metadata("default", "")} if null
 * @param observability per-plan observability toggles; defaults to JMX+logging enabled if null
 * @param scenarios the chaos scenarios to activate; must be non-empty
 */
public record ChaosPlan(
    Metadata metadata, Observability observability, List<ChaosScenario> scenarios) {

  /**
   * @param metadata plan identity; defaults to {@code Metadata("default", "")} if null
   * @param observability per-plan observability toggles; defaults to JMX+logging enabled if null
   * @param scenarios the chaos scenarios to activate; must be non-empty
   * @throws IllegalArgumentException if {@code scenarios} is null or empty
   */
  public ChaosPlan {
    metadata = metadata == null ? new Metadata("default", "") : metadata;
    observability = observability == null ? new Observability(true, true, false) : observability;
    if (scenarios == null || scenarios.isEmpty()) {
      throw new IllegalArgumentException("scenarios must not be empty");
    }
    scenarios = List.copyOf(scenarios);
  }

  /**
   * Identity and human-readable description of a chaos plan.
   *
   * <p>The name appears in diagnostics, JMX MBean attributes, JFR events, and log messages.
   *
   * @param name unique plan name used in diagnostics; must be non-blank
   * @param description optional free-text description; null is normalised to {@code ""}
   */
  public record Metadata(String name, String description) {

    /**
     * @param name unique plan name used in diagnostics; must be non-blank
     * @param description optional free-text description; null is normalised to {@code ""}
     * @throws IllegalArgumentException if {@code name} is null or blank
     */
    public Metadata {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("plan name must be non-blank");
      }
      description = description == null ? "" : description;
    }
  }

  /**
   * Per-plan toggles for the built-in observability integrations.
   *
   * <p>These settings complement but do not replace the agent-level configuration. Setting a toggle
   * to {@code false} here disables it for this plan regardless of the global setting.
   *
   * @param jmxEnabled whether this plan's diagnostics are published over JMX
   * @param structuredLoggingEnabled whether chaos events for this plan are written to the
   *     structured Java logging ({@code java.util.logging})
   * @param debugDumpOnStart whether a full diagnostics debug dump is printed to {@code System.err}
   *     when this plan is activated; useful during initial agent configuration
   */
  public record Observability(
      boolean jmxEnabled, boolean structuredLoggingEnabled, boolean debugDumpOnStart) {}
}
