package com.macstab.chaos.jvm.quarkus.management;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosManagement;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Serves {@code /q/chaos}, the Quarkus counterpart of the Spring Boot actuator's {@code
 * /actuator/chaos}: {@code GET} the diagnostics snapshot, {@code POST} a plan as the body to
 * activate it, {@code DELETE /q/chaos/<id>} to stop a plan activated here, {@code DELETE /q/chaos}
 * to stop them all.
 *
 * <p>Every request is answered: 404 while {@code macstab.chaos.management.enabled} is not {@code
 * true} (read on each request, so it can be switched without a rebuild); 404 outside its root; 401
 * for a request without a non-anonymous Quarkus {@code SecurityIdentity} (secure the path with
 * Quarkus security, e.g. {@code quarkus.http.auth.permission}), unless {@code
 * macstab.chaos.management.allow-anonymous} is {@code true}; for a {@code POST}, 415 unless it is
 * {@code application/json} and 413 for a body above {@link ChaosPlanMapper#MAX_JSON_LENGTH} bytes;
 * 503 when the agent is not installed; otherwise the operation's status and JSON. No body is read
 * before these checks admit the request; the body of a request refused early is discarded, and the
 * connection of a body too large is closed. The work runs on a worker thread, never on the event
 * loop; a body another extension has already read is used as it is.
 */
public final class ChaosManagementHandler implements Handler<RoutingContext>, AutoCloseable {

  private final String root;
  private final BooleanSupplier enabled;
  private final BooleanSupplier allowAnonymous;
  private final Supplier<ChaosControlPlane> controlPlane;
  private volatile @Nullable ChaosManagement management;

  private static final ChaosManagement.HttpResult TOO_LARGE =
      new ChaosManagement.HttpResult(
          413,
          "{\"status\":\"error\",\"message\":\"the plan exceeds "
              + ChaosPlanMapper.MAX_JSON_LENGTH
              + " bytes\"}");
  private static final ChaosManagement.HttpResult INVALID_LENGTH =
      new ChaosManagement.HttpResult(
          400, "{\"status\":\"error\",\"message\":\"invalid Content-Length\"}");
  private static final ChaosManagement.HttpResult FAILED =
      new ChaosManagement.HttpResult(
          500, "{\"status\":\"error\",\"message\":\"chaos management failed\"}");

  /**
   * Creates the handler.
   *
   * @param root the route's path, e.g. {@code /q/chaos}; the rest of a request path is the id
   * @param enabled whether management is switched on, read per request
   * @param allowAnonymous whether requests without an authenticated user are served, read per
   *     request
   * @param controlPlane the installed control plane; throws {@link IllegalStateException} when the
   *     agent is not installed
   */
  public ChaosManagementHandler(
      final String root,
      final BooleanSupplier enabled,
      final BooleanSupplier allowAnonymous,
      final Supplier<ChaosControlPlane> controlPlane) {
    this.root = root;
    this.enabled = enabled;
    this.allowAnonymous = allowAnonymous;
    this.controlPlane = controlPlane;
  }

  @Override
  public void handle(final RoutingContext context) {
    final RequestBody read = context.body();
    if (read != null && read.buffer() != null) {
      // A body handler (installed by another extension) already read the body; its limit applied.
      final String body = read.asString();
      context
          .vertx()
          .executeBlocking(
              () -> {
                serve(context, body);
                return null;
              },
              false);
      return;
    }
    // Nothing is read before the request is admitted: the switch, the path and the caller are
    // checked on a worker thread (resolving a deferred identity blocks), the body only afterwards.
    context.request().pause();
    final Context requestContext = context.vertx().getOrCreateContext();
    context
        .vertx()
        .executeBlocking(
            () -> {
              admit(context, requestContext);
              return null;
            },
            false);
  }

  /** Answers a request whose body is unread, or reads the body of an admitted POST first. */
  private void admit(final RoutingContext context, final Context requestContext) {
    final ChaosManagement.HttpResult refused = refusal(context);
    if (refused == null && HttpMethod.POST.equals(context.request().method())) {
      requestContext.runOnContext(ignored -> readBody(context));
      return;
    }
    if (refused != null && refused.status() == 413) {
      // The declared body is too large to read only to discard it.
      answer(context, refused).onComplete(sent -> context.request().connection().close());
      return;
    }
    // Resumed without a data handler, the unread body is discarded and the connection stays usable.
    context.request().resume();
    answer(context, refused != null ? refused : operate(context, null));
  }

  /** Reads the body of an admitted POST on the request's context, up to the plan size cap. */
  private void readBody(final RoutingContext context) {
    final HttpServerRequest request = context.request();
    if (request.isEnded()) {
      // A body handler ran but kept no buffer: there is no body left to read.
      operateOnWorker(context, null);
      return;
    }
    final Buffer body = Buffer.buffer();
    final AtomicBoolean finished = new AtomicBoolean();
    request
        .handler(
            chunk -> {
              if (finished.get()) {
                return;
              }
              if (body.length() + chunk.length() > ChaosPlanMapper.MAX_JSON_LENGTH) {
                finished.set(true);
                answer(context, TOO_LARGE)
                    .onComplete(sent -> context.request().connection().close());
                return;
              }
              body.appendBuffer(chunk);
            })
        .exceptionHandler(
            failed -> {
              if (finished.compareAndSet(false, true)) {
                operateOnWorker(context, null);
              }
            })
        .endHandler(
            ended -> {
              if (finished.compareAndSet(false, true)) {
                operateOnWorker(context, body.toString());
              }
            })
        .resume();
  }

  private void operateOnWorker(final RoutingContext context, final @Nullable String body) {
    context
        .vertx()
        .executeBlocking(
            () -> {
              answer(context, operate(context, body));
              return null;
            },
            false);
  }

  /** Answers the request; never leaves it unanswered. */
  void serve(final RoutingContext context, final @Nullable String body) {
    final ChaosManagement.HttpResult refused = refusal(context);
    answer(context, refused != null ? refused : operate(context, body));
  }

  /**
   * Why the request is refused, or {@code null} when it is admitted; reads no body. In order: the
   * switch, the path, the caller, and for a POST the media type and the declared length.
   */
  private ChaosManagement.@Nullable HttpResult refusal(final RoutingContext context) {
    try {
      final String path = context.normalizedPath();
      if (!enabled.getAsBoolean()) {
        return new ChaosManagement.HttpResult(
            404, "{\"status\":\"error\",\"message\":\"chaos management is disabled\"}");
      }
      if (!path.equals(root) && !path.startsWith(root + "/")) {
        return new ChaosManagement.HttpResult(
            404, "{\"status\":\"error\",\"message\":\"unknown path\"}");
      }
      if (!allowAnonymous.getAsBoolean() && anonymous(context)) {
        // Activating chaos is as powerful as deploying code: an anonymous caller may not.
        return new ChaosManagement.HttpResult(
            401, "{\"status\":\"error\",\"message\":\"authentication required\"}");
      }
      final HttpServerRequest request = context.request();
      if (!HttpMethod.POST.equals(request.method())) {
        return null;
      }
      final String contentType = request.getHeader("Content-Type");
      if (contentType == null
          || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
        return new ChaosManagement.HttpResult(
            415, "{\"status\":\"error\",\"message\":\"the plan must be application/json\"}");
      }
      final String contentLength = request.getHeader("Content-Length");
      if (contentLength != null) {
        final long length;
        try {
          length = Long.parseLong(contentLength.trim());
        } catch (final NumberFormatException unparsable) {
          return INVALID_LENGTH;
        }
        if (length < 0) {
          return INVALID_LENGTH;
        }
        if (length > ChaosPlanMapper.MAX_JSON_LENGTH) {
          return TOO_LARGE;
        }
      }
      return null;
    } catch (final RuntimeException failed) {
      return FAILED;
    }
  }

  /**
   * Whether the caller has no authenticated Quarkus identity: a user of another type carries none;
   * otherwise the identity proactive authentication set, or the one lazy authentication deferred.
   * May block, so never on the event loop.
   */
  private static boolean anonymous(final RoutingContext context) {
    if (context.user() != null && !(context.user() instanceof QuarkusHttpUser)) {
      return true;
    }
    final SecurityIdentity identity = QuarkusHttpUser.getSecurityIdentityBlocking(context, null);
    return identity == null || identity.isAnonymous();
  }

  /** Runs the operation of an admitted request. */
  private ChaosManagement.HttpResult operate(
      final RoutingContext context, final @Nullable String body) {
    try {
      final ChaosManagement current = management();
      if (current == null) {
        return new ChaosManagement.HttpResult(
            503, "{\"status\":\"error\",\"message\":\"the chaos agent is not installed\"}");
      }
      return current.handle(
          context.request().method().name(),
          context.normalizedPath().substring(root.length()),
          body);
    } catch (final RuntimeException failed) {
      return FAILED;
    }
  }

  private static Future<Void> answer(
      final RoutingContext context, final ChaosManagement.HttpResult result) {
    return context
        .response()
        .setStatusCode(result.status())
        .putHeader("Content-Type", "application/json")
        .end(result.json());
  }

  /** The management over the installed control plane; {@code null} while no agent is installed. */
  private @Nullable ChaosManagement management() {
    ChaosManagement current = management;
    if (current == null) {
      synchronized (this) {
        current = management;
        if (current == null) {
          final ChaosControlPlane installed;
          try {
            installed = controlPlane.get();
          } catch (final IllegalStateException notInstalled) {
            return null;
          }
          current = new ChaosManagement(installed);
          management = current;
        }
      }
    }
    return current;
  }

  /** Stops the plans activated through this route; on shutdown and dev-mode reload. */
  @Override
  public void close() {
    final ChaosManagement current = management;
    if (current != null) {
      current.close();
    }
  }
}
