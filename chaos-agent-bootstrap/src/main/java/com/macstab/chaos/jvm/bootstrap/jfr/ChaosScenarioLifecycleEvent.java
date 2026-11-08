package com.macstab.chaos.jvm.bootstrap.jfr;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import org.jspecify.annotations.Nullable;

/**
 * JFR event emitted on every chaos scenario state transition: REGISTERED, STARTED, STOPPED,
 * INACTIVE, RELEASED.
 *
 * <p>Enabled by default — lifecycle transitions are infrequent (one per test phase boundary) so the
 * overhead is negligible. Correlate with {@link ChaosEffectAppliedEvent} via {@code scenarioId} to
 * reconstruct the full chaos timeline in a JFR recording.
 */
@Name("com.macstab.chaos.jvm.ScenarioLifecycle")
@Category({"Chaos", "Lifecycle"})
@Label("Chaos Scenario Lifecycle")
@StackTrace(false)
@Enabled(true)
public final class ChaosScenarioLifecycleEvent extends Event {

  /** No-arg constructor required by the JFR runtime to instantiate event objects reflectively. */
  public ChaosScenarioLifecycleEvent() {}

  /** Unique identifier of the scenario that transitioned state. */
  @Label("Scenario ID")
  public @Nullable String scenarioId;

  /** Human-readable description of the scenario. */
  @Label("Description")
  public @Nullable String description;

  /**
   * Lifecycle transition: one of {@code REGISTERED}, {@code STARTED}, {@code STOPPED}, {@code
   * INACTIVE}, {@code RELEASED}.
   */
  @Label("Event Type")
  public @Nullable String eventType;

  /** Scope key that was active when the transition occurred; {@code null} for global scope. */
  @Label("Scope Key")
  public @Nullable String scopeKey;
}
