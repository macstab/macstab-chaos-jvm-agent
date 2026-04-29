package com.macstab.chaos.bootstrap.jfr;

import com.macstab.chaos.api.ChaosDiagnostics;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;

/**
 * Periodic JFR event that snapshots the current state of all active chaos scenarios.
 *
 * <p>Emitted at a 5-second interval by default. The interval can be adjusted in a JFR profile:
 *
 * <pre>{@code
 * <event name="com.macstab.chaos.StressorSnapshot">
 *   <setting name="enabled">true</setting>
 *   <setting name="period">10 s</setting>
 * </event>
 * }</pre>
 *
 * <p>The snapshot is driven by a {@link jdk.jfr.FlightRecorder} periodic event hook registered in
 * {@link JfrChaosEventSink}. The hook queries {@link ChaosDiagnostics} at each tick and emits one
 * event per invocation.
 */
@Name("com.macstab.chaos.StressorSnapshot")
@Category({"Chaos", "Stressor"})
@Label("Chaos Stressor Snapshot")
@Period("5 s")
@StackTrace(false)
public final class ChaosStressorSnapshotEvent extends Event {

  /** Creates a new ChaosStressorSnapshotEvent. */
  public ChaosStressorSnapshotEvent() {}

  /** Number of chaos scenarios currently active at the snapshot instant. */
  @Label("Active Scenario Count")
  public int activeScenarioCount;

  /** Total number of times any active scenario has been applied since runtime start. */
  @Label("Total Applied Count")
  public long totalAppliedCount;

  /**
   * Comma-separated list of active scenario IDs. Empty string when no scenarios are active.
   * Truncated to 1024 characters to bound JFR field size under extreme concurrency.
   */
  @Label("Active Scenario IDs")
  public String activeScenarioIds;
}
