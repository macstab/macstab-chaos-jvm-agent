package com.macstab.chaos.bootstrap.jfr;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted on every chaos scenario state transition: REGISTERED, STARTED, STOPPED,
 * RELEASED.
 *
 * <p>Enabled by default — lifecycle transitions are infrequent (one per test phase boundary) so the
 * overhead is negligible. Correlate with {@link ChaosEffectAppliedEvent} via {@code scenarioId} to
 * reconstruct the full chaos timeline in a JFR recording.
 */
@Name("com.macstab.chaos.ScenarioLifecycle")
@Category({"Chaos", "Lifecycle"})
@Label("Chaos Scenario Lifecycle")
@StackTrace(false)
@Enabled(true)
public final class ChaosScenarioLifecycleEvent extends Event {

  /** ID of the chaos scenario whose lifecycle state changed. */
  @Label("Scenario ID")
  public String scenarioId;

  /** Human-readable description of the scenario at the time of the event. */
  @Label("Description")
  public String description;

  /**
   * Lifecycle transition type, one of {@code REGISTERED}, {@code STARTED}, {@code STOPPED}, {@code
   * RELEASED}.
   */
  @Label("Event Type")
  public String eventType;

  /** Scope key identifying the activation context (JVM-wide or session-scoped). */
  @Label("Scope Key")
  public String scopeKey;
}
