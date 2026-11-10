package com.macstab.chaos.jvm.bootstrap.jfr;

import com.macstab.chaos.jvm.api.Internal;
import jdk.jfr.Category;
import jdk.jfr.Description;
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
@Internal
@Name("com.macstab.chaos.jvm.ScenarioLifecycle")
@Category({"Chaos", "Lifecycle"})
@Label("Chaos Scenario Lifecycle")
@Description("A chaos scenario changed its state")
@StackTrace(false)
@Enabled(true)
public final class ChaosScenarioLifecycleEvent extends Event {

  /** No-arg constructor required by the JFR runtime to instantiate event objects reflectively. */
  public ChaosScenarioLifecycleEvent() {}

  /** Unique identifier of the scenario that transitioned state. */
  @Label("Scenario ID")
  public @Nullable String scenarioId;

  /** The event's message, as {@code ChaosEvent.message()} gives it. */
  @Label("Message")
  @Description("The event's message")
  public @Nullable String message;

  /**
   * Lifecycle transition: one of {@code REGISTERED}, {@code STARTED}, {@code STOPPED}, {@code
   * INACTIVE}, {@code RELEASED}.
   */
  @Label("Event Type")
  public @Nullable String eventType;

  /** The scope the scenario runs in: {@code jvm}, or {@code session:<id>}; empty when unknown. */
  @Label("Scope Key")
  @Description("jvm, or session:<id>")
  public @Nullable String scopeKey;
}
