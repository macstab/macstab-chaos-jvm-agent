package com.macstab.chaos.jvm.quarkus.management.deployment;

import com.macstab.chaos.jvm.quarkus.management.ChaosManagementRecorder;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Registers the {@code /q/chaos} routes. Adding the extension is the build-time opt-in; the runtime
 * property {@code macstab.chaos.management.enabled} switches the route on. With the Quarkus
 * management interface enabled ({@code quarkus.management.enabled}), the route is served there,
 * under its root, and no longer on the HTTP port.
 */
public final class ChaosManagementBuildStep {

  /** The feature name shown in the Quarkus startup banner. */
  static final String FEATURE_NAME = "macstab-chaos-management";

  /** Default constructor invoked by Quarkus. */
  public ChaosManagementBuildStep() {}

  /**
   * Declares the feature.
   *
   * @return the feature build item
   */
  @BuildStep
  public FeatureBuildItem feature() {
    return new FeatureBuildItem(FEATURE_NAME);
  }

  /**
   * Registers the route at {@code chaos} and below under the management interface's root when that
   * interface is enabled, and under the non-application root ({@code /q}) otherwise.
   *
   * @param recorder the recorder providing the handler
   * @param nonApplicationRootPath where Quarkus serves its own endpoints
   * @param shutdown the shutdown context the handler is closed with
   * @param routes the route producer
   */
  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  public void routes(
      final ChaosManagementRecorder recorder,
      final NonApplicationRootPathBuildItem nonApplicationRootPath,
      final ShutdownContextBuildItem shutdown,
      final BuildProducer<RouteBuildItem> routes) {
    // The root the builder mounts a management route at: the management interface's root when it is
    // enabled, the non-application root otherwise.
    final Handler<RoutingContext> handler =
        recorder.handler(nonApplicationRootPath.getManagementRootPath() + "chaos", shutdown);
    // management() before route(): the builder reads it when the route is added.
    routes.produce(
        nonApplicationRootPath
            .routeBuilder()
            .management()
            .route("chaos")
            .handler(handler)
            .blockingRoute()
            .build());
    routes.produce(
        nonApplicationRootPath
            .routeBuilder()
            .management()
            .route("chaos/*")
            .handler(handler)
            .blockingRoute()
            .build());
  }
}
