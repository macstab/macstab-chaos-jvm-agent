package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.dynamic.loading.InjectionClassLoader;
import net.bytebuddy.implementation.FixedValue;
import org.jspecify.annotations.Nullable;

/**
 * Fills the JVM code cache by generating and loading a large number of unique synthetic classes,
 * each containing arithmetic methods that the JIT compiler will compile.
 *
 * <p>Once the code cache is full, the JIT stops compiling new methods and the JVM falls back to
 * interpreted execution, causing a 10–50× performance degradation with no exceptions thrown. This
 * simulates production scenarios where the code cache is exhausted by plugin frameworks, OSGi
 * containers, or reflection-heavy serialization libraries.
 *
 * <p>Implementation: each class is loaded by a fresh isolated {@link ClassLoader} so that the class
 * objects are separately addressable; the loader defines the class itself ({@link
 * InjectionClassLoader}), without {@code sun.misc.Unsafe}. A strong reference to each loaded class
 * is retained to prevent GC and to force the JIT to compile the methods. After loading, each method
 * is invoked via reflection to trigger compilation.
 *
 * <p>{@link #close()} nulls all retained references. The code cache itself is only reclaimed when
 * the JIT deoptimizes the compiled methods, which may not happen immediately.
 */
final class CodeCachePressureStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger(CodeCachePressureStressor.class.getName());
  private static final String CLASS_NAME_PREFIX = "com.macstab.chaos.jvm.synthetic.CodeCacheClass$";

  private volatile @Nullable List<Class<?>> retainedClasses;

  /** Background thread driving JIT compilation; stopped and joined by {@link #close()}. */
  private volatile @Nullable Thread compilerThread;

  private static final long COMPILER_JOIN_TIMEOUT_MILLIS = 1_000L;

  CodeCachePressureStressor(final ChaosEffect.CodeCachePressureEffect effect) {
    final CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
    final long compilationTimeBefore =
        compilationBean != null && compilationBean.isCompilationTimeMonitoringSupported()
            ? compilationBean.getTotalCompilationTime()
            : -1L;

    final List<Class<?>> classes = new ArrayList<>(effect.classCount());
    for (int i = 0; i < effect.classCount(); i++) {
      // Unsealed, so InjectionClassLoader.Strategy can define the class in it after construction.
      final ByteArrayClassLoader loader = new ByteArrayClassLoader(null, false, Map.of());
      final Class<?> cls = generateAndLoadClass(i, effect.methodsPerClass(), loader);
      if (cls != null) {
        // The class keeps its loader alive.
        classes.add(cls);
      }
      // else: nothing references the loader, so a collection reclaims it
    }
    this.retainedClasses = List.copyOf(classes);

    // Trigger JIT compilation on a background daemon thread so the activation path is not
    // blocked for the duration of the 15,000-invocations-per-method compilation loop.
    if (!classes.isEmpty()) {
      final List<Class<?>> snapshot = List.copyOf(classes);
      final int methodsPerClass = effect.methodsPerClass();
      // Agent-owned thread: it runs with the dispatch depth raised so the user's REFLECTION_INVOKE
      // scenarios do not apply to its Method.invoke loop.
      compilerThread =
          AgentThreads.start(
              "chaos-code-cache-compiler",
              true,
              () -> {
                for (final Class<?> loadedClass : snapshot) {
                  if (retainedClasses == null || Thread.currentThread().isInterrupted()) {
                    return;
                  }
                  triggerCompilation(loadedClass, methodsPerClass);
                }
              });
    }

    if (compilationBean != null && compilationBean.isCompilationTimeMonitoringSupported()) {
      final long delta = compilationBean.getTotalCompilationTime() - compilationTimeBefore;
      LOGGER.fine(
          "CodeCachePressureStressor loaded "
              + classes.size()
              + " classes; JIT compilation time delta: "
              + delta
              + " ms");
    } else {
      LOGGER.fine("CodeCachePressureStressor loaded " + classes.size() + " classes");
    }
  }

  /** Returns the compiler thread, or {@code null} once {@link #close()} has run. */
  @Nullable Thread compilerThread() {
    return compilerThread;
  }

  @Override
  public void close() {
    // Stop the compiler thread first: it only checked for close between classes, so it kept
    // invoking (and compiling) methods of classes that were being released.
    final Thread compiler = compilerThread;
    compilerThread = null;
    if (compiler != null) {
      AgentThreads.stopAll(List.of(compiler), COMPILER_JOIN_TIMEOUT_MILLIS);
    }
    // Null classes last: retainedClassCount() == 0 signals that nothing anchors the classes.
    retainedClasses = null;
  }

  /** Returns the number of classes currently retained, or 0 after {@link #close()}. */
  int retainedClassCount() {
    final List<Class<?>> snapshot = retainedClasses;
    return snapshot == null ? 0 : snapshot.size();
  }

  private static @Nullable Class<?> generateAndLoadClass(
      final int index, final int methodsPerClass, final ByteArrayClassLoader loader) {
    final String className = CLASS_NAME_PREFIX + index + "$" + Long.toHexString(System.nanoTime());
    try {
      DynamicType.Builder<?> builder = new ByteBuddy().subclass(Object.class).name(className);
      for (int m = 0; m < methodsPerClass; m++) {
        final int methodIndex = m;
        builder =
            builder
                .defineMethod(
                    "compute" + methodIndex,
                    long.class,
                    net.bytebuddy.description.modifier.Visibility.PUBLIC)
                .withParameters(long.class)
                .intercept(FixedValue.value(0L));
      }
      try (DynamicType.Unloaded<?> unloaded = builder.make()) {
        return unloaded.load(loader, InjectionClassLoader.Strategy.INSTANCE).getLoaded();
      }
    } catch (final Exception exception) {
      LOGGER.fine(
          "CodeCachePressureStressor: failed to generate class " + index + ": " + exception);
      return null;
    }
  }

  private static void triggerCompilation(final Class<?> cls, final int methodsPerClass) {
    try {
      for (int m = 0; m < methodsPerClass; m++) {
        final java.lang.reflect.Method method = cls.getMethod("compute" + m, long.class);
        final Object instance = cls.getDeclaredConstructor().newInstance();
        // HotSpot's C2 compile threshold is 10 000 invocations and C1's is ~1 500; anything
        // below that leaves the method interpreted and puts nothing into the code cache — the
        // whole point of this stressor. 15 000 clears C2 comfortably per-method; iteration
        // counts beyond this give diminishing returns because the method is already compiled.
        for (int invocation = 0; invocation < 15_000; invocation++) {
          if (Thread.currentThread().isInterrupted()) {
            return;
          }
          method.invoke(instance, (long) invocation);
        }
      }
    } catch (final Exception exception) {
      LOGGER.fine("CodeCachePressureStressor: failed to invoke methods: " + exception);
    }
  }
}
