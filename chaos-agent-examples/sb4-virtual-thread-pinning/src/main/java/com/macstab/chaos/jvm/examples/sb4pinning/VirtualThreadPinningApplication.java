package com.macstab.chaos.jvm.examples.sb4pinning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot application entry point for the virtual-thread pinning demonstration. */
@SpringBootApplication
public class VirtualThreadPinningApplication {

  /** Creates a new VirtualThreadPinningApplication. */
  public VirtualThreadPinningApplication() {}

  /**
   * Starts the Spring Boot application.
   *
   * @param args command-line arguments passed to Spring
   */
  public static void main(final String[] args) {
    SpringApplication.run(VirtualThreadPinningApplication.class, args);
  }
}
