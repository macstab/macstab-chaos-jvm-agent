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

  @Test
  @DisplayName("registers blocking routes at chaos and below, served by one handler")
  void registersRoutes() {
    final List<RouteBuildItem> produced = new ArrayList<>();
    new ChaosManagementBuildStep()
        .routes(
            // The real recorder is a build-time proxy in Quarkus; here a stub returns the handler.
            new ChaosManagementRecorder() {
              @Override
              public io.vertx.core.Handler<io.vertx.ext.web.RoutingContext> handler(
                  final String root, final io.quarkus.runtime.ShutdownContext shutdown) {
                return new ChaosManagementHandler(root, () -> true, () -> false, () -> null);
              }
            },
            new NonApplicationRootPathBuildItem("/", "/q", null),
            new ShutdownContextBuildItem(),
            produced::add);

    assertThat(produced).hasSize(2);
    assertThat(produced)
        .allSatisfy(
            route -> {
              assertThat(route.getHandler()).isInstanceOf(ChaosManagementHandler.class);
              assertThat(route.getType()).isEqualTo(HandlerType.BLOCKING);
            });
    assertThat(produced.get(0).getHandler()).isSameAs(produced.get(1).getHandler());
    assertThat(new ChaosManagementBuildStep().feature().getName())
        .isEqualTo(ChaosManagementBuildStep.FEATURE_NAME);
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
