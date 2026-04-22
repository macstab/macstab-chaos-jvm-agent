package com.macstab.chaos.instrumentation;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
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

  // ClassValue keyed on the actual Class (not just its name) so two okhttp3.RealCall
  // classes from different classloaders each get their own per-method cache; a shared
  // String cache keyed on class name would hand instances from one classloader a Method
  // resolved against the other, producing IllegalArgumentException on method.invoke and
  // silently pinning the first-seen classloader for JVM lifetime.
  private static final ClassValue<ConcurrentHashMap<String, Method>> METHOD_CACHE =
      new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<String, Method> computeValue(final Class<?> type) {
          return new ConcurrentHashMap<>();
        }
      };

  /**
   * Sentinel Method entry stored in the cache when {@link #findMethod} returns {@code null}, so
   * repeated calls against a class that doesn't define the target method skip the full superclass +
   * interface walk. ConcurrentHashMap disallows null values, so without a sentinel we'd re-run the
   * reflective search on every intercepted HTTP call — a per-request cost on a hot path.
   * NEGATIVE_CACHE_MARKER is an arbitrary Method object whose identity is used as the "negative
   * result" tag; it is never actually invoked.
   */
  private static final Method NEGATIVE_CACHE_MARKER;

  static {
    try {
      NEGATIVE_CACHE_MARKER = Object.class.getDeclaredMethod("hashCode");
    } catch (final NoSuchMethodException unreachable) {
      throw new ExceptionInInitializerError(unreachable);
    }
  }

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
      final String requestPath = extractApacheHc4RequestPath(request);
      final String combined = hostPart + requestPath;
      return combined.isEmpty() ? null : combined;
    } catch (final Throwable ignored) {
      return null;
    }
  }

  private static String extractApacheHc4RequestPath(final Object request) throws Throwable {
    if (request == null) {
      return "";
    }
    final Object requestLine = invoke(request, "getRequestLine");
    if (requestLine == null) {
      return "";
    }
    final Object uri = invoke(requestLine, "getUri");
    return uri == null ? "" : uri.toString();
  }

  /**
   * Extracts the URI from an Apache HttpComponents 4.x {@code HttpUriRequest} via {@code getURI()}.
   * Falls back to {@code getRequestLine().getUri()} if {@code getURI()} is absent (e.g., a plain
   * {@code HttpRequest} passed through the HttpUriRequest-first matcher by a driver subclass).
   *
   * @param request an {@code org.apache.http.client.methods.HttpUriRequest} (or superclass); may be
   *     {@code null}
   * @return the URL string, or {@code null} if extraction fails
   */
  static String fromApacheHc4UriRequest(final Object request) {
    if (request == null) {
      return null;
    }
    try {
      final Object uri = invoke(request, "getURI");
      if (uri != null) {
        return uri.toString();
      }
    } catch (final Throwable ignored) {
      // fall through to request-line path
    }
    try {
      final Object requestLine = invoke(request, "getRequestLine");
      if (requestLine != null) {
        final Object uri = invoke(requestLine, "getUri");
        return uri == null ? null : uri.toString();
      }
    } catch (final Throwable ignored) {
      return null;
    }
    return null;
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
    // HC5 ClassicHttpRequest exposes getUri() returning a full java.net.URI (scheme+host+path).
    // Prefer it over getRequestUri() which returns only the request-target path string
    // (e.g. "/api/v1/resource"), making host-pattern matching against "https://host/*" fail.
    try {
      final Object uri = invoke(request, "getUri");
      if (uri != null) {
        return uri.toString();
      }
    } catch (final Throwable ignored) {
      // fall through to path-only fallback
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
    final Class<?> targetClass = target.getClass();
    final ConcurrentHashMap<String, Method> perClass = METHOD_CACHE.get(targetClass);
    Method method = perClass.get(methodName);
    if (method == null) {
      method = findMethod(targetClass, methodName);
      if (method != null) {
        // setAccessible(true) can throw InaccessibleObjectException on JDK 17+ when the target
        // module has not opened the defining package for reflective access. Without catching
        // it specifically, the outer catch(Throwable) in every public entry point would hide
        // the concrete reason (module encapsulation) behind a silent null, leaking the real
        // diagnostic. Swallow InaccessibleObjectException here because several of the HTTP
        // client types (HttpRequest in java.net.http) ARE reachable without setAccessible
        // when called from a module that reads java.net.http — we still cache the Method so
        // invoke() can try it as-is. If the eventual invoke fails, the outer catch returns
        // null and the extractor falls back to "no URL filter".
        try {
          method.setAccessible(true);
        } catch (final java.lang.reflect.InaccessibleObjectException encapsulated) {
          // leave method un-accessible; invoke() may still succeed for public methods in
          // exported packages.
        }
        // putIfAbsent: if two threads both miss the cache and call findMethod concurrently, the
        // first accessible Method wins. If the loser's setAccessible threw, the winner's
        // accessible copy is already in place; the loser's un-accessible copy is discarded.
        final Method existing = perClass.putIfAbsent(methodName, method);
        if (existing != null) {
          method = existing;
        }
      } else {
        // Cache the *absence* of a matching method so that subsequent calls on the same class
        // skip the full superclass + interface walk. Without this, every intercepted HTTP call
        // against a class that happens not to expose the expected accessor re-runs findMethod —
        // a reflective traversal of the entire type hierarchy on every request.
        perClass.putIfAbsent(methodName, NEGATIVE_CACHE_MARKER);
      }
    }
    if (method == null || method == NEGATIVE_CACHE_MARKER) {
      return null;
    }
    return method.invoke(target);
  }

  private static Method findMethod(final Class<?> start, final String methodName) {
    final Set<Class<?>> visitedInterfaces = new HashSet<>();
    final Deque<Class<?>> interfaceQueue = new ArrayDeque<>();
    Class<?> current = start;
    while (current != null) {
      for (final Method candidate : current.getDeclaredMethods()) {
        if (candidate.getName().equals(methodName) && candidate.getParameterCount() == 0) {
          return candidate;
        }
      }
      // Walk the interface hierarchy transitively. Reactor Netty HttpClientRequest.uri(), for
      // instance, is declared on an interface that the concrete class inherits through several
      // levels of super-interface; only scanning current.getInterfaces() misses methods
      // declared on a super-super-interface and produces a spurious cache miss that falls back
      // to "no URL filter" for every request through that client.
      for (final Class<?> iface : current.getInterfaces()) {
        if (visitedInterfaces.add(iface)) {
          interfaceQueue.add(iface);
        }
      }
      current = current.getSuperclass();
    }
    Class<?> currentInterface;
    while ((currentInterface = interfaceQueue.poll()) != null) {
      for (final Method candidate : currentInterface.getDeclaredMethods()) {
        if (candidate.getName().equals(methodName) && candidate.getParameterCount() == 0) {
          return candidate;
        }
      }
      for (final Class<?> superInterface : currentInterface.getInterfaces()) {
        if (visitedInterfaces.add(superInterface)) {
          interfaceQueue.add(superInterface);
        }
      }
    }
    return null;
  }
}
