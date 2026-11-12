package com.macstab.chaos.jvm.core;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
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
 *   <li><b>directMemoryLimit</b> — {@code -XX:MaxDirectMemorySize};
 *   <li><b>explicitGc</b> — {@code -XX:+DisableExplicitGC};
 *   <li><b>gcName</b> — the {@code -XX:+Use…GC} flag that is set;
 *   <li><b>finalization</b> — {@code --finalization=disabled} among the launch options.
 * </ul>
 *
 * <p>The last four are read through {@code jdk.management} ({@code HotSpotDiagnosticMXBean}, {@code
 * RuntimeMXBean}; never the platform MBean server), lazily at the first validation that needs one
 * and memoised, never at construction, which runs in {@code premain}. A value that cannot be read
 * allows what it would refuse.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Instances are effectively immutable after construction and may be shared freely across
 * threads.
 */
final class FeatureSet {
  /** {@link #directMemoryLimit()} when the limit cannot be read. */
  static final long UNKNOWN_LIMIT = -1L;

  /** {@link #gcName()} when the collector cannot be read. */
  static final String UNKNOWN_GC = "unknown";

  /** The HotSpot flags that select a collector, with the name {@link #gcName()} gives it. */
  private static final String[][] GC_FLAGS = {
    {"UseSerialGC", "Serial"},
    {"UseParallelGC", "Parallel"},
    {"UseG1GC", "G1"},
    {"UseZGC", "ZGC"},
    {"UseShenandoahGC", "Shenandoah"},
    {"UseEpsilonGC", "Epsilon"},
  };

  private static final String FINALIZATION_OPTION = "--finalization=";

  private final int runtimeFeatureVersion;
  private final boolean jfrSupported;
  private final Probe<Long> directMemoryLimit;
  private final Probe<Boolean> explicitGc;
  private final Probe<String> gcName;
  private final Probe<Boolean> finalization;

  FeatureSet() {
    this(
        Runtime.version().feature(),
        probeJfr(),
        new Probe<>(FeatureSet::probeExplicitGc),
        new Probe<>(FeatureSet::probeGcName),
        new Probe<>(FeatureSet::probeFinalization),
        new Probe<>(FeatureSet::probeDirectMemoryLimit));
  }

  /**
   * Creates a feature set with fixed values instead of probing the JVM (test seam, U-72 c7).
   *
   * @param runtimeFeatureVersion the JDK feature release
   * @param jfrSupported whether JFR is available
   * @param explicitGc whether {@code System.gc()} collects ({@code false} under {@code
   *     -XX:+DisableExplicitGC})
   * @param gcName the collector's name, or {@link #UNKNOWN_GC}
   * @param finalization whether the JVM runs finalizers ({@code false} under {@code
   *     --finalization=disabled})
   * @param directMemoryLimit the direct memory limit in bytes, or {@code -1} when unknown
   */
  FeatureSet(
      final int runtimeFeatureVersion,
      final boolean jfrSupported,
      final boolean explicitGc,
      final String gcName,
      final boolean finalization,
      final long directMemoryLimit) {
    this(
        runtimeFeatureVersion,
        jfrSupported,
        new Probe<>(() -> explicitGc),
        new Probe<>(() -> gcName),
        new Probe<>(() -> finalization),
        new Probe<>(() -> directMemoryLimit));
  }

  private FeatureSet(
      final int runtimeFeatureVersion,
      final boolean jfrSupported,
      final Probe<Boolean> explicitGc,
      final Probe<String> gcName,
      final Probe<Boolean> finalization,
      final Probe<Long> directMemoryLimit) {
    this.runtimeFeatureVersion = runtimeFeatureVersion;
    this.jfrSupported = jfrSupported;
    this.explicitGc = explicitGc;
    this.gcName = gcName;
    this.finalization = finalization;
    this.directMemoryLimit = directMemoryLimit;
  }

  /**
   * A value read from the JVM at its first use and memoised: the feature set is built in {@code
   * premain}, where the agent must not touch the management API, so every management probe waits
   * for the first validation that needs it. Two threads racing the first read both probe; the
   * values are equal.
   */
  private static final class Probe<T> {
    private final Supplier<T> read;
    private volatile @Nullable T value;

    Probe(final Supplier<T> read) {
      this.read = read;
    }

    T get() {
      T current = value;
      if (current == null) {
        current = read.get();
        value = current;
      }
      return current;
    }
  }

  /**
   * Returns the JVM's direct memory limit in bytes, or {@link #UNKNOWN_LIMIT}. Read lazily, at the
   * first direct-buffer pressure validation, and memoised.
   *
   * @return the limit, or {@link #UNKNOWN_LIMIT}
   */
  long directMemoryLimit() {
    return directMemoryLimit.get();
  }

  /**
   * Returns whether {@code System.gc()} starts a collection: {@code false} under {@code
   * -XX:+DisableExplicitGC}, {@code true} when the flag cannot be read. Read lazily, at the first
   * safepoint-storm validation, and memoised.
   *
   * @return whether an explicit collection request collects
   */
  boolean explicitGc() {
    return explicitGc.get();
  }

  /**
   * Returns the name of the JVM's collector ({@code G1}, {@code ZGC}, {@code Shenandoah}, {@code
   * Parallel}, {@code Serial}, {@code Epsilon}), or {@link #UNKNOWN_GC}. Read lazily and memoised.
   *
   * @return the collector's name
   */
  String gcName() {
    return gcName.get();
  }

  /**
   * Returns whether the JVM runs finalizers: {@code false} under {@code --finalization=disabled}
   * (JDK 18+), {@code true} when the launch options cannot be read. Read lazily, at the first
   * finalizer-backlog validation, and memoised.
   *
   * @return whether finalization is enabled
   */
  boolean finalization() {
    return finalization.get();
  }

  /**
   * Reads {@code -XX:MaxDirectMemorySize} through the HotSpot diagnostic MXBean (no platform MBean
   * server is created). {@code 0}, the default, means the JVM's maximum heap, as the JDK reads it.
   */
  private static Long probeDirectMemoryLimit() {
    return probe(
        () -> {
          final long configured = Long.parseLong(hotSpotOption("MaxDirectMemorySize"));
          return configured == 0L ? Runtime.getRuntime().maxMemory() : configured;
        },
        UNKNOWN_LIMIT);
  }

  /** Reads {@code -XX:+DisableExplicitGC}; unknown allows. */
  private static Boolean probeExplicitGc() {
    return probe(() -> "false".equals(hotSpotOption("DisableExplicitGC")), true);
  }

  /** Reads which {@code -XX:+Use…GC} flag is set. */
  private static String probeGcName() {
    return probe(() -> gcName(FeatureSet::hotSpotOption), UNKNOWN_GC);
  }

  /**
   * Returns the name of the collector whose {@code -XX:+Use…GC} flag {@code flagValue} reports set;
   * a flag this JVM lacks ({@link IllegalArgumentException}) is skipped.
   *
   * @param flagValue the value of a HotSpot flag
   * @return the collector's name, or {@link #UNKNOWN_GC}
   */
  static String gcName(final Function<String, String> flagValue) {
    for (final String[] flag : GC_FLAGS) {
      try {
        if (Boolean.parseBoolean(flagValue.apply(flag[0]))) {
          return flag[1];
        }
      } catch (final IllegalArgumentException absent) {
        // not a flag of this JVM (an experimental or excluded collector)
      }
    }
    return UNKNOWN_GC;
  }

  /** Reads {@code --finalization} from the JVM's launch options; unknown allows. */
  private static Boolean probeFinalization() {
    return probe(
        () ->
            finalizationEnabled(
                java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()),
        true);
  }

  /**
   * Returns whether launch options {@code arguments} leave finalization enabled: the last {@code
   * --finalization=} wins, as in the launcher.
   *
   * @param arguments the JVM's input arguments
   * @return {@code false} when the last {@code --finalization=} is {@code disabled}
   */
  static boolean finalizationEnabled(final List<String> arguments) {
    boolean enabled = true;
    for (final String argument : arguments) {
      if (argument.startsWith(FINALIZATION_OPTION)) {
        enabled = !"disabled".equals(argument.substring(FINALIZATION_OPTION.length()));
      }
    }
    return enabled;
  }

  /** The value of a HotSpot flag, through the diagnostic MXBean (no platform MBean server). */
  private static String hotSpotOption(final String name) {
    return java.lang.management.ManagementFactory.getPlatformMXBean(
            com.sun.management.HotSpotDiagnosticMXBean.class)
        .getVMOption(name)
        .getValue();
  }

  /**
   * Runs one management read with the dispatch depth raised, so no scenario applies to it (L3);
   * {@code unknown} when {@code jdk.management} is absent, the JVM is not HotSpot or the value is
   * unexpected.
   */
  private static <T> T probe(final Supplier<T> read, final T unknown) {
    DispatchDepth.increment();
    try {
      return read.get();
    } catch (final RuntimeException | LinkageError unavailable) {
      return unknown;
    } finally {
      DispatchDepth.decrement();
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
