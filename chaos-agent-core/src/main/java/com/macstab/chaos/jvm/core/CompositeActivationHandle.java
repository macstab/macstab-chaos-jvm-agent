package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import java.util.List;

/**
 * A {@link com.macstab.chaos.jvm.api.ChaosActivationHandle} that aggregates multiple handles and
 * delegates every operation to all of them.
 *
 * <p>Used when a single {@link com.macstab.chaos.jvm.api.ChaosPlan} activation registers more than
 * one underlying scenario. All start/stop/destroy operations are broadcast to every delegate.
 *
 * <h2>State computation</h2>
 *
 * <p>{@link #state()} returns {@link
 * com.macstab.chaos.jvm.api.ChaosDiagnostics.ScenarioState#ACTIVE} if at least one delegate is
 * {@code ACTIVE}; {@link com.macstab.chaos.jvm.api.ChaosDiagnostics.ScenarioState#STOPPED} if every
 * delegate has reached the terminal {@code STOPPED} state; otherwise {@link
 * com.macstab.chaos.jvm.api.ChaosDiagnostics.ScenarioState#INACTIVE}.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The delegate list is an immutable copy created at construction time. Implementations delegate
 * to individually thread-safe handles; no additional synchronization is required in the composite.
 */
final class CompositeActivationHandle implements ChaosActivationHandle {
  private final String id;
  private final List<ChaosActivationHandle> delegates;
  private final Runnable onClose;
  private final java.util.concurrent.atomic.AtomicBoolean closed =
      new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * Creates a composite handle with the given logical ID and delegate handles.
   *
   * <p>The delegate list is defensively copied at construction time via {@link List#copyOf}.
   *
   * @param id a logical identifier for this composite, typically combining the source context and
   *     the plan name (e.g. {@code "session-plan:myPlan"})
   * @param delegates the individual handles to which all operations will be broadcast; must not be
   *     {@code null} or contain {@code null} elements
   */
  CompositeActivationHandle(final String id, final List<ChaosActivationHandle> delegates) {
    this(id, delegates, () -> {});
  }

  /**
   * Creates a composite handle that runs {@code onClose} after closing its delegates.
   *
   * @param id the logical identifier
   * @param delegates the individual handles
   * @param onClose run once the delegates are closed, even if one of them failed
   */
  CompositeActivationHandle(
      final String id, final List<ChaosActivationHandle> delegates, final Runnable onClose) {
    this.id = id;
    this.delegates = List.copyOf(delegates);
    this.onClose = onClose;
  }

  /**
   * Returns the logical identifier for this composite handle.
   *
   * @return the ID string supplied at construction time
   */
  @Override
  public String id() {
    return id;
  }

  /** Broadcasts {@link ChaosActivationHandle#start()} to all delegate handles. */
  @Override
  public void start() {
    broadcast("start", ChaosActivationHandle::start);
  }

  /** Broadcasts {@link ChaosActivationHandle#stop()} to all delegate handles. */
  @Override
  public void stop() {
    broadcast("stop", ChaosActivationHandle::stop);
  }

  /** Closes every scenario of the plan, removing each from the registry. */
  @Override
  public void close() {
    // Idempotent: a second close (try-with-resources plus framework teardown) must not run
    // onClose again after another plan took over the same ids.
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    try {
      broadcast("close", ChaosActivationHandle::close);
    } finally {
      onClose.run();
    }
  }

  /** Broadcasts {@link ChaosActivationHandle#release()} to all delegate handles. */
  @Override
  public void release() {
    broadcast("release", ChaosActivationHandle::release);
  }

  /**
   * Applies the given action to every delegate, collecting failures rather than letting the first
   * failure short-circuit the rest. {@code delegates.forEach(...)} stops on the first thrown
   * exception, leaking any delegate that came after it — for {@code stop} and {@code release} that
   * means leaked scenarios / unrestored state. Aggregate via {@code addSuppressed} so the caller
   * sees every failure, not just the one that happened to throw first.
   */
  private void broadcast(
      final String op, final java.util.function.Consumer<ChaosActivationHandle> action) {
    RuntimeException aggregate = null;
    for (final ChaosActivationHandle delegate : delegates) {
      try {
        action.accept(delegate);
      } catch (final RuntimeException perDelegate) {
        if (aggregate == null) {
          aggregate =
              new RuntimeException(
                  "one or more delegates failed during composite " + op, perDelegate);
        } else {
          aggregate.addSuppressed(perDelegate);
        }
      }
    }
    if (aggregate != null) {
      throw aggregate;
    }
  }

  /**
   * Returns the aggregated state across all delegates.
   *
   * <p>{@code ACTIVE} if any delegate is active, {@code STOPPED} if every delegate has reached the
   * terminal {@code STOPPED} state, otherwise {@code INACTIVE}. Collapsing post-teardown state to
   * {@code INACTIVE} would violate the {@link ChaosActivationHandle} contract: callers asserting
   * {@code handle.state() == STOPPED} after {@code destroy()} would never see the transition.
   *
   * @return the aggregated state across all delegates
   */
  @Override
  public long matchedCount() {
    long total = 0L;
    for (final ChaosActivationHandle handle : delegates) {
      total += handle.matchedCount();
    }
    return total;
  }

  @Override
  public long appliedCount() {
    long total = 0L;
    for (final ChaosActivationHandle handle : delegates) {
      total += handle.appliedCount();
    }
    return total;
  }

  @Override
  public ChaosDiagnostics.ScenarioState state() {
    boolean anyActive = false;
    boolean allStopped = !delegates.isEmpty();
    for (final ChaosActivationHandle handle : delegates) {
      final ChaosDiagnostics.ScenarioState delegateState = handle.state();
      if (delegateState == ChaosDiagnostics.ScenarioState.ACTIVE) {
        anyActive = true;
      }
      if (delegateState != ChaosDiagnostics.ScenarioState.STOPPED) {
        allStopped = false;
      }
    }
    if (anyActive) {
      return ChaosDiagnostics.ScenarioState.ACTIVE;
    }
    if (allStopped) {
      return ChaosDiagnostics.ScenarioState.STOPPED;
    }
    return ChaosDiagnostics.ScenarioState.INACTIVE;
  }
}
