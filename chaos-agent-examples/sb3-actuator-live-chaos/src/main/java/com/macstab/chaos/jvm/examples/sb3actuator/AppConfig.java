package com.macstab.chaos.jvm.examples.sb3actuator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/** Spring configuration for the sb3-actuator-live-chaos example application. */
@Configuration
public class AppConfig {

  /** Creates a new AppConfig. */
  public AppConfig() {}

  /**
   * Creates a {@link RestTemplate} bean.
   *
   * @return configured RestTemplate instance
   */
  @Bean
  public RestTemplate restTemplate() {
    return new RestTemplate();
  }
}
