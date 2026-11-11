package com.macstab.chaos.jvm.instrumentation;

import java.security.ProtectionDomain;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;
import org.jspecify.annotations.Nullable;

/**
 * Type matchers that walk a type's hierarchy, for which a type whose supertype cannot be resolved
 * is no target. A class extending a type of an optional dependency that is absent (Netty's Log4j 2
 * logger without Log4j 2) cannot be loaded anyway; reporting it as a weaving failure, with a
 * WARNING, would name a hook that was never lost. A failure while weaving a real target is still
 * reported.
 */
final class TypeMatchers {

  private TypeMatchers() {}

  /**
   * As {@link ElementMatchers#isSubTypeOf(Class)}, with an unresolvable supertype not matching.
   *
   * @param type the supertype
   * @param <T> the matched type
   * @return the matcher
   */
  static <T extends TypeDescription> ElementMatcher.Junction<T> subTypeOf(final Class<?> type) {
    return new Resolvable<>(ElementMatchers.isSubTypeOf(type));
  }

  /**
   * As {@link ElementMatchers#hasSuperType(ElementMatcher)}, with an unresolvable supertype not
   * matching.
   *
   * @param matcher the supertype's matcher
   * @param <T> the matched type
   * @return the matcher
   */
  static <T extends TypeDescription> ElementMatcher.Junction<T> superType(
      final ElementMatcher<? super TypeDescription> matcher) {
    return new Resolvable<>(ElementMatchers.hasSuperType(matcher));
  }

  /**
   * As {@link ElementMatchers#hasSuperClass(ElementMatcher)}, with an unresolvable superclass not
   * matching.
   *
   * @param matcher the superclass's matcher
   * @param <T> the matched type
   * @return the matcher
   */
  static <T extends TypeDescription> ElementMatcher.Junction<T> superClass(
      final ElementMatcher<? super TypeDescription> matcher) {
    return new Resolvable<>(ElementMatchers.hasSuperClass(matcher));
  }

  /** The JDK's socket channels, server socket channels and selectors ({@code jdkNamedOr}). */
  static final String JDK_CHANNELS = "sun.nio.ch.";

  /** The JDK's {@code SSLSocket} and {@code SSLEngine} implementations. */
  static final String JDK_TLS = "sun.security.ssl.";

  /** The JDK's {@code MBeanServer} implementations. */
  static final String JDK_JMX = "com.sun.jmx.";

  /** The JDK's {@code BlockingQueue} implementations. */
  static final String JDK_CONCURRENT = "java.util.concurrent.";

  /**
   * Restricts {@code types}, a matcher that walks a hierarchy the JDK implements, to the JDK
   * classes named with one of {@code jdkPrefixes}: a JDK class (one of a named module of the
   * bootstrap or platform class loader) with another name is not matched and its hierarchy not
   * walked. The hierarchy walk resolves every supertype of every class defined while the hook group
   * is woven, which made each class load several times slower for the rest of the JVM's life; the
   * JDK's own implementations of these types are few and known, so its other classes skip the walk.
   * A class of any other loader, also one appended to the boot class path, is walked as before.
   *
   * @param types the hierarchy matcher
   * @param jdkPrefixes the name prefixes of the JDK's implementations; none when the JDK has none
   * @return the matcher
   */
  static AgentBuilder.RawMatcher jdkNamedOr(
      final ElementMatcher<? super TypeDescription> types, final String... jdkPrefixes) {
    return new JdkNamedOr(types, List.of(jdkPrefixes));
  }

  /** See {@link #jdkNamedOr}. */
  private static final class JdkNamedOr implements AgentBuilder.RawMatcher {

    private final ElementMatcher<? super TypeDescription> types;
    private final List<String> jdkPrefixes;

    JdkNamedOr(
        final ElementMatcher<? super TypeDescription> types, final List<String> jdkPrefixes) {
      this.types = types;
      this.jdkPrefixes = jdkPrefixes;
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // a class loader's identity
    public boolean matches(
        final TypeDescription typeDescription,
        final @Nullable ClassLoader classLoader,
        final @Nullable JavaModule module,
        final @Nullable Class<?> classBeingRedefined,
        final @Nullable ProtectionDomain protectionDomain) {
      final boolean jdkClass =
          (classLoader == null || classLoader == ClassLoader.getPlatformClassLoader())
              && module != null
              && module.isNamed();
      if (jdkClass && !hasJdkPrefix(typeDescription.getName())) {
        return false;
      }
      return types.matches(typeDescription);
    }

    private boolean hasJdkPrefix(final String name) {
      for (final String prefix : jdkPrefixes) {
        if (name.startsWith(prefix)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public String toString() {
      return types + " (JDK classes: " + jdkPrefixes + ")";
    }
  }

  /** A matcher for which a type pool miss while walking the hierarchy is no match. */
  private static final class Resolvable<T extends TypeDescription>
      extends ElementMatcher.Junction.AbstractBase<T> {

    private final ElementMatcher<? super T> matcher;

    Resolvable(final ElementMatcher<? super T> matcher) {
      this.matcher = matcher;
    }

    @Override
    public boolean matches(final T target) {
      try {
        return matcher.matches(target);
      } catch (final TypePool.Resolution.NoSuchTypeException unresolvable) {
        return false;
      }
    }

    @Override
    public String toString() {
      return matcher.toString();
    }
  }
}
