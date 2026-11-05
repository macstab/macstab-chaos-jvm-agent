package com.macstab.chaos.jvm.quarkus.management;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.ConfigProvider;

/** Builds the {@code /q/chaos} handler at runtime initialisation. */
@Recorder
public class ChaosManagementRecorder {

  /** The runtime switch of the route. */
  public static final String ENABLED_PROPERTY = "macstab.chaos.management.enabled";

  /** Serves requests without an authenticated user; off by default. */
  public static final String ALLOW_ANONYMOUS_PROPERTY = "macstab.chaos.management.allow-anonymous";

  /** Default constructor invoked by the Quarkus build step. */
  public ChaosManagementRecorder() {}

  /**
   * Returns the route handler, closed on shutdown so the plans it activated stop with the
   * application (and with a dev-mode reload).
   *
   * @param root the route's full path
   * @param shutdown the application's shutdown context
   * @return the handler
   */
  public Handler<RoutingContext> handler(final String root, final ShutdownContext shutdown) {
    final ChaosManagementHandler handler =
        new ChaosManagementHandler(
            root,
            () -> flag(ENABLED_PROPERTY),
            () -> flag(ALLOW_ANONYMOUS_PROPERTY),
            ChaosPlatform::current);
    shutdown.addShutdownTask(handler::close);
    return handler;
  }

  private static boolean flag(final String property) {
    return ConfigProvider.getConfig()
        .getOptionalValue(property, Boolean.class)
        .orElse(Boolean.FALSE);
  }
}
