package com.macstab.chaos.jvm.quarkus.management.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.quarkus.management.ChaosManagementHandler;
import com.macstab.chaos.jvm.quarkus.management.ChaosManagementRecorder;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.runtime.HandlerType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The /q/chaos management build step")
class ChaosManagementBuildStepTest {

  /**
   * Runs the build step against {@code paths}; the handler roots it asked for go to {@code roots}.
   */
  private static List<RouteBuildItem> routes(
      final NonApplicationRootPathBuildItem paths, final List<String> roots) {
    final List<RouteBuildItem> produced = new ArrayList<>();
    new ChaosManagementBuildStep()
        .routes(
            // The real recorder is a build-time proxy in Quarkus; here a stub returns the handler.
            new ChaosManagementRecorder() {
              @Override
              public io.vertx.core.Handler<io.vertx.ext.web.RoutingContext> handler(
                  final String root, final io.quarkus.runtime.ShutdownContext shutdown) {
                roots.add(root);
                return new ChaosManagementHandler(root, () -> true, () -> false, () -> null);
              }
            },
            paths,
            new ShutdownContextBuildItem(),
            produced::add);
    return produced;
  }

  @Test
  @DisplayName("registers blocking routes at chaos and below, served by one handler")
  void registersRoutes() {
    final List<String> roots = new ArrayList<>();
    final List<RouteBuildItem> produced =
        routes(new NonApplicationRootPathBuildItem("/", "/q", null), roots);

    assertThat(produced).hasSize(2);
    assertThat(produced)
        .allSatisfy(
            route -> {
              assertThat(route.getHandler()).isInstanceOf(ChaosManagementHandler.class);
              assertThat(route.getType()).isEqualTo(HandlerType.BLOCKING);
            });
    assertThat(produced.get(0).getHandler()).isSameAs(produced.get(1).getHandler());
    // Without a management interface the route stays under the non-application root.
    assertThat(roots).containsExactly("/q/chaos");
    assertThat(new ChaosManagementBuildStep().feature().getName())
        .isEqualTo(ChaosManagementBuildStep.FEATURE_NAME);
  }

  @Test
  @DisplayName("roots the handler at the management root when the management interface is enabled")
  void managementRoot() {
    final List<String> roots = new ArrayList<>();
    final List<RouteBuildItem> produced =
        routes(new NonApplicationRootPathBuildItem("/", "/q", "/mgmt"), roots);

    // Served by the management interface, under its root, and the handler knows that root.
    assertThat(produced).hasSize(2).allSatisfy(route -> assertThat(route.isManagement()).isTrue());
    assertThat(roots).containsExactly("/mgmt/chaos");
  }

  @Test
  @DisplayName("the deployment jar lists its build step class")
  void buildStepIndex() throws Exception {
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("META-INF/quarkus-build-steps.list")) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8).strip())
          .isEqualTo(ChaosManagementBuildStep.class.getName());
    }
  }
}
