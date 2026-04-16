package com.macstab.chaos.core;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosDiagnostics;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosEvent;
import com.macstab.chaos.api.ChaosScenario;
import java.lang.instrument.Instrumentation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

final class ScenarioController {
  private final ChaosScenario scenario;
  private final String scopeKey;
  private final String sessionId;
  private final Clock clock;
  private final ObservabilityBus observabilityBus;
  private final Supplier<Optional<Instrumentation>> instrumentationSupplier;
  private final ManualGate gate = new ManualGate();
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicLong matchedCount = new AtomicLong();
  private final AtomicLong appliedCount = new AtomicLong();
  private volatile ChaosDiagnostics.ScenarioState state;
  private volatile String reason;
  private volatile Instant startedAt;
  private volatile ManagedStressor stressor;
  private volatile ClockSkewState clockSkewState;
  private volatile long rateWindowStartMillis;
  private volatile long rateWindowPermits;

  ScenarioController(
      final ChaosScenario scenario,
      final String scopeKey,
      final String sessionId,
      final Clock clock,
      final ObservabilityBus observabilityBus,
      final Supplier<Optional<Instrumentation>> instrumentationSupplier) {
    this.scenario = scenario;
    this.scopeKey = scopeKey;
    this.sessionId = sessionId;
    this.clock = clock;
    this.observabilityBus = observabilityBus;
    this.instrumentationSupplier = instrumentationSupplier;
    // Task 1: Always start REGISTERED regardless of start mode. The start() call
    // transitions to ACTIVE. Setting ACTIVE here while started=false produces a
    // diagnostic state that is observably wrong (ACTIVE but evaluating returns null).
    this.state = ChaosDiagnostics.ScenarioState.REGISTERED;
  }

  String key() {
    return scopeKey + "::" + scenario.id();
  }

  String scopeKey() {
    return scopeKey;
  }

  ChaosScenario scenario() {
    return scenario;
  }

  long matchedCount() {
    return matchedCount.get();
  }

  long appliedCount() {
    return appliedCount.get();
  }

  ChaosDiagnostics.ScenarioState state() {
    return state;
  }

  String reason() {
    return reason == null ? "" : reason;
  }

  void start() {
    gate.reset();
    startedAt = clock.instant();
    started.set(true);
    state = ChaosDiagnostics.ScenarioState.ACTIVE;
    reason = "started";
    stressor = createStressorIfNeeded();
    if (scenario.effect() instanceof ChaosEffect.ClockSkewEffect skewEffect) {
      clockSkewState =
          new ClockSkewState(skewEffect, System.currentTimeMillis(), System.nanoTime());
    }
    observabilityBus.publish(
        ChaosEvent.Type.STARTED, scenario.id(), "scenario started", Map.of("scope", scopeKey));
  }

  void stop() {
    started.set(false);
    state = ChaosDiagnostics.ScenarioState.STOPPED;
    reason = "stopped";
    gate.release();
    closeStressor();
    clockSkewState = null;
    observabilityBus.publish(
        ChaosEvent.Type.STOPPED, scenario.id(), "scenario stopped", Map.of("scope", scopeKey));
  }

  void release() {
    gate.release();
    observabilityBus.publish(
        ChaosEvent.Type.RELEASED, scenario.id(), "manual gate released", Map.of("scope", scopeKey));
  }

  ScenarioContribution evaluate(final InvocationContext context) {
    if (!started.get()) {
      return null;
    }
    if (sessionId != null && !sessionId.equals(context.sessionId())) {
      return null;
    }
    if (!SelectorMatcher.matches(scenario.selector(), context)) {
      return null;
    }
    final long matched = matchedCount.incrementAndGet();
    if (!passesActivationWindow()) {
      state = ChaosDiagnostics.ScenarioState.INACTIVE;
      reason = "expired";
      return null;
    }
    if (matched <= scenario.activationPolicy().activateAfterMatches()) {
      return null;
    }
    if (!passesRateLimit()) {
      return null;
    }
    if (!passesProbability(matched)) {
      return null;
    }
    // Task 2: CAS loop to ensure appliedCount never overshoots maxApplications under
    // concurrency. The naive incrementAndGet-then-check pattern allowed the counter to
    // exceed the cap when multiple threads raced through this branch simultaneously.
    final Long maxApplications = scenario.activationPolicy().maxApplications();
    if (maxApplications != null) {
      while (true) {
        final long current = appliedCount.get();
        if (current >= maxApplications) {
          state = ChaosDiagnostics.ScenarioState.INACTIVE;
          reason = "max applications reached";
          return null;
        }
        if (appliedCount.compareAndSet(current, current + 1L)) {
          break;
        }
      }
    } else {
      appliedCount.incrementAndGet();
    }
    Map<String, String> appliedAttrs = new LinkedHashMap<>();
    appliedAttrs.put("operation", context.operationType().name());
    appliedAttrs.put("scope", scopeKey);
    appliedAttrs.put("effectType", scenario.effect().getClass().getSimpleName());
    if (sessionId != null) {
      appliedAttrs.put("sessionId", sessionId);
    }
    observabilityBus.publish(
        ChaosEvent.Type.APPLIED, scenario.id(), "scenario effect applied", appliedAttrs);
    observabilityBus.incrementMetric(
        "chaos.effect.applied",
        Map.of("scenarioId", scenario.id(), "operation", context.operationType().name()));
    return new ScenarioContribution(
        this, scenario, scenario.effect(), sampleDelayMillis(matched), gateTimeout());
  }

  ChaosDiagnostics.ScenarioReport snapshot() {
    return new ChaosDiagnostics.ScenarioReport(
        scenario.id(),
        scenario.description(),
        scopeKey,
        scenario.scope(),
        state,
        matchedCount.get(),
        appliedCount.get(),
        reason());
  }

  void destroy() {
    stop();
  }

  ManualGate gate() {
    return gate;
  }

  /**
   * Returns the active {@link ClockSkewState} for this scenario, or {@code null} if this scenario
   * is not a clock-skew scenario or has not been started.
   */
  ClockSkewState clockSkewState() {
    return clockSkewState;
  }

  private ManagedStressor createStressorIfNeeded() {
    if (scenario.effect() instanceof ChaosEffect.HeapPressureEffect heapPressureEffect) {
      return new HeapPressureStressor(heapPressureEffect);
    }
    if (scenario.effect() instanceof ChaosEffect.KeepAliveEffect keepAliveEffect) {
      return new KeepAliveStressor(keepAliveEffect);
    }
    if (scenario.effect() instanceof ChaosEffect.MetaspacePressureEffect metaspacePressureEffect) {
      return new MetaspacePressureStressor(metaspacePressureEffect);
    }
    if (scenario.effect()
        instanceof ChaosEffect.DirectBufferPressureEffect directBufferPressureEffect) {
      return new DirectBufferPressureStressor(directBufferPressureEffect);
    }
    if (scenario.effect() instanceof ChaosEffect.GcPressureEffect gcPressureEffect) {
      return new GcPressureStressor(gcPressureEffect);
    }
    if (scenario.effect() instanceof ChaosEffect.FinalizerBacklogEffect finalizerBacklogEffect) {
      return new FinalizerBacklogStressor(finalizerBacklogEffect);
    }
    if (scenario.effect() instanceof ChaosEffect.DeadlockEffect deadlockEffect) {
      return new DeadlockStressor(deadlockEffect);
    }
    if (scenario.effect() instanceof ChaosEffect.ThreadLeakEffect threadLeakEffect) {
      return new ThreadLeakStressor(threadLeakEffect);
    }
    if (scenario.effect() instanceof ChaosEffect.ThreadLocalLeakEffect threadLocalLeakEffect) {
      return new ThreadLocalLeakStressor(threadLocalLeakEffect);
    }
    if (scenario.effect() instanceof ChaosEffect.MonitorContentionEffect monitorContentionEffect) {
      return new MonitorContentionStressor(monitorContentionEffect);
    }
    if (scenario.effect() instanceof ChaosEffect.CodeCachePressureEffect codeCachePressureEffect) {
      return new CodeCachePressureStressor(codeCachePressureEffect);
    }
    if (scenario.effect() instanceof ChaosEffect.SafepointStormEffect safepointStormEffect) {
      return new SafepointStormStressor(safepointStormEffect, instrumentationSupplier.get());
    }
    if (scenario.effect()
        instanceof ChaosEffect.StringInternPressureEffect stringInternPressureEffect) {
      return new StringInternPressureStressor(stringInternPressureEffect);
    }
    if (scenario.effect()
        instanceof ChaosEffect.ReferenceQueueFloodEffect referenceQueueFloodEffect) {
      return new ReferenceQueueFloodStressor(referenceQueueFloodEffect);
    }
    return null;
  }

  private void closeStressor() {
    final ManagedStressor current = stressor;
    stressor = null;
    if (current != null) {
      current.close();
    }
  }

  private boolean passesActivationWindow() {
    final Duration activeFor = scenario.activationPolicy().activeFor();
    if (activeFor == null || startedAt == null) {
      return true;
    }
    return clock.instant().isBefore(startedAt.plus(activeFor));
  }

  private boolean passesRateLimit() {
    final ActivationPolicy.RateLimit rateLimit = scenario.activationPolicy().rateLimit();
    if (rateLimit == null) {
      return true;
    }
    synchronized (this) {
      final long nowMillis = clock.millis();
      final long windowMillis = rateLimit.window().toMillis();
      if (nowMillis - rateWindowStartMillis >= windowMillis) {
        rateWindowStartMillis = nowMillis;
        rateWindowPermits = 0;
      }
      if (rateWindowPermits >= rateLimit.permits()) {
        return false;
      }
      rateWindowPermits++;
      return true;
    }
  }

  private boolean passesProbability(final long matched) {
    final double probability = scenario.activationPolicy().probability();
    if (probability >= 1.0d) {
      return true;
    }
    final long seed =
        scenario.activationPolicy().randomSeed() == null
            ? 0L
            : scenario.activationPolicy().randomSeed();
    final double draw =
        new java.util.SplittableRandom(seed ^ matched ^ scenario.id().hashCode()).nextDouble();
    return draw <= probability;
  }

  private long sampleDelayMillis(final long matched) {
    if (!(scenario.effect() instanceof ChaosEffect.DelayEffect delayEffect)) {
      return 0L;
    }
    final long min = delayEffect.minDelay().toMillis();
    final long max = delayEffect.maxDelay().toMillis();
    if (min == max) {
      return min;
    }
    final long seed =
        scenario.activationPolicy().randomSeed() == null
            ? 0L
            : scenario.activationPolicy().randomSeed();
    return new java.util.SplittableRandom(seed ^ matched ^ scenario.id().hashCode())
        .nextLong(min, max + 1);
  }

  private Duration gateTimeout() {
    if (!(scenario.effect() instanceof ChaosEffect.GateEffect gateEffect)) {
      return null;
    }
    return gateEffect.maxBlock();
  }
}
