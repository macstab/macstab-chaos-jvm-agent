package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Reflective URL-extraction helpers for intercepted HTTP client calls.
 *
 * <p>All client types ({@code okhttp3.Call}, Apache HttpComponents request classes, Reactor Netty
 * {@code HttpClient}) are {@code compileOnly} dependencies in the agent. (The JDK {@code
 * HttpClient} advice reads {@code HttpRequest.uri()} directly and needs no extraction.) They may or
 * may not be on the application classpath at runtime. Accessing them via {@link Method#invoke}
 * keeps the instrumentation module free of any hard link.
 *
 * <p>Failures are swallowed and returned as {@code null} — the caller treats a {@code null} URL as
 * "no URL filter will match" which matches the {@link com.macstab.chaos.jvm.api.NamePattern#any()}
 * selector default.
 */
public final class HttpUrlExtractor {

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

  /** OkHttp 4's end of a call: {@code RealCall.noMoreExchanges$okhttp(IOException)}. */
  private static final String OKHTTP_NO_MORE_EXCHANGES = "noMoreExchanges$okhttp";

  /**
   * {@code noMoreExchanges} per call class, typed {@code (Object, IOException)Object}; empty when
   * the class has none (OkHttp 3) or it cannot be made callable. A {@link MethodHandle}, not a
   * {@link Method}: calling it is no {@code Method.invoke}, so a REFLECTION_INVOKE scenario never
   * sees the agent's own call, while the listeners it runs still run as application code.
   */
  private static final ClassValue<Optional<MethodHandle>> OKHTTP_END = new OkHttpEnd();

  /** Finds {@code noMoreExchanges} of a call class ({@link #OKHTTP_END}). */
  private static final class OkHttpEnd extends ClassValue<Optional<MethodHandle>> {
    @Override
    protected Optional<MethodHandle> computeValue(final Class<?> callType) {
      try {
        final Method method =
            callType.getDeclaredMethod(OKHTTP_NO_MORE_EXCHANGES, java.io.IOException.class);
        try {
          method.setAccessible(true);
        } catch (final RuntimeException encapsulated) {
          // public: callable without it when its package is exported
        }
        return Optional.of(
            MethodHandles.lookup()
                .unreflect(method)
                .asType(
                    MethodType.methodType(Object.class, Object.class, java.io.IOException.class)));
      } catch (final ReflectiveOperationException | RuntimeException absent) {
        return Optional.empty();
      }
    }
  }

  private HttpUrlExtractor() {}

  /**
   * Ends an OkHttp call the agent rejected the way OkHttp ends a call whose network failed. {@code
   * getResponseWithInterceptorChain} throws what {@code noMoreExchanges(e)} returns for an {@link
   * java.io.IOException} its chain threw: that releases the call, exits its timeout (an expired
   * call timeout turns the failure into an {@code InterruptedIOException}) and reports {@code
   * EventListener.callFailed}. For any other failure its {@code finally} calls {@code
   * noMoreExchanges(null)}, which reports {@code callEnd}. The reject is thrown from the advice
   * before that body runs, so the advice runs this same step. Only the lookup is agent-internal;
   * {@code noMoreExchanges} itself runs as the application's code, as it does in OkHttp.
   *
   * @param call the {@code RealCall}
   * @param rejected what the dispatch threw
   * @return what to throw: {@code noMoreExchanges}' answer for an {@code IOException}, else {@code
   *     rejected}; also {@code rejected} when the call has no such method (OkHttp 3) or it cannot
   *     be made callable, and the listener's own failure when it throws, as OkHttp lets it
   *     propagate
   */
  public static Throwable okHttpCallFailed(final Object call, final Throwable rejected) {
    final Optional<MethodHandle> end;
    BootstrapDispatcher.beginInternalCall();
    try {
      end = OKHTTP_END.get(call.getClass());
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
    if (end.isEmpty()) {
      return rejected;
    }
    final java.io.@Nullable IOException failure =
        rejected instanceof java.io.IOException io ? io : null;
    try {
      final Object thrown = (Object) end.get().invokeExact(call, failure);
      return failure != null && thrown instanceof Throwable replacement ? replacement : rejected;
    } catch (final Throwable listenerFailed) {
      return listenerFailed;
    }
  }

  /**
   * Extracts the URL from an {@code okhttp3.Call} by calling {@code request().url().toString()}.
   *
   * @param call the {@code okhttp3.Call} instance; may be {@code null}
   * @return the URL string, or {@code null} if extraction fails
   */
  public static @Nullable String fromOkHttpCall(final @Nullable Object call) {
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
   * The URL of an Apache HttpComponents 4.x {@code doExecute(HttpHost, HttpRequest, HttpContext)}:
   * an absolute request target ({@code getRequestLine().getUri()}) wins; else the host's URI
   * ({@code HttpHost.toURI()}) followed by the target; else, without a host, the request's {@code
   * getURI()}, or its target when it has none.
   *
   * @param host the {@code org.apache.http.HttpHost}, or {@code null} when the caller gave none
   * @param request the {@code org.apache.http.HttpRequest}; may be {@code null}
   * @return the URL string, or {@code null} if extraction fails
   */
  public static @Nullable String fromApacheHc4(
      final @Nullable Object host, final @Nullable Object request) {
    try {
      final Object requestLine = request == null ? null : invoke(request, "getRequestLine");
      final Object target = requestLine == null ? null : invoke(requestLine, "getUri");
      final Object uri = host == null && request != null ? invoke(request, "getURI") : null;
      return compose(host, target, uri);
    } catch (final Throwable ignored) {
      return null;
    }
  }

  /**
   * The URL of an Apache HttpComponents 5.x {@code doExecute(HttpHost, ClassicHttpRequest,
   * HttpContext)}: an absolute request target ({@code getRequestUri()}) wins; else the host's URI
   * ({@code HttpHost.toURI()}) followed by the target; else, without a host (as {@code
   * executeOpen(null, request, context)} passes it), the request's full {@code getUri()}, or its
   * target when it has none.
   *
   * @param host the {@code org.apache.hc.core5.http.HttpHost}, or {@code null} when the caller gave
   *     none
   * @param request the {@code org.apache.hc.core5.http.ClassicHttpRequest}; may be {@code null}
   * @return the URL string, or {@code null} if extraction fails
   */
  public static @Nullable String fromApacheHc5(
      final @Nullable Object host, final @Nullable Object request) {
    try {
      final Object target = request == null ? null : invoke(request, "getRequestUri");
      final Object uri = host == null && request != null ? invoke(request, "getUri") : null;
      return compose(host, target, uri);
    } catch (final Throwable ignored) {
      return null;
    }
  }

  /**
   * The one URL rule of the Apache clients: an absolute {@code target} wins; else {@code
   * host.toURI()} followed by {@code target}; else, without a host, {@code uri}, else {@code
   * target}.
   */
  private static @Nullable String compose(
      final @Nullable Object host, final @Nullable Object target, final @Nullable Object uri)
      throws Throwable {
    final String path = target == null ? "" : target.toString();
    if (isAbsolute(path)) {
      return path;
    }
    if (host != null) {
      final Object hostUri = invoke(host, "toURI");
      final String combined = (hostUri == null ? "" : hostUri.toString()) + path;
      return combined.isEmpty() ? null : combined;
    }
    if (uri != null) {
      return uri.toString();
    }
    return path.isEmpty() ? null : path;
  }

  /**
   * The URL of a Reactor Netty request from its {@code HttpClientConfig}: an absolute {@code uri()}
   * wins; else {@code baseUrl()} followed by {@code uri()}; a missing part is left out.
   *
   * @param configuration the {@code reactor.netty.http.client.HttpClientConfig}; may be {@code
   *     null}
   * @return the URL string, or {@code null} if extraction fails
   */
  public static @Nullable String fromReactorNettyConfig(final @Nullable Object configuration) {
    if (configuration == null) {
      return null;
    }
    try {
      final Object uri = invoke(configuration, "uri");
      final String path = uri == null ? "" : uri.toString();
      if (isAbsolute(path)) {
        return path;
      }
      final Object baseUrl = invoke(configuration, "baseUrl");
      final String combined = (baseUrl == null ? "" : baseUrl.toString()) + path;
      return combined.isEmpty() ? null : combined;
    } catch (final Throwable ignored) {
      return null;
    }
  }

  /** Whether {@code url} is an absolute {@code http} or {@code https} URL. */
  private static boolean isAbsolute(final String url) {
    return url.regionMatches(true, 0, "http://", 0, "http://".length())
        || url.regionMatches(true, 0, "https://", 0, "https://".length());
  }

  private static @Nullable Object invoke(final Object target, final String methodName)
      throws Throwable {
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
        // diagnostic. Swallow InaccessibleObjectException here because a public method of a
        // client type can be reachable without setAccessible even when its module does not
        // open the package — we still cache the Method so invoke() can try it as-is. If the
        // eventual invoke fails, the outer catch returns
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
    if (method == null || isNegativeMarker(method)) {
      return null;
    }
    return method.invoke(target);
  }

  private static @Nullable Method findMethod(final Class<?> start, final String methodName) {
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

  @SuppressWarnings("ReferenceEquality") // the marker is one instance, compared by identity
  private static boolean isNegativeMarker(final Method method) {
    return method == NEGATIVE_CACHE_MARKER;
  }
}
