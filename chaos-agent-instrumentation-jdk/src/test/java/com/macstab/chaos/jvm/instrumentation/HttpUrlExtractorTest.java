package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The URL each HTTP client hook reports, read reflectively from stand-ins shaped like the client
 * types: what is read when the accessors answer, and {@code null} — no URL filter matches — when
 * they are missing, answer {@code null} or fail.
 */
@DisplayName("HttpUrlExtractor")
class HttpUrlExtractorTest {

  private static final String URL = "https://api.example.com/v1/orders";

  /** An accessor that fails, as a closed or half-built client object does. */
  static final class Broken {
    @Override
    public String toString() {
      throw new IllegalStateException("broken");
    }
  }

  /** The request line of an Apache HttpClient 4 request. */
  static final class Hc4RequestLine {
    private final Object uri;

    Hc4RequestLine(final Object uri) {
      this.uri = uri;
    }

    Object getUri() {
      return uri;
    }
  }

  /** An Apache HttpClient 4 request. */
  static final class Hc4Request {
    private final Hc4RequestLine requestLine;

    Hc4Request(final Hc4RequestLine requestLine) {
      this.requestLine = requestLine;
    }

    Hc4RequestLine getRequestLine() {
      return requestLine;
    }
  }

  @Nested
  @DisplayName("OkHttp call")
  class OkHttp {

    private record OkRequest(Object url) {}

    private record OkCall(Object request) {}

    @Test
    @DisplayName("reads request().url()")
    void readsTheUrl() {
      assertThat(HttpUrlExtractor.fromOkHttpCall(new OkCall(new OkRequest(URL)))).isEqualTo(URL);
    }

    @Test
    @DisplayName("is null without a call, a request or a URL, and when the call fails")
    void nullWhenUnreadable() {
      assertThat(HttpUrlExtractor.fromOkHttpCall(null)).isNull();
      assertThat(HttpUrlExtractor.fromOkHttpCall(new OkCall(null))).isNull();
      assertThat(HttpUrlExtractor.fromOkHttpCall(new OkCall(new OkRequest(null)))).isNull();
      assertThat(HttpUrlExtractor.fromOkHttpCall(new OkCall(new OkRequest(new Broken())))).isNull();
    }
  }

  @Nested
  @DisplayName("Apache HttpClient 4 doExecute(host, request)")
  class ApacheHc4 {

    /** An HttpHost: toURI() is scheme, host and port. */
    record Hc4Host(Object uri) {
      Object toURI() {
        return uri;
      }
    }

    /** An HttpUriRequest: its request line and getURI(). */
    static final class Hc4UriRequest {
      private final Object target;
      private final Object uri;

      Hc4UriRequest(final Object target, final Object uri) {
        this.target = target;
        this.uri = uri;
      }

      Hc4RequestLine getRequestLine() {
        return new Hc4RequestLine(target);
      }

      Object getURI() {
        return uri;
      }
    }

    private final Hc4Host host = new Hc4Host("https://api.example.com");

    @Test
    @DisplayName("an absolute request target wins over the host")
    void absoluteTargetWins() {
      assertThat(HttpUrlExtractor.fromApacheHc4(host, new Hc4Request(new Hc4RequestLine(URL))))
          .isEqualTo(URL);
      assertThat(
              HttpUrlExtractor.fromApacheHc4(
                  null, new Hc4UriRequest("http://other.example.com/", URI.create(URL))))
          .isEqualTo("http://other.example.com/");
    }

    @Test
    @DisplayName("else the host's URI followed by the request target")
    void hostAndTarget() {
      assertThat(
              HttpUrlExtractor.fromApacheHc4(
                  host, new Hc4Request(new Hc4RequestLine("/v1/orders"))))
          .isEqualTo(URL);
      assertThat(HttpUrlExtractor.fromApacheHc4(host, new Hc4Request(null)))
          .isEqualTo("https://api.example.com");
    }

    @Test
    @DisplayName("else, without a host, the request's getURI(), else its target")
    void withoutHost() {
      assertThat(HttpUrlExtractor.fromApacheHc4(null, new Hc4UriRequest("/v1/orders", URL)))
          .isEqualTo(URL);
      assertThat(HttpUrlExtractor.fromApacheHc4(null, new Hc4UriRequest("/v1/orders", null)))
          .isEqualTo("/v1/orders");
      assertThat(HttpUrlExtractor.fromApacheHc4(null, new Hc4Request(new Hc4RequestLine("/x"))))
          .isEqualTo("/x");
      // The absence of getURI() is remembered; the second call reads the same way.
      assertThat(HttpUrlExtractor.fromApacheHc4(null, new Hc4Request(new Hc4RequestLine("/x"))))
          .isEqualTo("/x");
    }

    @Test
    @DisplayName("is null when nothing can be read, or the host or the request fails")
    void nullWhenUnreadable() {
      assertThat(HttpUrlExtractor.fromApacheHc4(null, null)).isNull();
      assertThat(HttpUrlExtractor.fromApacheHc4(null, new Hc4Request(null))).isNull();
      assertThat(HttpUrlExtractor.fromApacheHc4(new Hc4Host(new Broken()), new Hc4Request(null)))
          .isNull();
      assertThat(
              HttpUrlExtractor.fromApacheHc4(
                  null, new Hc4Request(new Hc4RequestLine(new Broken()))))
          .isNull();
    }
  }

  @Nested
  @DisplayName("Apache HttpClient 5 doExecute(host, request)")
  class ApacheHc5 {

    static final class Hc5Request {
      private final Object uri;
      private final Object requestUri;

      Hc5Request(final Object uri, final Object requestUri) {
        this.uri = uri;
        this.requestUri = requestUri;
      }

      Object getUri() {
        return uri;
      }

      Object getRequestUri() {
        return requestUri;
      }
    }

    record Hc5Host(Object uri) {
      Object toURI() {
        return uri;
      }
    }

    private final Hc5Host host = new Hc5Host("https://api.example.com");

    @Test
    @DisplayName("an absolute request target wins over the host")
    void absoluteTargetWins() {
      assertThat(HttpUrlExtractor.fromApacheHc5(host, new Hc5Request(null, URL))).isEqualTo(URL);
      assertThat(
              HttpUrlExtractor.fromApacheHc5(
                  host, new Hc5Request(null, "http://other.example.com/")))
          .isEqualTo("http://other.example.com/");
    }

    @Test
    @DisplayName("else the host's URI followed by the request target")
    void hostAndTarget() {
      assertThat(HttpUrlExtractor.fromApacheHc5(host, new Hc5Request(null, "/v1/orders")))
          .isEqualTo(URL);
      assertThat(HttpUrlExtractor.fromApacheHc5(host, new Hc5Request(null, null)))
          .isEqualTo("https://api.example.com");
    }

    @Test
    @DisplayName("else, without a host (executeOpen(null, ...)), the full getUri()")
    void withoutHost() {
      assertThat(
              HttpUrlExtractor.fromApacheHc5(null, new Hc5Request(URI.create(URL), "/v1/orders")))
          .isEqualTo(URL);
      assertThat(HttpUrlExtractor.fromApacheHc5(null, new Hc5Request(null, "/v1/orders")))
          .as("no getUri() to read: the target")
          .isEqualTo("/v1/orders");
    }

    @Test
    @DisplayName("is null when nothing can be read, or the host or the request fails")
    void nullWhenUnreadable() {
      assertThat(HttpUrlExtractor.fromApacheHc5(null, null)).isNull();
      assertThat(HttpUrlExtractor.fromApacheHc5(null, new Hc5Request(null, null))).isNull();
      assertThat(
              HttpUrlExtractor.fromApacheHc5(new Hc5Host(new Broken()), new Hc5Request(null, "/x")))
          .isNull();
      assertThat(HttpUrlExtractor.fromApacheHc5(null, new Hc5Request(new Broken(), "/x"))).isNull();
    }
  }

  @Nested
  @DisplayName("Reactor Netty configuration")
  class ReactorNetty {

    private record RnConfiguration(Object uri, Object baseUrl) {}

    /** A uri() declared two interfaces up, as Reactor Netty declares its accessors. */
    interface UriSource {
      default Object uri() {
        return URI.create(URL);
      }

      default Object uriOf(final Object path) {
        return path;
      }
    }

    interface Left extends UriSource {
      default Object uri(final int port) {
        return port;
      }
    }

    interface Right extends UriSource {}

    /** Implements Left itself; its subclass implements it again, beside Right: a diamond. */
    static class InheritedBase implements Left {}

    static final class InheritedConfiguration extends InheritedBase implements Left, Right {
      Object uri(final String path) {
        return path;
      }
    }

    /** Declares neither accessor anywhere in its hierarchy. */
    static final class EmptyConfiguration implements java.io.Serializable {
      private static final long serialVersionUID = 1L;
    }

    @Test
    @DisplayName("reads uri(), else baseUrl()")
    void readsUriThenBaseUrl() {
      assertThat(HttpUrlExtractor.fromReactorNettyConfig(new RnConfiguration(URL, "x")))
          .isEqualTo(URL);
      assertThat(HttpUrlExtractor.fromReactorNettyConfig(new RnConfiguration(null, URL)))
          .isEqualTo(URL);
    }

    @Test
    @DisplayName("an absolute uri() wins; a relative one follows baseUrl()")
    void composesBaseUrlAndUri() {
      assertThat(
              HttpUrlExtractor.fromReactorNettyConfig(
                  new RnConfiguration(URL, "http://other.example.com")))
          .isEqualTo(URL);
      assertThat(
              HttpUrlExtractor.fromReactorNettyConfig(
                  new RnConfiguration("/v1/orders", "https://api.example.com")))
          .isEqualTo(URL);
      assertThat(HttpUrlExtractor.fromReactorNettyConfig(new RnConfiguration("/v1/orders", null)))
          .isEqualTo("/v1/orders");
      assertThat(HttpUrlExtractor.fromReactorNettyConfig(null)).isNull();
    }

    @Test
    @DisplayName("finds an accessor declared on a super-interface of an implemented interface")
    void findsInterfaceAccessors() {
      assertThat(HttpUrlExtractor.fromReactorNettyConfig(new InheritedConfiguration()))
          .isEqualTo(URL);
    }

    @Test
    @DisplayName("is null without a configuration or either accessor")
    void nullWhenUnreadable() {
      assertThat(HttpUrlExtractor.fromReactorNettyConfig(null)).isNull();
      assertThat(HttpUrlExtractor.fromReactorNettyConfig(new RnConfiguration(null, null))).isNull();
      assertThat(HttpUrlExtractor.fromReactorNettyConfig(new EmptyConfiguration())).isNull();
      assertThat(HttpUrlExtractor.fromReactorNettyConfig(new RnConfiguration(new Broken(), null)))
          .isNull();
    }

    @Test
    @DisplayName("is null when the accessor belongs to a package its module does not open")
    void nullWhenEncapsulated() {
      // HttpRequest.build() returns a jdk.internal.net.http type: its uri() is found but cannot
      // be made accessible, and calling it is refused.
      final Object encapsulated = HttpRequest.newBuilder(URI.create(URL)).build();
      final Class<?> type = encapsulated.getClass();
      assertThat(type.getModule().isOpen(type.getPackageName(), HttpUrlExtractor.class.getModule()))
          .isFalse();
      assertThat(HttpUrlExtractor.fromReactorNettyConfig(encapsulated)).isNull();
    }
  }
}
