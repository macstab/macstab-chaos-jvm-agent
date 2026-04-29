package com.macstab.chaos.jvm.examples.sb3actuator;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** HTTP client for the upstream payment gateway with circuit-breaker protection. */
@Component
public class PaymentGatewayClient {

  private final RestTemplate restTemplate;
  private final String gatewayUrl;

  /**
   * Creates a new PaymentGatewayClient.
   *
   * @param restTemplate HTTP client
   * @param gatewayUrl base URL of the upstream payment gateway
   */
  public PaymentGatewayClient(
      final RestTemplate restTemplate, @Value("${payment.gateway.url}") final String gatewayUrl) {
    this.restTemplate = restTemplate;
    this.gatewayUrl = gatewayUrl;
  }

  /**
   * Charges the given order against the upstream payment gateway.
   *
   * @param orderId the order identifier
   * @return charge result string from the gateway
   */
  @CircuitBreaker(name = "payment-gateway", fallbackMethod = "chargeFallback")
  public String charge(final String orderId) {
    return restTemplate.postForObject(gatewayUrl + "/charge", orderId, String.class);
  }

  /**
   * Fallback invoked by Resilience4j when the circuit breaker is open or the call throws.
   *
   * @param orderId the order identifier
   * @param t the exception or {@link CallNotPermittedException} that triggered the fallback
   * @return {@code "fallback:circuit-open"} if the breaker is open, otherwise {@code
   *     "fallback:exception"}
   */
  @SuppressWarnings("unused")
  public String chargeFallback(final String orderId, final Throwable t) {
    if (t instanceof CallNotPermittedException) {
      return "fallback:circuit-open";
    }
    return "fallback:exception";
  }
}
