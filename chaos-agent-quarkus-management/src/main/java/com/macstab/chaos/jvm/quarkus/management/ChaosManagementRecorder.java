package com.macstab.chaos.jvm.quarkus.management;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.startup.TrustedSources;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;

/** Builds the {@code /q/chaos} handler at runtime initialisation. */
@Recorder
public class ChaosManagementRecorder {

  /** The runtime switch of the route; honoured only from a launch-time local source. */
  public static final String ENABLED_PROPERTY = "macstab.chaos.management.enabled";

  /**
   * Serves requests without an authenticated user; off by default, and honoured only from a
   * launch-time local source.
   */
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
    return flag(ConfigProvider.getConfig(), property);
  }

  /**
   * Whether {@code property} switches its feature on in {@code config}: only from a launch-time
   * local source, or from any source once {@code macstab.chaos.allow-remote-enable=true} comes from
   * one.
   */
  static boolean flag(final Config config, final String property) {
    return TrustedSources.switchedOn(
        property,
        name -> {
          final ConfigValue value = config.getConfigValue(name);
          return value.getValue() == null
              ? null
              : new TrustedSources.Sourced(value.getValue(), String.valueOf(value.getSourceName()));
        },
        TrustedSources::smallRye);
  }
}
