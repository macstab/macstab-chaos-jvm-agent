package com.macstab.chaos.jvm.examples.sb3retry;

import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** HTTP client for the downstream service with automatic retry support. */
@Component
public class DownstreamClient {

  private final RestTemplate restTemplate;
  private final String downstreamUrl;

  /**
   * Creates a new DownstreamClient.
   *
   * @param restTemplate HTTP client
   * @param downstreamUrl base URL of the downstream service
   */
  public DownstreamClient(
      final RestTemplate restTemplate, @Value("${downstream.url}") final String downstreamUrl) {
    this.restTemplate = restTemplate;
    this.downstreamUrl = downstreamUrl;
  }

  /**
   * Fetches a ping response from the downstream service.
   *
   * @return the response body string
   */
  @Retry(name = "downstream")
  public String fetch() {
    return restTemplate.getForObject(downstreamUrl + "/ping", String.class);
  }
}
