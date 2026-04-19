package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;

/**
 * Simulates a classloader leak by generating and loading synthetic classes into the JVM Metaspace.
 *
 * <p>Each synthetic class is a subclass of {@link Object} with {@code fieldsPerClass} static {@code
 * byte[]} fields. ByteBuddy is used to generate valid class bytecode without external tooling or
 * hand-rolled class file writing.
 *
 * <p>When {@link ChaosEffect.MetaspacePressureEffect#retain()} is {@code true}, strong references
 * to the generated {@link Class} objects are kept in this stressor. This mirrors the realistic
 * classloader leak scenario: the classloader is not GC-able because live references prevent
 * collection, and therefore Metaspace is not reclaimed.
 *
 * <p>When {@code retain=false} the stressor drops all references immediately after loading. The
 * classes may or may not be GC-collected depending on JVM GC behaviour — this models a partial or
 * delayed leak.
 *
 * <p>{@link #close()} nulls the retained class list, making the classes eligible for collection if
 * no other references exist. Note: Metaspace reclamation only occurs when the classloader that
 * defined the class is itself collected, which may not happen immediately or at all if the
 * classloader is still reachable.
 */
final class MetaspacePressureStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos");
  private static final String CLASS_NAME_PREFIX = "com.macstab.chaos.synthetic.MetaspaceClass$";

  // Isolated classloader so generated classes can be reclaimed when the stressor is closed.
  // Injecting into the agent's classloader would anchor these classes for the JVM lifetime —
  // every activation/deactivation would permanently grow Metaspace.
  private volatile URLClassLoader isolatedLoader;
  private volatile List<Class<?>> retainedClasses;

  MetaspacePressureStressor(final ChaosEffect.MetaspacePressureEffect effect) {
    final URLClassLoader loader = new URLClassLoader(new URL[0], null);
    final List<Class<?>> classes = new ArrayList<>(effect.generatedClassCount());
    for (int i = 0; i < effect.generatedClassCount(); i++) {
      classes.add(generateClass(loader, i, effect.fieldsPerClass()));
    }
    this.isolatedLoader = loader;
    this.retainedClasses = effect.retain() ? List.copyOf(classes) : List.of();
  }

  @Override
  public void close() {
    retainedClasses = null;
    final URLClassLoader loader = isolatedLoader;
    isolatedLoader = null;
    if (loader != null) {
      try {
        loader.close();
      } catch (final Exception e) {
        LOGGER.fine(() -> "MetaspacePressureStressor: isolated loader close failed: " + e);
      }
    }
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
      final URLClassLoader targetLoader, final int index, final int fieldsPerClass) {
    final String className = CLASS_NAME_PREFIX + index + "$" + Long.toHexString(System.nanoTime());
    DynamicType.Builder<?> builder = new ByteBuddy().subclass(Object.class).name(className);
    for (int f = 0; f < fieldsPerClass; f++) {
      builder =
          builder.defineField(
              "f" + f, byte[].class, net.bytebuddy.description.modifier.Visibility.PUBLIC);
    }
    try (DynamicType.Unloaded<?> unloaded = builder.make()) {
      return unloaded.load(targetLoader, ClassLoadingStrategy.Default.INJECTION).getLoaded();
    }
  }
}
