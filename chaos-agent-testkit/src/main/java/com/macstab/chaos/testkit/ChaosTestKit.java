package com.macstab.chaos.testkit;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosSession;
import com.macstab.chaos.bootstrap.ChaosPlatform;

/**
 * Static helper providing one-liner access to the chaos agent from test code that does not use the
 * JUnit 5 {@link ChaosAgentExtension}.
 *
 * <p>Use this class when writing plain JUnit tests, TestNG tests, or programmatic test harnesses
 * that manage the chaos lifecycle manually:
 *
 * <pre>{@code
 * ChaosSession session = ChaosTestKit.openSession("my-test");
 * session.activate(scenario);
 * try (ChaosSession.ScopeBinding scope = session.bind()) {
 *     myService.call();
 * }
 * session.close();
 * }</pre>
 *
 * <p>For JUnit 5, prefer {@link ChaosAgentExtension}, which handles installation, session
 * lifecycle, and parameter injection automatically.
 *
 * <p>All methods are thread-safe. {@link #install()} is idempotent and may be called concurrently
 * from multiple test threads.
 */
public final class ChaosTestKit {
  private ChaosTestKit() {}

  /**
   * Ensures the chaos agent is installed in the current JVM and returns the {@link
   * ChaosControlPlane}.
   *
   * <p>If the agent was already installed (e.g., via {@code -javaagent:} or a previous call to this
   * method), the existing control plane is returned immediately. Otherwise, the agent is
   * self-attached via {@link ChaosPlatform#installLocally()}.
   *
   * @return the active {@link ChaosControlPlane}; never null
   * @throws IllegalStateException if dynamic self-attach is unavailable on this JVM
   */
  public static ChaosControlPlane install() {
    return ChaosPlatform.installLocally();
  }

  /**
   * Installs the chaos agent (if necessary) and opens a new {@link ChaosSession} with the given
   * display name.
   *
   * <p>Equivalent to {@code ChaosTestKit.install().openSession(displayName)}.
   *
   * <p>The caller is responsible for closing the session (preferably in a {@code finally} block or
   * try-with-resources) to stop all session-scoped scenarios and release session resources.
   *
   * @param displayName a human-readable name for the session, used in diagnostics and log messages;
   *     typically the test method name
   * @return a new open {@link ChaosSession}; never null
   */
  public static ChaosSession openSession(final String displayName) {
    return install().openSession(displayName);
  }
}
