package com.macstab.chaos.jvm.spring.boot.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code POST /actuator/chaos} on Spring MVC, with the auto-configuration of the Spring Boot on the
 * class path (3 for {@code test}, 4 for {@code testBoot4}).
 */
@DisplayName("/actuator/chaos request body cap (Spring MVC)")
class ChaosActuatorBodyLimitTest {

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ChaosAutoConfiguration.class))
          .withUserConfiguration(
              AutoConfigured.class, ChaosActuatorEndpointTest.StubControlPlaneConfiguration.class)
          .withPropertyValues(
              "macstab.chaos.enabled=true",
              "macstab.chaos.actuator.enabled=true",
              "management.endpoints.web.exposure.include=chaos");

  @Test
  @DisplayName("a body above the cap is refused with 413 before it is bound")
  void bodyAboveCapRefused() {
    runner.run(
        context -> {
          final MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
          final String plan = "x".repeat(ChaosActuatorBodyLimit.MAX_BODY_BYTES);

          assertThat(
                  mvc.perform(postChaos("{\"planJson\":\"" + plan + "\"}"))
                      .andReturn()
                      .getResponse()
                      .getStatus())
              .isEqualTo(413);
        });
  }

  @Test
  @DisplayName("a body within the cap reaches the endpoint, which answers for the plan")
  void bodyWithinCapReachesEndpoint() {
    runner.run(
        context -> {
          final MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

          assertThat(
                  mvc.perform(postChaos("{\"planJson\":\"not a plan\"}"))
                      .andReturn()
                      .getResponse()
                      .getStatus())
              .as("400 invalid, from the plan reader")
              .isEqualTo(400);
        });
  }

  @Test
  @DisplayName("a write to another endpoint is not capped")
  void otherEndpointsUntouched() {
    final ChaosActuatorBodyLimit limit = new ChaosActuatorBodyLimit(() -> "/actuator/chaos");
    final org.springframework.mock.web.MockHttpServletRequest request =
        new org.springframework.mock.web.MockHttpServletRequest();
    request.setAttribute(
        org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
        "/actuator/loggers/{name}");
    final org.springframework.mock.http.MockHttpInputMessage message =
        new org.springframework.mock.http.MockHttpInputMessage(
            new byte[ChaosActuatorBodyLimit.MAX_BODY_BYTES + 1]);
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        new org.springframework.web.context.request.ServletRequestAttributes(request));
    try {
      assertThat(limit.beforeBodyRead(message, null, Object.class, null)).isSameAs(message);
    } catch (final java.io.IOException unexpected) {
      throw new java.io.UncheckedIOException(unexpected);
    } finally {
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  @DisplayName("a body without Content-Length is refused while it is read")
  void bodyWithoutLengthRefusedWhileRead() {
    final ChaosActuatorBodyLimit limit = new ChaosActuatorBodyLimit(() -> "/actuator/chaos");
    final org.springframework.mock.web.MockHttpServletRequest request =
        new org.springframework.mock.web.MockHttpServletRequest();
    request.setAttribute(
        org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
        "/actuator/chaos");
    final org.springframework.mock.http.MockHttpInputMessage message =
        new org.springframework.mock.http.MockHttpInputMessage(
            new byte[ChaosActuatorBodyLimit.MAX_BODY_BYTES + 1]);
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        new org.springframework.web.context.request.ServletRequestAttributes(request));
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> limit.beforeBodyRead(message, null, Object.class, null))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
          .satisfies(
              thrown ->
                  assertThat(
                          ((org.springframework.web.server.ResponseStatusException) thrown)
                              .getStatusCode()
                              .value())
                      .isEqualTo(413));
    } finally {
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  @DisplayName("only the actuator's Map body is a candidate; a controller's body is not")
  void supportsOnlyTheActuatorBody() throws Exception {
    final ChaosActuatorBodyLimit limit = new ChaosActuatorBodyLimit(() -> "/actuator/chaos");
    final org.springframework.core.MethodParameter mapBody =
        new org.springframework.core.MethodParameter(
            Controller.class.getDeclaredMethod("map", java.util.Map.class), 0);
    final org.springframework.core.MethodParameter stringBody =
        new org.springframework.core.MethodParameter(
            Controller.class.getDeclaredMethod("string", String.class), 0);

    assertThat(limit.supports(mapBody, java.util.Map.class, null)).isFalse();
    assertThat(limit.supports(stringBody, String.class, null)).isFalse();
  }

  @Test
  @DisplayName("a body read outside a request, without a matched pattern or exposed path, passes")
  void passesWithoutChaosRequest() throws Exception {
    final org.springframework.mock.http.MockHttpInputMessage message =
        new org.springframework.mock.http.MockHttpInputMessage(
            new byte[ChaosActuatorBodyLimit.MAX_BODY_BYTES + 1]);
    final ChaosActuatorBodyLimit limit = new ChaosActuatorBodyLimit(() -> "/actuator/chaos");

    assertThat(limit.beforeBodyRead(message, null, Object.class, null))
        .as("no request bound to the thread")
        .isSameAs(message);
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        new org.springframework.web.context.request.ServletRequestAttributes(
            new org.springframework.mock.web.MockHttpServletRequest()));
    try {
      assertThat(limit.beforeBodyRead(message, null, Object.class, null))
          .as("no matched pattern")
          .isSameAs(message);
    } finally {
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  @DisplayName("without the actuator's web endpoints (no PathMappedEndpoints) nothing is capped")
  void nothingCappedWithoutWebEndpoints() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChaosAutoConfiguration.class))
        .withUserConfiguration(ChaosActuatorEndpointTest.StubControlPlaneConfiguration.class)
        .withPropertyValues("macstab.chaos.enabled=true", "macstab.chaos.actuator.enabled=true")
        .run(
            context -> {
              final ChaosActuatorBodyLimit limit = context.getBean(ChaosActuatorBodyLimit.class);
              final MockHttpServletRequest request = new MockHttpServletRequest();
              request.setAttribute(
                  org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                  "/actuator/chaos");
              final org.springframework.mock.http.MockHttpInputMessage message =
                  new org.springframework.mock.http.MockHttpInputMessage(new byte[16]);
              org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                  new org.springframework.web.context.request.ServletRequestAttributes(request));
              try {
                assertThat(limit.beforeBodyRead(message, null, Object.class, null))
                    .isSameAs(message);
              } finally {
                org.springframework.web.context.request.RequestContextHolder
                    .resetRequestAttributes();
              }
            });
  }

  /** A controller's body parameters, which the advice leaves alone. */
  static final class Controller {
    void map(final java.util.Map<String, String> body) {}

    void string(final String body) {}
  }

  /**
   * A JSON {@code POST /actuator/chaos}, built without MockMvc's request builder, whose method
   * signatures differ between Spring Framework 6 and 7.
   */
  private static RequestBuilder postChaos(final String body) {
    return servletContext -> {
      final MockHttpServletRequest request =
          new MockHttpServletRequest(servletContext, "POST", "/actuator/chaos");
      request.setContentType("application/json");
      request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return request;
    };
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class AutoConfigured {}
}
