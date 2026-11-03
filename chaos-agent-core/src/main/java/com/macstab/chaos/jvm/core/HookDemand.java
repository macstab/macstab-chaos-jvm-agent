package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosSelector;
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

  /**
   * Returns the hooks that were woven into their type but matched none of its methods — the method
   * changed in this JDK and the hook is inactive — reported in the diagnostics' runtime details as
   * {@code unmatchedHooks}.
   *
   * @return {@code type: advice on matcher} of each inactive hook; empty when all are active
   */
  default Set<String> unmatched() {
    return Set.of();
  }

  /**
   * Returns the types whose weaving failed — none of their hooks is applied — reported in the
   * diagnostics' runtime details as {@code hookFailures}.
   *
   * @return {@code type: error} of each failed type; empty when every woven type was transformed
   */
  default Set<String> failures() {
    return Set.of();
  }

  /**
   * Weaves method entry and exit hooks into exactly the methods {@code selectors} name, removing
   * them from methods no selector names any more. Called with the method selectors of every
   * registered scenario: before a scenario with a {@link ChaosSelector.MethodSelector} is
   * registered, and after a scenario is removed. Implementations must be idempotent: a call with
   * the set already woven does nothing.
   *
   * @param selectors the method selectors to weave; empty to remove every method hook
   * @throws IllegalStateException if the hooks cannot be woven; the activation fails
   */
  default void methods(final Set<ChaosSelector.MethodSelector> selectors) {}
}
