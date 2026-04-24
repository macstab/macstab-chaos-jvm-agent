package com.macstab.chaos.jvm.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of a single chaos injection scenario.
 *
 * <p>A scenario binds three orthogonal concerns:
 *
 * <ul>
 *   <li>{@link ChaosSelector} — which JVM operations are eligible
 *   <li>{@link ChaosEffect} — what happens when a match occurs
 *   <li>{@link ActivationPolicy} — when and how often the effect fires
 * </ul>
 *
 * <p>Scenarios are activated via {@link ChaosControlPlane#activate(ChaosScenario)}, which returns a
 * {@link ChaosActivationHandle} for lifecycle control. Multiple scenarios may be active
 * simultaneously; when more than one matches an operation, all effects compose: delays sum, and the
 * highest-{@link #precedence} terminal action (reject, suppress, exception) wins.
 *
 * <p>Use {@link #builder(String)} to construct instances:
 *
 * <pre>{@code
 * ChaosScenario scenario = ChaosScenario.builder("slow-redis")
 *     .description("Simulate a slow Redis connection under load")
 *     .selector(ChaosSelector.method(
 *         Set.of(OperationType.METHOD_ENTER),
 *         NamePattern.prefix("io.lettuce.core"),
 *         NamePattern.any()))
 *     .effect(ChaosEffect.delay(Duration.ofMillis(200), Duration.ofMillis(500)))
 *     .activationPolicy(ActivationPolicy.always())
 *     .build();
 * }</pre>
 *
 * <p><b>Scope:</b> {@link ScenarioScope#JVM} scenarios intercept all matching operations in the
 * JVM. {@link ScenarioScope#SESSION} scenarios intercept only operations on threads {@link
 * ChaosSession#bind() bound} to a specific session, enabling per-test isolation in shared JVM
 * environments.
 *
 * @param id unique identifier surfaced in diagnostics, JMX, JFR events, and logs
 * @param description human-readable description; {@code null} normalises to {@code ""}
 * @param scope visibility scope; {@code null} defaults to {@link ScenarioScope#JVM}
 * @param selector selects which JVM operations are eligible for chaos
 * @param effect the chaos effect applied when the selector matches
 * @param activationPolicy controls when and how often the effect fires
 * @param precedence tie-breaker for conflicting terminal actions (higher wins)
 * @param tags free-form metadata surfaced in diagnostics and JFR events
 */
public record ChaosScenario(
    String id,
    String description,
    ScenarioScope scope,
    ChaosSelector selector,
    ChaosEffect effect,
    ActivationPolicy activationPolicy,
    int precedence,
    Map<String, String> tags) {

  public ChaosScenario {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must be non-blank");
    }
    description = description == null ? "" : description;
    scope = scope == null ? ScenarioScope.JVM : scope;
    selector = Objects.requireNonNull(selector, "selector");
    effect = Objects.requireNonNull(effect, "effect");
    activationPolicy = activationPolicy == null ? ActivationPolicy.always() : activationPolicy;
    tags = copyTagsPreservingOrder(tags);
  }

  private static Map<String, String> copyTagsPreservingOrder(final Map<String, String> tags) {
    if (tags == null || tags.isEmpty()) {
      return Map.of();
    }
    final LinkedHashMap<String, String> ordered = new LinkedHashMap<>(tags.size());
    for (final Map.Entry<String, String> entry : tags.entrySet()) {
      final String key = entry.getKey();
      final String value = entry.getValue();
      if (key == null) {
        throw new IllegalArgumentException("tag key must not be null");
      }
      if (value == null) {
        throw new IllegalArgumentException("tag value must not be null (key=" + key + ")");
      }
      ordered.put(key, value);
    }
    return Collections.unmodifiableMap(ordered);
  }

  /**
   * Returns a new {@link Builder} seeded with the given scenario ID.
   *
   * @param id unique identifier for this scenario; used in diagnostics, JMX, JFR events, and logs
   * @return a new builder for this scenario ID
   */
  public static Builder builder(final String id) {
    return new Builder(id);
  }

  /**
   * Defines the visibility scope of a scenario.
   *
   * <p>Scope determines which thread observations trigger the scenario's selector evaluation.
   */
  public enum ScenarioScope {

    /**
     * The scenario is evaluated for every matching operation in the entire JVM, regardless of which
     * thread performs it. Use for global fault injection: network slowness, heap pressure, GC
     * chaos.
     */
    JVM,

    /**
     * The scenario is evaluated only for operations performed on threads that have been {@link
     * ChaosSession#bind() bound} to a specific session. Use for per-test isolation when multiple
     * tests share a JVM.
     *
     * <p>Session-scoped scenarios cannot use JVM-global selectors ({@link
     * ChaosSelector.ThreadSelector}, {@link ChaosSelector.ShutdownSelector}, {@link
     * ChaosSelector.ClassLoadingSelector}, {@link ChaosSelector.StressSelector}).
     */
    SESSION,
  }

  /**
   * Fluent builder for {@link ChaosScenario}.
   *
   * <p>Mandatory fields: {@link #selector} and {@link #effect}. All other fields have sensible
   * defaults ({@link ScenarioScope#JVM}, {@link ActivationPolicy#always()}, precedence 0).
   */
  public static final class Builder {
    private final String id;
    private String description = "";
    private ScenarioScope scope = ScenarioScope.JVM;
    private ChaosSelector selector;
    private ChaosEffect effect;
    private ActivationPolicy activationPolicy = ActivationPolicy.always();
    private int precedence;
    private final Map<String, String> tags = new LinkedHashMap<>();

    private Builder(final String id) {
      this.id = id;
    }

    /**
     * Human-readable description of what this scenario tests. Appears in diagnostics and debug
     * dumps.
     *
     * @param description free-form description text
     * @return this builder for chaining
     */
    public Builder description(final String description) {
      this.description = description;
      return this;
    }

    /**
     * Sets the scope. Defaults to {@link ScenarioScope#JVM} when not called.
     *
     * @param scope visibility scope for this scenario
     * @return this builder for chaining
     * @see ScenarioScope
     */
    public Builder scope(final ScenarioScope scope) {
      this.scope = scope;
      return this;
    }

    /**
     * Sets the selector that determines which JVM operations are eligible for chaos. Required.
     *
     * @param selector selector matching candidate operations
     * @return this builder for chaining
     * @see ChaosSelector
     */
    public Builder selector(final ChaosSelector selector) {
      this.selector = selector;
      return this;
    }

    /**
     * Sets the effect applied when the selector matches. Required.
     *
     * @param effect chaos effect to apply on a match
     * @return this builder for chaining
     * @see ChaosEffect
     */
    public Builder effect(final ChaosEffect effect) {
      this.effect = effect;
      return this;
    }

    /**
     * Sets the activation policy controlling when and how often the effect fires. Defaults to
     * {@link ActivationPolicy#always()} when not called.
     *
     * @param activationPolicy activation policy governing when the effect fires
     * @return this builder for chaining
     * @see ActivationPolicy
     */
    public Builder activationPolicy(final ActivationPolicy activationPolicy) {
      this.activationPolicy = activationPolicy;
      return this;
    }

    /**
     * Sets the precedence used to resolve conflicts when multiple scenarios match the same
     * operation. Higher values win. Defaults to {@code 0}.
     *
     * <p>Precedence only affects terminal actions (reject, suppress, exception injection). Delay
     * effects from all matching scenarios always accumulate regardless of precedence.
     *
     * @param precedence conflict-resolution priority; higher values win
     * @return this builder for chaining
     */
    public Builder precedence(final int precedence) {
      this.precedence = precedence;
      return this;
    }

    /**
     * Adds a free-form metadata tag. Tags are surfaced in diagnostics and JFR events. Not used for
     * selector evaluation.
     *
     * @param key metadata tag key
     * @param value metadata tag value
     * @return this builder for chaining
     */
    public Builder tag(final String key, final String value) {
      if (key == null) {
        throw new IllegalArgumentException("tag key must not be null");
      }
      if (value == null) {
        throw new IllegalArgumentException("tag value must not be null (key=" + key + ")");
      }
      tags.put(key, value);
      return this;
    }

    /**
     * Builds the scenario.
     *
     * @return the constructed ChaosScenario
     * @throws IllegalArgumentException if selector or effect is null
     */
    public ChaosScenario build() {
      return new ChaosScenario(
          id, description, scope, selector, effect, activationPolicy, precedence, tags);
    }
  }
}
