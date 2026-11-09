package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.OperationType;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The evaluation every dispatch domain shares: the operation index check, matching scenarios
 * against an invocation, resolving their contributions to one decision, and carrying it out (delay,
 * gate, terminal action, the byte count of a read or write).
 */
final class DispatchEngine {

  final FeatureSet featureSet;

  final ScopeContext scopeContext;

  final SessionHandoff sessionHandoff = new SessionHandoff();

  final ScenarioRegistry registry;

  /**
   * Returns the session id bound to the current thread, or {@code null} if none is active.
   *
   * @return the current thread's session id, or {@code null} when no session is bound
   */
  public @Nullable String currentSessionId() {
    return scopeContext.currentSessionId();
  }

  /**
   * Applies the decision of a call that can be delayed, failed or suppressed: delay and gate run
   * first, then a throwing terminal throws.
   *
   * @param decision the decision for the call
   * @return {@code true} when the call is suppressed
   * @throws Throwable the failure a throwing terminal carries
   */
  boolean suppressed(final RuntimeDecision decision) throws Throwable {
    applyDelayAndGate(decision);
    final TerminalAction terminal = decision.terminalAction();
    if (terminal instanceof TerminalAction.Throw thrown) {
      throw thrown.failure();
    }
    return terminal instanceof TerminalAction.Suppress;
  }

  /**
   * Applies a read's or write's decision and returns the byte count to transfer, flagging a
   * corrupting read. Delay and gate run first; a throwing terminal throws; {@code TrickleEffect}
   * and a torn write lower the count.
   */
  int transferCount(final RuntimeDecision decision, final int requested) throws Throwable {
    applyDelayAndGate(decision);
    final TerminalAction terminal = decision.terminalAction();
    if (terminal == null) {
      return requested;
    }
    switch (terminal) {
      case TerminalAction.Throw thrown -> throw thrown.failure();
      case TerminalAction.Timeout timeout -> {
        sleepNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout.waitMillis()));
        throw timeout.failure();
      }
      case TerminalAction.CapLength cap -> {
        return requested <= 0 ? requested : (int) Math.min(requested, cap.limit());
      }
      case TerminalAction.CorruptRead corrupt -> {
        // The bit the advice flips after the read derives from this seed (corruptionSeed()).
        final long[] seed = CORRUPTION_SEED.get();
        seed[0] = corrupt.seed();
        seed[1] = 1L;
        return corruptFlag(Math.max(requested, 0));
      }
      default -> {
        return requested;
      }
    }
  }

  /**
   * The seed of the corruption the current thread's read was just told to apply: {@code [seed,
   * set]}. Read once by {@link #corruptionSeed()}.
   */
  private static final ThreadLocal<long[]> CORRUPTION_SEED = new CorruptionSeed();

  /** {@link #CORRUPTION_SEED}'s type: agent-owned, so no THREAD_LOCAL scenario sees it (L3). */
  private static final class CorruptionSeed extends ThreadLocal<long[]> implements AgentOwned {
    @Override
    protected long[] initialValue() {
      return new long[2];
    }
  }

  /**
   * Returns the seed of the bit flip the current thread's read was just told to apply, so a fixed
   * {@code randomSeed} flips the same bit; a fresh random seed when there is none.
   *
   * @return the seed
   */
  public long corruptionSeed() {
    final long[] seed = CORRUPTION_SEED.get();
    if (seed[1] == 0L) {
      return java.util.concurrent.ThreadLocalRandom.current().nextLong();
    }
    seed[1] = 0L;
    return seed[0];
  }

  /** Whether the subject of a file operation is a {@code FileChannel}, which can write short. */
  static boolean isFileChannel(final @Nullable String className) {
    return className != null && className.endsWith("FileChannelImpl");
  }

  /**
   * Encodes a byte count whose read must be corrupted: {@code -(count) - 1}, always negative. The
   * advice decodes it and flips one bit in the bytes the read returned.
   *
   * @param count the byte count, {@code >= 0}
   * @return the encoded count
   */
  static int corruptFlag(final int count) {
    return -count - 1;
  }

  /**
   * Returns whether no scenario is indexed for {@code operation}: the dispatch can return the
   * unmodified result without building an {@link InvocationContext} (no allocation, no session or
   * class-name lookup).
   */
  boolean idle(final OperationType operation) {
    return registry.candidates(operation).length == 0;
  }

  RuntimeDecision evaluate(final InvocationContext context) {
    return evaluate(context, true);
  }

  /**
   * Evaluates a call whose return value may have nothing to corrupt.
   *
   * @param context the intercepted call
   * @param returnCorruptible whether the call returns a value of a known type; when not (a void
   *     method, a return type that cannot be loaded), a return-value corruption neither applies nor
   *     counts
   * @return the decision
   */
  RuntimeDecision evaluate(final InvocationContext context, final boolean returnCorruptible) {
    final List<ScenarioContribution> contributions = registry.match(context);
    if (contributions.isEmpty()) {
      return RuntimeDecision.none();
    }
    final boolean[] applied = new boolean[contributions.size()];
    boolean decided = false;
    try {
      long delayNanos = 0L;
      GateAction gateAction = null;
      int gateIndex = -1;
      int gatePrecedence = Integer.MIN_VALUE;
      TerminalAction terminalAction = null;
      int terminalIndex = -1;
      int terminalPrecedence = Integer.MIN_VALUE;
      for (int index = 0; index < contributions.size(); index++) {
        final ScenarioContribution contribution = contributions.get(index);
        // Saturating add: naive += with two large per-scenario delays would wrap to a negative
        // value, and the sleepNanos() helper treats negative as "skip sleep" — silently turning an
        // intended multi-minute delay into no delay at all is exactly the kind of quiet bug we
        // should not ship. Cap at Long.MAX_VALUE instead so the intent ("a very long delay") is
        // preserved; downstream Thread.sleep still accepts it.
        final long contributionDelay = contribution.delayNanos();
        if (contributionDelay > 0L && delayNanos > Long.MAX_VALUE - contributionDelay) {
          delayNanos = Long.MAX_VALUE;
        } else {
          delayNanos += contributionDelay;
        }
        // Tie-break rule for both gate and terminal: the ScenarioRegistry sorts contributions by
        // precedence DESC then id ASC, so on strict `>` the first-iterated (highest precedence,
        // lowest id within that precedence) wins and subsequent equal-precedence candidates do
        // not overwrite it. The first candidate always wins, whatever its precedence: comparing
        // it with the initial value alone would drop a sole Integer.MIN_VALUE gate or terminal.
        if (contribution.effect() instanceof ChaosEffect.GateEffect) {
          if (gateIndex < 0 || contribution.scenario().precedence() > gatePrecedence) {
            gateAction =
                new GateAction(contribution.controller().gate(), contribution.gateTimeout());
            gateIndex = index;
            gatePrecedence = contribution.scenario().precedence();
          }
          continue;
        }
        final TerminalAction candidate =
            terminalActionFor(
                context,
                contribution.effect(),
                contribution.scenario(),
                contribution.controller(),
                contribution.matchNumber(),
                returnCorruptible);
        if (candidate != null && candidate.kind() == TerminalKind.NOT_APPLICABLE) {
          // Neither takes effect nor counts: the contribution is released below.
          continue;
        }
        if (candidate == null) {
          // A delay (or an effect without a terminal action for this operation) always takes
          // effect: delays of all matching scenarios are summed.
          applied[index] = true;
        } else if (terminalIndex < 0 || contribution.scenario().precedence() > terminalPrecedence) {
          terminalAction = candidate;
          terminalIndex = index;
          terminalPrecedence = contribution.scenario().precedence();
        }
      }
      if (gateIndex >= 0) {
        applied[gateIndex] = true;
      }
      if (terminalIndex >= 0) {
        applied[terminalIndex] = true;
      }
      final RuntimeDecision decision = new RuntimeDecision(delayNanos, gateAction, terminalAction);
      decided = true;
      return decision;
    } finally {
      // Only contributions that take effect count as applied (and publish APPLIED); the others
      // return their maxApplications reservation. When building the decision failed, nothing is
      // applied and every reservation is returned, so a failure cannot leak reservations.
      for (int index = 0; index < contributions.size(); index++) {
        if (decided && applied[index]) {
          contributions.get(index).commit();
        } else {
          contributions.get(index).release();
        }
      }
    }
  }

  /** The target name of a QUEUE_OFFER on a queue without a capacity limit. */
  static final String UNBOUNDED_QUEUE = "unbounded";

  /** Salts of the effects' own random choices; see {@link ScenarioController#effectSeed}. */
  private static final long TORN_SALT = 1L;

  private static final long CORRUPT_SALT = 2L;

  private static final long SHUFFLE_SALT = 3L;

  @Nullable TerminalAction terminalActionFor(
      final InvocationContext context,
      final ChaosEffect effect,
      final ChaosScenario scenario,
      final ScenarioController controller,
      final long matchNumber,
      final boolean returnCorruptible) {
    final OperationType operationType = context.operationType();
    if (effect instanceof ChaosEffect.ConnectionResetEffect) {
      return new TerminalAction.AbortiveClose();
    }
    if (effect instanceof ChaosEffect.TrickleEffect trickleEffect) {
      // A read that asks for no more than the cap is not shortened: nothing to apply or count.
      if (context.byteCount() >= 0 && context.byteCount() <= trickleEffect.maxBytes()) {
        return new TerminalAction.NotApplicable();
      }
      return new TerminalAction.CapLength(trickleEffect.maxBytes());
    }
    if (effect instanceof ChaosEffect.DnsAnswerEffect answerEffect) {
      return new TerminalAction.DnsAnswer(
          new DnsShape(answerEffect, controller.effectSeed(matchNumber, SHUFFLE_SALT)));
    }
    if (effect instanceof ChaosEffect.DnsRewriteEffect rewriteEffect) {
      // InetAddress.getLocalHost() names no host: there is nothing to rewrite, and the call
      // must not count as an application.
      if (context.targetName() == null) {
        return new TerminalAction.NotApplicable();
      }
      return new TerminalAction.DnsRewrite(rewriteEffect.host());
    }
    if (effect instanceof ChaosEffect.RedirectEffect redirectEffect) {
      // A peer that is no InetSocketAddress (a Unix-domain connect) has no port, and a TCP
      // endpoint cannot replace it; an unresolved InetSocketAddress keeps its port and is
      // redirected.
      if (context.remotePort() == InvocationContext.UNKNOWN) {
        return new TerminalAction.NotApplicable();
      }
      return new TerminalAction.Redirect(redirectEffect);
    }
    if (effect instanceof ChaosEffect.TornWriteEffect) {
      // A channel write succeeds short; a socket stream write sends the prefix, then the advice
      // fails it as a reset (a stream write completes or throws). File streams and single bytes
      // cannot be torn; neither counts an application.
      final long requested = context.byteCount();
      final boolean tearable =
          context.operationType() == OperationType.SOCKET_WRITE
              || context.operationType() == OperationType.NIO_CHANNEL_WRITE
              || isFileChannel(context.targetClassName());
      if (requested < 2 || !tearable) {
        return new TerminalAction.NotApplicable();
      }
      final int torn =
          1
              + new java.util.SplittableRandom(controller.effectSeed(matchNumber, TORN_SALT))
                  .nextInt((int) requested - 1);
      return new TerminalAction.CapLength(torn);
    }
    if (effect instanceof ChaosEffect.CorruptReadEffect) {
      // The bytes of a transfer go from file to channel inside the kernel, and a read of no
      // bytes has none: there is nothing to flip.
      if ("transferTo".equals(context.targetName()) || context.byteCount() == 0) {
        return new TerminalAction.NotApplicable();
      }
      return new TerminalAction.CorruptRead(controller.effectSeed(matchNumber, CORRUPT_SALT));
    }
    if (effect instanceof ChaosEffect.TimeoutEffect) {
      // Like the native library, a timeout never fires on a call without one (infinite).
      if (context.timeoutMillis() <= 0) {
        return new TerminalAction.NotApplicable();
      }
      return new TerminalAction.Timeout(
          context.timeoutMillis(), () -> FailureFactory.timeout(operationType));
    }
    if (effect instanceof ChaosEffect.ErrnoEffect errnoEffect) {
      final String path =
          operationType == OperationType.PROCESS_START ? context.targetName() : context.path();
      final boolean javaIo = FailureFactory.isJavaIo(context);
      return new TerminalAction.Throw(
          () -> FailureFactory.errno(errnoEffect.errno(), operationType, path, javaIo));
    }
    if ((effect instanceof ChaosEffect.RejectEffect || effect instanceof ChaosEffect.SuppressEffect)
        && operationType == OperationType.QUEUE_OFFER
        && UNBOUNDED_QUEUE.equals(context.targetName())) {
      // A queue without a capacity limit is never full: a refused offer would only lose the
      // element (a scheduled pool drops the task), which no real offer to it does.
      return new TerminalAction.NotApplicable();
    }
    if (effect instanceof ChaosEffect.RejectEffect rejectEffect) {
      return rejectTerminal(context, rejectEffect.message());
    }
    if (effect instanceof ChaosEffect.SuppressEffect) {
      return suppressTerminal(operationType);
    }
    if (effect instanceof ChaosEffect.ExceptionalCompletionEffect exceptionalCompletionEffect) {
      return new TerminalAction.CompleteExceptionally(
          () ->
              FailureFactory.completionFailure(
                  exceptionalCompletionEffect.failureKind(),
                  exceptionalCompletionEffect.message()));
    }
    if (effect instanceof ChaosEffect.ExceptionInjectionEffect injectionEffect) {
      final java.lang.reflect.Constructor<? extends Throwable> constructor =
          java.util.Objects.requireNonNull(controller.injectedException(), "injectedException");
      return new TerminalAction.Throw(() -> FailureFactory.injected(injectionEffect, constructor));
    }
    if (effect instanceof ChaosEffect.ReturnValueCorruptionEffect corruptEffect) {
      // A void method, or a return type that cannot be loaded, has no value to corrupt.
      if (!returnCorruptible) {
        return new TerminalAction.NotApplicable();
      }
      return new TerminalAction.CorruptReturn(corruptEffect, scenario.id());
    }
    if (effect instanceof ChaosEffect.SpuriousWakeupEffect) {
      // A spurious selector wakeup semantically means: return from select() immediately with
      // zero ready keys instead of blocking. beforeNioSelect already implements that exact
      // shape as its SUPPRESS terminal path (return true → advice substitutes `return 0`), so
      // route SpuriousWakeupEffect through SUPPRESS rather than introducing a redundant kind.
      // CompatibilityValidator restricts this effect to NioSelector + NIO_SELECTOR_SELECT, so
      // no other dispatch path can observe this terminal here.
      return suppressTerminal(operationType);
    }
    return null;
  }

  TerminalAction rejectTerminal(final InvocationContext context, final String message) {
    return switch (context.operationType()) {
      case QUEUE_OFFER, ASYNC_COMPLETE, ASYNC_COMPLETE_EXCEPTIONALLY ->
          new TerminalAction.Return(Boolean.FALSE);
      case RESOURCE_LOAD -> new TerminalAction.Return(null);
      default -> new TerminalAction.Throw(() -> FailureFactory.reject(context, message));
    };
  }

  TerminalAction suppressTerminal(final OperationType operationType) {
    return switch (operationType) {
      case THREAD_START, VIRTUAL_THREAD_START ->
          new TerminalAction.Throw(FailureFactory::suppressedStart);
      case SYSTEM_EXIT_REQUEST -> new TerminalAction.Throw(FailureFactory::suppressedExit);
      case QUEUE_OFFER, ASYNC_COMPLETE, ASYNC_COMPLETE_EXCEPTIONALLY ->
          new TerminalAction.Return(Boolean.FALSE);
      // QUEUE_POLL: routed through beforeBooleanQueueOperation which returns the Boolean to the
      // advice. A non-null return triggers PollAdvice's skipOn = OnNonDefaultValue and the real
      // poll() is skipped — the skipped body's default reference return is null, which is the
      // intended SUPPRESS semantics ("queue returned nothing"). Boolean.FALSE is used only as a
      // non-null sentinel; its boolean value never reaches the caller.
      case QUEUE_POLL -> new TerminalAction.Return(Boolean.FALSE);
      case RESOURCE_LOAD -> new TerminalAction.Return(null);
      default -> new TerminalAction.Suppress();
    };
  }

  void applyPreDecision(final RuntimeDecision decision) throws Throwable {
    applyDelayAndGate(decision);
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction instanceof TerminalAction.Throw thrown) {
        throw thrown.failure();
      }
      if (terminalAction.kind() == TerminalKind.CORRUPT_RETURN) {
        // CORRUPT_RETURN is only meaningful once a return value exists — it is applied by
        // afterMethodExit, never here. Reaching this point means an effect or policy paired
        // a corrupt-return terminal with a pre-invocation operation type; silently swallowing
        // it would hide the misconfiguration and the scenario would appear to be a harmless
        // no-op. Fail loud so the invariant is enforced and the bug gets attributed to the
        // plan rather than the runtime.
        throw new IllegalStateException(
            "CORRUPT_RETURN terminal reached applyPreDecision — only valid from afterMethodExit");
      }
    }
  }

  /**
   * Applies the delay and then the gate of a decision, in the order {@link RuntimeDecision}
   * documents (delay, gate, terminal action). The delay of every matching scenario is applied even
   * when a terminal action then throws or returns early, so a THROW or RETURN terminal never skips
   * the delay.
   */
  void applyDelayAndGate(final RuntimeDecision decision) throws Throwable {
    sleepNanos(decision.delayNanos());
    applyGate(decision.gateAction());
  }

  /**
   * Holds the caller at the gate. An interrupt ends the wait: the interrupt flag is restored and
   * the call proceeds, so the intercepted JDK method observes the interrupt exactly as it would
   * have without chaos (sleep and wait throw, park returns, interruptible channels close). The
   * checked {@link InterruptedException} never escapes into methods that do not declare it, and the
   * flag is never left cleared.
   */
  void applyGate(final @Nullable GateAction gateAction) {
    if (gateAction == null) {
      return;
    }
    try {
      gateAction.gate().await(gateAction.maxBlock());
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  void sleepNanos(final long delayNanos) {
    if (delayNanos <= 0L) {
      return;
    }
    try {
      Thread.sleep(delayNanos / 1_000_000L, (int) (delayNanos % 1_000_000L));
    } catch (final InterruptedException interruptedException) {
      // Restore the interrupt flag and return. Wrapping this as IllegalStateException would break
      // the caller's interrupt contract: any subsequent blocking call they make will throw
      // InterruptedException anyway, which is the idiomatic signal that the thread should unwind.
      // A chaos-induced delay being cut short is not itself an error.
      Thread.currentThread().interrupt();
    }
  }

  RuntimeException propagate(final Throwable throwable) {
    if (throwable instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    // Gate blocking inside applyPreDecision() can unwind as InterruptedException — for example
    // an operator scenario that parks the caller until an external signal arrives, then a thread
    // pool shutdown interrupts the worker. Wrapping it without restoring the interrupt bit would
    // unwind the worker with the "chaos interception failed" exception but flag-clear, so the
    // next blocking call (Thread.sleep, Object.wait, Condition.await, …) would sit forever
    // instead of honouring the shutdown. Restore the interrupt flag before wrapping so subsequent
    // blocking calls see it; we still wrap in a RuntimeException because callers advised by Byte
    // Buddy cannot propagate a checked exception without advice-level `throws` declarations we
    // don't want to require everywhere.
    if (throwable instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
    return new IllegalStateException("chaos interception failed", throwable);
  }

  /**
   * The session for evaluating a task on a pool worker: the worker's own binding if it has one,
   * else the session the task was submitted under. The worker evaluates before the task's session
   * wrapper binds the session, so session-scoped EXECUTOR_WORKER_RUN and SCHEDULE_TICK scenarios
   * never matched work submitted from a session.
   */
  @Nullable String taskSession(final @Nullable Object task) {
    final String bound = scopeContext.currentSessionId();
    return bound != null ? bound : ScopeContext.sessionOf(task);
  }

  /**
   * Strips the JVM-generated synthetic lambda suffix from a class name so that {@code EXACT} and
   * {@code GLOB} task-class patterns work transparently for lambda tasks. A lambda defined in
   * {@code com.example.MyService} is named {@code com.example.MyService$$Lambda/0x0000...} on JDK
   * 21+ (hidden classes) and {@code com.example.MyService$$Lambda$14/0x0000...} on older JDKs; both
   * become {@code com.example.MyService}. Matching only the older form would leave the hex suffix
   * on every lambda on the supported JDKs.
   */
  static @Nullable String taskClassName(final @Nullable Object task) {
    if (task == null) {
      return null;
    }
    final String fullName = task.getClass().getName();
    final int lambdaSuffixIndex = fullName.indexOf("$$Lambda");
    return lambdaSuffixIndex == -1 ? fullName : fullName.substring(0, lambdaSuffixIndex);
  }

  DispatchEngine(final ChaosControlPlaneImpl controlPlane) {
    this.featureSet = controlPlane.featureSet();
    this.scopeContext = controlPlane.scopeContext();
    this.registry = controlPlane.registry();
  }
}
