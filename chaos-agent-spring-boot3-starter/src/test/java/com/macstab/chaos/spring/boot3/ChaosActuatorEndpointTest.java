package com.macstab.chaos.spring.boot3;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosDiagnostics;
import com.macstab.chaos.api.ChaosEventListener;
import com.macstab.chaos.api.ChaosPlan;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSession;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("ChaosActuatorEndpoint (Spring Boot 3)")
class ChaosActuatorEndpointTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ChaosAutoConfiguration.class))
          .withUserConfiguration(StubControlPlaneConfiguration.class);

  @Test
  @DisplayName("endpoint bean is present when macstab.chaos.actuator.enabled=true")
  void endpointPresentWhenActuatorEnabled() {
    runner
        .withPropertyValues("macstab.chaos.enabled=true", "macstab.chaos.actuator.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(ChaosActuatorEndpoint.class));
  }

  @Test
  @DisplayName("endpoint bean is absent when macstab.chaos.actuator.enabled is default")
  void endpointAbsentByDefault() {
    runner
        .withPropertyValues("macstab.chaos.enabled=true")
        .run(context -> assertThat(context).doesNotHaveBean(ChaosActuatorEndpoint.class));
  }

  @Test
  @DisplayName("endpoint bean is absent when macstab.chaos.actuator.enabled=false")
  void endpointAbsentWhenActuatorDisabled() {
    runner
        .withPropertyValues("macstab.chaos.enabled=true", "macstab.chaos.actuator.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ChaosActuatorEndpoint.class));
  }

  @Test
  @DisplayName("snapshot() returns a non-null diagnostics snapshot")
  void snapshotReturnsNonNull() {
    runner
        .withPropertyValues("macstab.chaos.enabled=true", "macstab.chaos.actuator.enabled=true")
        .run(
            context -> {
              final ChaosActuatorEndpoint endpoint = context.getBean(ChaosActuatorEndpoint.class);
              final ChaosDiagnostics.Snapshot snapshot = endpoint.snapshot();
              assertThat(snapshot).isNotNull();
              assertThat(snapshot.scenarios()).isNotNull();
              assertThat(snapshot.runtimeDetails()).isNotNull();
            });
  }

  @Test
  @DisplayName("stop() reports not-found for an unknown scenario ID")
  void stopReportsNotFoundForUnknownScenario() {
    runner
        .withPropertyValues("macstab.chaos.enabled=true", "macstab.chaos.actuator.enabled=true")
        .run(
            context -> {
              final ChaosActuatorEndpoint endpoint = context.getBean(ChaosActuatorEndpoint.class);
              final ChaosActuatorEndpoint.StopResponse response = endpoint.stop("unknown-id");
              assertThat(response.status()).isEqualTo("not-found");
              assertThat(response.scenarioId()).isEqualTo("unknown-id");
            });
  }

  @Test
  @DisplayName("stopAll() returns zero when no managed handles are registered")
  void stopAllReturnsZeroWhenEmpty() {
    runner
        .withPropertyValues("macstab.chaos.enabled=true", "macstab.chaos.actuator.enabled=true")
        .run(
            context -> {
              final ChaosActuatorEndpoint endpoint = context.getBean(ChaosActuatorEndpoint.class);
              final ChaosActuatorEndpoint.StopAllResponse response = endpoint.stopAll();
              assertThat(response.stoppedCount()).isZero();
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class StubControlPlaneConfiguration {

    @Bean
    ChaosControlPlane chaosControlPlane() {
      return new StubControlPlane();
    }
  }

  /**
   * Minimal stub used in endpoint tests to avoid installing the JVM-level agent during unit testing
   * of the Spring wiring.
   */
  static final class StubControlPlane implements ChaosControlPlane {

    @Override
    public ChaosActivationHandle activate(final ChaosScenario scenario) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChaosActivationHandle activate(final ChaosPlan plan) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChaosSession openSession(final String displayName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChaosDiagnostics diagnostics() {
      return new ChaosDiagnostics() {

        @Override
        public Snapshot snapshot() {
          return new Snapshot(Instant.EPOCH, List.of(), List.of(), Map.of());
        }

        @Override
        public Optional<ScenarioReport> scenario(final String scenarioId) {
          return Optional.empty();
        }

        @Override
        public String debugDump() {
          return "stub";
        }
      };
    }

    @Override
    public void addEventListener(final ChaosEventListener listener) {}

    @Override
    public void close() {}
  }
}
