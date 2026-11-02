package com.macstab.chaos.jvm.quarkus;

import com.macstab.chaos.jvm.bootstrap.ChaosManagement;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Serves {@code /q/chaos}, the Quarkus counterpart of the Spring Boot actuator's {@code
 * /actuator/chaos}: {@code GET} the diagnostics snapshot, {@code POST} a plan as the body to
 * activate it, {@code DELETE /q/chaos/<id>} to stop a plan activated here, {@code DELETE /q/chaos}
 * to stop them all. Registered by the deployment module only when {@code
 * macstab.chaos.management.enabled=true}; runs on a worker thread.
 */
public final class ChaosManagementHandler implements Handler<RoutingContext> {

  private final String root;
  private volatile ChaosManagement management;

  /**
   * Creates the handler.
   *
   * @param root the route's path, e.g. {@code /q/chaos}; the rest of a request path is the id
   */
  public ChaosManagementHandler(final String root) {
    this.root = root;
  }

  @Override
  public void handle(final RoutingContext context) {
    final String path = context.normalizedPath();
    final String subPath = path.startsWith(root) ? path.substring(root.length()) : "";
    context
        .request()
        .body()
        .onComplete(
            body -> {
              final ChaosManagement.HttpResult result =
                  management()
                      .handle(
                          context.request().method().name(),
                          subPath,
                          body.succeeded() && body.result() != null
                              ? body.result().toString()
                              : null);
              context
                  .response()
                  .setStatusCode(result.status())
                  .putHeader("Content-Type", "application/json")
                  .end(result.json());
            });
  }

  private ChaosManagement management() {
    ChaosManagement current = management;
    if (current == null) {
      synchronized (this) {
        current = management;
        if (current == null) {
          current = new ChaosManagement(ChaosPlatform.current());
          management = current;
        }
      }
    }
    return current;
  }
}
