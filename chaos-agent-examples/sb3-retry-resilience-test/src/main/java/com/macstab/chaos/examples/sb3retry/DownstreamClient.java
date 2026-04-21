package com.macstab.chaos.examples.sb3retry;

import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class DownstreamClient {

  private final RestTemplate restTemplate;
  private final String downstreamUrl;

  public DownstreamClient(
      final RestTemplate restTemplate, @Value("${downstream.url}") final String downstreamUrl) {
    this.restTemplate = restTemplate;
    this.downstreamUrl = downstreamUrl;
  }

  @Retry(name = "downstream")
  public String fetch() {
    return restTemplate.getForObject(downstreamUrl + "/ping", String.class);
  }
}
