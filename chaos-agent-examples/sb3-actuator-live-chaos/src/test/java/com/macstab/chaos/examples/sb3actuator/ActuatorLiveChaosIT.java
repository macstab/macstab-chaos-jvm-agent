package com.macstab.chaos.examples.sb3actuator;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosScenario.ScenarioScope;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.OperationType;
import com.macstab.chaos.spring.boot3.test.ChaosTest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

@ChaosTest(classes = PaymentServiceApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class ActuatorLiveChaosIT {

  @Autowired private PaymentGatewayClient paymentGatewayClient;

  @Test
  void circuitBreakerOpensUnderInjectedConnectFailures(ChaosControlPlane chaos) {
    ChaosScenario rejectConnects =
        ChaosScenario.builder("reject-gateway-connects")
            .description(
                "Reject every outbound SOCKET_CONNECT with a ConnectException so the "
                    + "Resilience4j circuit breaker records a failure on each charge() call; "
                    + "once the failure-rate threshold is exceeded the circuit opens and the "
                    + "@CircuitBreaker fallback fires.")
            .scope(ScenarioScope.JVM)
            .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT)))
            .effect(ChaosEffect.reject("chaos: gateway unreachable"))
            .activationPolicy(ActivationPolicy.always())
            .build();

    try (ChaosActivationHandle handle = chaos.activate(rejectConnects)) {
      int openCircuitFallbacks = 0;
      for (int i = 0; i < 6; i++) {
        String result = paymentGatewayClient.charge("order-" + i);
        // The fallback distinguishes "breaker-open" (CallNotPermittedException)
        // from "any exception" so this count genuinely reflects the OPEN state.
        if ("fallback:circuit-open".equals(result)) {
          openCircuitFallbacks++;
        }
      }

      assertThat(openCircuitFallbacks)
          .as(
              "Circuit breaker must transition to OPEN under chaos-injected connect failures; at"
                  + " least one of the 6 calls must short-circuit (minimumNumberOfCalls=3,"
                  + " slidingWindowSize=5, failureRateThreshold=60%%)")
          .isGreaterThan(0);
    }
  }
}
