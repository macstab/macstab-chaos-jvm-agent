package com.macstab.chaos.core;

import java.lang.reflect.Method;

/**
 * Probes the running JVM at construction time to discover which optional features are available.
 *
 * <p>Some chaos behaviours (virtual-thread detection, JFR event emission) require JVM features that
 * may not be present on the minimum supported JDK version (17). This class performs the necessary
 * reflective probes once at startup and caches the results so that all other components can branch
 * on feature availability without repeated reflection.
 *
 * <h2>Probes performed</h2>
 *
 * <ul>
 *   <li><b>runtimeFeatureVersion</b> — {@code Runtime.version().feature()} (always available on JDK
 *       17+).
 *   <li><b>isVirtualMethod</b> — {@code Thread.isVirtual()} via reflection; present on JDK 21+. Set
 *       to {@code null} if the method is absent.
 *   <li><b>jfrSupported</b> — {@code Class.forName("jdk.jfr.FlightRecorder")} probe; {@code true}
 *       if JFR is available.
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Instances are effectively immutable after construction and may be shared freely across
 * threads.
 */
final class FeatureSet {
  private final Method isVirtualMethod;
  private final int runtimeFeatureVersion;
  private final boolean jfrSupported;

  FeatureSet() {
    this.runtimeFeatureVersion = Runtime.version().feature();
    Method method;
    try {
      method = Thread.class.getMethod("isVirtual");
    } catch (NoSuchMethodException ignored) {
      method = null;
    }
    this.isVirtualMethod = method;
    this.jfrSupported = probeJfr();
  }

  /**
   * Returns {@code true} if the running JVM supports virtual threads (JDK 21+).
   *
   * <p>Equivalent to checking whether {@code Thread.isVirtual()} was found via reflection at
   * construction time. When this returns {@code false}, {@link #isVirtualThread(Thread)} always
   * returns {@code false}.
   *
   * @return {@code true} if virtual-thread detection is available
   */
  boolean supportsVirtualThreads() {
    return isVirtualMethod != null;
  }

  /**
   * Returns {@code true} if the JFR event subsystem is available on this JVM.
   *
   * <p>Determined at construction time by probing for {@code jdk.jfr.FlightRecorder} via {@link
   * Class#forName(String, boolean, ClassLoader)}. JFR is absent on some JRE distributions and on
   * JDK builds that omit the {@code jdk.jfr} module.
   *
   * @return {@code true} if JFR is available
   */
  boolean jfrSupported() {
    return jfrSupported;
  }

  private static boolean probeJfr() {
    try {
      Class.forName("jdk.jfr.FlightRecorder", false, FeatureSet.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException ignored) {
      return false;
    }
  }

  /**
   * Returns the JDK feature-release version of the running JVM (e.g. {@code 17}, {@code 21}, {@code
   * 25}).
   *
   * <p>Sourced from {@code Runtime.version().feature()}, which is always available on JDK 17+.
   *
   * @return the feature-release version number
   */
  int runtimeFeatureVersion() {
    return runtimeFeatureVersion;
  }

  /**
   * Tests whether {@code thread} is a virtual thread.
   *
   * <p>Invokes the cached {@code Thread.isVirtual()} {@link Method} reflectively. Returns {@code
   * false} without throwing if:
   *
   * <ul>
   *   <li>{@code thread} is {@code null},
   *   <li>the JVM does not support virtual threads ({@link #supportsVirtualThreads()} is {@code
   *       false}), or
   *   <li>the reflective invocation fails for any reason.
   * </ul>
   *
   * @param thread the thread to test; may be {@code null}
   * @return {@code true} if {@code thread} is a virtual thread
   */
  boolean isVirtualThread(final Thread thread) {
    if (thread == null || isVirtualMethod == null) {
      return false;
    }
    try {
      return (boolean) isVirtualMethod.invoke(thread);
    } catch (ReflectiveOperationException ignored) {
      return false;
    }
  }
}
