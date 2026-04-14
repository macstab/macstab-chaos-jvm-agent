package io.macstab.chaos.core;

import io.macstab.chaos.api.ActivationPolicy;
import io.macstab.chaos.api.ChaosDiagnostics;
import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosScenario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class ScenarioController {
  private final ChaosScenario scenario;
  private final String scopeKey;
  private final String sessionId;
  private final Clock clock;
  private final ObservabilityBus observabilityBus;
  private final ManualGate gate = new ManualGate();
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicLong matchedCount = new AtomicLong();
  private final AtomicLong appliedCount = new AtomicLong();
  private volatile ChaosDiagnostics.ScenarioState state;
  private volatile String reason;
  private volatile Instant startedAt;
  private volatile ManagedStressor stressor;
  private volatile long rateWindowStartMillis;
  private volatile long rateWindowPermits;

  ScenarioController(
      ChaosScenario scenario,
      String scopeKey,
      String sessionId,
      Clock clock,
      ObservabilityBus observabilityBus) {
    this.scenario = scenario;
    this.scopeKey = scopeKey;
    this.sessionId = sessionId;
    this.clock = clock;
    this.observabilityBus = observabilityBus;
    this.state =
        scenario.activationPolicy().startMode() == ActivationPolicy.StartMode.AUTOMATIC
            ? ChaosDiagnostics.ScenarioState.ACTIVE
            : ChaosDiagnostics.ScenarioState.INACTIVE;
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
    observabilityBus.publish(
        io.macstab.chaos.api.ChaosEvent.Type.STARTED,
        scenario.id(),
        "scenario started",
        Map.of("scope", scopeKey));
  }

  void stop() {
    started.set(false);
    state = ChaosDiagnostics.ScenarioState.STOPPED;
    reason = "stopped";
    gate.release();
    closeStressor();
    observabilityBus.publish(
        io.macstab.chaos.api.ChaosEvent.Type.STOPPED,
        scenario.id(),
        "scenario stopped",
        Map.of("scope", scopeKey));
  }

  void release() {
    gate.release();
    observabilityBus.publish(
        io.macstab.chaos.api.ChaosEvent.Type.RELEASED,
        scenario.id(),
        "manual gate released",
        Map.of("scope", scopeKey));
  }

  ScenarioContribution evaluate(InvocationContext context) {
    if (!started.get()) {
      return null;
    }
    if (sessionId != null && !sessionId.equals(context.sessionId())) {
      return null;
    }
    if (!SelectorMatcher.matches(scenario.selector(), context)) {
      return null;
    }
    long matched = matchedCount.incrementAndGet();
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
    long applied = appliedCount.incrementAndGet();
    Long maxApplications = scenario.activationPolicy().maxApplications();
    if (maxApplications != null && applied > maxApplications) {
      state = ChaosDiagnostics.ScenarioState.INACTIVE;
      reason = "max applications reached";
      return null;
    }
    Map<String, String> appliedAttrs = new LinkedHashMap<>();
    appliedAttrs.put("operation", context.operationType().name());
    appliedAttrs.put("scope", scopeKey);
    appliedAttrs.put("effectType", scenario.effect().getClass().getSimpleName());
    if (sessionId != null) {
      appliedAttrs.put("sessionId", sessionId);
    }
    observabilityBus.publish(
        io.macstab.chaos.api.ChaosEvent.Type.APPLIED,
        scenario.id(),
        "scenario effect applied",
        appliedAttrs);
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

  private ManagedStressor createStressorIfNeeded() {
    if (scenario.effect() instanceof ChaosEffect.HeapPressureEffect heapPressureEffect) {
      return new HeapPressureStressor(heapPressureEffect);
    }
    if (scenario.effect() instanceof ChaosEffect.KeepAliveEffect keepAliveEffect) {
      return new KeepAliveStressor(keepAliveEffect);
    }
    return null;
  }

  private void closeStressor() {
    ManagedStressor current = stressor;
    stressor = null;
    if (current != null) {
      current.close();
    }
  }

  private boolean passesActivationWindow() {
    Duration activeFor = scenario.activationPolicy().activeFor();
    if (activeFor == null || startedAt == null) {
      return true;
    }
    return clock.instant().isBefore(startedAt.plus(activeFor));
  }

  private boolean passesRateLimit() {
    ActivationPolicy.RateLimit rateLimit = scenario.activationPolicy().rateLimit();
    if (rateLimit == null) {
      return true;
    }
    synchronized (this) {
      long nowMillis = clock.millis();
      long windowMillis = rateLimit.window().toMillis();
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

  private boolean passesProbability(long matched) {
    double probability = scenario.activationPolicy().probability();
    if (probability >= 1.0d) {
      return true;
    }
    long seed =
        scenario.activationPolicy().randomSeed() == null
            ? 0L
            : scenario.activationPolicy().randomSeed();
    double draw =
        new java.util.SplittableRandom(seed ^ matched ^ scenario.id().hashCode()).nextDouble();
    return draw <= probability;
  }

  private long sampleDelayMillis(long matched) {
    if (!(scenario.effect() instanceof ChaosEffect.DelayEffect delayEffect)) {
      return 0L;
    }
    long min = delayEffect.minDelay().toMillis();
    long max = delayEffect.maxDelay().toMillis();
    if (min == max) {
      return min;
    }
    long seed =
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
