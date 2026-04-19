package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FixedValue;

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
 * objects are separately addressable. A strong reference to each loaded class is retained to
 * prevent GC and to force the JIT to compile the methods. After loading, each method is invoked
 * once via reflection to trigger compilation.
 *
 * <p>{@link #close()} nulls all retained references. The code cache itself is only reclaimed when
 * the JIT deoptimizes the compiled methods, which may not happen immediately.
 */
final class CodeCachePressureStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger(CodeCachePressureStressor.class.getName());
  private static final String CLASS_NAME_PREFIX = "com.macstab.chaos.synthetic.CodeCacheClass$";

  private volatile List<Class<?>> retainedClasses;
  private volatile List<URLClassLoader> retainedLoaders;

  CodeCachePressureStressor(final ChaosEffect.CodeCachePressureEffect effect) {
    final CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
    final long compilationTimeBefore =
        compilationBean != null && compilationBean.isCompilationTimeMonitoringSupported()
            ? compilationBean.getTotalCompilationTime()
            : -1L;

    final List<Class<?>> classes = new ArrayList<>(effect.classCount());
    final List<URLClassLoader> loaders = new ArrayList<>(effect.classCount());
    for (int i = 0; i < effect.classCount(); i++) {
      final URLClassLoader loader = new URLClassLoader(new URL[0], null);
      final Class<?> cls = generateAndLoadClass(i, effect.methodsPerClass(), loader);
      if (cls != null) {
        triggerCompilation(cls, effect.methodsPerClass());
        classes.add(cls);
        loaders.add(loader);
      } else {
        // Class generation failed — release the loader immediately rather than leaking it.
        try {
          loader.close();
        } catch (java.io.IOException ignored) {
          // Best-effort — there is no live class holding the loader, so GC will reclaim it.
        }
      }
    }
    this.retainedClasses = List.copyOf(classes);
    this.retainedLoaders = List.copyOf(loaders);

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

  @Override
  public void close() {
    retainedClasses = null;
    final List<URLClassLoader> loaders = retainedLoaders;
    retainedLoaders = null;
    if (loaders != null) {
      for (final URLClassLoader loader : loaders) {
        try {
          loader.close();
        } catch (java.io.IOException ignored) {
          // Best-effort: without a retained Class<?> the loader is unreachable once retainedClasses
          // is nulled, so GC + Metaspace reclamation is the ultimate path regardless.
        }
      }
    }
  }

  /** Returns the number of classes currently retained, or 0 after {@link #close()}. */
  int retainedClassCount() {
    final List<Class<?>> snapshot = retainedClasses;
    return snapshot == null ? 0 : snapshot.size();
  }

  private static Class<?> generateAndLoadClass(
      final int index, final int methodsPerClass, final URLClassLoader loader) {
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
        return unloaded.load(loader, ClassLoadingStrategy.Default.INJECTION).getLoaded();
      }
    } catch (Exception exception) {
      LOGGER.fine(
          "CodeCachePressureStressor: failed to generate class " + index + ": " + exception);
      return null;
    }
  }

  private static void triggerCompilation(final Class<?> cls, final int methodsPerClass) {
    try {
      for (int m = 0; m < methodsPerClass; m++) {
        final java.lang.reflect.Method method = cls.getMethod("compute" + m, long.class);
        // Invoke enough times to exceed the JIT compilation threshold (typically 10 000 calls).
        // A single call is sufficient to enqueue the method for compilation; repeated calls
        // increase the likelihood that the JIT actually compiles it before close().
        final Object instance = cls.getDeclaredConstructor().newInstance();
        for (int invocation = 0; invocation < 20; invocation++) {
          method.invoke(instance, (long) invocation);
        }
      }
    } catch (Exception exception) {
      LOGGER.fine("CodeCachePressureStressor: failed to invoke methods: " + exception);
    }
  }
}
