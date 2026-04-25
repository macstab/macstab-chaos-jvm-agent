package com.macstab.chaos.jvm.examples.sb4sla;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration for the sb4-sla-validation-test example application. */
@Configuration
public class AppConfig {

  /** Creates a new AppConfig. */
  public AppConfig() {}

  /**
   * Creates a {@link HttpClient} bean that routes through {@code sun.nio.ch.SocketChannelImpl},
   * which is instrumented by the chaos agent (unlike {@code java.net.Socket} on Java 13+, whose
   * default {@code NioSocketImpl} backend currently lacks instrumentation).
   *
   * @return configured HttpClient instance
   */
  @Bean
  public HttpClient httpClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }
}
