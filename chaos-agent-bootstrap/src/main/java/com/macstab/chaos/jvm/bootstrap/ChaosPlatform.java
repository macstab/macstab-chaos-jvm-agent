package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosControlPlane;

/**
 * Convenience facade over {@link ChaosAgentBootstrap} for consumer code that does not need to
 * depend directly on the bootstrap internals.
 *
 * <p>This class exposes only the two operations that external callers (test code, framework
 * integrations) typically need:
 *
 * <ul>
 *   <li>{@link #installLocally()} — ensure the agent is installed and return the control plane.
 *       Safe to call multiple times; idempotent.
 *   <li>{@link #current()} — retrieve the already-installed control plane, or throw if the agent
 *       has not been installed.
 * </ul>
 *
 * <p>Both methods delegate directly to {@link ChaosAgentBootstrap} without adding any state of
 * their own.
 */
public final class ChaosPlatform {
  private ChaosPlatform() {}

  /**
   * Ensures the chaos agent is installed in the current JVM and returns the {@link
   * ChaosControlPlane}.
   *
   * <p>Delegates to {@link ChaosAgentBootstrap#installForLocalTests()}, which performs a ByteBuddy
   * self-attach if the agent has not yet been installed. If the agent was already installed (e.g.,
   * via {@code -javaagent:}), the existing control plane is returned immediately.
   *
   * @return the active {@link ChaosControlPlane}; never null
   * @throws IllegalStateException if dynamic self-attach is unavailable on this JVM
   * @see ChaosAgentBootstrap#installForLocalTests()
   */
  public static ChaosControlPlane installLocally() {
    return ChaosAgentBootstrap.installForLocalTests();
  }

  /**
   * Returns the {@link ChaosControlPlane} for the already-installed chaos runtime.
   *
   * @return the active {@link ChaosControlPlane}; never null
   * @throws IllegalStateException if the chaos agent has not been installed in this JVM
   * @see ChaosAgentBootstrap#current()
   */
  public static ChaosControlPlane current() {
    return ChaosAgentBootstrap.current();
  }
}
