package com.macstab.chaos.jvm.quarkus.management;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosManagement;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
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
 * true} (read on each request, so it can be switched without a rebuild), 503 when the agent is not
 * installed, otherwise the operation's status and JSON. The work runs on a worker thread, never on
 * the event loop; a body another extension has already read is used as it is.
 */
public final class ChaosManagementHandler implements Handler<RoutingContext>, AutoCloseable {

  private final String root;
  private final BooleanSupplier enabled;
  private final Supplier<ChaosControlPlane> controlPlane;
  private volatile @Nullable ChaosManagement management;

  /**
   * Creates the handler.
   *
   * @param root the route's path, e.g. {@code /q/chaos}; the rest of a request path is the id
   * @param enabled whether management is switched on, read per request
   * @param controlPlane the installed control plane; throws {@link IllegalStateException} when the
   *     agent is not installed
   */
  public ChaosManagementHandler(
      final String root,
      final BooleanSupplier enabled,
      final Supplier<ChaosControlPlane> controlPlane) {
    this.root = root;
    this.enabled = enabled;
    this.controlPlane = controlPlane;
  }

  @Override
  public void handle(final RoutingContext context) {
    if (context.body() != null && context.body().buffer() != null) {
      // A body handler (installed by another extension) already read the body.
      serve(context, context.body().asString());
      return;
    }
    context
        .request()
        .body()
        .onComplete(
            body -> {
              final String text =
                  body.succeeded() && body.result() != null ? body.result().toString() : null;
              // The completion may run on the event loop: hand the work to a worker thread.
              context
                  .vertx()
                  .executeBlocking(
                      () -> {
                        serve(context, text);
                        return null;
                      },
                      false);
            });
  }

  /** Answers the request; never leaves it unanswered. */
  void serve(final RoutingContext context, final @Nullable String body) {
    int status;
    String json;
    try {
      final String path = context.normalizedPath();
      if (!enabled.getAsBoolean()) {
        status = 404;
        json = "{\"status\":\"error\",\"message\":\"chaos management is disabled\"}";
      } else if (!path.equals(root) && !path.startsWith(root + "/")) {
        status = 404;
        json = "{\"status\":\"error\",\"message\":\"unknown path\"}";
      } else {
        final ChaosManagement.HttpResult result =
            management()
                .handle(context.request().method().name(), path.substring(root.length()), body);
        status = result.status();
        json = result.json();
      }
    } catch (final IllegalStateException notInstalled) {
      status = 503;
      json = "{\"status\":\"error\",\"message\":\"the chaos agent is not installed\"}";
    } catch (final RuntimeException failed) {
      status = 500;
      json = "{\"status\":\"error\",\"message\":\"chaos management failed\"}";
    }
    context
        .response()
        .setStatusCode(status)
        .putHeader("Content-Type", "application/json")
        .end(json);
  }

  private ChaosManagement management() {
    ChaosManagement current = management;
    if (current == null) {
      synchronized (this) {
        current = management;
        if (current == null) {
          current = new ChaosManagement(controlPlane.get());
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
