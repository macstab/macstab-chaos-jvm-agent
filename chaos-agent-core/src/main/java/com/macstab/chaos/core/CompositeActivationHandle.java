package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosDiagnostics;
import java.util.List;

/**
 * A {@link com.macstab.chaos.api.ChaosActivationHandle} that aggregates multiple handles and
 * delegates every operation to all of them.
 *
 * <p>Used when a single {@link com.macstab.chaos.api.ChaosPlan} activation registers more than one
 * underlying scenario. All start/stop/destroy operations are broadcast to every delegate.
 *
 * <h2>State computation</h2>
 *
 * <p>{@link #state()} returns {@link com.macstab.chaos.api.ChaosDiagnostics.ScenarioState#ACTIVE}
 * if at least one delegate is {@code ACTIVE}; otherwise it returns {@link
 * com.macstab.chaos.api.ChaosDiagnostics.ScenarioState#INACTIVE}. This means the composite is
 * considered active as long as any one of its members is active.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The delegate list is an immutable copy created at construction time. Implementations delegate
 * to individually thread-safe handles; no additional synchronization is required in the composite.
 */
final class CompositeActivationHandle implements ChaosActivationHandle {
  private final String id;
  private final List<ChaosActivationHandle> delegates;

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
    this.id = id;
    this.delegates = List.copyOf(delegates);
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
    delegates.forEach(ChaosActivationHandle::start);
  }

  /** Broadcasts {@link ChaosActivationHandle#stop()} to all delegate handles. */
  @Override
  public void stop() {
    delegates.forEach(ChaosActivationHandle::stop);
  }

  /** Broadcasts {@link ChaosActivationHandle#release()} to all delegate handles. */
  @Override
  public void release() {
    delegates.forEach(ChaosActivationHandle::release);
  }

  /**
   * Returns {@link com.macstab.chaos.api.ChaosDiagnostics.ScenarioState#ACTIVE} if at least one
   * delegate reports {@code ACTIVE}; otherwise returns {@link
   * com.macstab.chaos.api.ChaosDiagnostics.ScenarioState#INACTIVE}.
   *
   * @return the aggregated state across all delegates
   */
  @Override
  public ChaosDiagnostics.ScenarioState state() {
    return delegates.stream()
            .anyMatch(handle -> handle.state() == ChaosDiagnostics.ScenarioState.ACTIVE)
        ? ChaosDiagnostics.ScenarioState.ACTIVE
        : ChaosDiagnostics.ScenarioState.INACTIVE;
  }
}
