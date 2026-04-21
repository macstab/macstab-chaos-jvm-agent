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
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Per-scenario runtime controller that evaluates whether the scenario applies to a given {@link
 * InvocationContext} and enforces all activation constraints.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>A controller is created by {@link ScenarioRegistry} when a scenario is registered and moves
 * through the following states:
 *
 * <ol>
 *   <li><b>REGISTERED</b> — initial state; {@link #evaluate} always returns {@code null}.
 *   <li><b>ACTIVE</b> — entered via {@link #start()}; {@link #evaluate} participates in chaos
 *       decisions.
 *   <li><b>INACTIVE</b> — entered via manual control (gate hold or explicit pause); evaluation is
 *       suspended.
 *   <li><b>STOPPED</b> — terminal; entered via {@link #stop()} or {@link #destroy()}; {@link
 *       #evaluate} always returns {@code null} after this point.
 * </ol>
 *
 * <h2>Evaluation pipeline</h2>
 *
 * <p>Each call to {@link #evaluate(InvocationContext)} runs the following checks in order,
 * short-circuiting on the first failure:
 *
 * <ol>
 *   <li>Controller is ACTIVE (started and not stopped).
 *   <li>Session ID matches (when the scenario is session-scoped).
 *   <li>{@link SelectorMatcher} agrees the invocation context matches the scenario's selector.
 *   <li>Activation window: current time is within {@code activateAfter} and {@code deactivateAt}.
 *   <li>Warm-up: {@code matchedCount} has reached {@code activateAfterMatches}.
 *   <li>Rate limit: the sliding-window rate limit has not been exceeded (guarded by {@code
 *       synchronized(this)}).
 *   <li>Probability: random draw passes the configured probability.
 *   <li>Max-applications CAS: {@code appliedCount} is below {@code maxApplications} and the CAS
 *       increment succeeds.
 * </ol>
 *
 * <h2>Thread safety</h2>
 *
 * <p>All public methods are thread-safe. {@code matchedCount} and {@code appliedCount} are {@link
 * java.util.concurrent.atomic.AtomicLong}. Lifecycle transitions — both the {@code start()} /
 * {@code stop()} entry points and the {@code ACTIVE → INACTIVE} transitions driven from {@link
 * #evaluate(InvocationContext)} ({@code "expired"}, {@code "max applications reached"}) — are
 * performed inside {@code synchronized(this)} blocks. Transitions out of {@code STOPPED} are
 * refused: once a controller is stopped, no other writer may lift it back to {@code INACTIVE} or
 * {@code ACTIVE}. The rate-window fields ({@code rateWindowStartMillis}, {@code rateWindowPermits})
 * are guarded by the same monitor.
 *
 * <p>Observability-bus publishes happen strictly <em>outside</em> the monitor: callbacks are
 * untrusted code that may block, throw, or re-enter controller methods, so holding the lock across
 * them would stall every concurrent {@code evaluate()} caller and risk listener-induced deadlock.
 */
final class ScenarioController {
  private final ChaosScenario scenario;
  private final String scopeKey;
  private final String sessionId;
  private final Clock clock;
  private final ObservabilityBus observabilityBus;
  private final StressorFactory stressorFactory;
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

  /** Base seed for PRNG draws; derived once from the scenario's randomSeed (or 0). */
  private final long baseSeed;

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
    this.stressorFactory = new StressorFactory(instrumentationSupplier);
    // randomSeed==null must mean "non-deterministic sampling", not "seed 0". Seeding with 0 made
    // splittableRandom(matched) collapse to baseSeed ^ matched ^ scenario.id().hashCode() — a
    // function of scenario ID and match count only, so every JVM run reproduced the exact same
    // fire/no-fire sequence. Operators with probability<1.0 expecting stochastic sampling across
    // CI runs instead got a fixed schedule. Draw the fallback seed from ThreadLocalRandom so
    // successive JVMs get distinct sequences; users who want reproducibility set randomSeed
    // explicitly.
    this.baseSeed =
        scenario.activationPolicy().randomSeed() != null
            ? scenario.activationPolicy().randomSeed()
            : ThreadLocalRandom.current().nextLong();
    // Task 1: Always start REGISTERED regardless of start mode. The start() call
    // transitions to ACTIVE. Setting ACTIVE here while started=false produces a
    // diagnostic state that is observably wrong (ACTIVE but evaluating returns null).
    this.state = ChaosDiagnostics.ScenarioState.REGISTERED;
  }

  /**
   * Returns the registry key for this controller, formed by concatenating the scope key and the
   * scenario ID ({@code "<scopeKey>::<scenarioId>"}). The key is unique within a {@link
   * ScenarioRegistry} instance.
   *
   * @return the composite registry key; never {@code null}
   */
  String key() {
    return scopeKey + "::" + scenario.id();
  }

  /**
   * Returns the scope key that was assigned when this controller was registered. For JVM-scoped
   * scenarios the value is {@code "jvm"}; for session-scoped scenarios it is {@code
   * "session:<sessionId>"}.
   *
   * @return the scope key; never {@code null}
   */
  String scopeKey() {
    return scopeKey;
  }

  /**
   * Returns the immutable {@link ChaosScenario} descriptor that was used to create this controller.
   * Callers must not mutate the returned object.
   *
   * @return the scenario descriptor; never {@code null}
   */
  ChaosScenario scenario() {
    return scenario;
  }

  /**
   * Returns the total number of times the scenario's selector matched an invocation context since
   * {@link #start()} was called. This count is incremented <em>before</em> the warm-up, rate-limit,
   * probability, and max-applications checks, so it reflects all matching invocations regardless of
   * whether chaos was ultimately applied.
   *
   * @return the cumulative match count; always &ge; 0
   */
  long matchedCount() {
    return matchedCount.get();
  }

  /**
   * Returns the total number of times this scenario's effect was actually applied (i.e., the full
   * evaluation pipeline passed and a {@link ScenarioContribution} was returned). This count is
   * incremented only after all filters — warm-up, rate limit, probability, and max-applications —
   * have been satisfied.
   *
   * @return the cumulative applied count; always &ge; 0 and &le; {@link #matchedCount()}
   */
  long appliedCount() {
    return appliedCount.get();
  }

  /**
   * Returns the current lifecycle state of this controller.
   *
   * @return one of {@link ChaosDiagnostics.ScenarioState#REGISTERED}, {@link
   *     ChaosDiagnostics.ScenarioState#ACTIVE}, {@link ChaosDiagnostics.ScenarioState#INACTIVE}, or
   *     {@link ChaosDiagnostics.ScenarioState#STOPPED}; never {@code null}
   */
  ChaosDiagnostics.ScenarioState state() {
    return state;
  }

  /**
   * Returns the last diagnostic reason string explaining why the most recent call to {@link
   * #evaluate(InvocationContext)} returned {@code null}, or a lifecycle transition message such as
   * {@code "started"} or {@code "stopped"}. Intended for observability and debugging only; the
   * format is not part of the public API.
   *
   * @return the reason string; never {@code null} (returns {@code ""} if no reason has been set)
   */
  String reason() {
    return reason == null ? "" : reason;
  }

  /**
   * Transitions this controller from {@code REGISTERED} to {@code ACTIVE} and begins chaos
   * evaluation. Calling {@code start()} on a controller that is already {@code ACTIVE} is
   * idempotent; the state and counters are not reset.
   *
   * <p>Side effects on a fresh start:
   *
   * <ul>
   *   <li>Resets the {@link ManualGate} so that any threads waiting on a previous run are
   *       unblocked.
   *   <li>Records {@code startedAt} for activation-window calculations.
   *   <li>Sets {@code started} to {@code true} and {@code state} to {@code ACTIVE}.
   *   <li>Creates and starts a background {@link ManagedStressor} if the scenario's effect requires
   *       one (e.g., heap pressure, deadlock, thread leak).
   *   <li>Initialises {@link ClockSkewState} if the effect is a {@link
   *       com.macstab.chaos.api.ChaosEffect.ClockSkewEffect}.
   *   <li>Publishes a {@link com.macstab.chaos.api.ChaosEvent.Type#STARTED} event to the {@link
   *       ObservabilityBus}.
   * </ul>
   */
  void start() {
    // start() and stop() must be mutually exclusive against each other and against the
    // ACTIVE→INACTIVE transitions in evaluate(). Without synchronisation a stop() racing between
    // state=ACTIVE and the stressor assignment below could run closeStressor() before the
    // stressor exists, then start() would finish assigning a stressor that no subsequent stop()
    // would ever close — leaking its threads for the rest of JVM lifetime.
    //
    // The observabilityBus.publish(...) call is deliberately OUTSIDE the synchronized block:
    // listeners are untrusted code (user callbacks, logging I/O, re-entrant controller calls),
    // and holding the monitor across them would stall every concurrent evaluate() caller and
    // risk deadlock.
    // Construct the stressor BEFORE entering the synchronized block: stressor constructors
    // may spawn threads or allocate large arrays (DeadlockStressor, HeapPressureStressor).
    // Holding the monitor across this work blocks every concurrent evaluate(), stop(), and
    // JFR snapshot that needs synchronized(this) for the entire startup duration.
    // If state has changed (STOPPED raced us) we close the freshly-constructed stressor below.
    final ManagedStressor newStressor = stressorFactory.createIfNeeded(scenario.effect());
    final boolean transitioned;
    synchronized (this) {
      if (state == ChaosDiagnostics.ScenarioState.STOPPED) {
        if (newStressor != null) {
          newStressor.close();
        }
        return;
      }
      if (state == ChaosDiagnostics.ScenarioState.ACTIVE) {
        // Idempotent: a second start() on a running controller would otherwise create a fresh
        // stressor and clobber startedAt, leaking the previous stressor for the JVM lifetime.
        if (newStressor != null) {
          newStressor.close();
        }
        return;
      }
      // releaseAndReset() atomically unblocks any threads parked on the previous latch and
      // installs a fresh closed latch. Separate release()+reset() calls leave a window where
      // a thread entering await() after release() but before reset() observes the already-
      // counted-down latch and passes through without blocking.
      gate.releaseAndReset();
      // Guard these clock reads against an active ClockSkewEffect: start() is called at
      // DEPTH=0 (outside any dispatcher path), so clock intercepts would skew startedAt and
      // the ClockSkewState reference timestamps. A +1h skew on startedAt would silently turn
      // a 5-minute activeFor window into 65 minutes; a -1h skew would make it expire instantly.
      ChaosDispatcher.incrementDispatchDepth();
      try {
        startedAt = clock.instant();
      } finally {
        ChaosDispatcher.decrementDispatchDepth();
      }
      // Construct ClockSkewState before setting started/state so that if the constructor
      // throws (e.g. Duration.toNanos() overflow), started remains false and the controller
      // does not enter a half-initialised ACTIVE state with a null clockSkewState.
      final ClockSkewState newClockSkewState;
      if (scenario.effect() instanceof ChaosEffect.ClockSkewEffect skewEffect) {
        ChaosDispatcher.incrementDispatchDepth();
        final long wallMillis;
        final long nanoRef;
        try {
          wallMillis = System.currentTimeMillis();
          nanoRef = System.nanoTime();
        } finally {
          ChaosDispatcher.decrementDispatchDepth();
        }
        newClockSkewState = new ClockSkewState(skewEffect, wallMillis, nanoRef);
      } else {
        newClockSkewState = null;
      }
      clockSkewState = newClockSkewState;
      stressor = newStressor;
      started.set(true);
      state = ChaosDiagnostics.ScenarioState.ACTIVE;
      reason = "started";
      transitioned = true;
    }
    if (transitioned) {
      observabilityBus.publish(
          ChaosEvent.Type.STARTED, scenario.id(), "scenario started", Map.of("scope", scopeKey));
    }
  }

  /**
   * Transitions this controller to {@code STOPPED}, permanently ending chaos evaluation.
   *
   * <p>Side effects:
   *
   * <ul>
   *   <li>Sets {@code started} to {@code false} and {@code state} to {@code STOPPED}.
   *   <li>Releases the {@link ManualGate}, unblocking any threads that are waiting inside a
   *       gate-effect hold.
   *   <li>Closes and nulls out the background {@link ManagedStressor} (if any), stopping ongoing
   *       resource stress.
   *   <li>Clears {@link ClockSkewState} so the clock is no longer skewed.
   *   <li>Publishes a {@link com.macstab.chaos.api.ChaosEvent.Type#STOPPED} event to the {@link
   *       ObservabilityBus}.
   * </ul>
   *
   * <p>Once stopped, calls to {@link #evaluate(InvocationContext)} always return {@code null}.
   * Calling {@code stop()} on an already-stopped controller is safe and has no additional effect
   * beyond re-publishing the event.
   */
  void stop() {
    // Mirror start(): mutate state under the monitor, publish the STOPPED event after release so
    // listener code cannot stall concurrent evaluate() callers or deadlock by re-entering
    // synchronized methods on this controller.
    synchronized (this) {
      started.set(false);
      state = ChaosDiagnostics.ScenarioState.STOPPED;
      reason = "stopped";
      gate.release();
      closeStressor();
      clockSkewState = null;
    }
    observabilityBus.publish(
        ChaosEvent.Type.STOPPED, scenario.id(), "scenario stopped", Map.of("scope", scopeKey));
  }

  /**
   * Releases the {@link ManualGate} for this scenario, unblocking all threads currently waiting
   * inside a gate-effect hold without stopping the scenario. Subsequent invocations that pass the
   * full evaluation pipeline will re-enter the gate and wait again until the next release.
   *
   * <p>Publishes a {@link com.macstab.chaos.api.ChaosEvent.Type#RELEASED} event to the {@link
   * ObservabilityBus}.
   */
  void release() {
    gate.release();
    observabilityBus.publish(
        ChaosEvent.Type.RELEASED, scenario.id(), "manual gate released", Map.of("scope", scopeKey));
  }

  /**
   * Evaluates whether this scenario should apply a chaos effect for the given invocation context.
   * Each call runs the following checks in order, returning {@code null} on the first failure:
   *
   * <ol>
   *   <li><b>Started</b>: the controller must be in the {@code ACTIVE} state ({@code started ==
   *       true}).
   *   <li><b>Session ID</b>: if the scenario is session-scoped, {@code context.sessionId()} must
   *       equal the session ID this controller was registered with.
   *   <li><b>Selector match</b>: {@link SelectorMatcher#matches} must return {@code true} for the
   *       scenario's selector and the given context.
   *   <li><b>Activation window</b>: if an {@code activeFor} duration is configured, the elapsed
   *       time since {@link #start()} must not exceed it. A failure transitions the state to {@code
   *       INACTIVE} with reason {@code "expired"}.
   *   <li><b>Warm-up</b>: {@code matchedCount} must exceed {@code activateAfterMatches}.
   *   <li><b>Rate limit</b>: if a rate limit is configured, the number of permits issued in the
   *       current sliding window must not exceed the limit (guarded by {@code synchronized(this)}).
   *   <li><b>Probability</b>: a random draw using {@link java.util.SplittableRandom} seeded with
   *       the configured seed XOR-ed with the match count and scenario ID hash must be &le; the
   *       configured probability.
   *   <li><b>Max applications CAS</b>: if {@code maxApplications} is set, a compare-and-swap loop
   *       ensures {@code appliedCount} does not exceed the cap under concurrent access. A failure
   *       transitions the state to {@code INACTIVE} with reason {@code "max applications reached"}.
   * </ol>
   *
   * <p>When all checks pass, {@code appliedCount} is incremented, a {@link
   * com.macstab.chaos.api.ChaosEvent.Type#APPLIED} event is published, and a {@link
   * ScenarioContribution} is returned carrying the effect, a sampled delay, and the gate timeout
   * (if any).
   *
   * <p>This method does not throw checked exceptions; all internal errors are handled silently.
   *
   * @param context the invocation context describing the JVM operation that triggered evaluation;
   *     must not be {@code null}
   * @return a {@link ScenarioContribution} when chaos should be applied, or {@code null} when any
   *     check fails
   */
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
    if (!passesActivationWindow()) {
      markInactive("expired");
      return null;
    }
    final long matched = matchedCount.incrementAndGet();
    if (matched <= scenario.activationPolicy().activateAfterMatches()) {
      return null;
    }
    // Probability is checked before rate-limit so that rate-limit permits are only consumed when
    // the effect will actually be applied. Checking rate-limit first would burn permits on
    // invocations that probability then rejects, making the effective throughput
    // `rate_limit * probability` instead of the operator-configured `rate_limit`.
    if (!passesProbability(matched)) {
      return null;
    }
    if (!passesRateLimit()) {
      return null;
    }
    // Re-check the started flag before we commit to applying the effect. stop() can flip
    // started.get() to false while we were in the rate-limit / probability / CAS branches
    // above. Without this recheck, a stop() happening concurrently with an in-flight evaluate()
    // will still increment appliedCount, publish an APPLIED event, and return a contribution —
    // the user gets a chaos effect after they explicitly turned the scenario off. The recheck
    // does not need a barrier beyond the volatile semantics of AtomicBoolean.
    if (!started.get()) {
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
          markInactive("max applications reached");
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

  /**
   * Permanently shuts down this controller. Delegates to {@link #stop()}, which transitions the
   * state to {@code STOPPED}, releases the gate, closes any background stressor, and clears clock
   * skew state. Calling {@code destroy()} more than once is safe.
   *
   * <p>This method is the canonical teardown entry point called by {@link ScenarioRegistry} when a
   * controller is unregistered.
   */
  void destroy() {
    stop();
  }

  /**
   * Returns the {@link ManualGate} associated with this controller. The gate is used by {@link
   * com.macstab.chaos.api.ChaosEffect.GateEffect} scenarios to block threads until {@link
   * #release()} is called. The gate is never {@code null}; if the scenario does not use a gate
   * effect, the gate simply remains in the open state and does not block callers.
   *
   * @return the gate; never {@code null}
   */
  ManualGate gate() {
    return gate;
  }

  /**
   * Returns the active {@link ClockSkewState} that was initialised when {@link #start()} was called
   * for a {@link com.macstab.chaos.api.ChaosEffect.ClockSkewEffect} scenario. {@code
   * ClockSkewState} holds the reference wall-clock and monotonic timestamps captured at start time,
   * and provides the {@code applyMillis} / {@code applyNanos} methods used by {@link
   * ChaosRuntime#applyClockSkew} to compute the skewed value.
   *
   * @return the clock-skew state, or {@code null} if this scenario does not have a {@link
   *     com.macstab.chaos.api.ChaosEffect.ClockSkewEffect} or if the controller has not been
   *     started (or has been stopped)
   */
  ClockSkewState clockSkewState() {
    return clockSkewState;
  }

  private void closeStressor() {
    final ManagedStressor current = stressor;
    stressor = null;
    if (current != null) {
      current.close();
    }
  }

  /**
   * Downgrades {@code state} to {@code INACTIVE} with the given reason, but only when the current
   * state is {@code ACTIVE}. Guards against:
   *
   * <ul>
   *   <li>A concurrent {@code stop()} writing {@code STOPPED} being overwritten back to {@code
   *       INACTIVE} by an in-flight {@code evaluate()} that already read the pre-stop state.
   *   <li>Re-entering {@code INACTIVE} from an unrelated state transition.
   * </ul>
   *
   * The write is performed under the same monitor that guards {@code start()} / {@code stop()} so
   * the transition is linearisable with respect to all lifecycle edges.
   */
  private void markInactive(final String newReason) {
    synchronized (this) {
      if (state == ChaosDiagnostics.ScenarioState.ACTIVE) {
        state = ChaosDiagnostics.ScenarioState.INACTIVE;
        started.set(false);
        reason = newReason;
      }
    }
  }

  private boolean passesActivationWindow() {
    final Duration activeFor = scenario.activationPolicy().activeFor();
    if (activeFor == null) {
      return true;
    }
    // Snapshot the volatile reference to a local so the null-check and the plus() call observe
    // the same value. Without the snapshot a concurrent stop()→start() sequence could set
    // startedAt=null briefly (not today's contract but cheap to defend), and two separate reads
    // could return non-null then null. One read, one check.
    final Instant capturedStart = startedAt;
    if (capturedStart == null) {
      return true;
    }
    // Instant.plus throws DateTimeException when activeFor would push the deadline past
    // Instant.MAX, and ArithmeticException for nanos overflow. ActivationPolicy does not bound
    // activeFor, so a misconfigured plan with e.g. Duration.ofDays(Long.MAX_VALUE) would leak a
    // runtime exception into the instrumented application thread instead of expiring cleanly.
    // Treat an un-representable deadline as "effectively never expires" — the common intent
    // when someone writes a preposterously large duration.
    final Instant deadline;
    try {
      deadline = capturedStart.plus(activeFor);
    } catch (final java.time.DateTimeException | ArithmeticException overflow) {
      return true;
    }
    return clock.instant().isBefore(deadline);
  }

  private boolean passesRateLimit() {
    final ActivationPolicy.RateLimit rateLimit = scenario.activationPolicy().rateLimit();
    if (rateLimit == null) {
      return true;
    }
    synchronized (this) {
      final long nowMillis = clock.millis();
      // Duration.toMillis() truncates sub-millisecond windows (e.g. Duration.ofNanos(500_000))
      // to 0. With a zero window, `nowMillis - start >= 0` is always true and the permit counter
      // resets on every call — the rate limit silently degrades to "unlimited". Clamp to a 1 ms
      // floor; anything finer has no hope of being observed through System.currentTimeMillis.
      final long windowMillis = Math.max(1L, rateLimit.window().toMillis());
      if (nowMillis - rateWindowStartMillis >= windowMillis) {
        // Advance by whole window multiples rather than snapping to nowMillis, so the window
        // boundary stays aligned to the original start time. Resetting to nowMillis on every
        // expiry causes the effective window to drift: a burst that hits exactly at the boundary
        // extends the window by the inter-call gap, granting more permits than configured.
        final long elapsed = nowMillis - rateWindowStartMillis;
        rateWindowStartMillis += (elapsed / windowMillis) * windowMillis;
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
    if (probability <= 0.0d) {
      return false;
    }
    return splittableRandom(matched).nextDouble() < probability;
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
    // SplittableRandom.nextLong(origin, bound) requires bound > origin. Computing `max + 1` in
    // plain long arithmetic overflows to Long.MIN_VALUE when max is Long.MAX_VALUE, which fails
    // the bound check inside nextLong and crashes the hot path with IllegalArgumentException.
    // When max is already saturated, draw from [min, max] inclusive by sampling the legal range
    // and treating the upper bound as achievable; we cannot add 1 without overflow.
    final long upperExclusive;
    if (max == Long.MAX_VALUE) {
      upperExclusive = Long.MAX_VALUE;
    } else {
      upperExclusive = max + 1L;
    }
    return splittableRandom(matched).nextLong(min, upperExclusive);
  }

  /**
   * Returns a {@link SplittableRandom} seeded with {@code baseSeed ^ matched ^ scenarioIdHash}.
   *
   * <p>A new instance is constructed per call because {@link SplittableRandom} is not thread-safe.
   * The seed incorporates {@code matched} so that successive invocations produce different draws
   * even when the scenario's {@link com.macstab.chaos.api.ActivationPolicy#randomSeed()} is fixed,
   * giving reproducible-but-varied sampling across the lifetime of the scenario.
   *
   * @param matched the current value of {@link #matchedCount} at the time of evaluation
   * @return a freshly seeded {@link SplittableRandom}; never {@code null}
   */
  private SplittableRandom splittableRandom(final long matched) {
    // String.hashCode() is 32-bit; sign-extension into long means all scenario IDs with the same
    // lower-32-bit hash produce identical seeds. Use a 64-bit Fibonacci hash mix to spread the
    // id's contribution across the full 64-bit seed space.
    final long idMix = (long) scenario.id().hashCode() * 0x9e3779b97f4a7c15L;
    return new SplittableRandom(baseSeed ^ Long.rotateLeft(matched, 32) ^ idMix);
  }

  private Duration gateTimeout() {
    if (!(scenario.effect() instanceof ChaosEffect.GateEffect gateEffect)) {
      return null;
    }
    return gateEffect.maxBlock();
  }
}
