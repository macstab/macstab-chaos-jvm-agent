package com.macstab.chaos.jvm.instrumentation;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Reflective pool-identifier extraction helpers for intercepted JDBC pool calls.
 *
 * <p>HikariCP and c3p0 are {@code compileOnly} dependencies; they may or may not be on the
 * application classpath at runtime. Accessing their types via {@link Method#invoke} keeps the
 * instrumentation module free of any hard link.
 *
 * <p>Failures are swallowed and returned as {@code null} — the caller treats a {@code null} pool
 * name as "no pool filter will match" which matches the {@link
 * com.macstab.chaos.jvm.api.NamePattern#any()} selector default.
 */
public final class JdbcTargetExtractor {

  // ClassValue keyed on the Class object (not its name) — see HttpUrlExtractor for the
  // shared rationale: a String cache keyed on class name collides across classloaders,
  // hands the wrong Method back, and pins loaders for JVM lifetime.
  private static final ClassValue<ConcurrentHashMap<String, Method>> METHOD_CACHE =
      new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<String, Method> computeValue(final Class<?> type) {
          return new ConcurrentHashMap<>();
        }
      };

  private JdbcTargetExtractor() {}

  /**
   * Extracts the HikariCP pool name. {@code HikariPool} declares no {@code getPoolName()} (only
   * {@code HikariDataSource} and {@code HikariConfig} do); its {@code PoolBase} holds the name in a
   * protected field and returns it from {@code toString()}. A {@code getPoolName()} is used where a
   * pool type has one, then a {@code toString()} the pool class declares itself, then the class
   * name.
   *
   * @param pool the {@code com.zaxxer.hikari.pool.HikariPool} instance; may be {@code null}
   * @return the pool name, or the class name as a fallback, or {@code null} when {@code pool} is
   *     {@code null}
   */
  public static @Nullable String fromHikariPool(final Object pool) {
    if (pool == null) {
      return null;
    }
    try {
      final Object name = invoke(pool, "getPoolName");
      if (name != null) {
        return name.toString();
      }
    } catch (final Throwable ignored) {
      // fall through
    }
    try {
      if (pool.getClass().getMethod("toString").getDeclaringClass() != Object.class) {
        final String name = pool.toString();
        if (name != null && !name.isBlank()) {
          return name;
        }
      }
    } catch (final Throwable ignored) {
      // fall through
    }
    return pool.getClass().getName();
  }

  private static @Nullable Object invoke(final Object target, final String methodName)
      throws Throwable {
    final Class<?> targetClass = target.getClass();
    final ConcurrentHashMap<String, Method> perClass = METHOD_CACHE.get(targetClass);
    Method method = perClass.get(methodName);
    if (method == null) {
      method = findMethod(targetClass, methodName);
      if (method != null) {
        // See HttpUrlExtractor for the full rationale: setAccessible can throw
        // InaccessibleObjectException on JDK 17+ when the target module has not opened
        // its package. Swallow only that specific exception so the Method still gets
        // cached — invoke() below may still succeed for public methods in exported
        // packages (HikariPool.getPoolName is public, so the common path works).
        try {
          method.setAccessible(true);
        } catch (final java.lang.reflect.InaccessibleObjectException encapsulated) {
          // leave method un-accessible; invoke() may still succeed.
        }
        perClass.put(methodName, method);
      }
    }
    if (method == null) {
      return null;
    }
    return method.invoke(target);
  }

  private static @Nullable Method findMethod(final Class<?> start, final String methodName) {
    Class<?> searchClass = start;
    while (searchClass != null) {
      for (final Method candidate : searchClass.getDeclaredMethods()) {
        if (candidate.getName().equals(methodName) && candidate.getParameterCount() == 0) {
          return candidate;
        }
      }
      searchClass = searchClass.getSuperclass();
    }
    return null;
  }
}
