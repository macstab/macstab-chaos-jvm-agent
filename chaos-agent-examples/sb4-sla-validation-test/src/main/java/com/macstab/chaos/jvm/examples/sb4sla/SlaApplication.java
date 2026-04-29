package com.macstab.chaos.jvm.examples.sb4sla;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point for the SLA validation test example. */
@SpringBootApplication
public class SlaApplication {

  /** Creates a new SlaApplication. */
  public SlaApplication() {}

  /**
   * Starts the Spring Boot application.
   *
   * @param args command-line arguments passed to Spring
   */
  public static void main(final String[] args) {
    SpringApplication.run(SlaApplication.class, args);
  }
}
