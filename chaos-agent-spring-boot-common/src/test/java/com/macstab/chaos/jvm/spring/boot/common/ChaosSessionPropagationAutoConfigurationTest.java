package com.macstab.chaos.jvm.spring.boot.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The session filter is on by default in the test starters, off by property, backs off for a user's
 * own, and in a running application exists only when chaos is enabled.
 */
@DisplayName("Session propagation auto-configuration")
class ChaosSessionPropagationAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ChaosSessionPropagationAutoConfiguration.class));

  @Test
  @DisplayName("registers the filter by default; without an agent it passes requests through")
  void onByDefault() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(ChaosSessionFilter.class);
          final AtomicBoolean passed = new AtomicBoolean();
          final MockHttpServletRequest request = new MockHttpServletRequest();
          request.addHeader(com.macstab.chaos.jvm.api.ChaosSession.HEADER, "some-session");
          context
              .getBean(ChaosSessionFilter.class)
              .doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(true));
          assertThat(passed).isTrue();
        });
  }

  @Test
  @DisplayName("macstab.chaos.session-propagation.enabled=false leaves it out")
  void offByProperty() {
    runner
        .withPropertyValues("macstab.chaos.session-propagation.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ChaosSessionFilter.class));
  }

  @Test
  @DisplayName("a filter of the application's own replaces it")
  void backsOff() {
    runner
        .withUserConfiguration(OwnFilter.class)
        .run(
            context ->
                assertThat(context.getBean(ChaosSessionFilter.class))
                    .isSameAs(context.getBean(OwnFilter.class).filter));
  }

  @Test
  @DisplayName("a running application without trusted chaos enablement has no filter")
  void runtimeNeedsEnablement() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChaosAutoConfiguration.class))
        .run(context -> assertThat(context).doesNotHaveBean(ChaosSessionFilter.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnFilter {
    final ChaosSessionFilter filter = new ChaosSessionFilter(() -> null);

    @Bean
    ChaosSessionFilter chaosSessionFilter() {
      return filter;
    }
  }
}
