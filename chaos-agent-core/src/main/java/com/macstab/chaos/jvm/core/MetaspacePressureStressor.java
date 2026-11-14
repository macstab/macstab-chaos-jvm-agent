package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.dynamic.loading.InjectionClassLoader;
import org.jspecify.annotations.Nullable;

/**
 * Simulates a classloader leak by generating and loading synthetic classes into the JVM Metaspace.
 *
 * <p>Each synthetic class is a subclass of {@link Object} with {@code fieldsPerClass} public
 * instance {@code byte[]} fields. ByteBuddy is used to generate valid class bytecode without
 * external tooling or hand-rolled class file writing.
 *
 * <p>When {@link ChaosEffect.MetaspacePressureEffect#retain()} is {@code true}, strong references
 * to the generated {@link Class} objects are kept in this stressor. This mirrors the realistic
 * classloader leak scenario: the classloader is not GC-able because live references prevent
 * collection, and therefore Metaspace is not reclaimed.
 *
 * <p>When {@code retain=false} the stressor drops the classes and their loader right after loading:
 * a transient spike that the next collection that unloads classes reclaims.
 *
 * <p>The classes are defined by the loader itself ({@link InjectionClassLoader}), not injected
 * through {@code sun.misc.Unsafe}, so they load on a JVM that denies Unsafe memory access and no
 * deprecation warning is printed.
 *
 * <p>{@link #close()} drops the isolated classloader, making its classes eligible for Metaspace
 * reclamation. Note: actual Metaspace reclamation occurs only when GC collects the loader, which
 * may not be immediate.
 */
final class MetaspacePressureStressor implements ManagedStressor {

  private static final String CLASS_NAME_PREFIX = "com.macstab.chaos.jvm.synthetic.MetaspaceClass$";

  // Isolated classloader so generated classes can be reclaimed when the stressor is closed.
  // Injecting into the agent's classloader would anchor these classes for the JVM lifetime —
  // every activation/deactivation would permanently grow Metaspace.
  private volatile @Nullable ClassLoader isolatedLoader;
  private volatile @Nullable List<Class<?>> retainedClasses;

  MetaspacePressureStressor(final ChaosEffect.MetaspacePressureEffect effect) {
    // Unsealed, so InjectionClassLoader.Strategy can define classes in it after construction.
    final ByteArrayClassLoader loader = new ByteArrayClassLoader(null, false, Map.of());
    final List<Class<?>> classes = new ArrayList<>(effect.generatedClassCount());
    // A failure part way leaves nothing behind: the loader and the classes it defined are only
    // referenced from this constructor, so they are unloadable once it throws.
    for (int i = 0; i < effect.generatedClassCount(); i++) {
      classes.add(generateClass(loader, i, effect.fieldsPerClass()));
    }
    if (effect.retain()) {
      this.isolatedLoader = loader;
      this.retainedClasses = List.copyOf(classes);
    } else {
      // retain=false: a transient spike. The loader (which keeps every class it defined
      // reachable) is not held, so the classes become unloadable at the next collection.
      this.retainedClasses = List.of();
    }
  }

  @Override
  public void close() {
    isolatedLoader = null;
    retainedClasses = null;
  }

  /** Returns {@code true} while the stressor still holds its class loader. */
  boolean holdsLoader() {
    return isolatedLoader != null;
  }

  /**
   * Returns the number of classes currently held by strong reference, or 0 if retain=false or
   * {@link #close()} has been called. Useful for test assertions.
   */
  int retainedClassCount() {
    final List<Class<?>> snapshot = retainedClasses;
    return snapshot == null ? 0 : snapshot.size();
  }

  private static Class<?> generateClass(
      final ByteArrayClassLoader targetLoader, final int index, final int fieldsPerClass) {
    final String className = CLASS_NAME_PREFIX + index + "$" + Long.toHexString(System.nanoTime());
    DynamicType.Builder<?> builder = new ByteBuddy().subclass(Object.class).name(className);
    for (int f = 0; f < fieldsPerClass; f++) {
      builder =
          builder.defineField(
              "f" + f, byte[].class, net.bytebuddy.description.modifier.Visibility.PUBLIC);
    }
    try (DynamicType.Unloaded<?> unloaded = builder.make()) {
      return unloaded.load(targetLoader, InjectionClassLoader.Strategy.INSTANCE).getLoaded();
    }
  }
}
