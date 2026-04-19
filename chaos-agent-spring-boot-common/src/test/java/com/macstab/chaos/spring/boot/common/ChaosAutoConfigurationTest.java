package com.macstab.chaos.spring.boot.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosDiagnostics;
import com.macstab.chaos.api.ChaosEventListener;
import com.macstab.chaos.api.ChaosPlan;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSession;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("ChaosAutoConfiguration")
class ChaosAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ChaosAutoConfiguration.class));

  @Test
  @DisplayName("registers ChaosControlPlane when macstab.chaos.enabled=true")
  void registersControlPlaneWhenEnabled() {
    runner
        .withPropertyValues("macstab.chaos.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(ChaosControlPlane.class));
  }

  @Test
  @DisplayName("registers ChaosHandleRegistry when macstab.chaos.enabled=true")
  void registersHandleRegistryWhenEnabled() {
    runner
        .withPropertyValues("macstab.chaos.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(ChaosHandleRegistry.class));
  }

  @Test
  @DisplayName("no ChaosControlPlane bean when macstab.chaos.enabled is absent (default)")
  void noControlPlaneByDefault() {
    runner.run(context -> assertThat(context).doesNotHaveBean(ChaosControlPlane.class));
  }

  @Test
  @DisplayName("no ChaosControlPlane bean when macstab.chaos.enabled=false")
  void noControlPlaneWhenDisabled() {
    runner
        .withPropertyValues("macstab.chaos.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ChaosControlPlane.class));
  }

  @Test
  @DisplayName("user-defined ChaosControlPlane bean overrides auto-configuration")
  void userBeanBacksOffAutoConfiguration() {
    runner
        .withPropertyValues("macstab.chaos.enabled=true")
        .withUserConfiguration(UserControlPlaneConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(ChaosControlPlane.class);
              assertThat(context.getBean(ChaosControlPlane.class))
                  .isInstanceOf(StubControlPlane.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class UserControlPlaneConfiguration {

    @Bean
    ChaosControlPlane chaosControlPlane() {
      return new StubControlPlane();
    }
  }

  /** Minimal stub so the test can run without actually installing the JVM-level agent. */
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
          return new Snapshot(java.time.Instant.EPOCH, List.of(), List.of(), Map.of());
        }

        @Override
        public java.util.Optional<ScenarioReport> scenario(final String scenarioId) {
          return java.util.Optional.empty();
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
