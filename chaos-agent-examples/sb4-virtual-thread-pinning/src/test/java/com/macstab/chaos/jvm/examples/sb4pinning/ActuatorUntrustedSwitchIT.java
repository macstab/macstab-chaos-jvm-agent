package com.macstab.chaos.jvm.examples.sb4pinning;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosAgentInitializer;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfiguration;

/**
 * {@code macstab.chaos.actuator.enabled=true} from a config server (an untrusted source) does not
 * expose {@code /actuator/chaos} on a real Spring Boot 4 server: the switch is honoured only from
 * launch-time local sources, so the endpoint answers 404 and a POSTed plan activates nothing.
 */
@ChaosTest(
    classes = VirtualThreadPinningApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "macstab.chaos.actuator.enabled=false")
@ContextConfiguration(
    initializers = {ChaosAgentInitializer.class, ActuatorUntrustedSwitchIT.RemoteSwitch.class})
@DisplayName("/actuator/chaos switched on from an untrusted source (Spring Boot 4)")
class ActuatorUntrustedSwitchIT {

  private final HttpClient client = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  @Test
  @DisplayName("the endpoint is not exposed and a POSTed plan activates nothing")
  void notExposed(final ChaosControlPlane chaos) throws Exception {
    final URI endpoint = URI.create("http://localhost:" + port + "/actuator/chaos");
    final String plan =
        "{\"planJson\": \"{\\\"id\\\": \\\"untrusted\\\", \\\"selector\\\": {\\\"type\\\":"
            + " \\\"thread\\\", \\\"operations\\\": [\\\"THREAD_SLEEP\\\"]}, \\\"effect\\\":"
            + " {\\\"type\\\": \\\"delay\\\", \\\"minDelay\\\": \\\"PT1S\\\", \\\"maxDelay\\\":"
            + " \\\"PT1S\\\"}}\"}";

    final HttpResponse<String> read =
        client.send(
            HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString());
    final HttpResponse<String> written =
        client.send(
            HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(plan))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(read.statusCode()).isEqualTo(404);
    assertThat(written.statusCode()).isEqualTo(404);
    assertThat(chaos.diagnostics().scenario("untrusted")).isEmpty();
  }

  /** Puts the switch, set to true, in a config-server source ahead of every other one. */
  static final class RemoteSwitch
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(final ConfigurableApplicationContext context) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "configserver:sb4-virtual-thread-pinning",
                  Map.of("macstab.chaos.actuator.enabled", "true")));
    }
  }
}
