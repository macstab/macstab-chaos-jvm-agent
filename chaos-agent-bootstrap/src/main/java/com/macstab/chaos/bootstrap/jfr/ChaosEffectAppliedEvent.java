package com.macstab.chaos.bootstrap.jfr;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted each time a chaos effect is applied to a matched JVM operation.
 *
 * <p>Disabled by default — this event fires on every intercepted operation and can reach high
 * frequency under load. Enable on demand via a JFR profile or {@code -XX:StartFlightRecording} with
 * an appropriate threshold, or via JMC's event settings panel during a recording session.
 *
 * <p>Fields {@code operationType} and {@code effectType} allow selective filtering in JFR queries:
 *
 * <pre>{@code
 * SELECT scenarioId, operationType, effectType, sessionId
 * FROM com.macstab.chaos.EffectApplied
 * WHERE effectType = 'DelayEffect'
 * }</pre>
 */
@Name("com.macstab.chaos.EffectApplied")
@Category({"Chaos", "Application"})
@Label("Chaos Effect Applied")
@StackTrace(false)
@Enabled(false)
public final class ChaosEffectAppliedEvent extends Event {

  /** Creates a new ChaosEffectAppliedEvent. */
  public ChaosEffectAppliedEvent() {}

  /** ID of the chaos scenario that matched and triggered this effect. */
  @Label("Scenario ID")
  public String scenarioId;

  /** Category of the intercepted operation, e.g. {@code HTTP_CLIENT}, {@code JDBC}. */
  @Label("Operation Type")
  public String operationType;

  /** Simple class name of the applied effect, e.g. {@code DelayEffect}, {@code ExceptionEffect}. */
  @Label("Effect Type")
  public String effectType;

  /** Scope key used to match this operation against the scenario's target criteria. */
  @Label("Scope Key")
  public String scopeKey;

  /** Null when the effect was applied at JVM scope (not session-scoped). */
  @Label("Session ID")
  public String sessionId;
}
