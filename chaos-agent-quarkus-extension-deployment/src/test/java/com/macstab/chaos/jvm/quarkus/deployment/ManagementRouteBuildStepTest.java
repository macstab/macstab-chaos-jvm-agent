package com.macstab.chaos.jvm.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.quarkus.ChaosManagementHandler;
import com.macstab.chaos.jvm.quarkus.ChaosQuarkusRecorder;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.runtime.HandlerType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The /q/chaos management route build step")
class ManagementRouteBuildStepTest {

  private static final String ENABLED = "macstab.chaos.management.enabled";

  @AfterEach
  void clear() {
    System.clearProperty(ENABLED);
  }

  private static List<RouteBuildItem> routes() {
    final List<RouteBuildItem> produced = new ArrayList<>();
    new ChaosQuarkusBuildStep()
        .managementRoute(
            new ChaosQuarkusRecorder(),
            new NonApplicationRootPathBuildItem("/", "/q", null),
            produced::add);
    return produced;
  }

  @Test
  @DisplayName("registers nothing unless macstab.chaos.management.enabled is true")
  void offByDefault() {
    assertThat(ChaosQuarkusRecorder.class.isAnnotationPresent(Recorder.class)).isTrue();
    assertThat(routes()).isEmpty();
  }

  @Test
  @DisplayName("registers blocking /q/chaos routes served by the management handler")
  void registersRoutes() {
    System.setProperty(ENABLED, "true");

    final List<RouteBuildItem> produced = routes();

    assertThat(produced).hasSize(2);
    assertThat(produced)
        .allSatisfy(
            route -> {
              assertThat(route.getHandler()).isInstanceOf(ChaosManagementHandler.class);
              assertThat(route.getType()).isEqualTo(HandlerType.BLOCKING);
            });
  }
}
