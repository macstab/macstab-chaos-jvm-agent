package com.macstab.chaos.instrumentation;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective pool-identifier extraction helpers for intercepted JDBC pool calls.
 *
 * <p>HikariCP and c3p0 are {@code compileOnly} dependencies; they may or may not be on the
 * application classpath at runtime. Accessing their types via {@link Method#invoke} keeps the
 * instrumentation module free of any hard link.
 *
 * <p>Failures are swallowed and returned as {@code null} — the caller treats a {@code null} pool
 * name as "no pool filter will match" which matches the {@link
 * com.macstab.chaos.api.NamePattern#any()} selector default.
 */
final class JdbcTargetExtractor {

  private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

  private JdbcTargetExtractor() {}

  /**
   * Extracts the HikariCP pool name by calling {@code getPoolName()} reflectively. Falls back to
   * the instance's fully qualified class name when extraction fails.
   *
   * @param pool the {@code com.zaxxer.hikari.pool.HikariPool} instance; may be {@code null}
   * @return the pool name, or the class name as a fallback, or {@code null} when {@code pool} is
   *     {@code null}
   */
  static String fromHikariPool(final Object pool) {
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
    return pool.getClass().getName();
  }

  private static Object invoke(final Object target, final String methodName) throws Throwable {
    final Class<?> cls = target.getClass();
    final String cacheKey = cls.getName() + "#" + methodName;
    Method method = METHOD_CACHE.get(cacheKey);
    if (method == null) {
      method = findMethod(cls, methodName);
      if (method != null) {
        method.setAccessible(true);
        METHOD_CACHE.put(cacheKey, method);
      }
    }
    if (method == null) {
      return null;
    }
    return method.invoke(target);
  }

  private static Method findMethod(final Class<?> start, final String methodName) {
    Class<?> current = start;
    while (current != null) {
      for (final Method candidate : current.getDeclaredMethods()) {
        if (candidate.getName().equals(methodName) && candidate.getParameterCount() == 0) {
          return candidate;
        }
      }
      current = current.getSuperclass();
    }
    return null;
  }
}
