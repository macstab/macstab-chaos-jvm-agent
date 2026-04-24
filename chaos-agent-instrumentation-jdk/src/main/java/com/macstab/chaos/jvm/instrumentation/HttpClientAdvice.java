package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.api.ChaosHttpSuppressException;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} classes for HTTP client interception points.
 *
 * <p>Targets:
 *
 * <ul>
 *   <li>Java 11+ {@code HttpClient} ({@code jdk.internal.net.http.HttpClientImpl})
 *   <li>OkHttp 3/4 ({@code okhttp3.RealCall})
 *   <li>Apache HttpComponents 4.x ({@code org.apache.http.impl.client.CloseableHttpClient})
 *   <li>Apache HttpComponents 5.x ({@code
 *       org.apache.hc.client5.http.impl.classic.CloseableHttpClient})
 *   <li>Spring WebClient / Reactor Netty ({@code reactor.netty.http.client.HttpClientConnect})
 * </ul>
 *
 * <p>Each inner class targets one specific client method. URL extraction uses reflection via {@link
 * HttpUrlExtractor} so the compileOnly dependencies need never be present at runtime.
 */
final class HttpClientAdvice {
  private HttpClientAdvice() {}

  /**
   * Intercepts {@code jdk.internal.net.http.HttpClientImpl.send(HttpRequest, BodyHandler)}.
   *
   * <p>When the dispatcher returns {@code true} (SUPPRESS), the advice throws {@link
   * ChaosHttpSuppressException} to abort the call with a terminal failure.
   */
  static final class JavaHttpClientSendAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final Object request) throws Throwable {
      final String url = HttpUrlExtractor.fromJavaHttpRequest(request);
      if (BootstrapDispatcher.beforeHttpSend(url)) {
        throw new ChaosHttpSuppressException("HTTP send suppressed by chaos agent: " + url);
      }
    }
  }

  /**
   * Intercepts {@code jdk.internal.net.http.HttpClientImpl.sendAsync(HttpRequest, BodyHandler)}.
   */
  static final class JavaHttpClientSendAsyncAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final Object request) throws Throwable {
      final String url = HttpUrlExtractor.fromJavaHttpRequest(request);
      if (BootstrapDispatcher.beforeHttpSendAsync(url)) {
        throw new ChaosHttpSuppressException("HTTP sendAsync suppressed by chaos agent: " + url);
      }
    }
  }

  /** Intercepts {@code okhttp3.RealCall.execute()}. */
  static final class OkHttpExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object call) throws Throwable {
      final String url = HttpUrlExtractor.fromOkHttpCall(call);
      if (BootstrapDispatcher.beforeHttpSend(url)) {
        throw new ChaosHttpSuppressException("HTTP execute suppressed by chaos agent: " + url);
      }
    }
  }

  /** Intercepts {@code okhttp3.RealCall.enqueue(Callback)}. */
  static final class OkHttpEnqueueAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object call) throws Throwable {
      final String url = HttpUrlExtractor.fromOkHttpCall(call);
      if (BootstrapDispatcher.beforeHttpSendAsync(url)) {
        throw new ChaosHttpSuppressException("HTTP enqueue suppressed by chaos agent: " + url);
      }
    }
  }

  /**
   * Intercepts Apache HttpComponents 4.x {@code CloseableHttpClient.execute(HttpHost, HttpRequest,
   * ...)} overloads — the HttpHost-first variants. Apache HC 4.x defines four such overloads
   * (with/without {@code HttpContext}, with/without {@code ResponseHandler}); the installer
   * restricts the matcher by first-argument type so this advice only binds to those, leaving the
   * HttpUriRequest-first overloads to {@link ApacheHc4UriExecuteAdvice}.
   */
  static final class ApacheHc4ExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final Object host, @Advice.Argument(1) final Object req)
        throws Throwable {
      final String url = HttpUrlExtractor.fromApacheHc4Request(host, req);
      if (BootstrapDispatcher.beforeHttpSend(url)) {
        throw new ChaosHttpSuppressException("HTTP execute suppressed by chaos agent: " + url);
      }
    }
  }

  /**
   * Intercepts Apache HttpComponents 4.x {@code CloseableHttpClient.execute(HttpUriRequest, ...)}
   * overloads — the variants where the request is the first argument with no separate host. The
   * previous single {@code takesArguments(2)} matcher bound the HttpHost-first advice to {@code
   * execute(HttpUriRequest, HttpContext)} and {@code execute(HttpUriRequest, ResponseHandler)} as
   * well, handing the advice an {@code HttpUriRequest} where it expected an {@code HttpHost} and
   * producing malformed URL strings (or none at all).
   */
  static final class ApacheHc4UriExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final Object request) throws Throwable {
      final String url = HttpUrlExtractor.fromApacheHc4UriRequest(request);
      if (BootstrapDispatcher.beforeHttpSend(url)) {
        throw new ChaosHttpSuppressException("HTTP execute suppressed by chaos agent: " + url);
      }
    }
  }

  /**
   * Intercepts Apache HttpComponents 5.x {@code CloseableHttpClient.execute(ClassicHttpRequest,
   * HttpClientResponseHandler)}.
   */
  static final class ApacheHc5ExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final Object request) throws Throwable {
      final String url = HttpUrlExtractor.fromApacheHc5Request(request);
      if (BootstrapDispatcher.beforeHttpSend(url)) {
        throw new ChaosHttpSuppressException("HTTP execute suppressed by chaos agent: " + url);
      }
    }
  }

  /**
   * Intercepts Reactor Netty {@code HttpClientConnect.connect(HttpClientRequest, NettyOutbound,
   * ...)}. Spring {@code WebClient} internally dispatches through this method.
   */
  static final class ReactorNettyConnectAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final Object request) throws Throwable {
      final String url = HttpUrlExtractor.fromReactorNettyRequest(request);
      if (BootstrapDispatcher.beforeHttpSendAsync(url)) {
        throw new ChaosHttpSuppressException("HTTP connect suppressed by chaos agent: " + url);
      }
    }
  }
}
