package com.macstab.chaos.jvm.examples.sb3actuator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

  private final PaymentGatewayClient client;

  public PaymentController(final PaymentGatewayClient client) {
    this.client = client;
  }

  @PostMapping("/{orderId}")
  public ResponseEntity<String> charge(@PathVariable final String orderId) {
    final String result = client.charge(orderId);
    return ResponseEntity.ok(result);
  }
}
