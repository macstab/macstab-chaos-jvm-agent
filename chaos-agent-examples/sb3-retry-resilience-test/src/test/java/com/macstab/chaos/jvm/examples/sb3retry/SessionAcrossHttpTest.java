package com.macstab.chaos.jvm.examples.sb3retry;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.spring.boot3.test.ChaosTest;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * A session-scoped scenario reaches the server side of an HTTP call: the test's request carries
 * {@code X-Chaos-Session}, the starter's filter binds the request thread to the session, and the
 * server's own downstream connect fails there — while the same request from outside the session
 * succeeds.
 */
@ChaosTest(classes = RetryDemoApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class SessionAcrossHttpTest {

  private static WireMockServer wireMock;

  @Autowired private TestRestTemplate restTemplate;

  @BeforeAll
  static void startWireMock() {
    if (wireMock != null) {
      return;
    }
    wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wireMock.start();
    wireMock.stubFor(
        get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200).withBody("pong")));
  }

  @org.springframework.test.context.DynamicPropertySource
  static void downstream(final org.springframework.test.context.DynamicPropertyRegistry registry) {
    if (wireMock == null) {
      startWireMock();
    }
    registry.add("downstream.url", () -> "http://localhost:" + wireMock.port());
  }

  @AfterAll
  static void stopWireMock() {
    if (wireMock != null) {
      wireMock.stop();
      wireMock = null;
    }
  }

  @Test
  void sessionScopedChaosFollowsTheRequestIntoTheServer(
      final ChaosSession session, final com.macstab.chaos.jvm.api.ChaosControlPlane chaos) {
    session.activate(
        ChaosScenario.builder("downstream-down-in-session")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(
                new ChaosSelector.NetworkSelector(
                    Set.of(OperationType.SOCKET_CONNECT), null, null, wireMock.port(), null, null))
            .effect(ChaosEffect.reject("downstream down for this session"))
            .build());

    final int insideSession =
        restTemplate.getForEntity("/fetch", String.class).getStatusCode().value();
    // A header the caller set itself is kept; naming no open session, it joins nothing. (A thread
    // started here would not do: it inherits the test's session.)
    final org.springframework.http.HttpHeaders otherSession =
        new org.springframework.http.HttpHeaders();
    otherSession.set(ChaosSession.HEADER, "no-such-session");
    final int outsideSession =
        restTemplate
            .exchange(
                "/fetch",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(otherSession),
                String.class)
            .getStatusCode()
            .value();

    // The request decides, not the server thread: Tomcat's workers were started while this test's
    // session was bound and inherited it, yet a request from another session runs in that one.
    final int otherOpenSession;
    try (ChaosSession other = chaos.openSession("other")) {
      otherOpenSession = restTemplate.getForEntity("/fetch", String.class).getStatusCode().value();
    }

    assertThat(insideSession)
        .as("the server's downstream call ran in the test's session")
        .isEqualTo(500);
    assertThat(outsideSession).as("a header naming no session runs outside any").isEqualTo(200);
    assertThat(otherOpenSession)
        .as("the header named a session without the scenario")
        .isEqualTo(200);
  }
}
