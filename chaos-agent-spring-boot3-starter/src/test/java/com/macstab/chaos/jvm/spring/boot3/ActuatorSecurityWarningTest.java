package com.macstab.chaos.jvm.spring.boot3;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.spring.boot.common.ChaosActuatorEndpoint;
import com.macstab.chaos.jvm.spring.boot.common.ChaosAutoConfiguration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * With Spring Security on the class path (this module's tests have spring-security-config), the
 * {@code /actuator/chaos} endpoint is created without the WARNING that an unprotected endpoint
 * gets.
 */
@DisplayName("The actuator endpoint with Spring Security present (Spring Boot 3)")
class ActuatorSecurityWarningTest {

  @Test
  @DisplayName("creates the endpoint and logs no missing-security WARNING")
  void noWarningWithSecurity() {
    final Logger logger = Logger.getLogger(ChaosAutoConfiguration.class.getName());
    final List<LogRecord> records = new CopyOnWriteArrayList<>();
    final Handler handler =
        new Handler() {
          @Override
          public void publish(final LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ChaosAutoConfiguration.class))
          .withPropertyValues("macstab.chaos.enabled=true", "macstab.chaos.actuator.enabled=true")
          .run(context -> assertThat(context).hasSingleBean(ChaosActuatorEndpoint.class));
    } finally {
      logger.removeHandler(handler);
    }

    assertThat(records)
        .noneMatch(
            record -> record.getMessage().contains("Spring Security is not on the classpath"));
  }
}
