package com.macstab.chaos.jvm.examples.sb3actuator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point for the payment service example application. */
@SpringBootApplication
public class PaymentServiceApplication {

  /** Creates a new PaymentServiceApplication. */
  public PaymentServiceApplication() {}

  /**
   * Starts the Spring Boot application.
   *
   * @param args command-line arguments passed to Spring
   */
  public static void main(final String[] args) {
    SpringApplication.run(PaymentServiceApplication.class, args);
  }
}
