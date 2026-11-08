package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import java.util.Optional;

/**
 * The supported entry point of the agent's bootstrap module: framework integrations, test code and
 * applications reach the agent through this class, not through {@link ChaosAgentBootstrap} or the
 * other bootstrap types, whose signatures may change in any release.
 *
 * <p>This class exposes the three operations that external callers (test code, framework
 * integrations) need:
 *
 * <ul>
 *   <li>{@link #installLocally()} — ensure the agent is installed and return the control plane.
 *       Safe to call multiple times; idempotent.
 *   <li>{@link #current()} — retrieve the already-installed control plane, or throw if the agent
 *       has not been installed.
 *   <li>{@link #installed()} — the already-installed control plane, or empty: the probe for "is the
 *       agent installed?", which never throws.
 * </ul>
 *
 * <p>The methods delegate directly to {@link ChaosAgentBootstrap} without adding any state of their
 * own.
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

  /**
   * Returns the {@link ChaosControlPlane} of the installed chaos runtime, or empty when the agent
   * is not (yet) installed. Never installs the agent and never throws: use it to ask whether the
   * agent is installed, instead of catching the exception of {@link #current()}.
   *
   * @return the active {@link ChaosControlPlane}, or empty
   */
  public static Optional<ChaosControlPlane> installed() {
    return Optional.ofNullable(ChaosAgentBootstrap.installedRuntime());
  }
}
