package com.macstab.chaos.jvm.examples.sb3retry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point for the retry resilience test example. */
@SpringBootApplication
public class RetryDemoApplication {

  /** Creates a new RetryDemoApplication. */
  public RetryDemoApplication() {}

  /**
   * Starts the Spring Boot application.
   *
   * @param args command-line arguments passed to Spring
   */
  public static void main(final String[] args) {
    SpringApplication.run(RetryDemoApplication.class, args);
  }
}
