package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.OperationTags;
import net.bytebuddy.asm.Advice;
import org.jspecify.annotations.Nullable;

/**
 * ByteBuddy {@link Advice} classes for HTTP client interception points.
 *
 * <p>Targets:
 *
 * <ul>
 *   <li>Java 11+ {@code HttpClient} ({@code jdk.internal.net.http.HttpClientImpl})
 *   <li>OkHttp 3/4 ({@code RealCall.execute} and its call chain)
 *   <li>Apache HttpComponents 4.x and 5.x ({@code doExecute} of the {@code CloseableHttpClient}
 *       implementations)
 *   <li>Spring WebClient / Reactor Netty ({@code HttpClientConnect.MonoHttpConnect.subscribe})
 * </ul>
 *
 * <p>Each inner class targets one specific client method. URL extraction uses reflection via {@link
 * HttpUrlExtractor} so the compileOnly dependencies need never be present at runtime.
 */
final class HttpClientAdvice {
  private HttpClientAdvice() {}

  /**
   * Intercepts {@code jdk.internal.net.http.HttpClientImpl.send(HttpRequest, BodyHandler)}. A
   * rejected send fails with the {@link java.io.IOException} the dispatcher throws.
   */
  static final class JavaHttpClientSendAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.Argument(
                value = 0,
                readOnly = false,
                typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC)
            Object request)
        throws Throwable {
      // Inside a session, a request to an internal host carries its id to the next service.
      if (request instanceof java.net.http.HttpRequest httpRequest
          && httpRequest.headers().firstValue(BootstrapDispatcher.SESSION_HEADER).isEmpty()) {
        final String session = BootstrapDispatcher.sessionHeaderFor(httpRequest.uri().getHost());
        if (session != null) {
          try {
            request =
                java.net.http.HttpRequest.newBuilder(httpRequest, BootstrapDispatcher.ALL_HEADERS)
                    .header(BootstrapDispatcher.SESSION_HEADER, session)
                    .build();
          } catch (final RuntimeException uncopyable) {
            // a custom HttpRequest the builder cannot copy goes out as it is
          }
        }
      }
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_HTTP)) {
        return;
      }
      // Inlined into HttpClientImpl (java.net.http), the advice calls HttpRequest.uri() directly:
      // the request's own class (jdk.internal.net.http) is not open to reflection by the agent.
      final String url =
          request instanceof java.net.http.HttpRequest httpRequest
              ? String.valueOf(httpRequest.uri())
              : null;
      try {
        BootstrapDispatcher.beforeHttpSend(url);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_HTTP);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_HTTP);
    }
  }

  /**
   * Intercepts {@code jdk.internal.net.http.HttpClientImpl.sendAsync} (2 and 3 arguments; the
   * 2-argument form delegates, {@code SCOPE_HTTP} evaluates the outermost). A reject skips the send
   * and returns a future failed with the {@link java.io.IOException} the dispatcher built, as the
   * client fails an asynchronous exchange: the call itself does not throw.
   */
  static final class JavaHttpClientSendAsyncAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static @Nullable Throwable enter(
        @Advice.Argument(
                value = 0,
                readOnly = false,
                typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC)
            Object request) {
      // Inside a session, a request to an internal host carries its id to the next service.
      if (request instanceof java.net.http.HttpRequest httpRequest
          && httpRequest.headers().firstValue(BootstrapDispatcher.SESSION_HEADER).isEmpty()) {
        final String session = BootstrapDispatcher.sessionHeaderFor(httpRequest.uri().getHost());
        if (session != null) {
          try {
            request =
                java.net.http.HttpRequest.newBuilder(httpRequest, BootstrapDispatcher.ALL_HEADERS)
                    .header(BootstrapDispatcher.SESSION_HEADER, session)
                    .build();
          } catch (final RuntimeException uncopyable) {
            // a custom HttpRequest the builder cannot copy goes out as it is
          }
        }
      }
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_HTTP)) {
        return null;
      }
      // Inlined into HttpClientImpl (java.net.http), the advice calls HttpRequest.uri() directly:
      // the request's own class (jdk.internal.net.http) is not open to reflection by the agent.
      final String url =
          request instanceof java.net.http.HttpRequest httpRequest
              ? String.valueOf(httpRequest.uri())
              : null;
      try {
        BootstrapDispatcher.beforeHttpSendAsync(url);
        return null;
      } catch (final Throwable rejected) {
        return rejected;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final @Nullable Throwable rejected,
        @Advice.Return(
                readOnly = false,
                typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC)
            Object response) {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_HTTP);
      if (rejected != null) {
        response = java.util.concurrent.CompletableFuture.failedFuture(rejected);
      }
    }
  }

  /**
   * Intercepts {@code sun.net.www.protocol.http.HttpURLConnection.plainConnect0()}: the JDK's
   * {@link java.net.HttpURLConnection} (and {@code HttpsURLConnection}, which delegates to it). It
   * runs once per request, when the connection is first used ({@code connect()}, {@code
   * getInputStream()}, {@code getOutputStream()}, {@code getResponseCode()}), so a request is
   * evaluated once however it is driven. This is the transport of Spring's {@code
   * SimpleClientHttpRequestFactory} ({@code RestTemplate}'s default) and of the AWS SDK's URL
   * connection client.
   */
  static final class HttpUrlConnectionConnectAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.FieldValue("url") final java.net.URL url) throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_HTTP)) {
        return;
      }
      // URL.toString() formats the URL; unlike equals()/hashCode() it never resolves the host.
      final String target = url == null ? null : url.toString();
      try {
        BootstrapDispatcher.beforeHttpSend(target);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_HTTP);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_HTTP);
    }
  }

  /**
   * Adds {@code X-Chaos-Session} to a {@code sun.net.www.protocol.http.HttpURLConnection} request
   * made inside a session, on {@code connect()}, {@code getInputStream()} and {@code
   * getOutputStream()} while the request headers can still change. A connection that already
   * connected, or that set the header itself, is left alone.
   */
  static final class HttpUrlConnectionSessionHeaderAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object connection) {
      final java.net.URLConnection urlConnection = (java.net.URLConnection) connection;
      final String session = BootstrapDispatcher.sessionHeaderFor(urlConnection.getURL().getHost());
      if (session == null) {
        return;
      }
      try {
        if (urlConnection.getRequestProperty(BootstrapDispatcher.SESSION_HEADER) == null) {
          urlConnection.setRequestProperty(BootstrapDispatcher.SESSION_HEADER, session);
        }
      } catch (final IllegalStateException alreadyConnected) {
        // headers are fixed once connected; the request went out without the session
      }
    }
  }

  /**
   * {@code RealCall.execute()} of OkHttp 3 and 4, scope-only ({@link
   * BootstrapDispatcher#SCOPE_HTTP_SYNC_CALL}): the call is evaluated in its chain ({@link
   * OkHttpChainAdvice}), which this scope marks synchronous.
   */
  static final class OkHttpExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter() {
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_HTTP_SYNC_CALL);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_HTTP_SYNC_CALL);
    }
  }

  /**
   * {@code RealCall.getResponseWithInterceptorChain} (OkHttp 4: {@code ...$okhttp}): every call
   * runs it once, an {@code execute()} on the caller's thread ({@code HTTP_CLIENT_SEND}), an {@code
   * enqueue}d call on OkHttp's dispatcher thread ({@code HTTP_CLIENT_SEND_ASYNC}). A reject throws
   * the {@link java.io.IOException} where OkHttp's own network failures are thrown: {@code
   * execute()} throws it, an enqueued call hands it to {@code Callback.onFailure}. It is thrown
   * before the method's body, so the advice first ends the call as that body ends a failed one
   * ({@link HttpUrlExtractor#okHttpCallFailed}: {@code callFailed}, the timeout's exit). A request
   * an interceptor sends is a call of its own and is evaluated too.
   */
  static final class OkHttpChainAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object call) throws Throwable {
      final boolean sync = BootstrapDispatcher.inScope(BootstrapDispatcher.SCOPE_HTTP_SYNC_CALL);
      if (BootstrapDispatcher.idle(
          sync ? OperationTags.HTTP_CLIENT_SEND : OperationTags.HTTP_CLIENT_SEND_ASYNC)) {
        return;
      }
      final String url;
      BootstrapDispatcher.beginInternalCall();
      try {
        url = HttpUrlExtractor.fromOkHttpCall(call);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      try {
        if (sync) {
          BootstrapDispatcher.beforeHttpSend(url);
        } else {
          BootstrapDispatcher.beforeHttpSendAsync(url);
        }
      } catch (final Throwable rejected) {
        throw HttpUrlExtractor.okHttpCallFailed(call, rejected);
      }
    }
  }

  /**
   * Intercepts Apache HttpComponents 4.x {@code doExecute(HttpHost, HttpRequest, HttpContext)},
   * which every public {@code execute} and {@code executeOpen} overload of {@code
   * CloseableHttpClient} reaches with the caller's host (or {@code null}): one evaluation per
   * request, with one URL rule ({@link HttpUrlExtractor#fromApacheHc4}). {@code SCOPE_HTTP} spans
   * {@code doExecute} only: a response handler runs after it returns, so a request the handler
   * sends is evaluated too.
   */
  static final class ApacheHc4DoExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.Argument(0) final Object host, @Advice.Argument(1) final Object request)
        throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_HTTP)) {
        return;
      }
      if (BootstrapDispatcher.idle(OperationTags.HTTP_CLIENT_SEND)) {
        return;
      }
      final String url;
      try {
        BootstrapDispatcher.beginInternalCall();
        try {
          url = HttpUrlExtractor.fromApacheHc4(host, request);
        } finally {
          BootstrapDispatcher.endInternalCall();
        }
        BootstrapDispatcher.beforeHttpSend(url);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_HTTP);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_HTTP);
    }
  }

  /**
   * Intercepts Apache HttpComponents 5.x {@code doExecute(HttpHost, ClassicHttpRequest,
   * HttpContext)}, which every public {@code execute} and {@code executeOpen} overload of {@code
   * CloseableHttpClient} reaches with the caller's host (or {@code null}): one evaluation per
   * request, with one URL rule ({@link HttpUrlExtractor#fromApacheHc5}). {@code SCOPE_HTTP} spans
   * {@code doExecute} only: a response handler runs after it returns, so a request the handler
   * sends is evaluated too.
   */
  static final class ApacheHc5DoExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.Argument(0) final Object host, @Advice.Argument(1) final Object request)
        throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_HTTP)) {
        return;
      }
      if (BootstrapDispatcher.idle(OperationTags.HTTP_CLIENT_SEND)) {
        return;
      }
      final String url;
      try {
        BootstrapDispatcher.beginInternalCall();
        try {
          url = HttpUrlExtractor.fromApacheHc5(host, request);
        } finally {
          BootstrapDispatcher.endInternalCall();
        }
        BootstrapDispatcher.beforeHttpSend(url);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_HTTP);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_HTTP);
    }
  }

  /**
   * Intercepts Reactor Netty {@code HttpClientConnect.MonoHttpConnect.subscribe(CoreSubscriber)}:
   * once per subscription, so each retry or resubscription is a request of its own, with the URL of
   * that subscription's configuration ({@link HttpUrlExtractor#fromReactorNettyConfig}; a deferred
   * {@code uri(Mono)} is resolved by then). Spring {@code WebClient} dispatches through it. A
   * reject skips the connection and signals the {@link java.io.IOException} to the subscriber
   * ({@code Operators.error}), as a failed connection does; nothing is thrown from {@code
   * subscribe}.
   */
  static final class ReactorNettySubscribeAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static @Nullable Throwable enter(@Advice.FieldValue("config") final Object config) {
      if (BootstrapDispatcher.idle(OperationTags.HTTP_CLIENT_SEND_ASYNC)) {
        return null;
      }
      final String url;
      BootstrapDispatcher.beginInternalCall();
      try {
        url = HttpUrlExtractor.fromReactorNettyConfig(config);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      try {
        BootstrapDispatcher.beforeHttpSendAsync(url);
        return null;
      } catch (final Throwable rejected) {
        return rejected;
      }
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final @Nullable Throwable rejected,
        @Advice.Argument(0) final Object subscriber) {
      if (rejected != null) {
        reactor.core.publisher.Operators.error(
            (org.reactivestreams.Subscriber<?>) subscriber, rejected);
      }
    }
  }
}
