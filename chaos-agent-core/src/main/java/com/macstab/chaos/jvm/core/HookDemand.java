package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.OperationType;
import java.util.Set;

/**
 * Callback through which the runtime asks the instrumentation layer to weave the hooks for a set of
 * operations before a scenario that needs them starts.
 *
 * <p>Hooks are woven on demand: a JVM with the agent attached but no scenario for, say, {@code
 * ThreadLocal} or clock reads carries no advice in those JDK methods. {@link #require(Set)} is
 * called synchronously during activation, so a scenario takes effect as soon as {@code activate()}
 * returns; implementations must be idempotent and thread-safe.
 */
@FunctionalInterface
public interface HookDemand {

  /** Demand that does nothing: used when no instrumentation is installed (plain unit tests). */
  HookDemand NONE = operations -> {};

  /**
   * Ensures the hooks serving {@code operations} are woven.
   *
   * @param operations the operations about to be evaluated; never {@code null}
   * @throws IllegalStateException if a hook cannot be woven; the activation fails
   */
  void require(Set<OperationType> operations);

  /**
   * Returns the names of the hook groups woven so far, reported in the diagnostics' runtime details
   * as {@code wovenHookGroups}.
   *
   * @return the woven group names; empty when nothing is woven
   */
  default Set<String> woven() {
    return Set.of();
  }
}
