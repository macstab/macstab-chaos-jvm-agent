package io.macstab.chaos.bootstrap.jfr;

import io.macstab.chaos.api.ChaosDiagnostics;
import io.macstab.chaos.api.ChaosEvent;
import io.macstab.chaos.api.ChaosEventListener;
import java.util.List;
import java.util.StringJoiner;
import jdk.jfr.FlightRecorder;

/**
 * Bridges the chaos {@link ChaosEventListener} contract to Java Flight Recorder.
 *
 * <p>On construction, registers a {@link ChaosStressorSnapshotEvent} periodic hook with JFR. On
 * {@link #close()}, the hook is removed cleanly. Callers must close this sink when the agent or
 * test is torn down to avoid a stale periodic hook holding a reference to the diagnostics object.
 *
 * <p>This class is only instantiated after {@link JfrAvailability#probe()} has confirmed that
 * {@code jdk.jfr} is accessible. It must never be loaded on a stripped JRE that lacks the module.
 *
 * <p>All methods on this class are non-blocking and safe to call from any thread.
 */
final class JfrChaosEventSink implements ChaosEventListener, AutoCloseable {

  private static final int MAX_IDS_LENGTH = 1024;

  private final ChaosDiagnostics diagnostics;
  private final Runnable periodicHook;

  JfrChaosEventSink(ChaosDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
    this.periodicHook = this::emitStressorSnapshot;
    FlightRecorder.addPeriodicEvent(ChaosStressorSnapshotEvent.class, periodicHook);
  }

  @Override
  public void onEvent(ChaosEvent event) {
    switch (event.type()) {
      case REGISTERED, STARTED, STOPPED, RELEASED -> emitLifecycle(event);
      case APPLIED -> emitEffectApplied(event);
      default -> { /* SKIPPED, FAILED — not emitted as discrete JFR events */ }
    }
  }

  @Override
  public void close() {
    FlightRecorder.removePeriodicEvent(periodicHook);
  }

  // ── Private emission helpers ───────────────────────────────────────────────

  private void emitLifecycle(ChaosEvent event) {
    ChaosScenarioLifecycleEvent jfrEvent = new ChaosScenarioLifecycleEvent();
    jfrEvent.scenarioId = event.scenarioId();
    jfrEvent.description = event.message();
    jfrEvent.eventType = event.type().name();
    jfrEvent.scopeKey = event.attributes().getOrDefault("scope", "");
    jfrEvent.commit();
  }

  private void emitEffectApplied(ChaosEvent event) {
    ChaosEffectAppliedEvent jfrEvent = new ChaosEffectAppliedEvent();
    if (!jfrEvent.isEnabled()) {
      return;
    }
    jfrEvent.scenarioId = event.scenarioId();
    jfrEvent.operationType = event.attributes().getOrDefault("operation", "");
    jfrEvent.effectType = event.attributes().getOrDefault("effectType", "");
    jfrEvent.scopeKey = event.attributes().getOrDefault("scope", "");
    jfrEvent.sessionId = event.attributes().get("sessionId");
    jfrEvent.commit();
  }

  private void emitStressorSnapshot() {
    ChaosStressorSnapshotEvent jfrEvent = new ChaosStressorSnapshotEvent();
    if (!jfrEvent.isEnabled()) {
      return;
    }
    ChaosDiagnostics.Snapshot snapshot = diagnostics.snapshot();
    List<ChaosDiagnostics.ScenarioReport> scenarios = snapshot.scenarios();

    int activeCount = 0;
    long totalApplied = 0L;
    StringJoiner ids = new StringJoiner(",");
    for (ChaosDiagnostics.ScenarioReport report : scenarios) {
      totalApplied += report.appliedCount();
      if (report.state() == ChaosDiagnostics.ScenarioState.ACTIVE) {
        activeCount++;
        ids.add(report.id());
      }
    }

    jfrEvent.activeScenarioCount = activeCount;
    jfrEvent.totalAppliedCount = totalApplied;
    String idsString = ids.toString();
    jfrEvent.activeScenarioIds =
        idsString.length() > MAX_IDS_LENGTH ? idsString.substring(0, MAX_IDS_LENGTH) : idsString;
    jfrEvent.commit();
  }
}
