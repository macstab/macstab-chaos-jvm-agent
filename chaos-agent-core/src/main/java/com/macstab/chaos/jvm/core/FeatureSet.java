package com.macstab.chaos.jvm.core;

import java.util.function.LongSupplier;
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
 *   <li><b>directMemoryLimit</b> — {@code -XX:MaxDirectMemorySize}, read lazily at first use, never
 *       at construction.
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Instances are effectively immutable after construction and may be shared freely across
 * threads.
 */
final class FeatureSet {
  /** {@link #directMemoryLimit()} when the limit cannot be read. */
  static final long UNKNOWN_LIMIT = -1L;

  /** {@link #directMemoryLimit} before the first probe. */
  private static final long NOT_PROBED = Long.MIN_VALUE;

  private final int runtimeFeatureVersion;
  private final boolean jfrSupported;
  private final LongSupplier directMemoryProbe;
  private volatile long directMemoryLimit = NOT_PROBED;

  FeatureSet() {
    this(Runtime.version().feature(), probeJfr(), FeatureSet::probeDirectMemoryLimit);
  }

  /**
   * Creates a feature set with fixed values instead of probing the JVM (test seam, U-72 c7).
   *
   * @param runtimeFeatureVersion the JDK feature release
   * @param jfrSupported whether JFR is available
   * @param directMemoryLimit the direct memory limit in bytes, or {@code -1} when unknown
   */
  FeatureSet(
      final int runtimeFeatureVersion, final boolean jfrSupported, final long directMemoryLimit) {
    this(runtimeFeatureVersion, jfrSupported, () -> directMemoryLimit);
  }

  private FeatureSet(
      final int runtimeFeatureVersion,
      final boolean jfrSupported,
      final LongSupplier directMemoryProbe) {
    this.runtimeFeatureVersion = runtimeFeatureVersion;
    this.jfrSupported = jfrSupported;
    this.directMemoryProbe = directMemoryProbe;
  }

  /**
   * Returns the JVM's direct memory limit in bytes, or {@link #UNKNOWN_LIMIT}. Read lazily, at the
   * first call (the first direct-buffer pressure validation), and memoised: the feature set is
   * built in {@code premain}, where the agent must not touch the management API.
   *
   * @return the limit, or {@link #UNKNOWN_LIMIT}
   */
  long directMemoryLimit() {
    long limit = directMemoryLimit;
    if (limit == NOT_PROBED) {
      limit = directMemoryProbe.getAsLong();
      directMemoryLimit = limit;
    }
    return limit;
  }

  /**
   * Reads {@code -XX:MaxDirectMemorySize} through the HotSpot diagnostic MXBean (no platform MBean
   * server is created). {@code 0}, the default, means the JVM's maximum heap, as the JDK reads it.
   * The read runs with the dispatch depth raised, so no scenario applies to it (L3).
   */
  private static long probeDirectMemoryLimit() {
    ChaosDispatcher.incrementDispatchDepth();
    try {
      final long configured =
          Long.parseLong(
              java.lang.management.ManagementFactory.getPlatformMXBean(
                      com.sun.management.HotSpotDiagnosticMXBean.class)
                  .getVMOption("MaxDirectMemorySize")
                  .getValue());
      return configured == 0L ? Runtime.getRuntime().maxMemory() : configured;
    } catch (final RuntimeException | LinkageError unavailable) {
      // jdk.management absent, not a HotSpot JVM, or an unexpected value: allow.
      return UNKNOWN_LIMIT;
    } finally {
      ChaosDispatcher.decrementDispatchDepth();
    }
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
