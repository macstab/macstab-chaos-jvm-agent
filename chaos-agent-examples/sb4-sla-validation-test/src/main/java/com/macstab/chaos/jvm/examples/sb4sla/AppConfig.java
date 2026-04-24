package com.macstab.chaos.jvm.examples.sb4sla;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/** Spring configuration for the sb4-sla-validation-test example application. */
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
