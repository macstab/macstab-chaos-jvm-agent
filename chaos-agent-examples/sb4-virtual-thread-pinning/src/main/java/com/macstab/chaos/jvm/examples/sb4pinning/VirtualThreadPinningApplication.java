package com.macstab.chaos.jvm.examples.sb4pinning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VirtualThreadPinningApplication {

  public static void main(final String[] args) {
    SpringApplication.run(VirtualThreadPinningApplication.class, args);
  }
}
