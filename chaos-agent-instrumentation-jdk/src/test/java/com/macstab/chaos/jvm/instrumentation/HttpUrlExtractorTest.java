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
  @DisplayName("Apache HttpClient 4 host and request")
  class ApacheHc4 {

    @Test
    @DisplayName("joins the host and the request line's URI")
    void joinsHostAndPath() {
      assertThat(
              HttpUrlExtractor.fromApacheHc4Request(
                  "https://api.example.com", new Hc4Request(new Hc4RequestLine("/v1/orders"))))
          .isEqualTo(URL);
    }

    @Test
    @DisplayName("keeps what it can read of the host and the request")
    void partial() {
      assertThat(
              HttpUrlExtractor.fromApacheHc4Request(null, new Hc4Request(new Hc4RequestLine("/x"))))
          .isEqualTo("/x");
      assertThat(HttpUrlExtractor.fromApacheHc4Request("https://h", null)).isEqualTo("https://h");
      assertThat(HttpUrlExtractor.fromApacheHc4Request("https://h", new Hc4Request(null)))
          .isEqualTo("https://h");
      assertThat(
              HttpUrlExtractor.fromApacheHc4Request(
                  "https://h", new Hc4Request(new Hc4RequestLine(null))))
          .isEqualTo("https://h");
    }

    @Test
    @DisplayName("is null when nothing can be read or the host fails")
    void nullWhenUnreadable() {
      assertThat(HttpUrlExtractor.fromApacheHc4Request(null, null)).isNull();
      assertThat(HttpUrlExtractor.fromApacheHc4Request(new Broken(), null)).isNull();
    }
  }

  @Nested
  @DisplayName("Apache HttpClient 4 URI request")
  class ApacheHc4UriRequests {

    static final class Hc4UriRequest {
      private final Object uri;

      Hc4UriRequest(final Object uri) {
        this.uri = uri;
      }

      Object getURI() {
        return uri;
      }

      Hc4RequestLine getRequestLine() {
        return new Hc4RequestLine("/from-request-line");
      }
    }

    @Test
    @DisplayName("reads getURI()")
    void readsTheUri() {
      assertThat(HttpUrlExtractor.fromApacheHc4UriRequest(new Hc4UriRequest(URI.create(URL))))
          .isEqualTo(URL);
    }

    @Test
    @DisplayName("falls back to the request line when getURI() is null, fails or is absent")
    void fallsBackToTheRequestLine() {
      assertThat(HttpUrlExtractor.fromApacheHc4UriRequest(new Hc4UriRequest(null)))
          .isEqualTo("/from-request-line");
      assertThat(HttpUrlExtractor.fromApacheHc4UriRequest(new Hc4UriRequest(new Broken())))
          .isEqualTo("/from-request-line");
      final Hc4Request plain = new Hc4Request(new Hc4RequestLine("/plain"));
      assertThat(HttpUrlExtractor.fromApacheHc4UriRequest(plain)).isEqualTo("/plain");
      // The absence is remembered; the second call reads the same way.
      assertThat(HttpUrlExtractor.fromApacheHc4UriRequest(plain)).isEqualTo("/plain");
    }

    @Test
    @DisplayName("is null without a request, a request line or its URI, and when it fails")
    void nullWhenUnreadable() {
      assertThat(HttpUrlExtractor.fromApacheHc4UriRequest(null)).isNull();
      assertThat(HttpUrlExtractor.fromApacheHc4UriRequest(new Hc4Request(null))).isNull();
      assertThat(HttpUrlExtractor.fromApacheHc4UriRequest(new Hc4Request(new Hc4RequestLine(null))))
          .isNull();
      assertThat(
              HttpUrlExtractor.fromApacheHc4UriRequest(
                  new Hc4Request(new Hc4RequestLine(new Broken()))))
          .isNull();
    }
  }

  @Nested
  @DisplayName("Apache HttpClient 5 request")
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

    static final class Hc5Host {
      private final Object uri;

      Hc5Host(final Object uri) {
        this.uri = uri;
      }

      Object toURI() {
        return uri;
      }
    }

    @Test
    @DisplayName("prefers the absolute getUri() over the request target")
    void prefersTheAbsoluteUri() {
      assertThat(
              HttpUrlExtractor.fromApacheHc5Request(new Hc5Request(URI.create(URL), "/v1/orders")))
          .isEqualTo(URL);
    }

    @Test
    @DisplayName("falls back to the request target when getUri() is null or fails")
    void fallsBackToTheTarget() {
      assertThat(HttpUrlExtractor.fromApacheHc5Request(new Hc5Request(null, "/v1/orders")))
          .isEqualTo("/v1/orders");
      assertThat(HttpUrlExtractor.fromApacheHc5Request(new Hc5Request(new Broken(), "/v1/orders")))
          .isEqualTo("/v1/orders");
    }

    @Test
    @DisplayName("is null without a request or a target, and when the target fails")
    void nullWhenUnreadable() {
      assertThat(HttpUrlExtractor.fromApacheHc5Request(null)).isNull();
      assertThat(HttpUrlExtractor.fromApacheHc5Request(new Hc5Request(null, null))).isNull();
      assertThat(HttpUrlExtractor.fromApacheHc5Request(new Hc5Request(null, new Broken())))
          .isNull();
    }

    @Test
    @DisplayName("joins an explicit host and a relative target; an absolute target stands alone")
    void hostAndRequest() {
      final Hc5Host host = new Hc5Host("https://api.example.com");
      assertThat(
              HttpUrlExtractor.fromApacheHc5HostRequest(host, new Hc5Request(null, "/v1/orders")))
          .isEqualTo(URL);
      assertThat(HttpUrlExtractor.fromApacheHc5HostRequest(host, new Hc5Request(null, URL)))
          .isEqualTo(URL);
      assertThat(
              HttpUrlExtractor.fromApacheHc5HostRequest(
                  host, new Hc5Request(null, "http://other.example.com/")))
          .isEqualTo("http://other.example.com/");
      assertThat(HttpUrlExtractor.fromApacheHc5HostRequest(host, null))
          .isEqualTo("https://api.example.com");
      assertThat(HttpUrlExtractor.fromApacheHc5HostRequest(host, new Hc5Request(null, null)))
          .isEqualTo("https://api.example.com");
      assertThat(
              HttpUrlExtractor.fromApacheHc5HostRequest(
                  new Hc5Host(null), new Hc5Request(null, "/x")))
          .isEqualTo("/x");
      assertThat(HttpUrlExtractor.fromApacheHc5HostRequest(null, new Hc5Request(null, "/x")))
          .isEqualTo("/x");
    }

    @Test
    @DisplayName("is null for a host and request with nothing to read, and when the host fails")
    void hostAndRequestUnreadable() {
      assertThat(HttpUrlExtractor.fromApacheHc5HostRequest(null, null)).isNull();
      assertThat(
              HttpUrlExtractor.fromApacheHc5HostRequest(
                  new Hc5Host(new Broken()), new Hc5Request(null, "/x")))
          .isNull();
    }
  }

  @Nested
  @DisplayName("Reactor Netty client")
  class ReactorNetty {

    private record RnConfiguration(Object uri, Object baseUrl) {}

    private record RnClient(Object configuration) {}

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
    @DisplayName("reads configuration().uri(), else configuration().baseUrl()")
    void readsUriThenBaseUrl() {
      assertThat(
              HttpUrlExtractor.fromReactorNettyClient(new RnClient(new RnConfiguration(URL, "x"))))
          .isEqualTo(URL);
      assertThat(
              HttpUrlExtractor.fromReactorNettyClient(new RnClient(new RnConfiguration(null, URL))))
          .isEqualTo(URL);
    }

    @Test
    @DisplayName("finds an accessor declared on a super-interface of an implemented interface")
    void findsInterfaceAccessors() {
      assertThat(
              HttpUrlExtractor.fromReactorNettyClient(new RnClient(new InheritedConfiguration())))
          .isEqualTo(URL);
    }

    @Test
    @DisplayName("is null without a client, a configuration or either accessor")
    void nullWhenUnreadable() {
      assertThat(HttpUrlExtractor.fromReactorNettyClient(null)).isNull();
      assertThat(HttpUrlExtractor.fromReactorNettyClient(new RnClient(null))).isNull();
      assertThat(
              HttpUrlExtractor.fromReactorNettyClient(
                  new RnClient(new RnConfiguration(null, null))))
          .isNull();
      assertThat(HttpUrlExtractor.fromReactorNettyClient(new RnClient(new EmptyConfiguration())))
          .isNull();
      assertThat(
              HttpUrlExtractor.fromReactorNettyClient(
                  new RnClient(new RnConfiguration(new Broken(), null))))
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
      assertThat(HttpUrlExtractor.fromReactorNettyClient(new RnClient(encapsulated))).isNull();
    }
  }
}
