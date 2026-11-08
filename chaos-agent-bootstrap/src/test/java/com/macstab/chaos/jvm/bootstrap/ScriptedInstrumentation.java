package com.macstab.chaos.jvm.bootstrap;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;

/**
 * The JVM's instrumentation with scripted interference, for the start-up paths a healthy JVM never
 * takes: a hook that runs on the first bridge append or module redefinition (the first calls the
 * agent makes while installing), a failure when the bridge jar is appended to the bootstrap class
 * path, a failure when a transformer is removed, and a refused retransformation of named classes.
 * It counts the bridge appends and module redefinitions, and records the retransformations, it
 * sees.
 */
final class ScriptedInstrumentation implements Instrumentation {

  private final Instrumentation delegate;
  private volatile @Nullable Runnable onFirstInstallStep;
  private volatile @Nullable Throwable bridgeFailure;
  private volatile boolean failTransformerRemoval;
  private volatile java.util.Set<String> failRetransformOf = java.util.Set.of();
  private final java.util.List<java.util.List<String>> retransformCalls =
      new java.util.concurrent.CopyOnWriteArrayList<>();
  private final AtomicInteger appends = new AtomicInteger();
  private final AtomicInteger moduleRedefinitions = new AtomicInteger();

  ScriptedInstrumentation(final Instrumentation delegate) {
    this.delegate = delegate;
  }

  /**
   * Runs {@code hook} on the installing thread, once, before the first bridge append or module
   * redefinition, whichever comes first.
   */
  ScriptedInstrumentation onFirstInstallStep(final Runnable hook) {
    this.onFirstInstallStep = hook;
    return this;
  }

  /**
   * Makes every retransformation that includes one of {@code classNames} fail as the JVM refuses
   * one; none refused when empty.
   */
  ScriptedInstrumentation failRetransformOf(final String... classNames) {
    this.failRetransformOf = java.util.Set.of(classNames);
    return this;
  }

  /** Returns the class names of every retransformation requested so far, one list per call. */
  java.util.List<java.util.List<String>> retransformCalls() {
    return java.util.List.copyOf(retransformCalls);
  }

  /** Returns how many times a jar was appended to the bootstrap class path. */
  int appends() {
    return appends.get();
  }

  /** Returns how many times a module was redefined. */
  int moduleRedefinitions() {
    return moduleRedefinitions.get();
  }

  private void runFirstInstallStep() {
    final Runnable hook = onFirstInstallStep;
    if (hook != null) {
      onFirstInstallStep = null;
      hook.run();
    }
  }

  /** Throws {@code failure} (checked or not) where the bridge jar is appended. */
  ScriptedInstrumentation failBridgeInjection(final Throwable failure) {
    this.bridgeFailure = failure;
    return this;
  }

  /** Makes every transformer removal fail. */
  ScriptedInstrumentation failTransformerRemoval() {
    this.failTransformerRemoval = true;
    return this;
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(final Throwable failure) throws T {
    throw (T) failure;
  }

  @Override
  public void redefineModule(
      final Module module,
      final Set<Module> extraReads,
      final Map<String, Set<Module>> extraExports,
      final Map<String, Set<Module>> extraOpens,
      final Set<Class<?>> extraUses,
      final Map<Class<?>, java.util.List<Class<?>>> extraProvides) {
    moduleRedefinitions.incrementAndGet();
    runFirstInstallStep();
    delegate.redefineModule(module, extraReads, extraExports, extraOpens, extraUses, extraProvides);
  }

  @Override
  public void appendToBootstrapClassLoaderSearch(final JarFile jarfile) {
    appends.incrementAndGet();
    runFirstInstallStep();
    final Throwable failure = bridgeFailure;
    if (failure != null) {
      ScriptedInstrumentation.<RuntimeException>sneakyThrow(failure);
    }
    delegate.appendToBootstrapClassLoaderSearch(jarfile);
  }

  @Override
  public boolean removeTransformer(final ClassFileTransformer transformer) {
    if (failTransformerRemoval) {
      throw new IllegalStateException("transformer removal refused");
    }
    return delegate.removeTransformer(transformer);
  }

  @Override
  public void addTransformer(final ClassFileTransformer transformer, final boolean canRetransform) {
    delegate.addTransformer(transformer, canRetransform);
  }

  @Override
  public void addTransformer(final ClassFileTransformer transformer) {
    delegate.addTransformer(transformer);
  }

  @Override
  public boolean isRetransformClassesSupported() {
    return delegate.isRetransformClassesSupported();
  }

  @Override
  public void retransformClasses(final Class<?>... classes) throws UnmodifiableClassException {
    final java.util.List<String> names =
        java.util.Arrays.stream(classes).map(Class::getName).toList();
    retransformCalls.add(names);
    for (final String name : names) {
      if (failRetransformOf.contains(name)) {
        throw new UnmodifiableClassException("scripted refusal of " + name);
      }
    }
    delegate.retransformClasses(classes);
  }

  @Override
  public boolean isRedefineClassesSupported() {
    return delegate.isRedefineClassesSupported();
  }

  @Override
  public void redefineClasses(final ClassDefinition... definitions)
      throws ClassNotFoundException, UnmodifiableClassException {
    delegate.redefineClasses(definitions);
  }

  @Override
  public boolean isModifiableClass(final Class<?> theClass) {
    return delegate.isModifiableClass(theClass);
  }

  @Override
  @SuppressWarnings("rawtypes")
  public Class[] getAllLoadedClasses() {
    return delegate.getAllLoadedClasses();
  }

  @Override
  @SuppressWarnings("rawtypes")
  public Class[] getInitiatedClasses(final ClassLoader loader) {
    return delegate.getInitiatedClasses(loader);
  }

  @Override
  public long getObjectSize(final Object objectToSize) {
    return delegate.getObjectSize(objectToSize);
  }

  @Override
  public void appendToSystemClassLoaderSearch(final JarFile jarfile) {
    delegate.appendToSystemClassLoaderSearch(jarfile);
  }

  @Override
  public boolean isNativeMethodPrefixSupported() {
    return delegate.isNativeMethodPrefixSupported();
  }

  @Override
  public void setNativeMethodPrefix(final ClassFileTransformer transformer, final String prefix) {
    delegate.setNativeMethodPrefix(transformer, prefix);
  }

  @Override
  public boolean isModifiableModule(final Module module) {
    return delegate.isModifiableModule(module);
  }
}
