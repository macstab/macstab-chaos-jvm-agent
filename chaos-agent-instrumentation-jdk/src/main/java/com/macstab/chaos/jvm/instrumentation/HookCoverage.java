package com.macstab.chaos.jvm.instrumentation;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Which hooks matched a method.
 *
 * <p>Hooks name JDK methods by name and signature, and the JDK changes them between releases
 * ({@code InetAddress.getHostFromNameService} lost its {@code boolean} in JDK 25). Such a hook
 * matches nothing and goes silently inert. Every hook's method matcher is therefore wrapped in a
 * recording one ({@link #track}): a hook is <em>registered</em> when it is woven into a type, and
 * <em>matched</em> once it matches a method of any type it is woven into. A hook registered but
 * never matched is inactive, and listed in the diagnostics' runtime details as {@code
 * unmatchedHooks}.
 *
 * <p>Matching is per hook, not per type: a hook on the subtypes of a JDK type ({@code
 * SelectorImpl}, a file system provider) is woven into subtypes that inherit the method instead of
 * declaring it, which is not a failure.
 */
final class HookCoverage {

  /** {@code advice on matcher} of every hook woven into a type. */
  private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

  /** {@code advice on matcher} of every hook that matched a method. */
  private static final Set<String> MATCHED = ConcurrentHashMap.newKeySet();

  /** {@code type: error} of every transformation that failed: all its hooks are inactive. */
  private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

  private HookCoverage() {}

  /**
   * Returns {@code methods}, recording that the hook is woven and whether it matches a method.
   *
   * @param advice the advice applied to the methods matched
   * @param methods the methods it is applied to
   * @return the recording matcher
   */
  static ElementMatcher<MethodDescription> track(
      final Class<?> advice, final ElementMatcher<? super MethodDescription> methods) {
    final Hook hook = new Hook(id(advice, methods), methods);
    REGISTERED.add(hook.id);
    return hook;
  }

  /**
   * Returns the hooks woven into a type that matched no method of any type, as {@code advice on
   * matcher}; empty when every woven hook is active. A hook being woven while this is read may
   * appear here until its type's transformation completes.
   *
   * @return the inactive hooks, sorted
   */
  static Set<String> unmatched() {
    final Set<String> unmatched = new TreeSet<>(REGISTERED);
    unmatched.removeAll(MATCHED);
    return Collections.unmodifiableSet(unmatched);
  }

  /**
   * Records that weaving {@code typeName} failed: none of its hooks is applied.
   *
   * @param typeName the type
   * @param failure why
   */
  static void failed(final String typeName, final Throwable failure) {
    FAILED.add(typeName + ": " + failure);
  }

  /**
   * Returns the types whose weaving failed, as {@code type: error}; empty when every woven type was
   * transformed.
   *
   * @return the failures, sorted
   */
  static Set<String> failures() {
    return Collections.unmodifiableSet(new TreeSet<>(FAILED));
  }

  /**
   * Returns the hooks that matched a method, as {@code advice on matcher}.
   *
   * @return the active hooks, sorted
   */
  static Set<String> matched() {
    return Collections.unmodifiableSet(new TreeSet<>(MATCHED));
  }

  private static String id(
      final Class<?> advice, final ElementMatcher<? super MethodDescription> methods) {
    final Class<?> holder = advice.getEnclosingClass();
    return (holder == null ? "" : holder.getSimpleName() + ".")
        + advice.getSimpleName()
        + " on "
        + methods;
  }

  /** A method matcher that records its first match. */
  private static final class Hook extends ElementMatcher.Junction.AbstractBase<MethodDescription> {
    private final String id;
    private final ElementMatcher<? super MethodDescription> methods;

    /** Set once; racing writers write the same value. */
    private volatile boolean matched;

    Hook(final String id, final ElementMatcher<? super MethodDescription> methods) {
      this.id = id;
      this.methods = methods;
    }

    @Override
    public boolean matches(final MethodDescription target) {
      final boolean matches = methods.matches(target);
      if (matches && !matched) {
        matched = true;
        MATCHED.add(id);
      }
      return matches;
    }

    @Override
    public String toString() {
      return methods.toString();
    }
  }
}
