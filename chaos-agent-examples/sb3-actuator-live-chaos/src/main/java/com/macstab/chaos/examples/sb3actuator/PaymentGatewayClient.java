package com.macstab.chaos.examples.sb3actuator;

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

  @SuppressWarnings("unused")
  public String chargeFallback(String orderId, Throwable t) {
    return "fallback:circuit-open";
  }
}
