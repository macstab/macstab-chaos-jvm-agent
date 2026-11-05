package com.macstab.chaos.jvm.core;

import org.jspecify.annotations.Nullable;

/**
 * Probes the running JVM at construction time to discover which optional features are available.
 *
 * <p>The agent's baseline is JDK 21 ({@code release=21} in the build, enforced at the bytecode
 * level), so virtual threads always exist; what varies is whether the runtime ships optional
 * modules (JFR).
 *
 * <h2>Probes performed</h2>
 *
 * <ul>
 *   <li><b>runtimeFeatureVersion</b> — {@code Runtime.version().feature()} (always available).
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
  private final int runtimeFeatureVersion;
  private final boolean jfrSupported;

  FeatureSet() {
    this.runtimeFeatureVersion = Runtime.version().feature();
    this.jfrSupported = probeJfr();
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
    } catch (final ClassNotFoundException ignored) {
      return false;
    }
  }

  /**
   * Returns the JDK feature-release version of the running JVM (e.g. {@code 17}, {@code 21}, {@code
   * 25}).
   *
   * <p>Sourced from {@code Runtime.version().feature()}.
   *
   * @return the feature-release version number
   */
  int runtimeFeatureVersion() {
    return runtimeFeatureVersion;
  }

  /**
   * Tests whether {@code thread} is a virtual thread.
   *
   * @param thread the thread to test; may be {@code null}
   * @return {@code true} if {@code thread} is a virtual thread
   */
  boolean isVirtualThread(final @Nullable Thread thread) {
    return thread != null && thread.isVirtual();
  }
}
