package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
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
import org.jspecify.annotations.Nullable;

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
 *   <li><b>INACTIVE</b> — entered when the activation window expires or {@code maxApplications} is
 *       used up; evaluation is suspended.
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
 *   <li>Activation window: the time since start is within {@code activeFor}.
 *   <li>Warm-up: {@code matchedCount} has reached {@code activateAfterMatches}.
 *   <li>Probability: random draw passes the configured probability.
 *   <li>Rate limit: the fixed-window rate limit has not been exceeded (guarded by {@code
 *       synchronized(this)}).
 *   <li>Max applications: a reservation fits under {@code maxApplications}; the dispatcher later
 *       commits it when the contribution takes effect, or releases it.
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
 *
 * <p>Lifecycle events are published once per transition, by the thread that made it: {@code
 * STARTED} on {@code REGISTERED → ACTIVE}, {@code INACTIVE} (with its {@code reason}) on {@code
 * ACTIVE → INACTIVE}, {@code STOPPED} on every transition into {@code STOPPED} (from {@code
 * REGISTERED}, {@code ACTIVE} or {@code INACTIVE}). A repeated {@code stop()} publishes nothing. A
 * stop racing an expiry therefore yields {@code STOPPED} alone or {@code INACTIVE} and {@code
 * STOPPED}, never two of the same; the two come from different threads, so a listener may receive
 * them in either order.
 */
final class ScenarioController {
  /** The policy's replay log as a sorted array, or {@code null} without replay. */
  private final long @Nullable [] replayMatches;

  private final ChaosScenario scenario;
  private final String scopeKey;
  private final @Nullable String sessionId;
  private final Clock clock;
  private final ObservabilityBus observabilityBus;
  private final StressorFactory stressorFactory;
  private final ManualGate gate = new ManualGate();
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicLong matchedCount = new AtomicLong();
  private final AtomicLong appliedCount = new AtomicLong();

  /**
   * Guards {@link #reservedApplications} together with {@code appliedCount} for capped scenarios.
   */
  private final Object applicationBudgetLock = new Object();

  /** Thread that ends a stressor scenario when {@code activeFor} elapses; {@code null} if none. */
  private volatile @Nullable Thread expiryThread;

  /** Reservations taken by {@link #reserveApplication()} and not yet committed or released. */
  private long reservedApplications;

  private volatile ChaosDiagnostics.ScenarioState state;
  private volatile @Nullable String reason;
  private volatile @Nullable Instant startedAt;
  private volatile @Nullable ManagedStressor stressor;
  private volatile @Nullable ClockSkewState clockSkewState;
  private volatile long rateWindowStartMillis;
  private volatile long rateWindowPermits;

  /** Base seed for PRNG draws; derived once from the scenario's randomSeed (or 0). */
  private final long baseSeed;

  /** The constructor of an injected exception, resolved once; {@code null} for other effects. */
  private final @Nullable Constructor<? extends Throwable> injectedException;

  ScenarioController(
      final ChaosScenario scenario,
      final String scopeKey,
      final @Nullable String sessionId,
      final Clock clock,
      final ObservabilityBus observabilityBus,
      final Supplier<Optional<Instrumentation>> instrumentationSupplier) {
    this(
        scenario,
        scopeKey,
        sessionId,
        clock,
        observabilityBus,
        new StressorFactory(instrumentationSupplier));
  }

  /**
   * Creates a controller whose stressors come from {@code stressorFactory}; the control plane
   * shares one factory, and tests pass an overriding one (test seam, U-72 c2).
   */
  ScenarioController(
      final ChaosScenario scenario,
      final String scopeKey,
      final @Nullable String sessionId,
      final Clock clock,
      final ObservabilityBus observabilityBus,
      final StressorFactory stressorFactory) {
    this.replayMatches =
        scenario.activationPolicy().replayMatches() == null
            ? null
            : scenario.activationPolicy().replayMatches().stream()
                .mapToLong(Long::longValue)
                .toArray();
    this.scenario = scenario;
    this.scopeKey = scopeKey;
    this.sessionId = sessionId;
    this.clock = clock;
    this.observabilityBus = observabilityBus;
    this.stressorFactory = stressorFactory;
    // randomSeed==null must mean "non-deterministic sampling", not "seed 0". Seeding with 0 would
    // make splittableRandom(matched) collapse to baseSeed ^ matched ^ scenario.id().hashCode() — a
    // function of scenario ID and match count only, so every JVM run would reproduce the exact
    // same fire/no-fire sequence, and operators with probability<1.0 expecting stochastic sampling
    // across CI runs would get a fixed schedule. Draw the fallback seed from ThreadLocalRandom so
    // successive JVMs get distinct sequences; users who want reproducibility set randomSeed
    // explicitly.
    this.baseSeed =
        scenario.activationPolicy().randomSeed() != null
            ? scenario.activationPolicy().randomSeed()
            : ThreadLocalRandom.current().nextLong();
    // Always start REGISTERED regardless of start mode. The start() call
    // transitions to ACTIVE. Setting ACTIVE here while started=false produces a
    // diagnostic state that is observably wrong (ACTIVE but evaluating returns null). The one
    // window where a controller is ACTIVE with started=false is while start() delivers STARTED:
    // a call evaluated then is neither matched nor applied, so no effect precedes STARTED.
    this.state = ChaosDiagnostics.ScenarioState.REGISTERED;
    this.injectedException =
        scenario.effect() instanceof ChaosEffect.ExceptionInjectionEffect injection
            ? FailureFactory.injectedConstructor(injection.exceptionClassName())
            : null;
  }

  /**
   * Returns the constructor of the exception an {@link ChaosEffect.ExceptionInjectionEffect}
   * injects, resolved when this controller was created.
   *
   * @return the constructor; {@code null} when the effect injects no exception
   */
  @Nullable Constructor<? extends Throwable> injectedException() {
    return injectedException;
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
   * Returns the total number of times this scenario's effect was actually applied: counted in
   * {@link #commitApplication}, when the dispatcher applies a contribution that passed every
   * filter, not when {@code evaluate} returns it (a contribution that loses on precedence is never
   * counted).
   *
   * @return the cumulative applied count; always &ge; 0 and &le; {@link #matchedCount()}
   */
  long appliedCount() {
    return appliedCount.get();
  }

  /** Returns the session this controller is scoped to, or {@code null} for a JVM scenario. */
  @Nullable String sessionId() {
    return sessionId;
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
   *   <li>Sets {@code state} to {@code ACTIVE}, publishes {@code STARTED}, and only then sets
   *       {@code started} to {@code true} (unless a {@code stop()} or an expiry ended the scenario
   *       meanwhile): a call evaluated while {@code STARTED} is delivered is neither matched nor
   *       applied, so no {@code APPLIED} precedes {@code STARTED}.
   *   <li>Creates and starts a background {@link ManagedStressor} if the scenario's effect requires
   *       one (e.g., heap pressure, deadlock, thread leak). A stressor that cannot get its
   *       resources ({@link OutOfMemoryError}, also a thread the JVM cannot start) fails the start
   *       with {@link ChaosUnsupportedFeatureException}, and the controller stays {@code
   *       REGISTERED}.
   *   <li>Initialises {@link ClockSkewState} if the effect is a {@link
   *       com.macstab.chaos.jvm.api.ChaosEffect.ClockSkewEffect}.
   *   <li>Schedules the end of a stressor with a lifetime, once {@code STARTED} is delivered.
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
    // The stressor's set-up is the agent's own work (L3): built with the dispatch depth raised,
    // so no user scenario applies to its allocations, thread starts or pool submissions. The
    // STARTED publish below stays at the caller's depth.
    final ManagedStressor newStressor;
    ChaosDispatcher.incrementDispatchDepth();
    try {
      newStressor =
          stressorFactory.createIfNeeded(
              scenario.effect(), scenario.activationPolicy().allowDestructiveEffects());
    } catch (final OutOfMemoryError exhausted) {
      // The stressor could not get its resources: heap, direct memory, or a native thread (the
      // JVM reports a thread it cannot start as an OutOfMemoryError). The runtime cannot run it,
      // which is not a configuration error. Any other failure keeps its own type.
      final ChaosUnsupportedFeatureException unsupported =
          new ChaosUnsupportedFeatureException(
              "stressor "
                  + scenario.effect().getClass().getSimpleName()
                  + " could not get its resources at start: "
                  + exhausted.getMessage());
      unsupported.addSuppressed(exhausted);
      throw unsupported;
    } finally {
      ChaosDispatcher.decrementDispatchDepth();
    }
    // Carried from phase 1 to phase 2: the stressor's lifetime, counted from startNanos, and the
    // clock skew, which takes effect only in phase 2 like every other effect.
    long startNanos = 0L;
    Duration lifetime = null;
    ClockSkewState newClockSkewState = null;
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
        startNanos = System.nanoTime();
        startedAt = clock.instant();
        // Anchor the rate-limit window at activation, so the first window ends one window after
        // start rather than at an epoch-aligned instant.
        rateWindowStartMillis = startedAt.toEpochMilli();
        rateWindowPermits = 0;
      } finally {
        ChaosDispatcher.decrementDispatchDepth();
      }
      // Construct ClockSkewState before setting started/state so that if the constructor
      // throws (e.g. Duration.toNanos() overflow), started remains false and the controller
      // does not enter a half-initialised ACTIVE state with a null clockSkewState.
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
        newClockSkewState =
            new ClockSkewState(
                skewEffect, wallMillis, nanoRef, scenario.activationPolicy().activeFrom());
      }
      stressor = newStressor;
      // A skew left from an earlier run must not apply before phase 2 sets the new one.
      clockSkewState = null;
      // Phase 1: ACTIVE, but evaluate() still returns null until phase 2 sets the started flag.
      state = ChaosDiagnostics.ScenarioState.ACTIVE;
      reason = "started";
      lifetime = newStressor == null ? null : stressorLifetime();
    }
    observabilityBus.publish(
        ChaosEvent.Type.STARTED, scenario.id(), "scenario started", Map.of("scope", scopeKey));
    // Phase 2, after STARTED: re-check the state, because evaluate() checks only the started flag
    // and a stop() or an expiry since phase 1 must not be revived. The started flag also tells a
    // second start() that ran phase 1 again (INACTIVE → ACTIVE) not to schedule a second expiry.
    synchronized (this) {
      if (state != ChaosDiagnostics.ScenarioState.ACTIVE || started.get()) {
        return;
      }
      started.set(true);
      // Clock reads bypass evaluate(): a clock skew takes effect when its state is set.
      clockSkewState = newClockSkewState;
      // Under the monitor: a stop() that runs once start() has left it then finds the expiry
      // thread and cancels it, instead of a thread scheduled afterwards parking for activeFor.
      if (stressor != null && lifetime != null) {
        ChaosDispatcher.incrementDispatchDepth();
        try {
          scheduleStressorExpiry(lifetime, startNanos);
        } finally {
          ChaosDispatcher.decrementDispatchDepth();
        }
      }
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
   *   <li>Publishes a {@link com.macstab.chaos.jvm.api.ChaosEvent.Type#STOPPED} event to the {@link
   *       ObservabilityBus}, on the transition into {@code STOPPED} only.
   * </ul>
   *
   * <p>Once stopped, calls to {@link #evaluate(InvocationContext)} always return {@code null}; a
   * call already decided before {@code stop()} returned completes. Calling {@code stop()} on an
   * already-stopped controller is safe and has no effect, and publishes nothing.
   */
  void stop() {
    // Mirror start(): mutate state under the monitor, publish the STOPPED event after release so
    // listener code cannot stall concurrent evaluate() callers or deadlock by re-entering
    // synchronized methods on this controller.
    final boolean transitioned;
    synchronized (this) {
      transitioned = state != ChaosDiagnostics.ScenarioState.STOPPED;
      started.set(false);
      state = ChaosDiagnostics.ScenarioState.STOPPED;
      reason = "stopped";
      gate.release();
      closeStressor();
      cancelStressorExpiry();
      clockSkewState = null;
    }
    if (transitioned) {
      observabilityBus.publish(
          ChaosEvent.Type.STOPPED, scenario.id(), "scenario stopped", Map.of("scope", scopeKey));
    }
  }

  /**
   * Releases the {@link ManualGate} for this scenario, unblocking all threads currently waiting
   * inside a gate-effect hold without stopping the scenario. Subsequent invocations that pass the
   * full evaluation pipeline will re-enter the gate and wait again until the next release.
   *
   * <p>On an active scenario the gate closes again in the same step; only counting the latch down
   * would leave the gate open after the first release, so no later call would ever be held.
   * Publishes a {@link com.macstab.chaos.jvm.api.ChaosEvent.Type#RELEASED} event to the {@link
   * ObservabilityBus}.
   *
   * <p>On an {@code INACTIVE} scenario (its {@code maxApplications} spent, or expired) the gate
   * opens once and is not closed again: no later call is held, including one admitted just before
   * the scenario became inactive.
   *
   * <p>A no-op, without an event, for a scenario that has no gate effect or is neither active nor
   * inactive, as {@link com.macstab.chaos.jvm.api.ChaosActivationHandle#release()} documents.
   */
  void release() {
    synchronized (this) {
      if (!(scenario.effect() instanceof ChaosEffect.GateEffect)) {
        return;
      }
      if (state == ChaosDiagnostics.ScenarioState.ACTIVE) {
        gate.releaseAndReset();
      } else if (state == ChaosDiagnostics.ScenarioState.INACTIVE) {
        gate.release();
      } else {
        return;
      }
    }
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
   *   <li><b>Activation window</b>: the current instant must lie before {@code activeUntil} and
   *       within {@code activeFor} of {@link #start()}; otherwise the state becomes {@code
   *       INACTIVE} with reason {@code "expired"}. Before {@code activeFrom} the call is skipped
   *       without being counted.
   *   <li><b>Warm-up</b>: {@code matchedCount} must exceed {@code activateAfterMatches}.
   *   <li><b>Probability</b>: a random draw using {@link java.util.SplittableRandom} seeded with
   *       the configured seed XOR-ed with the match count and scenario ID hash must be below the
   *       configured probability.
   *   <li><b>Rate limit</b>: if a rate limit is configured, the number of permits issued in the
   *       current fixed window must not exceed the limit (guarded by {@code synchronized(this)}).
   *   <li><b>Max applications</b>: if {@code maxApplications} is set, a reservation must fit under
   *       the cap. Once committed applications reach the cap the state becomes {@code INACTIVE}
   *       with reason {@code "max applications reached"}.
   * </ol>
   *
   * <p>When all checks pass, a {@link ScenarioContribution} is returned carrying the effect, a
   * sampled delay, and the gate timeout (if any). {@code appliedCount} and the {@link
   * com.macstab.chaos.jvm.api.ChaosEvent.Type#APPLIED} event follow only when the dispatcher
   * commits the contribution.
   *
   * <p>This method does not throw checked exceptions; all internal errors are handled silently.
   *
   * @param context the invocation context describing the JVM operation that triggered evaluation;
   *     must not be {@code null}
   * @return a {@link ScenarioContribution} when chaos should be applied, or {@code null} when any
   *     check fails
   */
  @Nullable ScenarioContribution evaluate(final InvocationContext context) {
    if (!started.get()) {
      return null;
    }
    if (sessionId != null && !sessionId.equals(context.sessionId())) {
      return null;
    }
    if (!SelectorMatcher.matches(scenario.selector(), context)) {
      return null;
    }
    final Window window = window();
    if (window == Window.EXPIRED) {
      endOnItsOwn("expired");
      return null;
    }
    if (window == Window.NOT_YET) {
      return skipped("notYetActive", context);
    }
    final long matchCount = matchedCount.incrementAndGet();
    if (matchCount <= scenario.activationPolicy().activateAfterMatches()) {
      return skipped("warmUp", context);
    }
    // Probability is checked before rate-limit so that rate-limit permits are only consumed when
    // the effect will actually be applied. Checking rate-limit first would burn permits on
    // invocations that probability then rejects, making the effective throughput
    // `rate_limit * probability` instead of the operator-configured `rate_limit`.
    if (!passesProbability(matchCount)) {
      return skipped("probability", context);
    }
    final long ratePermitWindow = takeRatePermit();
    if (ratePermitWindow == RATE_DENIED) {
      return skipped("rateLimit", context);
    }
    // Re-check the started flag before we commit to applying the effect. stop() can flip
    // started.get() to false while we were in the rate-limit / probability / CAS branches
    // above. Without this recheck, a stop() happening concurrently with an in-flight evaluate()
    // will still increment appliedCount, publish an APPLIED event, and return a contribution —
    // the user gets a chaos effect after they explicitly turned the scenario off. The recheck
    // does not need a barrier beyond the volatile semantics of AtomicBoolean.
    if (!started.get()) {
      releaseRatePermit(ratePermitWindow);
      return null;
    }
    if (!reserveApplication()) {
      releaseRatePermit(ratePermitWindow);
      return skipped("maxApplications", context);
    }
    return new ScenarioContribution(
        this,
        scenario,
        scenario.effect(),
        sampleDelayNanos(matchCount),
        gateTimeout(),
        context,
        matchCount,
        ratePermitWindow);
  }

  /**
   * Reserves one application against {@code maxApplications} for a contribution that has not yet
   * been chosen. The dispatcher decides afterwards which contributions take effect (every delay,
   * the winning gate and the winning terminal action by precedence) and calls {@link
   * #commitApplication(InvocationContext, long, long)} for those and {@link
   * #releaseApplication(long)} for the rest. Nothing is counted or published as {@code APPLIED}
   * here, so a scenario that loses on precedence neither reports itself as applied nor uses up its
   * budget.
   *
   * <p>Committed applications plus outstanding reservations never exceed the cap. The scenario is
   * marked {@code INACTIVE} only once committed applications reach the cap, so a reservation that
   * is released can never leave the scenario inactive with budget left.
   *
   * @return {@code true} when a reservation was taken
   */
  private boolean reserveApplication() {
    final Long maxApplications = scenario.activationPolicy().maxApplications();
    if (maxApplications == null) {
      return true;
    }
    final boolean ended;
    synchronized (applicationBudgetLock) {
      if (appliedCount.get() < maxApplications) {
        if (appliedCount.get() + reservedApplications >= maxApplications) {
          return false;
        }
        reservedApplications++;
        return true;
      }
      ended = markInactive(MAX_APPLICATIONS_REACHED);
    }
    // Published after leaving the budget lock: listeners are untrusted code.
    if (ended) {
      publishInactive(MAX_APPLICATIONS_REACHED);
    }
    return false;
  }

  /**
   * Records a reserved application as applied: increments {@code appliedCount}, publishes {@code
   * APPLIED} and the {@code chaos.effect.applied} metric, and marks the scenario {@code INACTIVE}
   * when the cap is reached.
   *
   * @param context the invocation the contribution was evaluated for
   */
  void commitApplication(
      final InvocationContext context, final long delayNanos, final long matchNumber) {
    final Long maxApplications = scenario.activationPolicy().maxApplications();
    boolean capReached = false;
    if (maxApplications == null) {
      appliedCount.incrementAndGet();
    } else {
      synchronized (applicationBudgetLock) {
        reservedApplications--;
        capReached = appliedCount.incrementAndGet() >= maxApplications;
      }
    }
    // The application is reported before the end it causes: APPLIED, then INACTIVE.
    publishAppliedEvent(context, delayNanos, matchNumber);
    if (capReached) {
      endOnItsOwn(MAX_APPLICATIONS_REACHED);
    }
  }

  /**
   * Returns what a contribution that was not applied took: its reservation from {@link
   * #reserveApplication()} and its rate-limit permit.
   *
   * @param ratePermitWindow the window the contribution's permit was taken in
   */
  void releaseApplication(final long ratePermitWindow) {
    releaseRatePermit(ratePermitWindow);
    if (scenario.activationPolicy().maxApplications() == null) {
      return;
    }
    synchronized (applicationBudgetLock) {
      reservedApplications--;
    }
  }

  private void publishAppliedEvent(
      final InvocationContext context, final long delayNanos, final long matchNumber) {
    final Map<String, String> eventAttributes = new LinkedHashMap<>();
    eventAttributes.put("operation", context.operationType().name());
    // The scenario's 1-based match number: what ChaosDecisionRecorder records for replay.
    eventAttributes.put("match", Long.toString(matchNumber));
    eventAttributes.put("scope", scopeKey);
    eventAttributes.put("effectType", scenario.effect().getClass().getSimpleName());
    final long delayMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(delayNanos);
    if (delayMillis > 0L) {
      // The delay this application adds to the call: the source of injected-latency metrics.
      eventAttributes.put("delayMillis", Long.toString(delayMillis));
    }
    if (sessionId != null) {
      eventAttributes.put("sessionId", sessionId);
    }
    observabilityBus.publish(
        ChaosEvent.Type.APPLIED, scenario.id(), "scenario effect applied", eventAttributes);
    observabilityBus.incrementMetric(
        "chaos.effect.applied",
        Map.of("scenarioId", scenario.id(), "operation", context.operationType().name()));
  }

  ChaosDiagnostics.ScenarioReport snapshot() {
    // Read appliedCount before matchedCount: a match is always counted before its application, so
    // this order guarantees applied <= matched in the report even while calls are in flight.
    final long applied = appliedCount.get();
    final long matched = matchedCount.get();
    return new ChaosDiagnostics.ScenarioReport(
        scenario.id(),
        scenario.description(),
        scopeKey,
        scenario.scope(),
        state,
        matched,
        applied,
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
   * com.macstab.chaos.jvm.api.ChaosEffect.GateEffect} scenarios to block threads until {@link
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
   * for a {@link com.macstab.chaos.jvm.api.ChaosEffect.ClockSkewEffect} scenario. {@code
   * ClockSkewState} holds the reference wall-clock and monotonic timestamps captured at start time,
   * and provides the {@code applyMillis} / {@code applyNanos} methods used by {@link
   * ChaosRuntime#applyClockSkew} to compute the skewed value.
   *
   * @return the clock-skew state, or {@code null} if this scenario does not have a {@link
   *     com.macstab.chaos.jvm.api.ChaosEffect.ClockSkewEffect} or if the controller has not been
   *     started (or has been stopped)
   */
  @Nullable ClockSkewState clockSkewState() {
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
   *
   * <p>Publishes nothing: the caller publishes {@code INACTIVE} ({@link #publishInactive}) when
   * this returns {@code true}, after leaving every lock it holds.
   *
   * @return {@code true} when this call made the transition
   */
  private boolean markInactive(final String newReason) {
    synchronized (this) {
      if (state == ChaosDiagnostics.ScenarioState.ACTIVE) {
        state = ChaosDiagnostics.ScenarioState.INACTIVE;
        started.set(false);
        reason = newReason;
        // An expired scenario applies nothing more: release threads parked on its gate (an
        // indefinite gate would otherwise hold them until stop()). A spent maxApplications keeps
        // them: its last admitted caller reaches the gate only after this commit, and is held
        // until release() or stop(). Either way the background load ends.
        if ("expired".equals(newReason)) {
          gate.release();
        }
        closeStressor();
        return true;
      }
      return false;
    }
  }

  /**
   * Ends an active scenario on its own ({@link #markInactive}) and publishes {@code INACTIVE} when
   * this call made the transition. The caller must hold no lock.
   */
  private void endOnItsOwn(final String newReason) {
    if (markInactive(newReason)) {
      publishInactive(newReason);
    }
  }

  private void publishInactive(final String newReason) {
    observabilityBus.publish(
        ChaosEvent.Type.INACTIVE,
        scenario.id(),
        "scenario inactive: " + newReason,
        Map.of("scope", scopeKey, "reason", newReason));
  }

  /**
   * Returns how long a stressor started now may run: the shorter of {@code activeFor} and the time
   * left until {@code activeUntil}, or {@code null} when neither is set.
   */
  private @Nullable Duration stressorLifetime() {
    final ActivationPolicy policy = scenario.activationPolicy();
    Duration lifetime = policy.activeFor();
    if (policy.activeUntil() != null) {
      final Duration untilEnd;
      ChaosDispatcher.incrementDispatchDepth();
      try {
        untilEnd = Duration.between(clock.instant(), policy.activeUntil());
      } finally {
        ChaosDispatcher.decrementDispatchDepth();
      }
      final Duration bounded = untilEnd.isNegative() ? Duration.ZERO : untilEnd;
      lifetime = lifetime == null || bounded.compareTo(lifetime) < 0 ? bounded : lifetime;
    }
    return lifetime;
  }

  /**
   * Ends a stressor scenario when its lifetime ({@code activeFor}, {@code activeUntil}) elapses.
   * Stressors are never evaluated per call, so the window check in {@link
   * #evaluate(InvocationContext)} cannot end them; this thread does.
   *
   * <p>The expiry thread is agent-owned: it runs with the dispatch depth raised for its whole life,
   * so user scenarios (thread start, park, sleep) never apply to it. It ends early when the
   * scenario is stopped.
   */
  private void scheduleStressorExpiry(final Duration activeFor, final long fromNanos) {
    long timeoutNanos;
    try {
      timeoutNanos = activeFor.toNanos();
    } catch (final ArithmeticException overflow) {
      return; // beyond ~292 years: effectively never expires
    }
    final long deadline = fromNanos + timeoutNanos;
    expiryThread =
        AgentThreads.start(
            "macstab-chaos-expiry-" + scenario.id(),
            true,
            () -> {
              long remaining;
              while ((remaining = deadline - System.nanoTime()) > 0L) {
                java.util.concurrent.locks.LockSupport.parkNanos(this, remaining);
                if (Thread.currentThread().isInterrupted()) {
                  return;
                }
              }
              endOnItsOwn("expired");
            });
  }

  private void cancelStressorExpiry() {
    final Thread expiry = expiryThread;
    expiryThread = null;
    if (expiry != null) {
      expiry.interrupt();
    }
  }

  /**
   * Publishes SKIPPED for a call the policy kept the effect from, when anyone listens; returns
   * {@code null} so the caller can return it.
   */
  private @Nullable ScenarioContribution skipped(
      final String reason, final InvocationContext context) {
    if (observabilityBus.hasListeners()) {
      observabilityBus.publish(
          ChaosEvent.Type.SKIPPED,
          scenario.id(),
          "skipped: " + reason,
          Map.of("reason", reason, "operation", context.operationType().name(), "scope", scopeKey));
    }
    return null;
  }

  /** Where the current instant lies relative to the policy's time bounds. */
  enum Window {
    /** Before {@code activeFrom}: the scenario does not fire yet. */
    NOT_YET,
    /** Within every bound. */
    OPEN,
    /** At or after {@code activeUntil}, or {@code activeFor} has elapsed since start. */
    EXPIRED
  }

  /**
   * Checks the policy's time bounds against the controller's clock. The clock is the real system
   * clock in production; it is read inside a dispatch, where the agent's own clock hooks return the
   * real value, so an active clock skew cannot move the window.
   *
   * @return the window state; {@link Window#OPEN} when no bound is configured
   */
  Window window() {
    final ActivationPolicy policy = scenario.activationPolicy();
    final Duration activeFor = policy.activeFor();
    final Instant activeFrom = policy.activeFrom();
    final Instant activeUntil = policy.activeUntil();
    if (activeFor == null && activeFrom == null && activeUntil == null) {
      return Window.OPEN;
    }
    final Instant now = clock.instant();
    if (activeUntil != null && !now.isBefore(activeUntil)) {
      return Window.EXPIRED;
    }
    if (activeFor != null) {
      // Snapshot the volatile reference so the null-check and plus() observe the same value.
      final Instant capturedStart = startedAt;
      if (capturedStart != null) {
        // Instant.plus throws for a deadline past Instant.MAX; ActivationPolicy does not bound
        // activeFor, so treat an un-representable deadline as "never expires" instead of leaking
        // the exception into the instrumented application thread.
        try {
          if (!now.isBefore(capturedStart.plus(activeFor))) {
            return Window.EXPIRED;
          }
        } catch (final java.time.DateTimeException | ArithmeticException overflow) {
          // never expires
        }
      }
    }
    if (activeFrom != null && now.isBefore(activeFrom)) {
      return Window.NOT_YET;
    }
    return Window.OPEN;
  }

  /**
   * Checks the time bounds for a caller that bypasses {@link #evaluate} (clock skew), marking the
   * scenario {@code INACTIVE} once they have passed.
   *
   * @return {@code true} when the scenario may apply now
   */
  boolean withinWindow() {
    final Window window = window();
    if (window == Window.EXPIRED) {
      endOnItsOwn("expired");
      return false;
    }
    return window == Window.OPEN;
  }

  /** The reason of the INACTIVE transition when committed applications reach the cap. */
  private static final String MAX_APPLICATIONS_REACHED = "max applications reached";

  /** {@link #takeRatePermit()}: the scenario has no rate limit, so there is no permit. */
  private static final long NO_RATE_LIMIT = Long.MIN_VALUE;

  /** {@link #takeRatePermit()}: the window's permits are used up. */
  private static final long RATE_DENIED = Long.MAX_VALUE;

  /**
   * Takes a rate-limit permit for a contribution that may still not take effect.
   *
   * @return the start of the window the permit was taken in, {@link #NO_RATE_LIMIT} without a rate
   *     limit, or {@code RATE_DENIED} when the window has no permit left
   */
  private long takeRatePermit() {
    final ActivationPolicy.RateLimit rateLimit = scenario.activationPolicy().rateLimit();
    if (rateLimit == null) {
      return NO_RATE_LIMIT;
    }
    synchronized (this) {
      final long nowMillis = clock.millis();
      // Duration.toMillis() truncates sub-millisecond windows (e.g. Duration.ofNanos(500_000))
      // to 0. With a zero window, `nowMillis - start >= 0` is always true and the permit counter
      // resets on every call — the rate limit silently degrades to "unlimited". Clamp to a 1 ms
      // floor; anything finer has no hope of being observed through System.currentTimeMillis.
      final long windowMillis = Math.max(1L, rateLimit.window().toMillis());
      if (nowMillis < rateWindowStartMillis) {
        // The wall clock stepped back (NTP correction, manual change). Without re-anchoring,
        // elapsed stays negative and the window never rolls over: the permits remain exhausted
        // until the clock catches up with the old window start.
        rateWindowStartMillis = nowMillis;
        rateWindowPermits = 0;
      }
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
        return RATE_DENIED;
      }
      rateWindowPermits++;
      return rateWindowStartMillis;
    }
  }

  /**
   * Gives back a permit whose contribution did not take effect, unless its window has rolled over:
   * a permit of a past window must not enlarge the current one.
   */
  private void releaseRatePermit(final long ratePermitWindow) {
    if (ratePermitWindow == NO_RATE_LIMIT) {
      return;
    }
    synchronized (this) {
      if (rateWindowStartMillis == ratePermitWindow && rateWindowPermits > 0) {
        rateWindowPermits--;
      }
    }
  }

  private boolean passesProbability(final long matched) {
    if (replayMatches != null) {
      // A recorded decision log replaces the draw: fire on exactly these match numbers.
      return java.util.Arrays.binarySearch(replayMatches, matched) >= 0;
    }
    final double probability = scenario.activationPolicy().probability();
    if (probability >= 1.0d) {
      return true;
    }
    if (probability <= 0.0d) {
      return false;
    }
    return splittableRandom(matched).nextDouble() < probability;
  }

  private long sampleDelayNanos(final long matched) {
    if (!(scenario.effect() instanceof ChaosEffect.DelayEffect delayEffect)) {
      return 0L;
    }
    // Nanoseconds: a delay below a millisecond, or its sub-millisecond part, is a delay too.
    // DelayEffect.MAX_DELAY keeps both bounds far inside the long range.
    final long min = delayEffect.minDelay().toNanos();
    final long max = delayEffect.maxDelay().toNanos();
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
    // split(): an independent stream derived from the same seed, so the delay is not correlated
    // with the probability draw (a draw just below the threshold would otherwise always come with
    // a short delay).
    return splittableRandom(matched).split().nextLong(min, upperExclusive);
  }

  /**
   * Returns the seed of an effect's own random choice (a torn length, a corrupted bit, a DNS
   * shuffle) for the {@code matched}-th match: derived like the probability draw, so a fixed {@code
   * randomSeed} reproduces it, and salted per choice so it is independent of the draw.
   *
   * @param matched the 1-based match number
   * @param salt distinguishes the kinds of choice
   * @return the seed
   */
  long effectSeed(final long matched, final long salt) {
    return splittableRandom(matched).nextLong() ^ (salt * 0xbf58476d1ce4e5b9L);
  }

  /**
   * Returns a {@link SplittableRandom} seeded with {@code baseSeed ^ matched ^ scenarioIdHash}.
   *
   * <p>A new instance is constructed per call because {@link SplittableRandom} is not thread-safe.
   * The seed incorporates {@code matched} so that successive invocations produce different draws
   * even when the scenario's {@link com.macstab.chaos.jvm.api.ActivationPolicy#randomSeed()} is
   * fixed, giving reproducible-but-varied sampling across the lifetime of the scenario.
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

  private @Nullable Duration gateTimeout() {
    if (!(scenario.effect() instanceof ChaosEffect.GateEffect gateEffect)) {
      return null;
    }
    return gateEffect.maxBlock();
  }
}
