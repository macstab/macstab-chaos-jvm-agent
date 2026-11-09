package com.macstab.chaos.jvm.instrumentation;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

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
