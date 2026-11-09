package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.lang.instrument.Instrumentation;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.jspecify.annotations.Nullable;

/**
 * Weaves method entry and exit hooks into the methods the registered {@link
 * ChaosSelector.MethodSelector}s name.
 *
 * <p>One transformer covers the union of all selectors and is rebuilt whenever that set changes:
 * the previous transformer is reset (its classes are retransformed back to their original bytecode)
 * and a new one is installed. Two selectors naming the same method therefore weave it once;
 * separate transformers per scenario would weave the advice twice and evaluate every call twice.
 *
 * <p>Woven: concrete methods (including interface default methods) of types whose name a selector's
 * class pattern matches, whose name and descriptor match the selector. Never woven: constructors,
 * static initialisers, abstract, native, synthetic and bridge methods, annotation types, and the
 * agent's own classes (excluded by the base builder).
 */
final class MethodWeaver {
  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos.jvm");

  private static Set<ChaosSelector.MethodSelector> woven = Set.of();
  private static @Nullable ResettableClassFileTransformer transformer;

  private MethodWeaver() {}

  /**
   * Weaves exactly {@code selectors}, replacing what was woven before. A call with the set already
   * woven does nothing.
   *
   * @param instrumentation the instrumentation handle
   * @param selectors the selectors to weave; empty removes every method hook
   * @param baseBuilder supplies the agent's base builder (retransformation, ignore rules)
   * @throws IllegalStateException if weaving fails; nothing is woven afterwards
   */
  static synchronized void weave(
      final Instrumentation instrumentation,
      final Set<ChaosSelector.MethodSelector> selectors,
      final Supplier<AgentBuilder> baseBuilder) {
    if (selectors.equals(woven)) {
      return;
    }
    reset(instrumentation);
    if (selectors.isEmpty()) {
      return;
    }
    final Set<ChaosSelector.MethodSelector> snapshot = Set.copyOf(selectors);
    try {
      transformer =
          baseBuilder
              .get()
              // Never a JDK class: the bootstrap and platform loaders' classes are not woven,
              // whatever the patterns say (the selector guard cannot spot every match-all form).
              .type(
                  typeMatcher(snapshot),
                  ElementMatchers.not(ElementMatchers.isBootstrapClassLoader())
                      .and(ElementMatchers.not(ElementMatchers.isExtensionClassLoader())))
              .transform(
                  (builder, type, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              net.bytebuddy.asm.Advice.to(MethodHookAdvice.Enter.class)
                                  .on(methodMatcher(snapshot, type, OperationType.METHOD_ENTER)))
                          .visit(
                              net.bytebuddy.asm.Advice.to(MethodHookAdvice.Exit.class)
                                  .on(
                                      methodMatcher(snapshot, type, OperationType.METHOD_EXIT)
                                          .and(
                                              ElementMatchers.not(
                                                  ElementMatchers.returns(void.class)))))
                          .visit(
                              net.bytebuddy.asm.Advice.to(MethodHookAdvice.VoidExit.class)
                                  .on(
                                      methodMatcher(snapshot, type, OperationType.METHOD_EXIT)
                                          .and(ElementMatchers.returns(void.class)))))
              .installOn(instrumentation);
    } catch (final RuntimeException failure) {
      HookCoverage.failed("METHODS", failure);
      throw new IllegalStateException("failed to weave method hooks for " + snapshot, failure);
    }
    woven = snapshot;
    LOGGER.fine(() -> "chaos method hooks woven for " + snapshot.size() + " selector(s)");
  }

  /**
   * Removes every method hook.
   *
   * @param instrumentation the instrumentation handle
   * @return {@code true} if method hooks were woven
   * @throws IllegalStateException if the JVM refused to retransform a class back; the transformer
   *     is removed all the same
   */
  static synchronized boolean reset(final Instrumentation instrumentation) {
    final ResettableClassFileTransformer current = transformer;
    transformer = null;
    woven = Set.of();
    if (current == null) {
      return false;
    }
    JdkInstrumentationInstaller.reset(current, instrumentation);
    return true;
  }

  /** Returns whether any method hook is woven; for diagnostics. */
  static synchronized boolean active() {
    return transformer != null;
  }

  private static ElementMatcher<TypeDescription> typeMatcher(
      final Set<ChaosSelector.MethodSelector> selectors) {
    return type -> {
      if (type.isAnnotation()) {
        return false;
      }
      final String name = type.getName();
      for (final ChaosSelector.MethodSelector selector : selectors) {
        if (selector.classPattern().matches(name)) {
          return true;
        }
      }
      return false;
    };
  }

  private static ElementMatcher.Junction<MethodDescription> methodMatcher(
      final Set<ChaosSelector.MethodSelector> selectors,
      final TypeDescription type,
      final OperationType operation) {
    final String typeName = type.getName();
    return new ElementMatcher.Junction.AbstractBase<>() {
      @Override
      public boolean matches(final MethodDescription method) {
        if (!method.isMethod()
            || method.isAbstract()
            || method.isNative()
            || method.isSynthetic()
            || method.isBridge()) {
          return false;
        }
        for (final ChaosSelector.MethodSelector selector : selectors) {
          if (selector.operations().contains(operation)
              && selector.classPattern().matches(typeName)
              && selector.methodNamePattern().matches(method.getInternalName())
              && selector.signaturePattern().matches(method.getDescriptor())) {
            return true;
          }
        }
        return false;
      }
    };
  }
}
