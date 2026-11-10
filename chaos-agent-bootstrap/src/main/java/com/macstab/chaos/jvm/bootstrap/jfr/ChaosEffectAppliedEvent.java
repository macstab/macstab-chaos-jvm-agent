package com.macstab.chaos.jvm.bootstrap.jfr;

import com.macstab.chaos.jvm.api.Internal;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;
import org.jspecify.annotations.Nullable;

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
 * FROM com.macstab.chaos.jvm.EffectApplied
 * WHERE effectType = 'DelayEffect'
 * }</pre>
 */
@Internal
@Name("com.macstab.chaos.jvm.EffectApplied")
@Category({"Chaos", "Application"})
@Label("Chaos Effect Applied")
@Description("A chaos effect applied to an intercepted call")
@StackTrace(false)
@Enabled(false)
public final class ChaosEffectAppliedEvent extends Event {

  /** Creates a new ChaosEffectAppliedEvent. */
  public ChaosEffectAppliedEvent() {}

  /** ID of the chaos scenario that matched and triggered this effect. */
  @Label("Scenario ID")
  public @Nullable String scenarioId;

  /** Category of the intercepted operation, e.g. {@code HTTP_CLIENT}, {@code JDBC}. */
  @Label("Operation Type")
  public @Nullable String operationType;

  /** Simple class name of the applied effect, e.g. {@code DelayEffect}, {@code ExceptionEffect}. */
  @Label("Effect Type")
  public @Nullable String effectType;

  /** The scope the scenario runs in: {@code jvm}, or {@code session:<id>}. */
  @Label("Scope Key")
  @Description("jvm, or session:<id>")
  public @Nullable String scopeKey;

  /** Null when the effect was applied at JVM scope (not session-scoped). */
  @Label("Session ID")
  public @Nullable String sessionId;

  /** The number of the call among the scenario's matches, from 1; 0 when unknown. */
  @Label("Match")
  @Description("The number of the call among the scenario's matches, from 1")
  public long match;

  /** The delay the effect applied, in nanoseconds; 0 for an effect without a delay. */
  @Label("Delay")
  @Description("The delay the effect applied; 0 for an effect without a delay")
  @Timespan(Timespan.NANOSECONDS)
  public long delayNanos;
}
