package com.macstab.chaos.instrumentation;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective URL-extraction helpers for intercepted HTTP client calls.
 *
 * <p>All client types ({@code java.net.http.HttpRequest}, {@code okhttp3.Call}, Apache
 * HttpComponents request classes, Reactor Netty {@code HttpClientRequest}) are {@code compileOnly}
 * dependencies in the agent. They may or may not be on the application classpath at runtime.
 * Accessing them via {@link Method#invoke} keeps the instrumentation module free of any hard link.
 *
 * <p>Failures are swallowed and returned as {@code null} — the caller treats a {@code null} URL as
 * "no URL filter will match" which matches the {@link com.macstab.chaos.api.NamePattern#any()}
 * selector default.
 */
final class HttpUrlExtractor {

  private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

  private HttpUrlExtractor() {}

  /**
   * Extracts the URL from a {@code java.net.http.HttpRequest} by calling {@code uri().toString()}
   * reflectively.
   *
   * @param request the {@code HttpRequest} instance; may be {@code null}
   * @return the URL string, or {@code null} if extraction fails
   */
  static String fromJavaHttpRequest(final Object request) {
    if (request == null) {
      return null;
    }
    try {
      final Object uri = invoke(request, "uri");
      return uri == null ? null : uri.toString();
    } catch (final Throwable ignored) {
      return null;
    }
  }

  /**
   * Extracts the URL from an {@code okhttp3.Call} by calling {@code request().url().toString()}.
   *
   * @param call the {@code okhttp3.Call} instance; may be {@code null}
   * @return the URL string, or {@code null} if extraction fails
   */
  static String fromOkHttpCall(final Object call) {
    if (call == null) {
      return null;
    }
    try {
      final Object request = invoke(call, "request");
      if (request == null) {
        return null;
      }
      final Object url = invoke(request, "url");
      return url == null ? null : url.toString();
    } catch (final Throwable ignored) {
      return null;
    }
  }

  /**
   * Builds a URL-like string from an Apache HttpComponents 4.x {@code HttpHost} and {@code
   * HttpRequest} pair.
   *
   * @param host an {@code org.apache.http.HttpHost} instance; may be {@code null}
   * @param request an {@code org.apache.http.HttpRequest} instance; may be {@code null}
   * @return the combined URL string, or {@code null} if extraction fails
   */
  static String fromApacheHc4Request(final Object host, final Object request) {
    try {
      final String hostPart = host == null ? "" : host.toString();
      String path = "";
      if (request != null) {
        final Object requestLine = invoke(request, "getRequestLine");
        if (requestLine != null) {
          final Object uri = invoke(requestLine, "getUri");
          path = uri == null ? "" : uri.toString();
        }
      }
      final String combined = hostPart + path;
      return combined.isEmpty() ? null : combined;
    } catch (final Throwable ignored) {
      return null;
    }
  }

  /**
   * Extracts the URI from an Apache HttpComponents 5.x {@code ClassicHttpRequest} via {@code
   * getRequestUri}.
   *
   * @param request an {@code org.apache.hc.core5.http.ClassicHttpRequest} instance; may be {@code
   *     null}
   * @return the URI string, or {@code null} if extraction fails
   */
  static String fromApacheHc5Request(final Object request) {
    if (request == null) {
      return null;
    }
    try {
      final Object uri = invoke(request, "getRequestUri");
      return uri == null ? null : uri.toString();
    } catch (final Throwable ignored) {
      return null;
    }
  }

  /**
   * Extracts a URL-like string from a Reactor Netty {@code HttpClientRequest} by calling {@code
   * uri()}.
   *
   * @param request an {@code reactor.netty.http.client.HttpClientRequest} instance; may be {@code
   *     null}
   * @return the URL string, or {@code null} if extraction fails
   */
  static String fromReactorNettyRequest(final Object request) {
    if (request == null) {
      return null;
    }
    try {
      final Object uri = invoke(request, "uri");
      return uri == null ? null : uri.toString();
    } catch (final Throwable ignored) {
      return null;
    }
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
      for (final Class<?> iface : current.getInterfaces()) {
        for (final Method candidate : iface.getDeclaredMethods()) {
          if (candidate.getName().equals(methodName) && candidate.getParameterCount() == 0) {
            return candidate;
          }
        }
      }
      current = current.getSuperclass();
    }
    return null;
  }
}
