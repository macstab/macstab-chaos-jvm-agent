package com.macstab.chaos.jvm.bootstrap;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;

/**
 * The JVM's instrumentation with scripted interference, for the start-up paths a healthy JVM never
 * takes: a hook that runs on the first module redefinition (the first call the agent makes while
 * installing), a failure when the bridge jar is appended to the bootstrap class path, and a failure
 * when a transformer is removed.
 */
final class ScriptedInstrumentation implements Instrumentation {

  private final Instrumentation delegate;
  private volatile @Nullable Runnable onFirstModuleRedefinition;
  private volatile @Nullable Throwable bridgeFailure;
  private volatile boolean failTransformerRemoval;

  ScriptedInstrumentation(final Instrumentation delegate) {
    this.delegate = delegate;
  }

  /** Runs {@code hook} on the installing thread, once, before the first module redefinition. */
  ScriptedInstrumentation onFirstModuleRedefinition(final Runnable hook) {
    this.onFirstModuleRedefinition = hook;
    return this;
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
    final Runnable hook = onFirstModuleRedefinition;
    if (hook != null) {
      onFirstModuleRedefinition = null;
      hook.run();
    }
    delegate.redefineModule(module, extraReads, extraExports, extraOpens, extraUses, extraProvides);
  }

  @Override
  public void appendToBootstrapClassLoaderSearch(final JarFile jarfile) {
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
