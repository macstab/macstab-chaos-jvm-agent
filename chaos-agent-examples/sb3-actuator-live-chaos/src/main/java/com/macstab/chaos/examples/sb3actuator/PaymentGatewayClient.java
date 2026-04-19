package com.macstab.chaos.examples.sb3actuator;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class PaymentGatewayClient {

  private final RestTemplate restTemplate;
  private final String gatewayUrl;

  public PaymentGatewayClient(
      RestTemplate restTemplate, @Value("${payment.gateway.url}") String gatewayUrl) {
    this.restTemplate = restTemplate;
    this.gatewayUrl = gatewayUrl;
  }

  @CircuitBreaker(name = "payment-gateway", fallbackMethod = "chargeFallback")
  public String charge(String orderId) {
    return restTemplate.postForObject(gatewayUrl + "/charge", orderId, String.class);
  }

  // Resilience4j invokes the fallback on *every* failing call — both individual
  // exceptions and short-circuited calls after the breaker has opened. The two
  // cases must be distinguishable so tests can assert on actual open-circuit
  // behaviour instead of the noisier "any exception" path.
  @SuppressWarnings("unused")
  public String chargeFallback(String orderId, Throwable t) {
    if (t instanceof CallNotPermittedException) {
      return "fallback:circuit-open";
    }
    return "fallback:exception";
  }
}
