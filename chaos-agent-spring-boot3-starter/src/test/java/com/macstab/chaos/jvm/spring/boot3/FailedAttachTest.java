package com.macstab.chaos.jvm.spring.boot3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.spring.boot.common.ChaosAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * A self-attach that fails with a {@link LinkageError} (a missing class, a native library that does
 * not load) leaves the application starting without chaos, and the auto-configuration does not try
 * to attach again.
 */
@DisplayName("A failed self-attach (Spring Boot 3)")
class FailedAttachTest {

  private static final String ATTACH_FAILED_PROPERTY = "macstab.chaos.agent.attach-failed";

  private static ChaosAgentEnvironmentPostProcessor failingAttach() {
    return new ChaosAgentEnvironmentPostProcessor(
        () -> {
          throw new NoClassDefFoundError("com/sun/tools/attach/VirtualMachine");
        });
  }

  @Test
  @DisplayName("the environment post-processor does not throw and marks the failure")
  void postProcessorSurvivesALinkageError() {
    final StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new SimpleCommandLinePropertySource("--macstab.chaos.enabled=true"));

    assertThatCode(
            () -> failingAttach().postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
    assertThat(environment.getProperty(ATTACH_FAILED_PROPERTY)).isEqualTo("true");
    assertThat(environment.getProperty(ChaosAgentEnvironmentPostProcessor.ATTACH_MARKER_PROPERTY))
        .isNull();
  }

  @Test
  @DisplayName("the context starts without chaos beans")
  void contextStartsWithoutChaos() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChaosAutoConfiguration.class))
        .withInitializer(
            context ->
                failingAttach()
                    .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
        .withPropertyValues("macstab.chaos.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(ChaosControlPlane.class);
            });
  }
}
