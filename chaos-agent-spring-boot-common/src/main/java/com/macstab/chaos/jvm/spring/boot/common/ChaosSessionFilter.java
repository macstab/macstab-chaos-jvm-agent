package com.macstab.chaos.jvm.spring.boot.common;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosSession;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.function.Supplier;
import org.springframework.core.Ordered;

/**
 * Joins the chaos session a request came from: when the request carries {@link ChaosSession#HEADER}
 * and a session with that id is open in this JVM, the request thread is bound to it while the
 * request is handled, so session-scoped scenarios apply to the server side of the call. A test's
 * HTTP client sends the header automatically from inside a session.
 *
 * <p>Requests without the header, or with an unknown id, pass through untouched. Work the request
 * hands to other threads follows the session as for any bound thread; asynchronous servlet
 * processing that resumes on another container thread is not bound.
 */
public final class ChaosSessionFilter implements Filter, Ordered {

  private final Supplier<ChaosControlPlane> controlPlane;

  /**
   * Creates the filter.
   *
   * @param controlPlane supplies the installed control plane, or {@code null} when the agent is not
   *     installed
   */
  public ChaosSessionFilter(final Supplier<ChaosControlPlane> controlPlane) {
    this.controlPlane = controlPlane;
  }

  @Override
  public void doFilter(
      final ServletRequest request, final ServletResponse response, final FilterChain chain)
      throws IOException, ServletException {
    final String sessionId =
        request instanceof HttpServletRequest http ? http.getHeader(ChaosSession.HEADER) : null;
    final ChaosControlPlane plane =
        sessionId == null || sessionId.isBlank() ? null : controlPlane.get();
    if (plane == null) {
      chain.doFilter(request, response);
      return;
    }
    try (ChaosSession.ScopeBinding joined = plane.bindSession(sessionId.strip())) {
      chain.doFilter(request, response);
    }
  }

  /** Runs first, so every later filter and the handler run inside the session. */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
